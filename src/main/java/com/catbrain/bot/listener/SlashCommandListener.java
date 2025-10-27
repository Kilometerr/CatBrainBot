package com.catbrain.bot.listener;

import com.catbrain.bot.service.SchedulerService;
import com.catbrain.bot.service.StatusService;
import com.catbrain.bot.util.StatusFormatter;
import com.catbrain.bot.util.VersionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.time.Instant;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class SlashCommandListener extends ListenerAdapter {
    private final StatusService statusService;
    private final SchedulerService schedulerService;
    private final String channelId;
    @Nullable
    private final String guildId;  // Optional: restrict to specific guild

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("❌ This bot only works in servers, not in DMs.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (guildId != null && !Objects.requireNonNull(event.getGuild()).getId().equals(guildId)) {
            log.warn("Command attempted from unauthorized guild: {} (expected: {})",
                    event.getGuild().getId(), guildId);
            event.reply("❌ This bot is not authorized to run in this server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        // Check if command is in the correct channel
        if (!event.getChannel().getId().equals(channelId)) {
            event.reply("❌ This command can only be used in the designated Cat Brain channel.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!event.getName().equals("catbrain")) {
            return;
        }

        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            event.reply("❌ Invalid command usage.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        log.info("Processing /catbrain {} command from user: {} in guild: {}",
                subcommand, event.getUser().getAsTag(), Objects.requireNonNull(event.getGuild()).getName());

        try {
            switch (subcommand) {
                case "check" -> handleCheckCommand(event);
                case "help" -> handleHelpCommand(event);
                case "changelog" -> handleChangelogCommand(event);
                default -> event.reply("❌ Unknown subcommand.")
                        .setEphemeral(true)
                        .queue();
            }
        } catch (Exception e) {
            log.error("Error handling slash command: {}", subcommand, e);
            event.reply("❌ An error occurred while processing your command.")
                    .setEphemeral(true)
                    .queue();
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
                        " `/catbrain changelog`",
                        "Displays recent updates and changes to the bot.",
                        false
                )
                .setFooter(VersionUtil.getFormattedVersion())
                .setTimestamp(Instant.now())
                .build();

        event.replyEmbeds(embed).setEphemeral(true).queue();
        log.info("Help command displayed to user: {}", event.getUser().getAsTag());
    }

    private void handleChangelogCommand(SlashCommandInteractionEvent event) {
        var embed = new EmbedBuilder()
                .setTitle("Cat Brain Bot - Changelog")
                .setColor(new Color(67, 181, 129))
                .addField(
                        "Last Stable Update: Version 0.2.0",
                        """
                                Changes:
                                - Automatic re-scheduling at midnight
                                - Totally needed multithreading
                                - Basic slash commands
                                - Smart Thought ETA actually counts down
                                - Dynamic box formatting
                                - Time collision prevention
                                """,
                        false
                )
                .addField(
                        "Current update (experimental): -",
                        """
                                """,
                        false
                )
                .setFooter(VersionUtil.getFormattedVersion())
                .setTimestamp(Instant.now())
                .build();

        event.replyEmbeds(embed).setEphemeral(true).queue();
        log.info("Changelog displayed to user: {}", event.getUser().getAsTag());
    }
}