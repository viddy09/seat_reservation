package com.paytmmoney.seatreservation.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves Prometheus text format at {@code /metrics}, the path the assignment expects. The same registry is also
 * exposed at {@code /actuator/prometheus}.
 */
@RestController
public class PrometheusController {

    static final MediaType PROMETHEUS_TEXT = MediaType.parseMediaType("text/plain;version=0.0.4;charset=utf-8");

    private final PrometheusMeterRegistry registry;

    public PrometheusController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/metrics")
    public ResponseEntity<String> scrape() {
        return ResponseEntity.ok().contentType(PROMETHEUS_TEXT).body(registry.scrape());
    }
}
