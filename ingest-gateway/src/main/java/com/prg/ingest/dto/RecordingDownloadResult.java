package com.prg.ingest.dto;

import com.prg.ingest.entity.RecordingSession;
import com.prg.ingest.entity.Segment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Internal DTO holding the data needed to stream a recording download
 * (either as a single MP4 or as a ZIP archive with multiple segments).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingDownloadResult {

    private RecordingSession session;
    private List<Segment> segments;
    private String hostname;
    private String baseFilename;
    private boolean useZip;
}
