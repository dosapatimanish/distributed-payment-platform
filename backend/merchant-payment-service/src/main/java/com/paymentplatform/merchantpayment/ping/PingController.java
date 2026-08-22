package com.paymentplatform.merchantpayment.ping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Smallest possible REST endpoint - same toolchain-check role as the other services'. */
@RestController
public class PingController {

    @GetMapping("/api/v1/ping")
    public PingResponse ping() {
        return new PingResponse("merchant-payment-service", "UP", Instant.now());
    }

    public record PingResponse(String service, String status, Instant timestamp) {
    }
}
