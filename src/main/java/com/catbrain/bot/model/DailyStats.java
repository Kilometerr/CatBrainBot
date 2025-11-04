package com.catbrain.bot.model;

import java.time.LocalDate;

public record DailyStats(
        LocalDate date,
        int postCount,
        double avgCoherence,
        String dominantStatus,
        double qualityScore
) {
}