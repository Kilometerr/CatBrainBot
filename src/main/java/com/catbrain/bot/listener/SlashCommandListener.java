package com.catbrain.bot.listener;

import com.catbrain.bot.service.ChangelogManager;
import com.catbrain.bot.service.PersistenceService;
import com.catbrain.bot.service.SchedulerService;
import com.catbrain.bot.service.StatusService;
import com.catbrain.bot.util.StatusFormatter;
import com.catbrain.bot.util.VersionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.time.Instant;
import java.time.Period;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class SlashCommandListener extends ListenerAdapter {
    private final StatusService statusService;
    private final SchedulerService schedulerService;
    private final ChangelogManager changelogManager;
    private final PersistenceService persistenceService;
    private final String channelId;
    @Nullable
    private final String guildId;

    private void replyEphemeral(SlashCommandInteractionEvent event, String msg) {
        event.reply(msg).setEphemeral(true).queue();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            replyEphemeral(event,"❌ This bot only works in servers, not in DMs.");
            return;
        }

        if (guildId != null && !Objects.requireNonNull(event.getGuild()).getId().equals(guildId)) {
            log.warn("Command attempted from unauthorized guild: {} (expected: {})",
                    event.getGuild().getId(), guildId);
            replyEphemeral(event,"❌ This bot is not authorized to run in this server.");
            return;
        }

        if (!event.getChannel().getId().equals(channelId)) {
            replyEphemeral(event,"❌ This command can only be used in the designated Cat Brain channel.");
            return;
        }

        if (!event.getName().equals("catbrain")) {
            return;
        }

        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            replyEphemeral(event,"❌ Invalid command usage.");
            return;
        }

        log.info("Processing /catbrain {} command from user: {} in guild: {}",
                subcommand, event.getUser().getAsTag(), Objects.requireNonNull(event.getGuild()).getName());

        persistenceService.logCommandUsage(
                "catbrain",
                subcommand,
                event.getUser().getId(),
                event.getUser().getAsTag(),
                event.getGuild().getId(),
                event.getGuild().getName()
        );

        try {
            switch (subcommand) {
                case "check" -> handleCheckCommand(event);
                case "help" -> handleHelpCommand(event);
                case "changelog" -> handleChangelogCommand(event);
                case "stats" -> handleStatsCommand(event);
                default -> event.reply("❌ Unknown subcommand.")
                        .setEphemeral(true)
                        .queue();
            }
        } catch (Exception e) {
            log.error("Error handling slash command: {}", subcommand, e);
            replyEphemeral(event,"❌ An error occurred while processing your command.");
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("catbrain") ||
                !"changelog".equals(event.getSubcommandName()) ||
                !"version".equals(event.getFocusedOption().getName())) {
            return;
        }

        try {
            String userInput = event.getFocusedOption().getValue().toLowerCase();

            var versions = changelogManager.getAllVersions();

            var choices = versions.stream()
                    .map(entry -> {
                        var version = entry.getVersion();
                        var description = String.format("Phase %d - %s",
                                entry.getPhase(),
                                entry.getDate());
                        return new Command.Choice(version + " (" + description + ")", version);
                    })
                    .filter(choice -> userInput.isEmpty() ||
                            choice.getName().toLowerCase().contains(userInput))
                    .limit(25)
                    .toList();

            event.replyChoices(choices).queue();
            log.debug("Provided {} version choices for autocomplete", choices.size());

        } catch (Exception e) {
            log.error("Error handling autocomplete for changelog versions", e);
            event.replyChoices(java.util.List.of()).queue();
        }
    }

    private void handleCheckCommand(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        try {
            var nextPostTime = schedulerService.getNextScheduledPostTime().orElse(null);
            var status = statusService.generateStatus(nextPostTime);
            var embed = StatusFormatter.format(status);

            event.getHook().sendMessageEmbeds(embed).queue(
                    success -> log.info("Manual status check posted successfully"),
                    error -> log.error("Failed to post manual status check", error)
            );
        } catch (Exception e) {
            log.error("Error generating status for check command", e);
            event.getHook().sendMessage("❌ Failed to generate status.")
                    .queue();
        }
    }

    private void handleHelpCommand(SlashCommandInteractionEvent event) {
        var embed = new EmbedBuilder()
                .setTitle("🐱 Cat Brain Bot - Commands")
                .setColor(new Color(88, 101, 242))
                .addField(
                        " `/catbrain check`",
                        "Immediately generates and displays the current cat brain status.",
                        false
                )
                .addField(
                        " `/catbrain stats [period]`",
                        "View bot statistics and analytics. Optional period: `day`, `week`, `month`, or `alltime` (default: week).",
                        false
                )
                .addField(
                        " `/catbrain changelog [version]`",
                        "Displays recent updates and changes to the bot. Optionally specify a version (e.g., `0.1.0`) to view that specific release.",
                        false
                )
                .setFooter(VersionUtil.getFormattedVersion())
                .setTimestamp(Instant.now())
                .build();

        event.replyEmbeds(embed).setEphemeral(true).queue();
        log.info("Help command displayed to user: {}", event.getUser().getAsTag());
    }

    private void handleChangelogCommand(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        try {
            var versionOption = event.getOption("version");
            MessageEmbed embed;

            if (versionOption != null) {
                String requestedVersion = versionOption.getAsString().trim();
                log.info("User {} requested changelog for version: {}",
                        event.getUser().getAsTag(), requestedVersion);
                embed = changelogManager.formatVersion(requestedVersion);
            } else {
                embed = changelogManager.formatLatestVersion();
            }

            event.getHook().sendMessageEmbeds(embed).queue(
                    success -> log.info("Changelog displayed to user: {}", event.getUser().getAsTag()),
                    error -> log.error("Failed to display changelog", error)
            );
        } catch (Exception e) {
            log.error("Error displaying changelog", e);

            var errorEmbed = new EmbedBuilder()
                    .setTitle("❌ Changelog Error")
                    .setDescription("Failed to load changelog. Please contact the bot administrator.")
                    .setColor(new Color(240, 71, 71))
                    .setTimestamp(Instant.now())
                    .build();

            event.getHook().sendMessageEmbeds(errorEmbed).queue();
        }
    }

    private void handleStatsCommand(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        try {
            var periodOption = event.getOption("period");
            String periodStr = periodOption != null ? periodOption.getAsString().toLowerCase() : "week";

            Period period = switch (periodStr) {
                case "day" -> Period.ofDays(1);
                case "week" -> Period.ofWeeks(1);
                case "month" -> Period.ofMonths(1);
                case "alltime" -> Period.ofYears(100);
                default -> Period.ofWeeks(1);
            };

            String periodLabel = switch (periodStr) {
                case "day" -> "Today";
                case "week" -> "This Week";
                case "month" -> "This Month";
                case "alltime" -> "All Time";
                default -> "This Week";
            };

            var embed = buildStatsEmbed(period, periodLabel);

            event.getHook().sendMessageEmbeds(embed).queue(
                    success -> log.info("Stats displayed to user: {} (period: {})",
                            event.getUser().getAsTag(), periodStr),
                    error -> log.error("Failed to display stats", error)
            );
        } catch (Exception e) {
            log.error("Error generating stats", e);

            var errorEmbed = new EmbedBuilder()
                    .setTitle("❌ Statistics Error")
                    .setDescription("Failed to generate statistics. Please try again later.")
                    .setColor(new Color(240, 71, 71))
                    .setTimestamp(Instant.now())
                    .build();

            event.getHook().sendMessageEmbeds(errorEmbed).queue();
        }
    }

    private MessageEmbed buildStatsEmbed(Period period, String periodLabel) {
        var embed = new EmbedBuilder()
                .setTitle("📊 Cat Brain Statistics - " + periodLabel)
                .setColor(new Color(67, 181, 129))
                .setTimestamp(Instant.now());

        int postCount = persistenceService.getPostCount(period);
        int totalPosts = persistenceService.getTotalPostCount();
        double avgCoherence = persistenceService.getAverageCoherence(period);

        embed.addField("📝 Total Posts", String.valueOf(postCount), true);

        if (postCount > 0) {
            embed.addField("🧠 Avg Coherence", String.format("%.1f%%", avgCoherence), true);
            embed.addBlankField(true);
        }

        var statusDist = persistenceService.getBraincellStatusDistribution(period);
        if (!statusDist.isEmpty()) {
            StringBuilder statusText = new StringBuilder();
            statusDist.forEach((status, count) -> {
                double percentage = (count * 100.0) / postCount;
                statusText.append(String.format("**%s**: %d (%.1f%%)\n", status, count, percentage));
            });
            embed.addField("🔌 Braincell Status Distribution", statusText.toString().trim(), false);
        }

        var confusionDist = persistenceService.getConfusionDistribution(period);
        if (!confusionDist.isEmpty() && confusionDist.size() <= 5) {
            StringBuilder confusionText = new StringBuilder();
            confusionDist.forEach((confusion, count) -> {
                double percentage = (count * 100.0) / postCount;
                confusionText.append(String.format("**%s**: %d (%.1f%%)\n", confusion, count, percentage));
            });
            embed.addField("😵 Confusion Index Distribution", confusionText.toString().trim(), false);
        }

        // Command usage
        int commandCount = persistenceService.getCommandUsageCount(period);
        if (commandCount > 0) {
            embed.addField("⚡ Commands Used", String.valueOf(commandCount), true);

            var topCommands = persistenceService.getTopCommands(period, 3);
            if (!topCommands.isEmpty()) {
                StringBuilder commandText = new StringBuilder();
                topCommands.forEach((cmd, count) ->
                        commandText.append(String.format("`%s`: %d\n", cmd, count))
                );
                embed.addField("🏆 Top Commands", commandText.toString().trim(), true);
            }

            embed.addBlankField(true);
        }

        var bestDay = persistenceService.getBestDay();
        var worstDay = persistenceService.getWorstDay();

        if (bestDay.isPresent() && worstDay.isPresent()) {
            var best = bestDay.get();
            var worst = worstDay.get();

            String bestDayText = String.format(
                    "**%s**\n%d posts %.1f%% coherence\n*Quality Score: %.1f*",
                    best.date().format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")),
                    best.postCount(),
                    best.avgCoherence(),
                    best.qualityScore()
            );

            String worstDayText = String.format(
                    "**%s**\n%d posts %.1f%% coherence\n*Quality Score: %.1f*",
                    worst.date().format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")),
                    worst.postCount(),
                    worst.avgCoherence(),
                    worst.qualityScore()
            );

            embed.addField("Best Day", bestDayText, true);
            embed.addField("Worst Day", worstDayText, true);
            embed.addBlankField(true);
        }

        if (!periodLabel.equals("All Time")) {
            embed.setFooter(String.format("All-time posts: %d • %s",
                    totalPosts, VersionUtil.getFormattedVersion()));
        } else {
            embed.setFooter(VersionUtil.getFormattedVersion());
        }

        return embed.build();
    }
}