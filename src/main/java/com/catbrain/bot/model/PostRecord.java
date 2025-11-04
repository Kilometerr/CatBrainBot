package com.catbrain.bot.model;

import java.time.LocalDateTime;

public record PostRecord(
        long id,
        LocalDateTime postedAt,
        String braincellStatus,
        int coherenceLevel,
        String confusionIndex,
        String processingSpeed,
        String memoryCache,
        String smartThoughtETA,
        String channelId
) {
}