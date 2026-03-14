package com.prg.controlplane.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceGroupStatsResponse {
    private int totalDevices;
    private int onlineDevices;
    private double totalVideoGb;
}
