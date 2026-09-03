// Talks to the 5 Spring Boot services through the Vite dev proxy (`/svc/*`).
// Surface mirrors the old mock `platform` object so the screens are unchanged.
//
// Notes forced by the backend:
//  - no auth; the CIF is client-supplied
//  - no list endpoints → ids we create are remembered in localStore
//  - every write needs an `Idempotency-Key` header
//  - a 16-digit `transactionId` is client-generated for wallet / fx / payment
//    writes; the conversion-orchestrator generates its own.

import { idempotencyKey, transactionId } from './ids'
import { localStore } from './localStore'
import {
  FAILED_SAGA,
  TERMINAL_SAGA,
  type BalanceResponse,
  type Conversion,
  type Currency,
  type Payment,
  type RateLock,
  type RateResponse,
  type Reservation,
  type SagaEvent,
  type SagaState,
  type StatementResponse,
  type Wallet,
} from './types'

const BASE = {
  wallet: '/svc/wallet',
  fx: '/svc/fx',
  orchestrator: '/svc/orchestrator',
  merchant: '/svc/merchant',
  ledger: '/svc/ledger',
}

/* ---------------------------------------------------------------- fetch --- */

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new Error('Cannot reach the backend — is it running on :8081–8085?')
  }
  const text = await res.text()
  const body = text ? JSON.parse(text) : null
  if (!res.ok) {
    const msg = (body && (body.message || body.code)) || `${res.status} ${res.statusText}`
    throw new Error(msg)
  }
  return body as T
}

const post = <T>(path: string, body?: unknown) =>
  req<T>(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey() },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

const del = <T>(path: string) => req<T>(path, { method: 'DELETE' })

/* ------------------------------------------------------ change signal ---- */

let version = 0
const listeners = new Set<() => void>()
function notify() {
  version++
  listeners.forEach((l) => l())
}
export function subscribe(l: () => void) {
  listeners.add(l)
  return () => listeners.delete(l)
}
export const getVersion = () => version

/* --------------------------------------------------- conversion history -- */
// Backend ConversionResponse has no history; we log observed state changes.
const sagaLog = new Map<string, SagaEvent[]>()

function decorate(raw: Conversion, merchantId: string | null): Conversion {
  const log = sagaLog.get(raw.transactionId) ?? []
  const last = log[log.length - 1]
  if (!last || last.state !== raw.sagaState) {
    log.push({ state: raw.sagaState, at: raw.updatedAt || new Date().toISOString() })
    sagaLog.set(raw.transactionId, log)
    notify()
  }
  return {
    ...raw,
    merchantId: raw.merchantId ?? merchantId,
    error: raw.error ?? (FAILED_SAGA.includes(raw.sagaState) ? raw.sagaState : null),
    history: [...log],
  }
}

/* ---------------------------------------------------------------- api ----- */

export const platform = {
  /* --- reference --- */
  listCurrencies: () => req<Currency[]>(`${BASE.wallet}/currencies`),

  /* --- wallets --- */
  async listWallets(cif: string): Promise<Wallet[]> {
    const known = localStore.wallets(cif)
    const merged = await Promise.all(
      known.map(async (w) => {
        try {
          const b = await req<BalanceResponse>(
            `${BASE.wallet}/wallets/${w.accountNo}/balance`,
          )
          return { ...w, balance: b.balance, status: b.status }
        } catch {
          return w // keep last-known if the balance call fails
        }
      }),
    )
    localStore.setWallets(cif, merged)
    return merged
  },

  async getWallet(accountNo: string): Promise<BalanceResponse> {
    return req<BalanceResponse>(`${BASE.wallet}/wallets/${accountNo}/balance`)
  },

  async createWallet(input: {
    cif: string
    currency: string
    highContention?: boolean
  }): Promise<Wallet> {
    const w = await post<Wallet>(`${BASE.wallet}/wallets`, {
      cif: input.cif,
      currency: input.currency,
      highContention: !!input.highContention,
    })
    localStore.addWallet(input.cif, w)
    notify()
    return w
  },

  async credit(accountNo: string, body: { amount: string }): Promise<Wallet> {
    const w = await post<Wallet>(`${BASE.wallet}/wallets/${accountNo}/credit`, {
      amount: body.amount,
      transactionId: transactionId(),
    })
    notify()
    return w
  },

  async debit(accountNo: string, body: { amount: string }): Promise<Wallet> {
    const w = await post<Wallet>(`${BASE.wallet}/wallets/${accountNo}/debit`, {
      amount: body.amount,
      transactionId: transactionId(),
    })
    notify()
    return w
  },

  /* --- reservations / holds --- */
  listReservations: async (): Promise<Reservation[]> => localStore.reservations(),

  async reserve(accountNo: string, body: { amount: string }): Promise<Reservation> {
    const r = await post<Reservation>(`${BASE.wallet}/wallets/${accountNo}/reserve`, {
      amount: body.amount,
      transactionId: transactionId(),
    })
    localStore.addReservation(r)
    notify()
    return r
  },

  async captureReservation(reservationId: string): Promise<Reservation> {
    await post(`${BASE.wallet}/wallets/reservations/${reservationId}/capture`)
    localStore.patchReservation(reservationId, { status: 'CAPTURED' })
    notify()
    return localStore.reservations().find((r) => r.reservationId === reservationId)!
  },

  async releaseReservation(reservationId: string): Promise<Reservation> {
    await post(`${BASE.wallet}/wallets/reservations/${reservationId}/release`)
    localStore.patchReservation(reservationId, { status: 'RELEASED' })
    notify()
    return localStore.reservations().find((r) => r.reservationId === reservationId)!
  },

  /* --- fx --- */
  getRate: (base: string, quote: string) =>
    req<RateResponse>(`${BASE.fx}/rates/${base}/${quote}`),

  listRateLocks: async (): Promise<RateLock[]> => localStore.locks(),

  async lockRate(body: {
    baseCurrency: string
    quoteCurrency: string
    amount: string
  }): Promise<RateLock> {
    const l = await post<RateLock>(`${BASE.fx}/rate-lock`, {
      ...body,
      transactionId: transactionId(),
    })
    localStore.addLock(l)
    notify()
    return l
  },

  async consumeRateLock(lockId: string): Promise<RateLock> {
    const l = await post<RateLock>(`${BASE.fx}/rate-lock/${lockId}/consume`)
    localStore.patchLock(lockId, { status: l.status })
    notify()
    return l
  },

  async releaseRateLock(lockId: string): Promise<RateLock> {
    const l = await del<RateLock>(`${BASE.fx}/rate-lock/${lockId}`)
    localStore.patchLock(lockId, { status: l.status })
    notify()
    return l
  },

  /* --- merchant payments --- */
  listPayments: async (): Promise<Payment[]> => {
    const ids = localStore.payments()
    const out = await Promise.all(
      ids.map((id) => req<Payment>(`${BASE.merchant}/${id}`).catch(() => null)),
    )
    return out.filter((p): p is Payment => p != null)
  },

  getPayment: (paymentId: string) => req<Payment>(`${BASE.merchant}/${paymentId}`),

  async charge(body: {
    merchantId: string
    amount: string
    currency: string
  }): Promise<Payment> {
    const p = await post<Payment>(`${BASE.merchant}`, {
      transactionId: transactionId(),
      merchantId: body.merchantId,
      amount: body.amount,
      currency: body.currency,
    })
    localStore.addPayment(p.paymentId)
    notify()
    return p
  },

  async refund(paymentId: string): Promise<Payment> {
    const p = await post<Payment>(`${BASE.merchant}/${paymentId}/refund`)
    notify()
    return p
  },

  /* --- ledger --- */
  getStatement: (accountNo: string) =>
    req<StatementResponse>(`${BASE.ledger}/wallets/${accountNo}/statement`),

  /* --- conversion saga --- */
  async listConversions(cif: string): Promise<Conversion[]> {
    const known = localStore.conversions(cif)
    const out = await Promise.all(
      known.map((k) =>
        req<Conversion>(`${BASE.orchestrator}/${k.transactionId}`)
          .then((raw) => decorate(raw, k.merchantId))
          .catch(() => null),
      ),
    )
    return out
      .filter((c): c is Conversion => c != null)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  },

  async getConversion(txnId: string): Promise<Conversion> {
    const raw = await req<Conversion>(`${BASE.orchestrator}/${txnId}`)
    return decorate(raw, lookupMerchant(txnId) ?? raw.merchantId ?? null)
  },

  async startConversion(reqBody: {
    cif: string
    sourceAccountNo: string
    destAccountNo: string
    sourceCurrency: string
    destCurrency: string
    sourceAmount: string
    merchantId?: string | null
  }): Promise<Conversion> {
    const merchantId = reqBody.merchantId?.trim() || null
    const payload: Record<string, unknown> = {
      cif: reqBody.cif,
      sourceAccountNo: reqBody.sourceAccountNo,
      destAccountNo: reqBody.destAccountNo,
      sourceCurrency: reqBody.sourceCurrency,
      destCurrency: reqBody.destCurrency,
      sourceAmount: reqBody.sourceAmount,
    }
    if (merchantId) payload.merchantId = merchantId

    const raw = await post<Conversion>(`${BASE.orchestrator}`, payload)
    sagaLog.set(raw.transactionId, [
      { state: raw.sagaState, at: raw.createdAt || new Date().toISOString() },
    ])
    localStore.addConversion(reqBody.cif, raw.transactionId, merchantId)
    notify()
    return decorate(raw, merchantId)
  },

  /** clear the local record of created ids (does not touch the backend) */
  reset() {
    localStore.clearAll()
    sagaLog.clear()
    notify()
  },
}

function lookupMerchant(txnId: string): string | null {
  for (const key of safeKeys()) {
    if (!key.startsWith('greenback:conversions:')) continue
    try {
      const list = JSON.parse(localStorage.getItem(key) || '[]') as {
        transactionId: string
        merchantId: string | null
      }[]
      const hit = list.find((x) => x.transactionId === txnId)
      if (hit) return hit.merchantId
    } catch {
      /* ignore */
    }
  }
  return null
}

function safeKeys(): string[] {
  try {
    return Object.keys(localStorage)
  } catch {
    return []
  }
}

/* --------------------------------------------------- rate poll ticker ---- */
// The FX screen wants "live" rates; the service has no stream, so poll.

let tickerRefs = 0
let timer: ReturnType<typeof setInterval> | null = null

export function startRateTicker() {
  tickerRefs++
  if (timer) return
  timer = setInterval(notify, 5000)
}

export function stopRateTicker() {
  tickerRefs = Math.max(0, tickerRefs - 1)
  if (tickerRefs === 0 && timer) {
    clearInterval(timer)
    timer = null
  }
}

export { TERMINAL_SAGA }
export type { SagaState }
