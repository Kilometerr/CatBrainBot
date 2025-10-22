package com.catbrain.bot.service;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
    private ScheduledFuture<?> midnightTask;

    public void scheduleDailyPosts(int postCount, int startHour, int endHour, Runnable postAction) {
        var scheduledTimes = generateRandomTimesWithSpacing(postCount, startHour, endHour)
                .stream()
                .sorted()
                .toList();

        log.info("Scheduled {} posts for today:", postCount);
        scheduledTimes.forEach(time -> log.info("  - {}", time.format(TIME_FORMATTER)));

        var now = LocalDateTime.now();
        scheduledTimes.stream()
                .filter(time -> time.isAfter(now))
                .forEach(time -> schedulePost(time, now, postAction));

        scheduleMidnightReschedule(postCount, startHour, endHour, postAction);
    }

    private void schedulePost(LocalDateTime scheduledTime, LocalDateTime now, Runnable postAction) {
        var delayMinutes = ChronoUnit.MINUTES.between(now, scheduledTime);

        if (delayMinutes < 0) {
            log.warn("Skipping past time: {}", scheduledTime.format(TIME_FORMATTER));
            return;
        }

        var future = scheduler.schedule(postAction, delayMinutes, TimeUnit.MINUTES);
        scheduledPosts.add(future);
        log.debug("Post scheduled for {} (in {} minutes)", scheduledTime.format(TIME_FORMATTER), delayMinutes);
    }

    private void scheduleMidnightReschedule(int postCount, int startHour, int endHour, Runnable postAction) {
        var now = LocalDateTime.now();
        var nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        var delayMinutes = ChronoUnit.MINUTES.between(now, nextMidnight);

        log.info("Next schedule refresh at midnight: {} (in {} minutes)",
                nextMidnight.format(TIME_FORMATTER), delayMinutes);

        midnightTask = scheduler.schedule(() -> {
            log.info("=== Midnight reached - rescheduling daily posts ===");
            clearPreviousDayTasks();
            scheduleDailyPosts(postCount, startHour, endHour, postAction);
        }, delayMinutes, TimeUnit.MINUTES);
    }

    private void clearPreviousDayTasks() {
        int remainingTasks = (int) scheduledPosts.stream()
                .filter(future -> !future.isDone())
                .count();

        log.info("Clearing {} remaining tasks from previous day", remainingTasks);

        scheduledPosts.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(false);
            }
        });
        scheduledPosts.clear();

        if (midnightTask != null && !midnightTask.isDone()) {
            midnightTask.cancel(false);
        }
    }

    private List<LocalDateTime> generateRandomTimesWithSpacing(int count, int startHour, int endHour) {
        var today = LocalDateTime.now().toLocalDate().atStartOfDay();
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

            times.add(today
                    .withHour(minute / 60)
                    .withMinute(minute % 60)
                    .withSecond(0)
                    .withNano(0));
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
    }
}