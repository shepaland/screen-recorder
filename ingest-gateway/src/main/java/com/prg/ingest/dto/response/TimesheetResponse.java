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
public class TimesheetResponse {
    private String username;
    private String displayName;
    private String month;
    private WorktimeResponse.WorkSchedule workSchedule;
    private TimesheetSummary summary;
    private List<TimesheetDay> days;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimesheetSummary {
        private int workDaysInMonth;
        private int daysPresent;
        private int daysAbsent;
        private int daysPartial;
        private double totalExpectedHours;
        private double totalActualHours;
        private double totalOvertimeHours;
        private String avgArrivalTime;
        private String avgDepartureTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimesheetDay {
        private String date;
        private String weekday;
        private boolean isWorkday;
        private String status;
        private String arrivalTime;
        private String departureTime;
        private double totalHours;
        private double activeHours;
        private double idleHours;
        private boolean isLate;
        private boolean isEarlyLeave;
        private double overtimeHours;
    }
}
