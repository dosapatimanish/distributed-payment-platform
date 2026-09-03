// The backend has no "list" endpoints, so the app remembers the ids it created.
// Wallets + conversions are per-CIF; reservations / locks / payments are global
// (the screens that show them don't filter by CIF).

import type { RateLock, Reservation, Wallet } from './types'

const read = <T>(key: string, fallback: T): T => {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

const write = (key: string, value: unknown) => {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* private mode / quota — ignore */
  }
}

const wKey = (cif: string) => `greenback:wallets:${cif}`
const cKey = (cif: string) => `greenback:conversions:${cif}`
const R_KEY = 'greenback:reservations'
const L_KEY = 'greenback:locks'
const P_KEY = 'greenback:payments'

export interface KnownConversion {
  transactionId: string
  merchantId: string | null
}

export const localStore = {
  wallets: (cif: string): Wallet[] => read(wKey(cif), []),
  setWallets: (cif: string, list: Wallet[]) => write(wKey(cif), list),
  addWallet(cif: string, w: Wallet) {
    const list = this.wallets(cif).filter((x) => x.accountNo !== w.accountNo)
    list.push(w)
    write(wKey(cif), list)
  },

  conversions: (cif: string): KnownConversion[] => read(cKey(cif), []),
  addConversion(cif: string, transactionId: string, merchantId: string | null) {
    const list = this.conversions(cif).filter((x) => x.transactionId !== transactionId)
    list.unshift({ transactionId, merchantId })
    write(cKey(cif), list)
  },

  reservations: (): Reservation[] => read(R_KEY, []),
  addReservation(r: Reservation) {
    write(R_KEY, [
      r,
      ...this.reservations().filter((x) => x.reservationId !== r.reservationId),
    ])
  },
  patchReservation(id: string, patch: Partial<Reservation>) {
    write(
      R_KEY,
      this.reservations().map((x) => (x.reservationId === id ? { ...x, ...patch } : x)),
    )
  },

  locks: (): RateLock[] => read(L_KEY, []),
  addLock(l: RateLock) {
    write(L_KEY, [l, ...this.locks().filter((x) => x.lockId !== l.lockId)])
  },
  patchLock(id: string, patch: Partial<RateLock>) {
    write(
      L_KEY,
      this.locks().map((x) => (x.lockId === id ? { ...x, ...patch } : x)),
    )
  },

  payments: (): string[] => read(P_KEY, []),
  addPayment(id: string) {
    write(P_KEY, [id, ...this.payments().filter((x) => x !== id)])
  },

  clearAll() {
    try {
      Object.keys(localStorage)
        .filter((k) => k.startsWith('greenback:'))
        .forEach((k) => localStorage.removeItem(k))
    } catch {
      /* ignore */
    }
  },
}
