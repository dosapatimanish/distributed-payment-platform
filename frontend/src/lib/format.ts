/** Display-only. Real balances stay as decimal strings end to end. */
export function formatMoney(amount: string | number, currency: string): string {
  const n = typeof amount === 'string' ? Number(amount) : amount
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
    }).format(n)
  } catch {
    return `${amount} ${currency}`
  }
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/** Group balances by currency and sum (fixture math — swap for a decimal lib). */
export function totalsByCurrency(
  items: { currency: string; balance: string }[],
): { currency: string; total: string }[] {
  const map = new Map<string, number>()
  for (const it of items) {
    map.set(it.currency, (map.get(it.currency) ?? 0) + Number(it.balance))
  }
  return [...map.entries()].map(([currency, total]) => ({
    currency,
    total: total.toFixed(4),
  }))
}
