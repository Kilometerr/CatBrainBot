package com.catbrain.bot.service;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SchedulerService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");
    private static final int MIN_SPACING_MINUTES = 5;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Random random = new SecureRandom();
    private final List<ScheduledFuture<?>> scheduledPosts = new ArrayList<>();
    private final List<LocalDateTime> scheduledTimes = new ArrayList<>();
    private ScheduledFuture<?> midnightTask;

    public void scheduleDailyPosts(int postCount, int startHour, int endHour, Runnable postAction) {
        var now = LocalDateTime.now();

        var times = generateRandomTimesWithSpacing(postCount, startHour, endHour)
                .stream()
                .sorted()
                .toList();

        scheduledTimes.clear();
        scheduledTimes.addAll(times);

        log.info("Scheduled {} posts for today:", postCount);
        times.forEach(time -> log.info("  - {}", time.format(TIME_FORMATTER)));

        int scheduled = 0;
        for (LocalDateTime time : times) {
            if (time.isAfter(now)) {
                schedulePost(time, postAction);
                scheduled++;
            } else {
                log.debug("Skipping past time: {}", time.format(TIME_FORMATTER));
            }
        }

        log.info("Scheduled {} posts (skipped {} past times)", scheduled, postCount - scheduled);

        scheduleMidnightReschedule(postCount, startHour, endHour, postAction);
    }

    public Optional<LocalDateTime> getNextScheduledPostTime() {
        var now = LocalDateTime.now();
        return scheduledTimes.stream()
                .filter(time -> time.isAfter(now))
                .min(LocalDateTime::compareTo);
    }

    private void schedulePost(LocalDateTime scheduledTime, Runnable postAction) {
        var now = LocalDateTime.now();
        var delayMinutes = ChronoUnit.MINUTES.between(now, scheduledTime);

        if (delayMinutes < 0) {
            log.warn("Negative delay calculated for {}: {} minutes",
                    scheduledTime.format(TIME_FORMATTER), delayMinutes);
            return;
        }

        if (delayMinutes == 0) {
            delayMinutes = 1;
        }

        log.debug("Scheduling post for {} in {} minutes",
                scheduledTime.format(TIME_FORMATTER), delayMinutes);

        Runnable safePostAction = () -> {
            try {
                log.info("Executing scheduled post now (was scheduled for {})",
                        scheduledTime.format(TIME_FORMATTER));
                postAction.run();
            } catch (Exception e) {
                log.error("Error executing post action", e);
            }
        };

        var future = scheduler.schedule(safePostAction, delayMinutes, TimeUnit.MINUTES);
        scheduledPosts.add(future);

        log.debug("Post successfully scheduled for {}", scheduledTime.format(TIME_FORMATTER));
    }

    private void scheduleMidnightReschedule(int postCount, int startHour, int endHour, Runnable postAction) {
        var now = LocalDateTime.now();

        var nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        var delaySeconds = ChronoUnit.SECONDS.between(now, nextMidnight);

        if (delaySeconds <= 0) {
            log.warn("Calculated delay to midnight was {} seconds, using 86400 (24 hours)", delaySeconds);
            delaySeconds = 86400;
        }

        log.info("Next schedule refresh at midnight in {} seconds ({} hours)",
                delaySeconds, delaySeconds / 3600.0);

        midnightTask = scheduler.schedule(() -> {
            try {
                log.info("=== Midnight reached - rescheduling daily posts ===");
                clearPreviousDayTasks();
                scheduleDailyPosts(postCount, startHour, endHour, postAction);
            } catch (Exception e) {
                log.error("Error during midnight rescheduling", e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void clearPreviousDayTasks() {
        int remainingTasks = (int) scheduledPosts.stream()
                .filter(future -> !future.isDone())
                .count();

        log.info("Clearing {} remaining tasks from previous day (total: {})",
                remainingTasks, scheduledPosts.size());

        scheduledPosts.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(false);
            }
        });
        scheduledPosts.clear();
        scheduledTimes.clear();

        if (midnightTask != null && !midnightTask.isDone()) {
            midnightTask.cancel(false);
        }
    }

    private List<LocalDateTime> generateRandomTimesWithSpacing(int count, int startHour, int endHour) {
        var now = LocalDateTime.now();
        var currentHour = now.getHour();

        LocalDate targetDate;
        if (currentHour < startHour) {
            targetDate = now.toLocalDate();
            log.debug("Scheduling for today (current hour {} is before start hour {})",
                    currentHour, startHour);
        } else if (currentHour < endHour) {
            targetDate = now.toLocalDate();
            log.debug("Scheduling for today (current hour {} is within window {}-{})",
                    currentHour, startHour, endHour);
        } else {
            targetDate = now.toLocalDate().plusDays(1);
            log.debug("Scheduling for tomorrow (current hour {} is after end hour {})",
                    currentHour, endHour);
        }

        var baseDate = targetDate.atStartOfDay();
        log.debug("Target date for scheduling: {}", baseDate.toLocalDate());

        var windowMinutes = (endHour - startHour) * 60;
        var startMinute = startHour * 60;

        var segmentSize = windowMinutes / count;

        if (segmentSize < MIN_SPACING_MINUTES) {
            log.warn("Window too small for {} posts with {} minute spacing. Posts will be packed tighter.",
                    count, MIN_SPACING_MINUTES);
        }

        List<LocalDateTime> times = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            var segmentStart = startMinute + (i * segmentSize);
            var segmentEnd = Math.min(segmentStart + segmentSize, startMinute + windowMinutes);

            if (i > 0) {
                segmentStart = Math.max(segmentStart, startMinute + (i * MIN_SPACING_MINUTES));
            }

            var availableRange = Math.max(1, segmentEnd - segmentStart);
            var randomOffset = random.nextInt(availableRange);
            var minute = Math.min(segmentStart + randomOffset, startMinute + windowMinutes - 1);

            var scheduledTime = baseDate
                    .withHour(minute / 60)
                    .withMinute(minute % 60)
                    .withSecond(0)
                    .withNano(0);

            times.add(scheduledTime);
        }

        return times;
    }

    public void shutdown() {
        log.info("Shutting down scheduler...");

        clearPreviousDayTasks();
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Scheduler shutdown complete");
    }
}