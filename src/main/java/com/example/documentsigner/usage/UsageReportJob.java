package com.example.documentsigner.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Job diário: envia o resumo de uso de ontem (dia civil em America/Sao_Paulo)
 * por POST JSON para o webhook configurado em usage.webhook.url.
 *
 * Envia mesmo com zero eventos (funciona como heartbeat do serviço).
 */
@Component
public class UsageReportJob {

    private static final Logger log = LoggerFactory.getLogger(UsageReportJob.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 2000L;
    private static final int TIMEOUT_MS = 15000;

    private final UsageTracker usageTracker;
    private final ObjectMapper objectMapper;
    private final String webhookUrl;

    public UsageReportJob(
            UsageTracker usageTracker,
            ObjectMapper objectMapper,
            @Value("${usage.webhook.url:}") String webhookUrl) {
        this.usageTracker = usageTracker;
        this.objectMapper = objectMapper;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "America/Sao_Paulo")
    public void sendDailyReport() {
        try {
            if (webhookUrl.isEmpty()) {
                log.warn("Usage report: usage.webhook.url (USAGE_WEBHOOK_URL) not set, skipping");
                return;
            }
            LocalDate yesterday = LocalDate.now(UsageTracker.REPORT_ZONE).minusDays(1);
            DailyStats stats = usageTracker.dailyStats(yesterday);
            String payload = buildPayload(stats);
            postWithRetries(payload);
        } catch (Exception e) {
            log.error("Usage report: failed to build/send daily report: {}", e.getMessage());
        }
    }

    /**
     * Monta o JSON do relatório. Visível para testes.
     */
    String buildPayload(DailyStats stats) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("service", "signer");
        root.put("date", stats.getDate().toString());
        root.put("events", stats.getTotalEvents());
        root.put("unique_users", stats.getUniqueUsers());
        ArrayNode top = root.putArray("top_repeats");
        for (DailyStats.TopUser user : stats.getTopRepeats()) {
            ObjectNode entry = top.addObject();
            entry.put("user", user.getUser());
            entry.put("count", user.getCount());
        }
        return objectMapper.writeValueAsString(root);
    }

    private void postWithRetries(String payload) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                int status = post(payload);
                if (status >= 200 && status < 300) {
                    log.info("Usage report sent (HTTP {})", status);
                    return;
                }
                log.warn("Usage report: webhook returned HTTP {} (attempt {}/{})",
                        status, attempt, MAX_ATTEMPTS);
            } catch (IOException e) {
                log.warn("Usage report: POST failed (attempt {}/{}): {}",
                        attempt, MAX_ATTEMPTS, e.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Usage report: interrupted during retry backoff, giving up");
                    return;
                }
                backoff *= 2;
            }
        }
        log.error("Usage report: giving up after {} attempts", MAX_ATTEMPTS);
    }

    // HttpURLConnection: o pom compila para Java 8 (java.net.http exigiria 11+).
    private int post(String payload) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int status = conn.getResponseCode();
            // Drena a resposta para reaproveitamento da conexão.
            try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[1024];
                    while (in.read(buf) != -1) {
                        // discard
                    }
                }
            } catch (IOException ignored) {
                // corpo da resposta é irrelevante
            }
            return status;
        } finally {
            conn.disconnect();
        }
    }
}
