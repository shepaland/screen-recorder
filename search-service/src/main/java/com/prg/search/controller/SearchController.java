package com.prg.search.controller;

import com.prg.search.dto.PageResponse;
import com.prg.search.dto.SegmentSearchResult;
import com.prg.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/segments")
    public ResponseEntity<PageResponse<SegmentSearchResult>> searchSegments(
            @RequestParam(required = false) String q,
            @RequestParam(name = "tenant_id") UUID tenantId,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(searchService.search(
            q, tenantId, deviceId, from, to, page, size));
    }
}
