package com.paymentplatform.wallet.ping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Smallest possible REST endpoint for the wallet-service.
 *
 * Purpose: prove the toolchain (Maven build, Spring Boot startup, embedded
 * Tomcat, JSON serialization) works end-to-end before any real wallet
 * business logic is added. Every later feature (wallets, balances, debit/
 * credit, concurrency control) builds on this same request/response plumbing.
 */
@RestController
public class PingController {

    @GetMapping("/api/v1/ping")
    public PingResponse ping() {
        return new PingResponse("wallet-service", "UP", Instant.now());
    }

    public record PingResponse(String service, String status, Instant timestamp) {
    }
}
