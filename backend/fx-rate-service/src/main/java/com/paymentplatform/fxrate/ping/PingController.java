package com.paymentplatform.fxrate.ping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Smallest possible REST endpoint for the fx-rate-service - same toolchain-check role as
 * wallet-service's PingController.
 */
@RestController
public class PingController {

    @GetMapping("/api/v1/ping")
    public PingResponse ping() {
        return new PingResponse("fx-rate-service", "UP", Instant.now());
    }

    public record PingResponse(String service, String status, Instant timestamp) {
    }
}
