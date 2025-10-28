package com.catbrain.bot.service;

import com.catbrain.bot.model.ChangelogData;
import com.catbrain.bot.model.ChangelogEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;

@Slf4j
public class ChangelogManager {
    private static final String CHANGELOG_FILE = "changelog.json";
    private static final Color COLOR_LATEST = new Color(67, 181, 129);  // Green
    private static final Color COLOR_OLDER = new Color(88, 101, 242);   // Blue

    private static final String EMOJI_ADDED = "➕";
    private static final String EMOJI_FIXED = "🔧";
    private static final String EMOJI_CHANGED = "🔄";
    private static final String EMOJI_REMOVED = "➖";

    private ChangelogData changelogData;

    public void load() throws IOException {
        log.info("Loading changelog from {}...", CHANGELOG_FILE);

        try (var input = getClass().getClassLoader().getResourceAsStream(CHANGELOG_FILE)) {
            if (input == null) {
                throw new IOException("Changelog file not found: " + CHANGELOG_FILE);
            }

            var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            var gson = createGson();

            changelogData = gson.fromJson(reader, ChangelogData.class);

            if (changelogData == null || changelogData.getVersions().isEmpty()) {
                throw new IOException("Changelog file is empty or invalid");
            }

            log.info("Loaded {} version(s) from changelog", changelogData.getVersions().size());
        } catch (Exception e) {
            log.error("Failed to load changelog", e);
            throw new IOException("Failed to load changelog: " + e.getMessage(), e);
        }
    }

    public Optional<ChangelogEntry> getLatestVersion() {
        if (changelogData == null || changelogData.getVersions().isEmpty()) {
            log.warn("Changelog data not loaded or empty");
            return Optional.empty();
        }

        return changelogData.getVersions().stream()
                .max(Comparator.comparing(ChangelogEntry::getDate));
    }

    public Optional<ChangelogEntry> getVersion(String version) {
        if (changelogData == null) {
            log.warn("Changelog data not loaded");
            return Optional.empty();
        }

        return changelogData.getVersions().stream()
                .filter(entry -> entry.getVersion().equalsIgnoreCase(version))
                .findFirst();
    }

    public java.util.List<ChangelogEntry> getAllVersions() {
        if (changelogData == null || changelogData.getVersions().isEmpty()) {
            return java.util.List.of();
        }

        return changelogData.getVersions().stream()
                .sorted(Comparator.comparing(ChangelogEntry::getDate).reversed())
                .toList();
    }

    public MessageEmbed formatLatestVersion() {
        var latest = getLatestVersion();

        return latest.map(changelogEntry -> formatVersionEmbed(changelogEntry, true)).orElseGet(() -> createErrorEmbed("No changelog data available"));

    }

    public MessageEmbed formatVersion(String version) {
        var entry = getVersion(version);

        if (entry.isEmpty()) {
            return createErrorEmbed("Version " + version + " not found in changelog");
        }

        var latest = getLatestVersion();
        boolean isLatest = latest.isPresent() && latest.get().getVersion().equals(version);

        return formatVersionEmbed(entry.get(), isLatest);
    }

    private MessageEmbed formatVersionEmbed(ChangelogEntry entry, boolean isLatest) {
        var embed = new EmbedBuilder()
                .setTitle(String.format("🐱 Cat Brain Bot - %sChangelog",
                        isLatest ? "Latest " : ""))
                .setColor(isLatest ? COLOR_LATEST : COLOR_OLDER)
                .setTimestamp(Instant.now());

        var versionHeader = String.format("**Version %s** (Phase %d)\nReleased: %s",
                entry.getVersion(),
                entry.getPhase(),
                entry.getDate().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")));

        embed.setDescription(versionHeader);

        var changes = entry.getChanges();

        if (!changes.getAdded().isEmpty()) {
            embed.addField(
                    EMOJI_ADDED + " Added",
                    formatChangeList(changes.getAdded()),
                    false
            );
        }

        if (!changes.getFixed().isEmpty()) {
            embed.addField(
                    EMOJI_FIXED + " Fixed",
                    formatChangeList(changes.getFixed()),
                    false
            );
        }

        if (!changes.getChanged().isEmpty()) {
            embed.addField(
                    EMOJI_CHANGED + " Changed",
                    formatChangeList(changes.getChanged()),
                    false
            );
        }

        if (!changes.getRemoved().isEmpty()) {
            embed.addField(
                    EMOJI_REMOVED + " Removed",
                    formatChangeList(changes.getRemoved()),
                    false
            );
        }

        if (!changes.hasAnyChanges()) {
            embed.addField("Changes", "*No changes listed*", false);
        }

        return embed.build();
    }

    private String formatChangeList(java.util.List<String> changes) {
        if (changes.isEmpty()) {
            return "*None*";
        }

        var sb = new StringBuilder();
        for (String change : changes) {
            sb.append("• ").append(change).append("\n");
        }
        return sb.toString().trim();
    }

    private MessageEmbed createErrorEmbed(String message) {
        return new EmbedBuilder()
                .setTitle("❌ Changelog Error")
                .setDescription(message)
                .setColor(new Color(240, 71, 71))
                .setTimestamp(Instant.now())
                .build();
    }

    private Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (json, type, context) ->
                                LocalDate.parse(json.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE))
                .create();
    }
}