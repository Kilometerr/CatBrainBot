package com.catbrain.bot.model;

import org.jetbrains.annotations.NotNull;

public record StatusBox(
        @NotNull String braincellStatus,
        int coherenceLevel,
        @NotNull String confusionIndex,
        @NotNull String processingSpeed,
        @NotNull String memoryCache,
        @NotNull String smartThoughtETA
) {
    public StatusBox {
        if (coherenceLevel < 0 || coherenceLevel > 100) {
            throw new IllegalArgumentException("Coherence level must be between 0 and 100");
        }
    }
}