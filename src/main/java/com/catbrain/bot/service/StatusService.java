package com.catbrain.bot.service;

import com.catbrain.bot.model.StatusBox;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
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

    public StatusBox generateStatus() {
        log.debug("Generating new status box");

        return new StatusBox(
                generateBraincellStatus(),
                generateCoherenceLevel(),
                randomFrom(CONFUSION_LEVELS),
                randomFrom(PROCESSING_SPEEDS),
                randomFrom(MEMORY_CACHES),
                generateSmartThoughtETA()
        );
    }

    private String generateBraincellStatus() {
        return random.nextBoolean()
                ? "Online"
                : randomFrom(BRAINCELL_STATUSES.subList(1, 4));
    }

    private int generateCoherenceLevel() {
        return random.nextInt(101); // 0 to 100 inclusive
    }

    private String generateSmartThoughtETA() {
        var minutes = 1 + random.nextInt(59); // 1 to 59
        var seconds = random.nextInt(60); // 0 to 59
        return "%dm %ds".formatted(minutes, seconds);
    }

    private <T> T randomFrom(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}