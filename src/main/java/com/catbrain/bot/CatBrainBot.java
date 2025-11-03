package com.catbrain.bot;

import com.catbrain.bot.config.BotConfig;
import com.catbrain.bot.listener.SlashCommandListener;
import com.catbrain.bot.service.ChangelogManager;
import com.catbrain.bot.service.PersistenceService;
import com.catbrain.bot.service.SchedulerService;
import com.catbrain.bot.service.StatusService;
import com.catbrain.bot.util.StatusFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
public class CatBrainBot {
    private final BotConfig config;
    private final StatusService statusService;
    private final SchedulerService schedulerService;
    private final ChangelogManager changelogManager;
    private final PersistenceService persistenceService;

    private JDA jda;

    public void start() throws InterruptedException {
        log.info("Cat Brain Bot starting...");

        try {
            changelogManager.load();
            log.info("Changelog loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load changelog - bot will continue but changelog command may not work", e);
        }

        jda = JDABuilder.createDefault(config.botToken())
                .enableIntents(GatewayIntent.GUILD_MESSAGES)
                .build()
                .awaitReady();

        log.info("Connected to Discord!");

        registerSlashCommands();
        registerEventListeners();
        scheduleDailyPosts();
        registerShutdownHook();
    }

    private void registerSlashCommands() {
        log.info("Registering slash commands...");

        var catbrainCommand = Commands.slash("catbrain", "Cat Brain Bot commands")
                .addSubcommands(
                        new SubcommandData("check", "Check the current cat brain status"),
                        new SubcommandData("help", "Show help information and available commands"),
                        new SubcommandData("changelog", "View recent changes and updates")
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING,
                                        "version",
                                        "Specific version to view (e.g., 0.2.0). Leave empty for latest.",
                                        false,
                                        true),
                        new SubcommandData("stats", "View bot statistics and analytics")
                                .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING,
                                        "period",
                                        "Time period for statistics (day/week/month/alltime)",
                                        false)
                );

        if (config.guildId() != null) {
            var guild = jda.getGuildById(config.guildId());
            if (guild != null) {
                guild.updateCommands()
                        .addCommands(catbrainCommand)
                        .queue(
                                success -> log.info("Slash commands registered successfully (guild-scoped)"),
                                error -> log.error("Failed to register guild-scoped slash commands", error)
                        );
                log.info("Command registration initiated (guild-scoped - instant propagation)");
            } else {
                log.warn("Guild ID configured but guild not found: {}", config.guildId());
                registerGlobalCommands(catbrainCommand);
            }
        } else {
            registerGlobalCommands(catbrainCommand);
        }
    }

    private void registerGlobalCommands(CommandData catbrainCommand) {
        jda.updateCommands()
                .addCommands(catbrainCommand)
                .queue(
                        success -> log.info("Slash commands registered successfully (global)"),
                        error -> log.error("Failed to register global slash commands", error)
                );
        log.info("Command registration initiated (global - may take up to 1 hour to propagate)");
    }

    private void registerEventListeners() {
        var slashCommandListener = new SlashCommandListener(
                statusService,
                schedulerService,
                changelogManager,
                persistenceService,
                config.channelId(),
                config.guildId()
        );
        jda.addEventListener(slashCommandListener);
        log.info("Event listeners registered");
    }

    private void scheduleDailyPosts() {
        schedulerService.scheduleDailyPosts(
                config.minDailyPosts(),
                config.maxDailyPosts(),
                config.startHour(),
                config.endHour(),
                this::postStatusUpdate,
                persistenceService
        );
    }

    private void postStatusUpdate() {
        try {
            log.info("Posting status update to channel...");

            var channel = jda.getTextChannelById(config.channelId());
            if (channel == null) {
                log.error("Could not find channel with ID: {}", config.channelId());
                return;
            }

            var nextPostTime = schedulerService.getNextScheduledPostTime().orElse(null);
            var status = statusService.generateStatus(nextPostTime);
            log.debug("Generated status: {} (next post at: {})", status, nextPostTime);

            var embed = StatusFormatter.format(status);

            channel.sendMessageEmbeds(embed).queue(
                    success -> {
                        log.info("Status update posted successfully!");
                        persistenceService.savePost(status, LocalDateTime.now(), config.channelId());
                    },
                    error -> log.error("Failed to post status update", error)
            );
        } catch (Exception e) {
            log.error("Error posting status update", e);
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));
    }

    private void shutdown() {
        log.info("Cat Brain Bot shutting down...");

        if (schedulerService != null) {
            schedulerService.shutdown();
            log.debug("Scheduler shut down.");
        }

        if (persistenceService != null) {
            persistenceService.close();
            log.debug("Persistence service shut down.");
        }

        if (jda != null) {
            jda.shutdown();
            log.debug("JDA shut down.");
        }

        log.info("Bot shut down successfully.");
    }

    public static void main(@NotNull String[] args) {
        PersistenceService persistenceService = null;
        try {
            var config = BotConfig.load();

            persistenceService = new PersistenceService();
            log.info("Persistence service initialized");

            var bot = new CatBrainBot(
                    config,
                    new StatusService(),
                    new SchedulerService(),
                    new ChangelogManager(),
                    persistenceService
            );
            bot.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted during bot startup", e);
            persistenceService.close();
            System.exit(1);
        } catch (Exception e) {
            log.error("Failed to start Cat Brain Bot", e);
            if (persistenceService != null) {
                persistenceService.close();
            }
            System.exit(1);
        }
    }
}