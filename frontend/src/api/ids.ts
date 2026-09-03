const pad = (n: number, len: number) => String(n).padStart(len, '0')

/** A 16-digit transaction id the client supplies on any wallet / fx / payment
 *  write that requires one (`^\d{16}$`). The conversion-orchestrator makes its
 *  own id, so conversions never send this. */
export function transactionId(): string {
  const t = String(Date.now()).slice(-10)
  const r = pad(Math.floor(Math.random() * 1e6), 6)
  return `${t}${r}`
}

/** Fresh key for the mandatory `Idempotency-Key` header on every write. */
export function idempotencyKey(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`
  }
}
