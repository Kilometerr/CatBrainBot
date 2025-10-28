package com.catbrain.bot.model;

import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.List;

@Data
public class ChangelogEntry {
    @NotNull
    private String version;

    private int phase;

    @NotNull
    private LocalDate date;

    @NotNull
    private Changes changes;

    @Data
    public static class Changes {
        @NotNull
        private List<String> added = List.of();

        @NotNull
        private List<String> fixed = List.of();

        @NotNull
        private List<String> changed = List.of();

        @NotNull
        private List<String> removed = List.of();

        public boolean hasAnyChanges() {
            return !added.isEmpty() || !fixed.isEmpty() || !changed.isEmpty() || !removed.isEmpty();
        }
    }
}