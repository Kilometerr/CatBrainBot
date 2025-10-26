package com.catbrain.bot;

import com.catbrain.bot.config.BotConfig;
import com.catbrain.bot.listener.SlashCommandListener;
import com.catbrain.bot.service.SchedulerService;
import com.catbrain.bot.service.StatusService;
import com.catbrain.bot.util.StatusFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;

@Slf4j
@RequiredArgsConstructor
public class CatBrainBot {
    private final BotConfig config;
    private final StatusService statusService;
    private final SchedulerService schedulerService;

    private JDA jda;

    public void start() throws InterruptedException {
        log.info("Cat Brain Bot starting...");

        jda = JDABuilder.createDefault(config.botToken())
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
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
                );

        jda.updateCommands()
                .addCommands(catbrainCommand)
                .queue(
                        success -> log.info("Slash commands registered successfully"),
                        error -> log.error("Failed to register slash commands", error)
                );

        log.info("Command registration initiated (may take up to 1 hour to propagate globally)");
    }

    private void registerEventListeners() {
        var slashCommandListener = new SlashCommandListener(statusService, config.channelId());
        jda.addEventListener(slashCommandListener);
        log.info("Event listeners registered");
    }

    private void scheduleDailyPosts() {
        schedulerService.scheduleDailyPosts(
                config.dailyPosts(),
                config.startHour(),
                config.endHour(),
                this::postStatusUpdate
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

            var status = statusService.generateStatus();
            log.debug("Generated status: {}", status);

            var embed = StatusFormatter.format(status);

            channel.sendMessageEmbeds(embed).queue(
                    success -> log.info("Status update posted successfully!"),
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

        if (jda != null) {
            jda.shutdown();
            log.debug("JDA shut down.");
        }

        log.info("Bot shut down successfully.");
    }

    public static void main(@NotNull String[] args) {
        try {
            var config = BotConfig.load();
            var bot = new CatBrainBot(config, new StatusService(), new SchedulerService());
            bot.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted during bot startup", e);
            System.exit(1);
        } catch (Exception e) {
            log.error("Failed to start Cat Brain Bot", e);
            System.exit(1);
        }
    }
}