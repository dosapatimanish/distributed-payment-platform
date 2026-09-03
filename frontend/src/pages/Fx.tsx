import { useEffect, useRef, useState } from 'react'
import { NoteFrame } from '../components/note/NoteFrame'
import { Button, Empty, ErrorNote, Field, Select } from '../components/ui/controls'
import { StatusPill } from '../components/ui/StatusPill'
import { formatTime } from '../lib/format'
import { platform, startRateTicker, stopRateTicker } from '../api/client'
import { usePlatformMutation, usePlatformQuery } from '../api/hooks'

const PAIRS: [string, string][] = [
  ['USD', 'INR'],
  ['USD', 'EUR'],
  ['EUR', 'INR'],
  ['USD', 'GBP'],
]
const CCY = ['USD', 'EUR', 'INR', 'GBP']

export default function Fx() {
  useEffect(() => {
    startRateTicker()
    return () => stopRateTicker()
  }, [])

  return (
    <div className="flex flex-col gap-8">
      <header>
        <h1 className="text-2xl">FX Rates</h1>
        <p className="mono mt-1 text-xs uppercase tracking-[0.2em] text-muted">
          fx-rate-service · polled every 5s
        </p>
      </header>

      <div className="grid gap-4 sm:grid-cols-2">
        {PAIRS.map(([b, q]) => (
          <RateTile key={`${b}/${q}`} base={b} quote={q} />
        ))}
      </div>

      <LockPanel />
    </div>
  )
}

function RateTile({ base, quote }: { base: string; quote: string }) {
  const { data, error } = usePlatformQuery(
    () => platform.getRate(base, quote),
    [base, quote],
  )
  const prev = useRef<number | null>(null)
  const dir = data && prev.current != null ? Math.sign(data.rate - prev.current) : 0
  if (data) prev.current = data.rate

  return (
    <NoteFrame denomination={`${base}/${quote}`}>
      <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
        {base} → {quote}
      </p>
      {error ? (
        <ErrorNote>{error}</ErrorNote>
      ) : (
        <>
          <p
            className={
              'mono mt-3 text-2xl font-semibold tabular-nums ' +
              (dir > 0 ? 'text-accent' : dir < 0 ? 'text-gold' : 'text-text')
            }
          >
            {data ? data.rate.toFixed(4) : '—'}
            {dir > 0 && ' ▲'}
            {dir < 0 && ' ▼'}
          </p>
          <p className="mt-2 text-[0.7rem] text-muted">
            {data ? `as of ${formatTime(data.effectiveAt)}` : 'loading'}
          </p>
        </>
      )}
    </NoteFrame>
  )
}

function LockPanel() {
  const locks = usePlatformQuery(() => platform.listRateLocks(), [])
  const [base, setBase] = useState('USD')
  const [quote, setQuote] = useState('INR')
  const [amount, setAmount] = useState('1000')
  const [lock, lockState] = usePlatformMutation(platform.lockRate)
  const [consume, consumeState] = usePlatformMutation(platform.consumeRateLock)
  const [release, releaseState] = usePlatformMutation(platform.releaseRateLock)

  const list = (locks.data ?? []).slice().reverse()

  return (
    <NoteFrame denomination="lock">
      <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
        Rate lock
      </p>
      <div className="mt-4 grid gap-3 sm:grid-cols-4 sm:items-end">
        <Select
          label="Base"
          value={base}
          onChange={(e) => setBase(e.target.value)}
          options={CCY.map((c) => ({ value: c, label: c }))}
        />
        <Select
          label="Quote"
          value={quote}
          onChange={(e) => setQuote(e.target.value)}
          options={CCY.map((c) => ({ value: c, label: c }))}
        />
        <Field
          label="Amount"
          inputMode="decimal"
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))}
        />
        <Button
          loading={lockState.loading}
          onClick={() => lock({ baseCurrency: base, quoteCurrency: quote, amount })}
        >
          Lock rate
        </Button>
      </div>
      <ErrorNote>{lockState.error}</ErrorNote>

      <div className="mt-5">
        {list.length === 0 ? (
          <Empty>No rate locks yet.</Empty>
        ) : (
          <ul className="flex flex-col divide-y divide-line">
            {list.map((l) => (
              <li
                key={l.lockId}
                className="flex flex-wrap items-center justify-between gap-2 py-3"
              >
                <div>
                  <p className="mono text-sm">
                    {l.lockId} · {l.baseCurrency}/{l.quoteCurrency} @{' '}
                    {l.lockedRate.toFixed(4)}
                  </p>
                  <p className="mono text-xs text-muted">
                    amount {l.amount} · exp {formatTime(l.expiresAt)}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <StatusPill status={l.status} />
                  {l.status === 'ACTIVE' && (
                    <>
                      <Button
                        variant="ghost"
                        loading={consumeState.loading}
                        onClick={() => consume(l.lockId)}
                      >
                        Consume
                      </Button>
                      <Button
                        variant="danger"
                        loading={releaseState.loading}
                        onClick={() => release(l.lockId)}
                      >
                        Release
                      </Button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </NoteFrame>
  )
}
