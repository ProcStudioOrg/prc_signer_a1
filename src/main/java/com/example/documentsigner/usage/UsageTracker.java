package com.example.documentsigner.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Rastreamento de uso em SQLite local (JDBC puro, sem JPA).
 *
 * Privacidade: nunca armazena IP em claro — apenas SHA-256(ip + salt).
 * Robustez: {@link #track} é fire-and-forget; qualquer falha é logada e
 * engolida — nunca pode quebrar a request do usuário.
 */
@Component
public class UsageTracker {

    private static final Logger log = LoggerFactory.getLogger(UsageTracker.class);

    /** Fuso de referência para o "dia" do relatório. */
    public static final ZoneId REPORT_ZONE = ZoneId.of("America/Sao_Paulo");

    /**
     * Formato fixo (millis sempre presentes) para created_at em UTC.
     * Largura fixa => comparação lexicográfica == comparação temporal.
     */
    static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final String dbPath;
    private final String ipSalt;

    public UsageTracker(
            @Value("${usage.db.path:./data/usage.sqlite3}") String dbPath,
            @Value("${usage.ip.salt:}") String ipSalt) {
        this.dbPath = dbPath;
        this.ipSalt = ipSalt == null ? "" : ipSalt;
    }

    @PostConstruct
    public void init() {
        try {
            File dbFile = new File(dbPath);
            File parent = dbFile.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("Usage tracking: could not create data directory {}", parent);
            }
            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS usage_events ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "event_type TEXT NOT NULL, "
                        + "ip_hash TEXT NOT NULL, "
                        + "created_at TEXT NOT NULL)");
                st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_usage_events_created_at "
                                + "ON usage_events (created_at)");
            }
            log.info("Usage tracking initialized (db: {})", dbPath);
        } catch (Exception e) {
            // Nunca derrubar o serviço por causa de telemetria.
            log.error("Usage tracking init failed (tracking disabled until restart): {}",
                    e.getMessage());
        }
    }

    /**
     * Registra um evento de uso. Fire-and-forget: nunca lança exceção.
     */
    public void track(String eventType, String ip) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO usage_events (event_type, ip_hash, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, eventType);
            ps.setString(2, sha256Hex((ip == null ? "" : ip) + ipSalt));
            ps.setString(3, UTC_FORMAT.format(Instant.now()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Usage tracking: failed to record event '{}': {}", eventType, e.getMessage());
        }
    }

    /**
     * Estatísticas do dia civil {@code date} em America/Sao_Paulo
     * (range convertido para UTC ao filtrar created_at).
     */
    public DailyStats dailyStats(LocalDate date) {
        ZonedDateTime dayStart = date.atStartOfDay(REPORT_ZONE);
        String from = UTC_FORMAT.format(dayStart.toInstant());
        String to = UTC_FORMAT.format(dayStart.plusDays(1).toInstant());

        try (Connection conn = connect()) {
            long total = 0;
            long unique = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*), COUNT(DISTINCT ip_hash) FROM usage_events "
                            + "WHERE created_at >= ? AND created_at < ?")) {
                ps.setString(1, from);
                ps.setString(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getLong(1);
                        unique = rs.getLong(2);
                    }
                }
            }

            List<DailyStats.TopUser> topRepeats = new ArrayList<DailyStats.TopUser>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ip_hash, COUNT(*) AS cnt FROM usage_events "
                            + "WHERE created_at >= ? AND created_at < ? "
                            + "GROUP BY ip_hash HAVING cnt > 1 "
                            + "ORDER BY cnt DESC, ip_hash ASC LIMIT 5")) {
                ps.setString(1, from);
                ps.setString(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String hash = rs.getString(1);
                        String user = hash.length() > 8 ? hash.substring(0, 8) : hash;
                        topRepeats.add(new DailyStats.TopUser(user, rs.getLong(2)));
                    }
                }
            }

            return new DailyStats(date, total, unique, topRepeats);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read usage stats: " + e.getMessage(), e);
        }
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    /**
     * IP do cliente: primeiro valor de X-Forwarded-For (setado pelo nginx),
     * fallback para o remote address da conexão.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 é obrigatório em qualquer JRE; não deve acontecer.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
