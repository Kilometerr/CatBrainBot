package com.catbrain.bot.model;

import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@Data
public class ChangelogData {
    @NotNull
    private List<ChangelogEntry> versions = List.of();
}