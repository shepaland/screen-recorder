package com.prg.controlplane.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ThrottleConfig {

    @Value("${throttle.maintenance-mode:false}")
    private boolean maintenanceMode;

    public boolean isUploadEnabled() {
        if (maintenanceMode) return false;
        return true;
    }
}
