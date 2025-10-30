package com.catbrain.bot.service;

import com.catbrain.bot.model.StatusBox;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Slf4j
public class StatusService {
    private final Random random = new SecureRandom();

    private static final List<String> BRAINCELL_STATUSES = List.of(
            "Online", "Offline", "Buffering", "404 Not Found"
    );

    private static final List<String> CONFUSION_LEVELS = List.of(
            "Crystal Clear", "Slightly Fuzzy", "Confused", "Very Confused", "What?"
    );

    private static final List<String> PROCESSING_SPEEDS = List.of(
            "56k Modem", "Dial-up", "Carrier Pigeon", "Smoke Signals", "Pony Express",
            "Snail Mail", "Turtle Pace", "Molasses Flow", "Glacial Drift", "Hourglass Sand",
            "Abacus Calculation", "Morse Code Telegraph", "Broadband", "DSL", "Cable Internet",
            "Fiber Optic", "5G Network", "Gigabit Ethernet", "Quantum Link", "Warp Speed",
            "Light Pulse", "Neural Synapse", "Instantaneous Teleport"
    );

    private static final List<String> MEMORY_CACHES = List.of(
            "30 seconds", "10 seconds", "3 seconds", "What were we talking about?"
    );

    public StatusBox generateStatus(LocalDateTime nextPostTime) {
        log.debug("Generating new status box (next post time: {})", nextPostTime);

        int coherence = generateCoherenceLevel();
        String confusion = getConfusionFromCoherence(coherence);

        return new StatusBox(
                generateBraincellStatus(),
                coherence,
                confusion,
                randomFrom(PROCESSING_SPEEDS),
                randomFrom(MEMORY_CACHES),
                generateSmartThoughtETA(nextPostTime)
        );
    }

    private String generateBraincellStatus() {
        return random.nextBoolean()
                ? "Online"
                : randomFrom(BRAINCELL_STATUSES.subList(1, 4));
    }

    private int generateCoherenceLevel() {
        return random.nextInt(101);
    }

    private String getConfusionFromCoherence(int coherence) {
        int baseLevel;
        if (coherence >= 80) {
            baseLevel = 0;
        } else if (coherence >= 60) {
            baseLevel = 1;
        } else if (coherence >= 40) {
            baseLevel = 2;
        } else if (coherence >= 20) {
            baseLevel = 3;
        } else {
            baseLevel = 4;
        }

        if (random.nextInt(100) < 10) {
            int shift = random.nextBoolean() ? 1 : -1;
            int adjustedLevel = baseLevel + shift;

            adjustedLevel = Math.max(0, Math.min(4, adjustedLevel));

            log.debug("Confusion variance applied: base={} (coherence={}%), adjusted={}",
                    baseLevel, coherence, adjustedLevel);

            return CONFUSION_LEVELS.get(adjustedLevel);
        }

        return CONFUSION_LEVELS.get(baseLevel);
    }

    private String generateSmartThoughtETA(LocalDateTime nextPostTime) {
        if (nextPostTime == null) {
            var minutes = 1 + random.nextInt(59);
            var seconds = random.nextInt(60);
            return "%dm %ds".formatted(minutes, seconds);
        }

        var now = LocalDateTime.now();

        if (nextPostTime.isBefore(now) || nextPostTime.equals(now)) {
            return "Now!";
        }

        var duration = Duration.between(now, nextPostTime);
        var totalSeconds = duration.getSeconds();

        if (totalSeconds >= 3600) {
            var hours = totalSeconds / 3600;
            var minutes = (totalSeconds % 3600) / 60;
            return "%dh %dm".formatted(hours, minutes);
        }

        var minutes = totalSeconds / 60;
        var seconds = totalSeconds % 60;
        return "%dm %ds".formatted(minutes, seconds);
    }

    private <T> T randomFrom(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}