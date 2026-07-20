package com.example.documentsigner.usage;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Estatísticas de uso de um dia (dia civil em America/Sao_Paulo).
 */
public class DailyStats {

    private final LocalDate date;
    private final long totalEvents;
    private final long uniqueUsers;
    private final List<TopUser> topRepeats;

    public DailyStats(LocalDate date, long totalEvents, long uniqueUsers, List<TopUser> topRepeats) {
        this.date = date;
        this.totalEvents = totalEvents;
        this.uniqueUsers = uniqueUsers;
        this.topRepeats = topRepeats == null
                ? Collections.<TopUser>emptyList()
                : Collections.unmodifiableList(topRepeats);
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
