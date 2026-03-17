package com.prg.search.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenSearchInitializer implements ApplicationRunner {

    private final OpenSearchClient client;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            client.indices().putIndexTemplate(t -> t
                .name("segments-template")
                .indexPatterns("segments-*")
                .template(tp -> tp
                    .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0"))
                    .mappings(m -> m
                        .properties("tenant_id", p -> p.keyword(k -> k))
                        .properties("device_id", p -> p.keyword(k -> k))
                        .properties("session_id", p -> p.keyword(k -> k))
                        .properties("segment_id", p -> p.keyword(k -> k))
                        .properties("sequence_num", p -> p.integer(i -> i))
                        .properties("s3_key", p -> p.keyword(k -> k))
                        .properties("size_bytes", p -> p.long_(l -> l))
                        .properties("duration_ms", p -> p.integer(i -> i))
                        .properties("checksum_sha256", p -> p.keyword(k -> k))
                        .properties("timestamp", p -> p.date(d -> d))
                        .properties("metadata", p -> p.object(o -> o))
                    )
                )
            );
            log.info("OpenSearch index template 'segments-template' created/updated");
        } catch (Exception e) {
            log.error("Failed to create OpenSearch index template: {}", e.getMessage());
        }
    }
}
