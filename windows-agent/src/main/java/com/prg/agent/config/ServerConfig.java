package com.prg.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server-provided configuration received from the device-login response.
 * These settings override local defaults.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {

    @JsonProperty("heartbeat_interval_sec")
    private int heartbeatIntervalSec;

    @JsonProperty("segment_duration_sec")
    private int segmentDurationSec;

    @JsonProperty("capture_fps")
    private int captureFps;

    @JsonProperty("quality")
    private String quality;

    @JsonProperty("ingest_base_url")
    private String ingestBaseUrl;

    @JsonProperty("control_plane_base_url")
    private String controlPlaneBaseUrl;
}
