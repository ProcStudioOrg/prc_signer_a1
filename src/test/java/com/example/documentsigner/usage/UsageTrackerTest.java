package com.example.documentsigner.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UsageTrackerTest {

    @TempDir
    Path tempDir;

    private UsageTracker newTracker(String salt) {
        UsageTracker tracker = new UsageTracker(dbPath(), salt);
        tracker.init();
        return tracker;
    }

    private String dbPath() {
        return tempDir.resolve("usage-test.sqlite3").toString();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath());
    }

    private void insertEvent(String eventType, String ipHash, String createdAt) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO usage_events (event_type, ip_hash, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, eventType);
            ps.setString(2, ipHash);
            ps.setString(3, createdAt);
            ps.executeUpdate();
        }
    }

    @Test
    public void trackInsertsEventWithSaltedHashAndUtcTimestamp() throws Exception {
        UsageTracker tracker = newTracker("pepper");

        tracker.track("documento_baixado", "203.0.113.7");

        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT event_type, ip_hash, created_at FROM usage_events");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "expected one row");
            assertEquals("documento_baixado", rs.getString(1));
            assertEquals(UsageTracker.sha256Hex("203.0.113.7" + "pepper"), rs.getString(2));
            String createdAt = rs.getString(3);
            assertNotNull(createdAt);
            // Parseable como instante UTC ISO-8601
            Instant parsed = Instant.parse(createdAt);
            assertTrue(Math.abs(parsed.toEpochMilli() - System.currentTimeMillis()) < 60_000,
                    "created_at should be ~now, got " + createdAt);
            assertTrue(!rs.next(), "expected exactly one row");
        }
    }

    @Test
    public void trackSwallowsExceptionsAndNeverThrows() {
        // dbPath aponta para um DIRETÓRIO — abrir/inserir falha internamente.
        UsageTracker broken = new UsageTracker(tempDir.toString(), "salt");
        assertDoesNotThrow(broken::init);
        assertDoesNotThrow(() -> broken.track("documento_baixado", "10.0.0.1"));
    }

    @Test
    public void dailyStatsCountsOnlyTheSaoPauloDayAndRanksRepeats() throws Exception {
        UsageTracker tracker = newTracker("");
        LocalDate day = LocalDate.of(2026, 7, 18);

        String hashA = UsageTracker.sha256Hex("ip-a");
        String hashB = UsageTracker.sha256Hex("ip-b");
        String hashC = UsageTracker.sha256Hex("ip-c");

        // Dia 2026-07-18 em America/Sao_Paulo (UTC-3) = [2026-07-18T03:00Z, 2026-07-19T03:00Z)
        insertEvent("documento_baixado", hashA, "2026-07-18T03:00:00.000Z"); // limite inferior (incluso)
        insertEvent("documento_baixado", hashA, "2026-07-18T12:00:00.000Z");
        insertEvent("documento_baixado", hashA, "2026-07-19T02:59:59.999Z"); // fim do dia SP (incluso)
        insertEvent("documento_baixado", hashB, "2026-07-18T15:30:00.000Z");
        insertEvent("documento_baixado", hashB, "2026-07-18T16:30:00.000Z");
        insertEvent("documento_baixado", hashC, "2026-07-18T20:00:00.000Z");
        // Fora do dia — não devem contar:
        insertEvent("documento_baixado", hashA, "2026-07-18T02:59:59.999Z"); // ainda dia 17 em SP
        insertEvent("documento_baixado", hashB, "2026-07-19T03:00:00.000Z"); // já dia 19 em SP

        DailyStats stats = tracker.dailyStats(day);

        assertEquals(day, stats.getDate());
        assertEquals(6, stats.getTotalEvents());
        assertEquals(3, stats.getUniqueUsers());
        // Apenas quem tem count > 1, ordenado desc
        assertEquals(2, stats.getTopRepeats().size());
        assertEquals(hashA.substring(0, 8), stats.getTopRepeats().get(0).getUser());
        assertEquals(3, stats.getTopRepeats().get(0).getCount());
        assertEquals(hashB.substring(0, 8), stats.getTopRepeats().get(1).getUser());
        assertEquals(2, stats.getTopRepeats().get(1).getCount());
    }

    @Test
    public void dailyStatsLimitsTopRepeatsToFive() throws Exception {
        UsageTracker tracker = newTracker("");
        LocalDate day = LocalDate.of(2026, 7, 18);

        for (int ip = 0; ip < 7; ip++) {
            String hash = UsageTracker.sha256Hex("ip-" + ip);
            for (int n = 0; n <= ip; n++) { // ip-0 => 1 evento, ip-6 => 7 eventos
                insertEvent("documento_baixado", hash, "2026-07-18T12:00:00.000Z");
            }
        }

        DailyStats stats = tracker.dailyStats(day);
        assertEquals(28, stats.getTotalEvents());
        assertEquals(7, stats.getUniqueUsers());
        assertEquals(5, stats.getTopRepeats().size());
        assertEquals(7, stats.getTopRepeats().get(0).getCount());
        assertEquals(3, stats.getTopRepeats().get(4).getCount());
    }

    @Test
    public void dailyStatsEmptyDayReturnsZeros() {
        UsageTracker tracker = newTracker("");

        DailyStats stats = tracker.dailyStats(LocalDate.of(2026, 1, 1));

        assertEquals(0, stats.getTotalEvents());
        assertEquals(0, stats.getUniqueUsers());
        assertTrue(stats.getTopRepeats().isEmpty());
    }
}
