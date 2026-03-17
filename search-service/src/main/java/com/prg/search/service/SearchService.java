package com.prg.search.service;

import com.prg.search.dto.PageResponse;
import com.prg.search.dto.SegmentSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final OpenSearchClient client;

    @SuppressWarnings("unchecked")
    public PageResponse<SegmentSearchResult> search(
            String q, UUID tenantId, UUID deviceId,
            Instant from, Instant to, int page, int size) {
        try {
            var boolQuery = new BoolQuery.Builder();

            // Tenant isolation (mandatory)
            boolQuery.filter(f -> f.term(t -> t
                .field("tenant_id").value(v -> v.stringValue(tenantId.toString()))));

            if (deviceId != null) {
                boolQuery.filter(f -> f.term(t -> t
                    .field("device_id").value(v -> v.stringValue(deviceId.toString()))));
            }
            if (from != null || to != null) {
                boolQuery.filter(f -> f.range(r -> {
                    var range = r.field("timestamp");
                    if (from != null) range.gte(JsonData.of(from.toString()));
                    if (to != null) range.lte(JsonData.of(to.toString()));
                    return range;
                }));
            }
            if (q != null && !q.isBlank()) {
                boolQuery.must(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields("s3_key", "metadata.*")));
            }

            SearchResponse<Map> response = client.search(s -> s
                .index("segments-*")
                .query(boolQuery.build()._toQuery())
                .from(page * size)
                .size(size)
                .sort(so -> so.field(f -> f.field("timestamp").order(SortOrder.Desc))),
                Map.class);

            var results = response.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> src = hit.source();
                    return SegmentSearchResult.builder()
                        .segmentId(getStr(src, "segment_id"))
                        .tenantId(getStr(src, "tenant_id"))
                        .deviceId(getStr(src, "device_id"))
                        .sessionId(getStr(src, "session_id"))
                        .sequenceNum(getInt(src, "sequence_num"))
                        .s3Key(getStr(src, "s3_key"))
                        .sizeBytes(getLong(src, "size_bytes"))
                        .durationMs(getInt(src, "duration_ms"))
                        .checksumSha256(getStr(src, "checksum_sha256"))
                        .timestamp(getStr(src, "timestamp"))
                        .metadata((Map<String, Object>) src.get("metadata"))
                        .build();
                })
                .toList();

            long totalHits = response.hits().total() != null ? response.hits().total().value() : 0;

            return PageResponse.<SegmentSearchResult>builder()
                .content(results)
                .page(page)
                .size(size)
                .totalElements(totalHits)
                .totalPages((int) Math.ceil((double) totalHits / size))
                .build();
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage());
            return PageResponse.<SegmentSearchResult>builder()
                .content(java.util.List.of())
                .page(page)
                .size(size)
                .totalElements(0)
                .totalPages(0)
                .build();
        }
    }

    private String getStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private Integer getInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private Long getLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        return null;
    }
}
