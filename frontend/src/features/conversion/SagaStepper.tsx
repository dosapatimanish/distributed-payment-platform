import { MoneyLoader, type LoaderStatus } from '../../three/MoneyLoader'
import { formatTime } from '../../lib/format'
import type { Conversion, SagaState } from '../../api/types'

const FAIL_AT: Partial<Record<SagaState, SagaState>> = {
  DEBIT_FAILED: 'SOURCE_DEBITED',
  CREDIT_FAILED: 'DEST_CREDITED',
  PAYMENT_FAILED: 'PAYMENT_COMPLETED',
}

const COMPENSATION: SagaState[] = [
  'COMPENSATING',
  'DEST_DEBITED_BACK',
  'SOURCE_CREDITED_BACK',
  'LOCK_RELEASED',
  'COMPENSATED',
]

function loaderStatus(s: SagaState): LoaderStatus {
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED' || s === 'COMPENSATED') return 'failure'
  return 'processing'
}

export function SagaStepper({ conv }: { conv: Conversion }) {
  const hasMerchant = !!conv.merchantId
  const steps: { key: SagaState; label: string }[] = [
    { key: 'RATE_LOCKED', label: 'Rate locked' },
    { key: 'SOURCE_DEBITED', label: 'Source debited' },
    { key: 'DEST_CREDITED', label: 'Destination credited' },
    ...(hasMerchant
      ? [{ key: 'PAYMENT_COMPLETED' as SagaState, label: 'Merchant charged' }]
      : []),
    { key: 'COMPLETED', label: 'Completed' },
  ]

  const reached = new Set(conv.history.map((h) => h.state))
  const failedStep = FAIL_AT[conv.sagaState]
  const compensating = COMPENSATION.includes(conv.sagaState)
  const status = loaderStatus(conv.sagaState)

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col items-center">
        <MoneyLoader
          status={status}
          label={
            status === 'processing'
              ? conv.sagaState.replace(/_/g, ' ').toLowerCase()
              : conv.sagaState
          }
        />
      </div>

      <ol className="flex flex-col gap-2">
        {steps.map((step) => {
          const done = reached.has(step.key)
          const failed = failedStep === step.key
          const tone = failed
            ? 'border-gold/60 text-gold'
            : done
              ? 'border-accent/50 text-accent'
              : 'border-line text-muted'
          return (
            <li
              key={step.key}
              className={`flex items-center gap-3 rounded-lg border px-3 py-2 ${tone}`}
            >
              <span className="mono text-xs">{failed ? '✕' : done ? '✓' : '·'}</span>
              <span className="text-sm">{step.label}</span>
            </li>
          )
        })}
      </ol>

      {compensating && (
        <p className="rounded-lg border border-gold/50 bg-gold/10 px-3 py-2 text-xs text-gold">
          Compensation ran — balances reversed. Reason: {conv.error ?? 'unknown'}
        </p>
      )}
      {conv.sagaState === 'FAILED' && (
        <p className="rounded-lg border border-gold/50 bg-gold/10 px-3 py-2 text-xs text-gold">
          Failed: {conv.error ?? 'unknown'}
        </p>
      )}

      <details className="rounded-lg border border-line px-3 py-2">
        <summary className="cursor-pointer mono text-[0.7rem] uppercase tracking-widest text-muted">
          Saga log ({conv.history.length})
        </summary>
        <ul className="mt-2 flex flex-col gap-1">
          {conv.history.map((h, i) => (
            <li key={i} className="mono text-xs text-muted">
              {formatTime(h.at)} — {h.state}
              {h.note ? ` (${h.note})` : ''}
            </li>
          ))}
        </ul>
      </details>
    </div>
  )
}
