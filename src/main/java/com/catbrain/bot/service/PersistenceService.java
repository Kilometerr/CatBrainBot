package com.catbrain.bot.service;

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
    private static final DateTimeFormatter SQL_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Object dbLock = new Object();
    private final Connection connection;

    public PersistenceService() throws SQLException {
        File baseDir = determineBaseDirectory();
        File dataDir = new File(baseDir, "data");

        if (!dataDir.exists()) {
            if (dataDir.mkdirs()) {
                log.info("Created data directory: {}", dataDir.getAbsolutePath());
            } else {
                log.warn("Failed to create data directory: {}", dataDir.getAbsolutePath());
            }
        }

        String dbPath = new File(dataDir, "catbrain.db").getAbsolutePath();
        boolean isNewDatabase = !new File(dbPath).exists();

        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.connection.setAutoCommit(true);

        if (isNewDatabase) {
            log.info("Creating new database: {}", dbPath);
        } else {
            log.info("Connected to existing database: {}", dbPath);
        }

        initializeSchema();
    }

    private File determineBaseDirectory() {
        File currentDir = new File(System.getProperty("user.dir"));

        if (currentDir.getName().equals("target")) {
            File parentDir = currentDir.getParentFile();
            if (parentDir != null) {
                log.debug("Running from target/ directory, using project root: {}", parentDir.getAbsolutePath());
                return parentDir;
            }
        }

        log.debug("Using current directory as base: {}", currentDir.getAbsolutePath());
        return currentDir;
    }

    private void initializeSchema() throws SQLException {
        synchronized (dbLock) {
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
                """,
                    """
                CREATE TABLE IF NOT EXISTS best_worst_days (
                    type TEXT PRIMARY KEY CHECK(type IN ('best', 'worst')),
                    date TEXT NOT NULL,
                    post_count INTEGER NOT NULL,
                    avg_coherence REAL NOT NULL,
                    dominant_status TEXT NOT NULL,
                    quality_score REAL NOT NULL,
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
    }

    // ==================== POST HISTORY ====================

    public void savePost(StatusBox status, LocalDateTime postedAt, String channelId) {
        synchronized (dbLock) {
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
    }

    // ==================== SCHEDULED POSTS ====================

    public void saveScheduledPosts(List<LocalDateTime> scheduledTimes) {
        synchronized (dbLock) {
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
    }

    public List<LocalDateTime> getUnexecutedSchedules() {
        synchronized (dbLock) {
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
    }

    public void markPostExecuted(LocalDateTime scheduledFor) {
        synchronized (dbLock) {
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
    }

    public void clearOldSchedules(LocalDate beforeDate) {
        synchronized (dbLock) {
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
    }

    // ==================== COMMAND USAGE ====================

    public void logCommandUsage(String commandName, String subcommand, String userId,
                                String userTag, String guildId, String guildName) {
        synchronized (dbLock) {
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
    }

    // ==================== STATISTICS ====================

    public int getPostCount(Period period) {
        synchronized (dbLock) {
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
    }

    public int getTotalPostCount() {
        synchronized (dbLock) {
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
    }

    public double getAverageCoherence(Period period) {
        synchronized (dbLock) {
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
    }

    public Map<String, Integer> getBraincellStatusDistribution(Period period) {
        synchronized (dbLock) {
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
    }

    public Map<String, Integer> getConfusionDistribution(Period period) {
        synchronized (dbLock) {
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
    }

    public int getCommandUsageCount(Period period) {
        synchronized (dbLock) {
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
    }

    public Map<String, Integer> getTopCommands(Period period, int limit) {
        synchronized (dbLock) {
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
    }

    // ==================== BEST/WORST DAY TRACKING ====================

    public void updateBestWorstDayIfNeeded(LocalDate date) {
        synchronized (dbLock) {
            log.info("Checking if {} should update best/worst day records", date);

            String sql = """
                SELECT 
                    COUNT(*) as post_count,
                    AVG(coherence_level) as avg_coherence,
                    braincell_status
                FROM post_history
                WHERE DATE(posted_at) = ?
                GROUP BY braincell_status
                ORDER BY COUNT(*) DESC
                LIMIT 1
                """;

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, date.toString());

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        log.warn("No posts found for date {}, skipping best/worst update", date);
                        return;
                    }

                    int postCount = rs.getInt("post_count");
                    double avgCoherence = rs.getDouble("avg_coherence");
                    String dominantStatus = rs.getString("braincell_status");

                    double normalizedPostCount = Math.min(postCount / 20.0, 1.0);
                    double qualityScore = (avgCoherence * 0.7) + (normalizedPostCount * 100 * 0.3);

                    log.debug("Today's stats - Posts: {}, Coherence: {}%, Quality: {}",
                            postCount, avgCoherence, qualityScore);

                    updateIfBetter(date, postCount, avgCoherence, dominantStatus, qualityScore, true);

                    updateIfBetter(date, postCount, avgCoherence, dominantStatus, qualityScore, false);

                }
            } catch (SQLException e) {
                log.error("Failed to update best/worst day for {}", date, e);
            }
        }
    }

    private void updateIfBetter(LocalDate date, int postCount, double avgCoherence,
                                String dominantStatus, double qualityScore, boolean isBest) {
        String type = isBest ? "best" : "worst";

        String selectSql = "SELECT quality_score FROM best_worst_days WHERE type = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(selectSql)) {
            pstmt.setString(1, type);

            try (ResultSet rs = pstmt.executeQuery()) {
                boolean shouldUpdate = false;

                if (!rs.next()) {
                    shouldUpdate = true;
                    log.info("No {} day record exists, inserting first record", type);
                } else {
                    double currentScore = rs.getDouble("quality_score");

                    if (isBest && qualityScore > currentScore) {
                        shouldUpdate = true;
                        log.info("New best day! {} (score: {}) beats previous best (score: {})",
                                date, qualityScore, currentScore);
                    } else if (!isBest && qualityScore < currentScore) {
                        shouldUpdate = true;
                        log.info("New worst day! {} (score: {}) beats previous worst (score: {})",
                                date, qualityScore, currentScore);
                    }
                }

                if (shouldUpdate) {
                    saveBestWorstDay(type, date, postCount, avgCoherence, dominantStatus, qualityScore);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to check/update {} day", type, e);
        }
    }

    private void saveBestWorstDay(String type, LocalDate date, int postCount, double avgCoherence,
                                  String dominantStatus, double qualityScore) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO best_worst_days 
            (type, date, post_count, avg_coherence, dominant_status, quality_score, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, type);
            pstmt.setString(2, date.toString());
            pstmt.setInt(3, postCount);
            pstmt.setDouble(4, avgCoherence);
            pstmt.setString(5, dominantStatus);
            pstmt.setDouble(6, qualityScore);
            pstmt.setString(7, LocalDateTime.now().format(SQL_DATETIME_FORMAT));

            pstmt.executeUpdate();
            log.info("Updated {} day record: {} (quality score: {})", type, date, qualityScore);
        }
    }

    public Optional<com.catbrain.bot.model.DailyStats> getBestDay() {
        synchronized (dbLock) {
            return getBestWorstDay("best");
        }
    }

    public Optional<com.catbrain.bot.model.DailyStats> getWorstDay() {
        synchronized (dbLock) {
            return getBestWorstDay("worst");
        }
    }

    private Optional<com.catbrain.bot.model.DailyStats> getBestWorstDay(String type) {
        String sql = "SELECT date, post_count, avg_coherence, dominant_status, quality_score FROM best_worst_days WHERE type = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, type);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new com.catbrain.bot.model.DailyStats(
                            LocalDate.parse(rs.getString("date")),
                            rs.getInt("post_count"),
                            rs.getDouble("avg_coherence"),
                            rs.getString("dominant_status"),
                            rs.getDouble("quality_score")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get {} day", type, e);
        }

        return Optional.empty();
    }

    // ==================== CLEANUP ====================

    @Override
    public void close() {
        synchronized (dbLock) {
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
}