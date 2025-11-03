package com.catbrain.bot.service;

import com.catbrain.bot.model.PostRecord;
import com.catbrain.bot.model.StatusBox;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
public class PersistenceService implements AutoCloseable {
    private static final String DB_FILE = "catbrain.db";
    private static final DateTimeFormatter SQL_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Connection connection;

    public PersistenceService() throws SQLException {
        boolean isNewDatabase = !new File(DB_FILE).exists();

        this.connection = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
        this.connection.setAutoCommit(true);

        if (isNewDatabase) {
            log.info("Creating new database: {}", DB_FILE);
        } else {
            log.info("Connected to existing database: {}", DB_FILE);
        }

        initializeSchema();
    }

    private void initializeSchema() throws SQLException {
        String[] schemas = {
                """
            CREATE TABLE IF NOT EXISTS post_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                posted_at TEXT NOT NULL,
                braincell_status TEXT NOT NULL,
                coherence_level INTEGER NOT NULL,
                confusion_index TEXT NOT NULL,
                processing_speed TEXT NOT NULL,
                memory_cache TEXT NOT NULL,
                smart_thought_eta TEXT NOT NULL,
                channel_id TEXT NOT NULL
            )
            """,
                """
            CREATE INDEX IF NOT EXISTS idx_post_history_posted_at 
            ON post_history(posted_at)
            """,
                """
            CREATE TABLE IF NOT EXISTS scheduled_posts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scheduled_for TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                executed INTEGER DEFAULT 0,
                executed_at TEXT
            )
            """,
                """
            CREATE INDEX IF NOT EXISTS idx_scheduled_posts_executed 
            ON scheduled_posts(executed, scheduled_for)
            """,
                """
            CREATE TABLE IF NOT EXISTS command_usage (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                command_name TEXT NOT NULL,
                subcommand TEXT,
                user_id TEXT NOT NULL,
                user_tag TEXT NOT NULL,
                guild_id TEXT,
                guild_name TEXT,
                executed_at TEXT NOT NULL
            )
            """,
                """
            CREATE INDEX IF NOT EXISTS idx_command_usage_executed_at 
            ON command_usage(executed_at)
            """,
                """
            CREATE TABLE IF NOT EXISTS bot_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        };

        try (Statement stmt = connection.createStatement()) {
            for (String schema : schemas) {
                stmt.execute(schema);
            }
            log.info("Database schema initialized successfully");
        }
    }

    // ==================== POST HISTORY ====================

    public void savePost(StatusBox status, LocalDateTime postedAt, String channelId) {
        String sql = """
            INSERT INTO post_history 
            (posted_at, braincell_status, coherence_level, confusion_index, 
             processing_speed, memory_cache, smart_thought_eta, channel_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, postedAt.format(SQL_DATETIME_FORMAT));
            pstmt.setString(2, status.braincellStatus());
            pstmt.setInt(3, status.coherenceLevel());
            pstmt.setString(4, status.confusionIndex());
            pstmt.setString(5, status.processingSpeed());
            pstmt.setString(6, status.memoryCache());
            pstmt.setString(7, status.smartThoughtETA());
            pstmt.setString(8, channelId);

            pstmt.executeUpdate();
            log.debug("Saved post to database: {} at {}", status.braincellStatus(), postedAt);
        } catch (SQLException e) {
            log.error("Failed to save post to database", e);
        }
    }

    public List<PostRecord> getPostHistory(LocalDate date) {
        String sql = """
            SELECT * FROM post_history 
            WHERE DATE(posted_at) = ?
            ORDER BY posted_at DESC
            """;

        List<PostRecord> records = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, date.toString());

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapToPostRecord(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to retrieve post history for date: {}", date, e);
        }

        return records;
    }

    public List<PostRecord> getRecentPosts(int limit) {
        String sql = "SELECT * FROM post_history ORDER BY posted_at DESC LIMIT ?";

        List<PostRecord> records = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapToPostRecord(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to retrieve recent posts", e);
        }

        return records;
    }

    private PostRecord mapToPostRecord(ResultSet rs) throws SQLException {
        return new PostRecord(
                rs.getLong("id"),
                LocalDateTime.parse(rs.getString("posted_at"), SQL_DATETIME_FORMAT),
                rs.getString("braincell_status"),
                rs.getInt("coherence_level"),
                rs.getString("confusion_index"),
                rs.getString("processing_speed"),
                rs.getString("memory_cache"),
                rs.getString("smart_thought_eta"),
                rs.getString("channel_id")
        );
    }

    // ==================== SCHEDULED POSTS ====================

    public void saveScheduledPost(LocalDateTime scheduledFor) {
        String sql = """
            INSERT OR IGNORE INTO scheduled_posts (scheduled_for, created_at)
            VALUES (?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, scheduledFor.format(SQL_DATETIME_FORMAT));
            pstmt.setString(2, LocalDateTime.now().format(SQL_DATETIME_FORMAT));
            pstmt.executeUpdate();

            log.debug("Saved scheduled post: {}", scheduledFor);
        } catch (SQLException e) {
            log.error("Failed to save scheduled post", e);
        }
    }

    public void saveScheduledPosts(List<LocalDateTime> scheduledTimes) {
        String sql = """
            INSERT OR IGNORE INTO scheduled_posts (scheduled_for, created_at)
            VALUES (?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            String now = LocalDateTime.now().format(SQL_DATETIME_FORMAT);

            for (LocalDateTime time : scheduledTimes) {
                pstmt.setString(1, time.format(SQL_DATETIME_FORMAT));
                pstmt.setString(2, now);
                pstmt.addBatch();
            }

            pstmt.executeBatch();
            log.info("Saved {} scheduled posts to database", scheduledTimes.size());
        } catch (SQLException e) {
            log.error("Failed to save scheduled posts batch", e);
        }
    }

    public List<LocalDateTime> getUnexecutedSchedules() {
        String sql = """
            SELECT scheduled_for FROM scheduled_posts 
            WHERE executed = 0 
            ORDER BY scheduled_for ASC
            """;

        List<LocalDateTime> times = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                times.add(LocalDateTime.parse(rs.getString("scheduled_for"), SQL_DATETIME_FORMAT));
            }

            log.info("Retrieved {} unexecuted scheduled posts from database", times.size());
        } catch (SQLException e) {
            log.error("Failed to retrieve unexecuted schedules", e);
        }

        return times;
    }

    public void markPostExecuted(LocalDateTime scheduledFor) {
        String sql = """
            UPDATE scheduled_posts 
            SET executed = 1, executed_at = ?
            WHERE scheduled_for = ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, LocalDateTime.now().format(SQL_DATETIME_FORMAT));
            pstmt.setString(2, scheduledFor.format(SQL_DATETIME_FORMAT));
            pstmt.executeUpdate();

            log.debug("Marked post as executed: {}", scheduledFor);
        } catch (SQLException e) {
            log.error("Failed to mark post as executed", e);
        }
    }

    public void clearOldSchedules(LocalDate beforeDate) {
        String sql = "DELETE FROM scheduled_posts WHERE DATE(scheduled_for) < ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, beforeDate.toString());
            int deleted = pstmt.executeUpdate();

            if (deleted > 0) {
                log.info("Cleared {} old scheduled posts from before {}", deleted, beforeDate);
            }
        } catch (SQLException e) {
            log.error("Failed to clear old schedules", e);
        }
    }

    // ==================== COMMAND USAGE ====================

    public void logCommandUsage(String commandName, String subcommand, String userId,
                                String userTag, String guildId, String guildName) {
        String sql = """
            INSERT INTO command_usage 
            (command_name, subcommand, user_id, user_tag, guild_id, guild_name, executed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, commandName);
            pstmt.setString(2, subcommand);
            pstmt.setString(3, userId);
            pstmt.setString(4, userTag);
            pstmt.setString(5, guildId);
            pstmt.setString(6, guildName);
            pstmt.setString(7, LocalDateTime.now().format(SQL_DATETIME_FORMAT));

            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to log command usage", e);
        }
    }

    // ==================== STATISTICS ====================

    public int getPostCount(Period period) {
        LocalDateTime cutoff = LocalDateTime.now().minus(period);
        String sql = "SELECT COUNT(*) FROM post_history WHERE posted_at >= ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, cutoff.format(SQL_DATETIME_FORMAT));

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get post count", e);
        }

        return 0;
    }

    public int getTotalPostCount() {
        String sql = "SELECT COUNT(*) FROM post_history";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to get total post count", e);
        }

        return 0;
    }

    public double getAverageCoherence(Period period) {
        LocalDateTime cutoff = LocalDateTime.now().minus(period);
        String sql = "SELECT AVG(coherence_level) FROM post_history WHERE posted_at >= ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, cutoff.format(SQL_DATETIME_FORMAT));

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get average coherence", e);
        }

        return 0.0;
    }

    public Map<String, Integer> getBraincellStatusDistribution(Period period) {
        LocalDateTime cutoff = LocalDateTime.now().minus(period);
        String sql = """
            SELECT braincell_status, COUNT(*) as count 
            FROM post_history 
            WHERE posted_at >= ?
            GROUP BY braincell_status
            """;

        Map<String, Integer> distribution = new HashMap<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, cutoff.format(SQL_DATETIME_FORMAT));

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    distribution.put(rs.getString("braincell_status"), rs.getInt("count"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get braincell status distribution", e);
        }

        return distribution;
    }

    public Map<String, Integer> getConfusionDistribution(Period period) {
        LocalDateTime cutoff = LocalDateTime.now().minus(period);
        String sql = """
            SELECT confusion_index, COUNT(*) as count 
            FROM post_history 
            WHERE posted_at >= ?
            GROUP BY confusion_index
            """;

        Map<String, Integer> distribution = new LinkedHashMap<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, cutoff.format(SQL_DATETIME_FORMAT));

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    distribution.put(rs.getString("confusion_index"), rs.getInt("count"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get confusion distribution", e);
        }

        return distribution;
    }

    public int getCommandUsageCount(Period period) {
        LocalDateTime cutoff = LocalDateTime.now().minus(period);
        String sql = "SELECT COUNT(*) FROM command_usage WHERE executed_at >= ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, cutoff.format(SQL_DATETIME_FORMAT));

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get command usage count", e);
        }

        return 0;
    }

    public Map<String, Integer> getTopCommands(Period period, int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minus(period);
        String sql = """
            SELECT command_name || COALESCE(' ' || subcommand, '') as full_command, 
                   COUNT(*) as count
            FROM command_usage 
            WHERE executed_at >= ?
            GROUP BY full_command
            ORDER BY count DESC
            LIMIT ?
            """;

        Map<String, Integer> commands = new LinkedHashMap<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, cutoff.format(SQL_DATETIME_FORMAT));
            pstmt.setInt(2, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    commands.put(rs.getString("full_command"), rs.getInt("count"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get top commands", e);
        }

        return commands;
    }

    // ==================== BOT STATE ====================

    public void setState(String key, String value) {
        String sql = """
            INSERT OR REPLACE INTO bot_state (key, value, updated_at)
            VALUES (?, ?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.setString(3, LocalDateTime.now().format(SQL_DATETIME_FORMAT));

            pstmt.executeUpdate();
            log.debug("Set bot state: {} = {}", key, value);
        } catch (SQLException e) {
            log.error("Failed to set bot state", e);
        }
    }

    public Optional<String> getState(String key) {
        String sql = "SELECT value FROM bot_state WHERE key = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, key);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("value"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get bot state for key: {}", key, e);
        }

        return Optional.empty();
    }

    // ==================== CLEANUP ====================

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("Database connection closed");
            }
        } catch (SQLException e) {
            log.error("Error closing database connection", e);
        }
    }
}