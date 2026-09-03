# Greenback — frontend

React + TypeScript single-page app for the Distributed Payment Platform.

## Stack

| Concern    | Choice                                                        |
| ---------- | ----------------------------------------------------------- |
| Build      | Vite                                                        |
| UI         | React 19                                                    |
| State      | Redux Toolkit + react-redux, `redux-persist` (theme only)  |
| Styling    | Tailwind v4 (CSS-first `@theme`) + CSS custom-property tokens |
| 3D         | three + @react-three/fiber + @react-three/drei — one `MoneyLoader` |
| Animation  | gsap + @gsap/react (`useGSAP`, ScrollTrigger)               |
| Routing    | react-router-dom                                            |

## Scripts

```bash
npm run dev      # dev server on http://localhost:5173
npm run build    # tsc -b && vite build
npm run preview  # serve the production build
npx prettier --write "src/**/*.{ts,tsx}"
```

## Design system

- **Palette** — dollar-bill green anchor, banknote-cream paper, muted gold accent.
  Raw palette on `:root` (light) and `[data-theme="dark"]`; semantic tokens
  (`--bg`, `--surface`, `--text`, `--accent`, …) reference the palette and are
  mapped to Tailwind utilities via `@theme inline` in `src/styles/theme.css`.
- **`NoteFrame`** — the banknote-shaped panel every card/form/result uses:
  double rule, dashed inset edge, corner denomination, faint guilloché watermark.
- **`Guilloche`** — tiling spirograph line-work; fixed low-opacity background layer
  plus a shrunk copy inside each `NoteFrame`. Carries the "old bank" register.
- **Typography** — Space Grotesk (display), Inter (body), IBM Plex Mono (amounts/IDs).

## The 3D piece: `MoneyLoader`

`src/three/` — the app's only 3D surface and its loading / async-status primitive.
`<MoneyLoader status="idle | processing | success | failure" />`. Each status maps
to a purposeful banknote animation (`src/three/useBanknoteAnim.ts`). Falls back to a
CSS bill when motion is reduced, WebGL is missing, or the element is off-screen /
the tab is hidden (so the render loop never runs unwatched). The three.js chunk is
lazy-loaded on first real use.

Demo the four states: `http://localhost:5173/?loader=processing` (also `success`,
`failure`, `idle`).

## Backend in dev

No CORS on the services, so `vite.config.ts` proxies:

| Frontend path        | Service                  | Port |
| -------------------- | ------------------------ | ---- |
| `/svc/wallet/*`      | wallet-service           | 8081 |
| `/svc/fx/*`          | fx-rate-service          | 8082 |
| `/svc/orchestrator/*`| conversion-orchestrator  | 8083 |
| `/svc/merchant/*`    | merchant-payment-service | 8084 |
| `/svc/ledger/*`      | ledger-service           | 8085 |

Each rewrites the prefix to `/api/v1`. Start the backend with
`cd ../backend && docker compose up -d --build`. **Not yet used** — every screen
currently runs against the in-memory mock engine (`src/mock/`).

## API layer

`src/api/client.ts` (`platform.*`) talks to the 5 services through the Vite proxy.
`usePlatformQuery` / `usePlatformMutation` (`src/api/hooks.ts`) bind React to it;
any mutation bumps a version counter so live queries refetch.

Backend realities handled here:
- **no auth** — the CIF is client-supplied
- **no list endpoints** — ids we create are kept in `localStore` (`src/api/localStore.ts`),
  wallets + conversions per-CIF, the rest global; "clear local" in the sub-nav wipes it
- **`Idempotency-Key`** — a fresh UUID on every write (`src/api/ids.ts`)
- **`transactionId`** — a 16-digit id is generated client-side for wallet / fx / payment
  writes; the conversion-orchestrator makes its own, so `POST /conversions` sends none
- **conversion history** — `ConversionResponse` has no event log, so the client records
  observed `sagaState` transitions while polling `GET /conversions/{id}`

Requires the backend running (`cd ../backend && docker compose up -d --build`) and the
Vite dev server (the proxy). A production build has no proxy — services would need CORS.

## Status

Every flow works end to end on mock data:

- **Wallets** — list, holdings-by-currency, open wallet, per-wallet credit / debit /
  reserve, capture / release holds.
- **FX** — live-ticking rate tiles with up/down deltas, rate lock → consume / release.
- **Conversion** — wallet→wallet saga with live `MoneyLoader` stepper, rate preview,
  optional merchant charge, compensation path (`merchantId = acct-decline` or
  insufficient funds), conversion history, plus a direct merchant charge / refund panel.
- **Ledger** — per-account immutable statement with running balance, debit/credit totals.

Next: swap `src/mock/platform.ts` for RTK Query slices against the `/svc/*` proxy.
