package com.paymentplatform.fxrate.exception;

/** No rate is currently cached for this pair - either it's not in {@code fx.rate.pairs}, or the first refresh tick hasn't run yet. */
public class UnsupportedCurrencyPairException extends RuntimeException {

    public UnsupportedCurrencyPairException(String base, String quote) {
        super("No rate available for pair %s/%s".formatted(base, quote));
    }
}
