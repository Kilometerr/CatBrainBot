package com.catbrain.bot.listener;

import com.catbrain.bot.service.SchedulerService;
import com.catbrain.bot.service.StatusService;
import com.catbrain.bot.util.StatusFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.time.Instant;

@Slf4j
@RequiredArgsConstructor
public class SlashCommandListener extends ListenerAdapter {
    private final StatusService statusService;
    private final SchedulerService schedulerService;
    private final String channelId;

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
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

        log.info("Processing /catbrain {} command from user: {}", subcommand, event.getUser().getAsTag());

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
        event.deferReply().queue();

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
                    .setEphemeral(true)
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
                .setFooter("Cat Brain Bot v0.1.5")
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
                .setFooter("Cat Brain Bot v0.1.5")
                .setTimestamp(Instant.now())
                .build();

        event.replyEmbeds(embed).setEphemeral(true).queue();
        log.info("Changelog displayed to user: {}", event.getUser().getAsTag());
    }
}