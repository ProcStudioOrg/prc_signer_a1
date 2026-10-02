package com.example.documentsigner.usage;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Estatísticas de uso de um dia (dia civil em America/Sao_Paulo).
 */
public class DailyStats {

    private final LocalDate date;
    private final long totalEvents;
    private final long uniqueUsers;
    private final List<TopUser> topRepeats;
    private final Map<String, Long> byEvent;

    public DailyStats(LocalDate date, long totalEvents, long uniqueUsers, List<TopUser> topRepeats) {
        this(date, totalEvents, uniqueUsers, topRepeats, Collections.<String, Long>emptyMap());
    }

    public DailyStats(LocalDate date, long totalEvents, long uniqueUsers, List<TopUser> topRepeats,
                      Map<String, Long> byEvent) {
        this.date = date;
        this.totalEvents = totalEvents;
        this.uniqueUsers = uniqueUsers;
        this.topRepeats = topRepeats == null
                ? Collections.<TopUser>emptyList()
                : Collections.unmodifiableList(topRepeats);
        this.byEvent = Collections.unmodifiableMap(new LinkedHashMap<String, Long>(byEvent));
    }

    public LocalDate getDate() {
        return date;
    }

    public long getTotalEvents() {
        return totalEvents;
    }

    public long getUniqueUsers() {
        return uniqueUsers;
    }

    public List<TopUser> getTopRepeats() {
        return topRepeats;
    }

    public Map<String, Long> getByEvent() {
        return byEvent;
    }

    /**
     * Usuário recorrente: primeiros 8 chars do ip_hash + contagem de eventos.
     */
    public static class TopUser {

        private final String user;
        private final long count;

        public TopUser(String user, long count) {
            this.user = user;
            this.count = count;
        }

        public String getUser() {
            return user;
        }

        public long getCount() {
            return count;
        }
    }
}
