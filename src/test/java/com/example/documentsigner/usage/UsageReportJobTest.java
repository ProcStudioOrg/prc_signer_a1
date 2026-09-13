package com.example.documentsigner.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UsageReportJobTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UsageReportJob job = new UsageReportJob(null, mapper, "");

    @Test
    public void buildPayloadSerializesStats() throws Exception {
        DailyStats stats = new DailyStats(
                LocalDate.of(2026, 7, 18), 10, 7,
                Arrays.asList(
                        new DailyStats.TopUser("a1b2c3d4", 3),
                        new DailyStats.TopUser("deadbeef", 2)));

        JsonNode json = mapper.readTree(job.buildPayload(stats));

        assertEquals("signer", json.get("service").asText());
        assertEquals("2026-07-18", json.get("date").asText());
        assertEquals(10, json.get("events").asLong());
        assertEquals(7, json.get("unique_users").asLong());
        assertTrue(json.get("top_repeats").isArray());
        assertEquals(2, json.get("top_repeats").size());
        assertEquals("a1b2c3d4", json.get("top_repeats").get(0).get("user").asText());
        assertEquals(3, json.get("top_repeats").get(0).get("count").asLong());
        assertEquals("deadbeef", json.get("top_repeats").get(1).get("user").asText());
        assertEquals(2, json.get("top_repeats").get(1).get("count").asLong());
    }

    @Test
    public void buildPayloadWithZeroEventsIsValidHeartbeat() throws Exception {
        DailyStats stats = new DailyStats(
                LocalDate.of(2026, 7, 18), 0, 0, Collections.<DailyStats.TopUser>emptyList());

        JsonNode json = mapper.readTree(job.buildPayload(stats));

        assertEquals("signer", json.get("service").asText());
        assertEquals("2026-07-18", json.get("date").asText());
        assertEquals(0, json.get("events").asLong());
        assertEquals(0, json.get("unique_users").asLong());
        assertTrue(json.get("top_repeats").isArray());
        assertEquals(0, json.get("top_repeats").size());
    }
}
