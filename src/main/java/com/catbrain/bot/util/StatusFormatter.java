package com.catbrain.bot.util;

import com.catbrain.bot.model.StatusBox;
import lombok.experimental.UtilityClass;

@UtilityClass
public class StatusFormatter {
    private static final int BOX_WIDTH = 42;
    private static final String BOX_TOP = "╔════════════ CAT BRAIN STATUS ════════════╗";
    private static final String BOX_BOTTOM = "╚═══════════════════════════════════════════╝";

    public static String format(StatusBox status) {
        return """
            ```
            %s
            %s%s%s%s%s%s%s
            ```
            """.formatted(
                BOX_TOP,
                formatLine("Braincell Status: " + status.braincellStatus()),
                formatLine("Coherence Level: " + status.coherenceLevel() + "%"),
                formatLine("Confusion Index: " + status.confusionIndex()),
                formatLine("Processing Speed: " + status.processingSpeed()),
                formatLine("Memory Cache: " + status.memoryCache()),
                formatLine("Smart Thought ETA: " + status.smartThoughtETA()),
                BOX_BOTTOM
        );
    }

    private static String formatLine(String content) {
        return "║ %s ║\n".formatted(padRight(content));
    }

    private static String padRight(String s) {
        return s.length() >= StatusFormatter.BOX_WIDTH
                ? s.substring(0, StatusFormatter.BOX_WIDTH)
                : s + " ".repeat(StatusFormatter.BOX_WIDTH - s.length());
    }
}