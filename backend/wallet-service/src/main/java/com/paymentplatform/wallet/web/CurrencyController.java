package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.service.CurrencyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only currency reference API (backend-documents/identifier-scheme.md). No Idempotency-Key.
 * conversion-orchestrator caches {@code GET /currencies} at startup to resolve the 2-digit
 * short_code it puts in transaction ids.
 */
@RestController
@RequestMapping("/api/v1/currencies")
public class CurrencyController {

    private final CurrencyService currencyService;

    public CurrencyController(CurrencyService currencyService) {
        this.currencyService = currencyService;
    }

    @GetMapping
    public List<CurrencyResponse> list() {
        return currencyService.listActive().stream().map(CurrencyResponse::from).toList();
    }

    @GetMapping("/{code}")
    public CurrencyResponse get(@PathVariable String code) {
        return CurrencyResponse.from(currencyService.get(code));
    }
}
