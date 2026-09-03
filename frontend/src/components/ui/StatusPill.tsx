type Tone = 'good' | 'warn' | 'bad' | 'muted'

const TONE: Record<Tone, string> = {
  good: 'border-accent/50 text-accent',
  warn: 'border-gold/60 text-gold',
  bad: 'border-gold/60 text-gold',
  muted: 'border-line text-muted',
}

const MAP: Record<string, Tone> = {
  ACTIVE: 'good',
  COMPLETED: 'good',
  HELD: 'good',
  CONSUMED: 'good',
  PENDING: 'warn',
  FROZEN: 'warn',
  COMPENSATED: 'warn',
  REFUNDED: 'warn',
  FAILED: 'bad',
  CLOSED: 'muted',
  RELEASED: 'muted',
  EXPIRED: 'muted',
  CAPTURED: 'muted',
}

export function StatusPill({ status }: { status: string }) {
  const tone = MAP[status] ?? 'muted'
  return (
    <span
      className={`shrink-0 rounded-full border px-2.5 py-0.5 mono text-[0.65rem] uppercase tracking-widest ${TONE[tone]}`}
    >
      {status}
    </span>
  )
}
