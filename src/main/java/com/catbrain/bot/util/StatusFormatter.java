package com.catbrain.bot.util;

import com.catbrain.bot.model.StatusBox;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.*;
import java.time.Instant;

@UtilityClass
public class StatusFormatter {

    // Color scheme for different braincell statuses
    private static final Color COLOR_ONLINE = new Color(67, 181, 129);
    private static final Color COLOR_OFFLINE = new Color(240, 71, 71);
    private static final Color COLOR_BUFFERING = new Color(250, 166, 26);
    private static final Color COLOR_NOT_FOUND = new Color(153, 170, 181);
    private static final Color COLOR_DEFAULT = new Color(88, 101, 242);

    public static MessageEmbed format(StatusBox status) {
        return createEmbed(
                status.braincellStatus(),
                status.coherenceLevel(),
                status.confusionIndex(),
                status.processingSpeed(),
                status.memoryCache(),
                status.smartThoughtETA()
        );
    }

    public static MessageEmbed createEmbed(
            String braincell,
            int coherence,
            String confusion,
            String processing,
            String memory,
            String eta) {

        EmbedBuilder embed = new EmbedBuilder();

        embed.setTitle("🧠 Cat Brain Status Report");
        embed.setColor(getColorForStatus(braincell));

        embed.addField("🔌 Braincell Status", braincell, true);
        embed.addField("📊 Coherence Level", coherence + "%", true);

        embed.addField("😵 Confusion Index", confusion, true);
        embed.addField("⚡ Processing Speed", processing, true);

        embed.addField("💾 Memory Cache", memory, true);
        embed.addField("💡 Smart Thought ETA", eta, true);

        embed.setFooter("Status checked");
        embed.setTimestamp(Instant.now());

        return embed.build();
    }

    private static Color getColorForStatus(String status) {
        return switch (status.toLowerCase()) {
            case "online" -> COLOR_ONLINE;
            case "offline" -> COLOR_OFFLINE;
            case "buffering" -> COLOR_BUFFERING;
            case "404 not found" -> COLOR_NOT_FOUND;
            default -> COLOR_DEFAULT;
        };
    }
}