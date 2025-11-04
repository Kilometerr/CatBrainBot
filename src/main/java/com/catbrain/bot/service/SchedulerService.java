package com.catbrain.bot.service;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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

    private final List<ScheduledFuture<?>> scheduledPosts = Collections.synchronizedList(new ArrayList<>());
    private final List<LocalDateTime> scheduledTimes = Collections.synchronizedList(new ArrayList<>());

    private volatile ScheduledFuture<?> midnightTask;
    private volatile ScheduledFuture<?> endOfDayStatsTask;

    private int minDailyPosts;
    private int maxDailyPosts;
    private PersistenceService persistenceService;

    public void scheduleDailyPosts(int minPosts, int maxPosts, int startHour, int endHour,
                                   Runnable postAction, PersistenceService persistence) {
        // Validate parameters early
        if (minPosts > maxPosts) {
            throw new IllegalArgumentException(String.format(
                    "minPosts must be <= maxPosts: received minPosts=%d, maxPosts=%d", minPosts, maxPosts));
        }
        if (minPosts <= 0) {
            throw new IllegalArgumentException(String.format(
                    "minPosts must be > 0: received minPosts=%d", minPosts));
        }

        this.minDailyPosts = minPosts;
        this.maxDailyPosts = maxPosts;
        this.persistenceService = persistence;

        if (restoreScheduleFromDatabase(postAction)) {
            log.info("Schedule restored from database after restart");
        } else {
            int postCount = generateRandomPostCount(minPosts, maxPosts);
            schedulePostsWithCount(postCount, startHour, endHour, postAction);
        }

        scheduleMidnightReschedule(startHour, endHour, postAction);
        scheduleEndOfDayStats();
    }

    private boolean restoreScheduleFromDatabase(Runnable postAction) {
        if (persistenceService == null) {
            log.warn("Persistence service not available, cannot restore schedule");
            return false;
        }

        var unexecuted = persistenceService.getUnexecutedSchedules();
        if (unexecuted.isEmpty()) {
            log.info("No unexecuted schedules found in database");
            return false;
        }

        var now = LocalDateTime.now();
        var today = now.toLocalDate();

        var todaySchedules = unexecuted.stream()
                .filter(time -> time.toLocalDate().equals(today))
                .toList();

        if (todaySchedules.isEmpty()) {
            log.info("No schedules for today found in database, will create new schedule");
            persistenceService.clearOldSchedules(today);
            return false;
        }

        log.info("Found {} scheduled posts for today in database", todaySchedules.size());

        synchronized (scheduledTimes) {
            scheduledTimes.clear();
            scheduledTimes.addAll(todaySchedules);
        }

        int restored = 0;
        int skipped = 0;
        for (LocalDateTime time : todaySchedules) {
            if (time.isAfter(now)) {
                schedulePost(time, postAction, true);
                restored++;
            } else {
                persistenceService.markPostExecuted(time);
                skipped++;
                log.debug("Marked missed post as executed: {}", time.format(TIME_FORMATTER));
            }
        }

        log.info("Restored {} future posts, marked {} missed posts as executed", restored, skipped);

        persistenceService.clearOldSchedules(today);

        return restored > 0;
    }

    private int generateRandomPostCount(int min, int max) {
        if (min == max) {
            return min;
        }
        int count = min + random.nextInt(max - min + 1);

        log.info("Cat brain intensity for today: {} moments scheduled", count);
        log.debug("Random post count generated: {} (range: {}-{})", count, min, max);

        return count;
    }

    private void schedulePostsWithCount(int postCount, int startHour, int endHour, Runnable postAction) {
        var now = LocalDateTime.now();

        int windowMinutes = (endHour - startHour) * 60;
        int maxPossiblePosts = windowMinutes / MIN_SPACING_MINUTES;

        if (postCount > maxPossiblePosts) {
            log.warn("Requested {} posts cannot fit in {}h window with {}-minute spacing " +
                            "(max: {}). Capping at {} posts.",
                    postCount, (endHour - startHour), MIN_SPACING_MINUTES, maxPossiblePosts, maxPossiblePosts);
            postCount = maxPossiblePosts;
        }

        var times = generateRandomTimesWithSpacing(postCount, startHour, endHour)
                .stream()
                .sorted()
                .toList();

        synchronized (scheduledTimes) {
            scheduledTimes.clear();
            scheduledTimes.addAll(times);
        }

        if (persistenceService != null) {
            persistenceService.saveScheduledPosts(times);
        }

        var currentHour = now.getHour();
        var dayLabel = (currentHour >= endHour) ? "tomorrow" : "today";

        log.info("Scheduled {} posts for {}:", postCount, dayLabel);
        times.forEach(time -> log.info("  - {}", time.format(TIME_FORMATTER)));

        int scheduled = 0;
        for (LocalDateTime time : times) {
            if (time.isAfter(now)) {
                schedulePost(time, postAction, false);
                scheduled++;
            } else {
                log.debug("Skipping past time: {}", time.format(TIME_FORMATTER));
            }
        }

        log.info("Scheduled {} posts (skipped {} past times)", scheduled, postCount - scheduled);
    }

    public Optional<LocalDateTime> getNextScheduledPostTime() {
        var now = LocalDateTime.now();
        var threshold = now.plusSeconds(5);

        synchronized (scheduledTimes) {
            return scheduledTimes.stream()
                    .filter(time -> time.isAfter(threshold))
                    .min(LocalDateTime::compareTo);
        }
    }

    private void schedulePost(LocalDateTime scheduledTime, Runnable postAction, boolean isRestored) {
        var now = LocalDateTime.now();
        var delaySeconds = Math.max(1, ChronoUnit.SECONDS.between(now, scheduledTime));

        log.debug("Scheduling post for {} in {} seconds{}",
                scheduledTime.format(TIME_FORMATTER),
                delaySeconds,
                isRestored ? " (restored from DB)" : "");

        Runnable safePostAction = () -> {
            try {
                log.info("Executing scheduled post now (was scheduled for {})",
                        scheduledTime.format(TIME_FORMATTER));
                postAction.run();

                if (persistenceService != null) {
                    persistenceService.markPostExecuted(scheduledTime);
                }
            } catch (Exception e) {
                log.error("Error executing post action", e);
            }
        };

        var future = scheduler.schedule(safePostAction, delaySeconds, TimeUnit.SECONDS);
        synchronized (scheduledPosts) {
            scheduledPosts.add(future);
        }

        log.debug("Post successfully scheduled for {}", scheduledTime.format(TIME_FORMATTER));
    }

    private void scheduleMidnightReschedule(int startHour, int endHour, Runnable postAction) {
        var now = LocalDateTime.now();

        var nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        var delaySeconds = ChronoUnit.SECONDS.between(now, nextMidnight);

        if (delaySeconds <= 0) {
            log.warn("Calculated delay to midnight was {} seconds, using 86400 (24 hours)", delaySeconds);
            delaySeconds = 86400;
        }

        log.info("Next schedule refresh at midnight in {} seconds ({} hours)",
                delaySeconds, String.format("%.1f", delaySeconds / 3600.0));

        midnightTask = scheduler.schedule(() -> {
            try {
                log.info("=== Midnight reached - generating new random post schedule ===");
                clearPreviousDayTasks();

                int newPostCount = generateRandomPostCount(minDailyPosts, maxDailyPosts);
                schedulePostsWithCount(newPostCount, startHour, endHour, postAction);

                scheduleMidnightReschedule(startHour, endHour, postAction);
            } catch (Exception e) {
                log.error("Error during midnight rescheduling", e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void scheduleEndOfDayStats() {
        if (persistenceService == null) {
            log.warn("Persistence service not available, cannot schedule end-of-day stats");
            return;
        }

        var now = LocalDateTime.now();

        var today = now.toLocalDate();
        var statsTime = today.atTime(23, 58, 0);

        if (!now.isBefore(statsTime)) {
            statsTime = today.plusDays(1).atTime(23, 58, 0);
        }

        var delaySeconds = ChronoUnit.SECONDS.between(now, statsTime);

        if (delaySeconds < 10) {
            log.warn("Calculated delay is only {} seconds, forcing next day schedule", delaySeconds);
            statsTime = today.plusDays(1).atTime(23, 58, 0);
            delaySeconds = ChronoUnit.SECONDS.between(now, statsTime);
        }

        var targetDate = statsTime.toLocalDate();

        log.info("End-of-day best/worst day check scheduled for {} in {} seconds ({} hours)",
                statsTime.format(TIME_FORMATTER), delaySeconds, String.format("%.1f", delaySeconds / 3600.0));

        endOfDayStatsTask = scheduler.schedule(() -> {
            try {
                log.info("=== End of day reached - checking if {} is a best/worst day ===", targetDate);
                persistenceService.updateBestWorstDayIfNeeded(targetDate);

                scheduleEndOfDayStats();
            } catch (Exception e) {
                log.error("Error during end-of-day best/worst day check", e);
                scheduleEndOfDayStats();
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void clearPreviousDayTasks() {
        synchronized (scheduledPosts) {
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
        }

        synchronized (scheduledTimes) {
            scheduledTimes.clear();
        }

        var task = midnightTask;
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }

        var statsTask = endOfDayStatsTask;
        if (statsTask != null && !statsTask.isDone()) {
            statsTask.cancel(false);
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

        var minSpan = Math.max(MIN_SPACING_MINUTES, 1);

        long requiredMinutes = (count - 1) * (long) minSpan;
        if (count > 0 && requiredMinutes > windowMinutes) {
            log.error("Cannot fit {} posts with {}-minute spacing in window of {}m (requires {}m minimum)",
                    count, minSpan, windowMinutes, requiredMinutes);
            throw new IllegalArgumentException(String.format(
                    "Window too small: %d posts need %dm spacing = %dm, but window is only %dm",
                    count, minSpan, requiredMinutes, windowMinutes));
        }

        if (count > 0 && count * minSpan > windowMinutes) {
            log.warn("Requested {} posts do not fit {}-minute spacing in window ({}m). Will compress spacing.",
                    count, minSpan, windowMinutes);
        }

        List<LocalDateTime> times = new ArrayList<>(count);

        double baseStep = windowMinutes / (double) (count + 1);
        int halfJitter = Math.max(0, (int)Math.floor(baseStep / 2) - minSpan/2);

        for (int i = 1; i <= count; i++) {
            int ideal = startMinute + (int)Math.round(i * baseStep);
            int jitter = halfJitter > 0 ? random.nextInt(halfJitter * 2 + 1) - halfJitter : 0;
            int minute = Math.min(Math.max(ideal + jitter, startMinute), startMinute + windowMinutes - 1);
            times.add(baseDate.withHour(minute / 60).withMinute(minute % 60).withSecond(0).withNano(0));
        }

        times.sort(LocalDateTime::compareTo);
        for (int i = 1; i < times.size(); i++) {
            var prev = times.get(i - 1);
            var curr = times.get(i);
            long gap = ChronoUnit.MINUTES.between(prev, curr);
            if (gap < minSpan) {
                times.set(i, prev.plusMinutes(minSpan));
            }
        }

        var windowEnd = baseDate.plusMinutes(startMinute + windowMinutes - 1);
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i).isAfter(windowEnd)) {
                times.set(i, windowEnd);
            }
        }

        for (int i = times.size() - 1; i > 0; i--) {
            var curr = times.get(i);
            var prev = times.get(i - 1);
            long gap = ChronoUnit.MINUTES.between(prev, curr);
            if (gap < minSpan) {
                times.set(i - 1, curr.minusMinutes(minSpan));
            }
        }
        var windowStart = baseDate.plusMinutes(startMinute);
        if (!times.isEmpty() && times.get(0).isBefore(windowStart)) {
            long shift = ChronoUnit.MINUTES.between(times.get(0), windowStart);
            log.debug("First time is before windowStart, applying {}m shift to all times", shift);

            times.replaceAll(localDateTime -> localDateTime.plusMinutes(shift));
            for (int i = 1; i < times.size(); i++) {
                var prev = times.get(i - 1);
                var curr = times.get(i);
                long gap = ChronoUnit.MINUTES.between(prev, curr);
                if (gap < minSpan) {
                    times.set(i, prev.plusMinutes(minSpan));
                    log.debug("Adjusted time[{}] to maintain {}m spacing", i, minSpan);
                }
            }

            boolean clamped = false;
            for (int i = 0; i < times.size(); i++) {
                if (times.get(i).isAfter(windowEnd)) {
                    times.set(i, windowEnd);
                    clamped = true;
                    log.debug("Clamped time[{}] to windowEnd", i);
                }
            }

            if (clamped) {
                for (int i = times.size() - 2; i >= 0; i--) {
                    var next = times.get(i + 1);
                    var curr = times.get(i);
                    long gap = ChronoUnit.MINUTES.between(curr, next);
                    if (gap < minSpan) {
                        var newTime = next.minusMinutes(minSpan);
                        if (newTime.isBefore(windowStart)) {
                            log.warn("Cannot maintain {}m spacing after clamping, bunching times at boundaries", minSpan);
                            times.set(i, windowStart);
                        } else {
                            times.set(i, newTime);
                            log.debug("Adjusted time[{}] backwards to maintain {}m spacing after clamping", i, minSpan);
                        }
                    }
                }
            }
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