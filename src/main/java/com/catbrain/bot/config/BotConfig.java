package com.catbrain.bot.config;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;

@Slf4j
public record BotConfig(
        @NotNull String botToken,
        @NotNull String channelId,
        int dailyPosts,
        int startHour,
        int endHour
) {

    public BotConfig {
        validateHourRange(startHour, "start.hour");
        validateHourRange(endHour, "end.hour");

        if (startHour >= endHour) {
            throw new IllegalStateException("start.hour must be less than end.hour");
        }
        if (dailyPosts <= 0) {
            throw new IllegalStateException("daily.posts must be greater than 0");
        }
    }

    public static BotConfig load() throws IOException {
        var properties = loadProperties();

        // Fallback to env vars for secrets
        var botToken = getProperty(properties, "bot.token", "BOT_TOKEN");
        var channelId = getProperty(properties, "channel.id", "CHANNEL_ID");
        var dailyPosts = getIntProperty(properties, "daily.posts");
        var startHour = getIntProperty(properties, "start.hour");
        var endHour = getIntProperty(properties, "end.hour");

        log.info("Configuration loaded successfully.");
        return new BotConfig(botToken, channelId, dailyPosts, startHour, endHour);
    }

    private static Properties loadProperties() throws IOException {
        var properties = new Properties();
        try (var input = BotConfig.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new IOException("Unable to find config.properties");
            }
            properties.load(input);
        }
        return properties;
    }

    private static String getProperty(Properties properties, String propertyKey, String envKey) {
        return Optional.ofNullable(System.getenv(envKey))
                .or(() -> Optional.ofNullable(properties.getProperty(propertyKey)))
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing required property: %s (or env var: %s)".formatted(propertyKey, envKey)
                ));
    }

    private static int getIntProperty(Properties properties, String key) {
        var value = Optional.ofNullable(properties.getProperty(key))
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("Missing required property: " + key));

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid integer value for %s: %s".formatted(key, value), e);
        }
    }

    private static void validateHourRange(int hour, String fieldName) {
        if (hour < 0 || hour > 23) {
            throw new IllegalStateException("%s must be between 0 and 23, got: %d".formatted(fieldName, hour));
        }
    }
}