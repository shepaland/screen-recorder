package com.prg.controlplane.controller;

import com.prg.controlplane.dto.response.PageResponse;
import com.prg.controlplane.entity.WebhookDelivery;
import com.prg.controlplane.entity.WebhookSubscription;
import com.prg.controlplane.security.DevicePrincipal;
import com.prg.controlplane.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping
    public ResponseEntity<WebhookSubscription> create(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        DevicePrincipal principal = getPrincipal(request);
        @SuppressWarnings("unchecked")
        List<String> eventTypes = (List<String>) body.getOrDefault("event_types", List.of());
        WebhookSubscription sub = webhookService.create(
            principal.getTenantId(),
            principal.getUserId(),
            (String) body.get("url"),
            eventTypes,
            (String) body.get("secret"));
        return ResponseEntity.status(HttpStatus.CREATED).body(sub);
    }

    @GetMapping
    public ResponseEntity<List<WebhookSubscription>> list(HttpServletRequest request) {
        DevicePrincipal principal = getPrincipal(request);
        return ResponseEntity.ok(webhookService.list(principal.getTenantId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WebhookSubscription> get(
            @PathVariable UUID id, HttpServletRequest request) {
        DevicePrincipal principal = getPrincipal(request);
        return ResponseEntity.ok(webhookService.get(id, principal.getTenantId()));
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}")
    public ResponseEntity<WebhookSubscription> update(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        DevicePrincipal principal = getPrincipal(request);
        return ResponseEntity.ok(webhookService.update(
            id, principal.getTenantId(),
            (String) body.get("url"),
            (List<String>) body.get("event_types"),
            (String) body.get("secret"),
            (Boolean) body.get("active")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id, HttpServletRequest request) {
        DevicePrincipal principal = getPrincipal(request);
        webhookService.delete(id, principal.getTenantId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deliveries")
    public ResponseEntity<PageResponse<WebhookDelivery>> getDeliveries(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        getPrincipal(request); // auth check
        return ResponseEntity.ok(webhookService.getDeliveries(id, page, size));
    }

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute("principal");
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found");
        }
        return principal;
    }
}
