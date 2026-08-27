# Get Current Rate

`GET /api/v1/fx/rates/{base}/{quote}`

## Purpose

Read-only lookup of the current cached rate for a currency pair. No DB hit on the happy path —
served straight from the in-memory cache that `RateRefreshScheduler` keeps warm every second.

## Request

Path params: `base`, `quote` — 3-letter currency codes (e.g. `USD`, `INR`).

## Response — `200 OK`

```json
{
  "baseCurrency": "USD",
  "quoteCurrency": "INR",
  "rate": 82.75822683,
  "source": "SIMULATED_FEED",
  "effectiveAt": "2026-08-22T13:45:03.131936300Z"
}
```

## Error responses

| Status | Code | When |
|---|---|---|
| 404 | `UNSUPPORTED_CURRENCY_PAIR` | pair isn't in `fx.rate.pairs` (or the first refresh tick hasn't run yet) |

## Flow (file by file)

1. [FxRateController.getCurrentRate](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/web/FxRateController.java) — `@PathVariable base, quote`, no request DTO.
2. [FxRateService.getCurrentRate](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateService.java) — `cache.get(base, quote)`, throws `UnsupportedCurrencyPairException` if absent.
3. [FxRateCache.get](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateCache.java) — takes the cache's read lock, looks up the pair in the current snapshot map, releases. Never touches the database.
4. `RateResponse.of(base, quote, snapshot)` — maps the cache's internal `RateSnapshot` record to the response DTO.

## Features

- **No DB read on the hot path**: the "current rate" concept is deliberately cache-only — `fx_rate` in Oracle is a separate append-only history table that `RateRefreshScheduler` writes to alongside the cache, for later audit, not for serving reads.
- **Many-readers-no-blocking**: concurrent `getCurrentRate` calls (and `lockRate` calls, which read the same cache) never block each other — only the scheduler's once-a-second write briefly takes the exclusive lock. See [FxRateCache](../../backend/fx-rate-service/src/main/java/com/paymentplatform/fxrate/service/FxRateCache.java) javadoc.
- **Simulated feed, not live**: `rate` moves every second via a small random walk around a configured seed value (`fx.rate.pairs` in `application.properties`) — there's no real external FX provider wired in yet (see implementation notes).
