package com.catbrain.bot.service;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Slf4j
public class SchedulerService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Random random = new SecureRandom();

    public void scheduleDailyPosts(int postCount, int startHour, int endHour, Runnable postAction) {
        var scheduledTimes = generateRandomTimes(postCount, startHour, endHour)
                .stream()
                .sorted()
                .toList();

        log.info("Scheduled {} posts for today:", postCount);
        scheduledTimes.forEach(time -> log.info("  - {}", time.format(TIME_FORMATTER)));

        var now = LocalDateTime.now();
        scheduledTimes.stream()
                .filter(time -> time.isAfter(now))
                .forEach(time -> schedulePost(time, now, postAction));
    }

    private void schedulePost(LocalDateTime scheduledTime, LocalDateTime now, Runnable postAction) {
        var delayMinutes = ChronoUnit.MINUTES.between(now, scheduledTime);

        if (delayMinutes < 0) {
            log.warn("Skipping past time: {}", scheduledTime.format(TIME_FORMATTER));
            return;
        }

        scheduler.schedule(postAction, delayMinutes, TimeUnit.MINUTES);
    }

    private List<LocalDateTime> generateRandomTimes(int count, int startHour, int endHour) {
        var today = LocalDateTime.now().toLocalDate().atStartOfDay();
        var windowMinutes = (endHour - startHour) * 60;
        var startMinute = startHour * 60;

        return IntStream.range(0, count)
                .mapToObj(i -> {
                    var randomMinute = startMinute + random.nextInt(windowMinutes);
                    return today
                            .withHour(randomMinute / 60)
                            .withMinute(randomMinute % 60)
                            .withSecond(0)
                            .withNano(0);
                })
                .toList();
    }

    public void shutdown() {
        log.info("Shutting down scheduler...");
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