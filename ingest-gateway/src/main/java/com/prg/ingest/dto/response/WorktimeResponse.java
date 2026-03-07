package com.prg.ingest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorktimeResponse {
    private String username;
    private UserActivityResponse.PeriodRange period;
    private WorkSchedule workSchedule;
    private List<WorktimeDay> days;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkSchedule {
        private String start;
        private String end;
        private String timezone;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorktimeDay {
        private String date;
        private String weekday;
        private boolean isWorkday;
        private String status; // present, absent, partial, weekend, holiday
        private String arrivalTime;
        private String departureTime;
        private double totalHours;
        private double activeHours;
        private boolean isLate;
        private boolean isEarlyLeave;
        private double overtimeHours;
    }
}
