package com.paymentplatform.orchestrator.ping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Smallest possible REST endpoint - same toolchain-check role as the other two services'. */
@RestController
public class PingController {

    @GetMapping("/api/v1/ping")
    public PingResponse ping() {
        return new PingResponse("conversion-orchestrator", "UP", Instant.now());
    }

    public record PingResponse(String service, String status, Instant timestamp) {
    }
}
