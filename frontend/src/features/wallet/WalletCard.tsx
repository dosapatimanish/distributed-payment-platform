import { useState } from 'react'
import { NoteFrame } from '../../components/note/NoteFrame'
import { Button, ErrorNote, Field } from '../../components/ui/controls'
import { StatusPill } from '../../components/ui/StatusPill'
import { formatDate, formatMoney } from '../../lib/format'
import type { Wallet } from '../../api/types'
import { platform } from '../../api/client'
import { usePlatformMutation } from '../../api/hooks'
import { toast } from '../../lib/toast'

type Move = 'credit' | 'debit' | 'reserve'

export function WalletCard({ wallet }: { wallet: Wallet }) {
  const frozen = wallet.status !== 'ACTIVE'
  const [open, setOpen] = useState<Move | null>(null)
  const [amount, setAmount] = useState('')

  const [credit, creditState] = usePlatformMutation(platform.credit)
  const [debit, debitState] = usePlatformMutation(platform.debit)
  const [reserve, reserveState] = usePlatformMutation(platform.reserve)

  const busy = creditState.loading || debitState.loading || reserveState.loading
  const error = creditState.error || debitState.error || reserveState.error

  async function submit() {
    if (!open || !amount) return
    const fn = open === 'credit' ? credit : open === 'debit' ? debit : reserve
    const res = await fn(wallet.accountNo, { amount })
    if (res) {
      toast(`${open} ${amount} ${wallet.currency} — ok`)
      setAmount('')
      setOpen(null)
    }
  }

  return (
    <NoteFrame denomination={wallet.currency}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
            {wallet.currency} wallet
          </p>
          <p className="mono mt-1 text-xs text-muted">{wallet.accountNo}</p>
        </div>
        <StatusPill status={wallet.status} />
      </div>

      <p
        className={
          'mono mt-5 text-2xl font-semibold tracking-tight ' +
          (frozen ? 'text-muted line-through decoration-1' : 'text-text')
        }
      >
        {formatMoney(wallet.balance, wallet.currency)}
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-2 text-[0.7rem] text-muted">
        <span>opened {formatDate(wallet.createdAt)}</span>
        {wallet.highContention && (
          <span className="rounded border border-line px-1.5 py-0.5 mono uppercase tracking-widest">
            high contention
          </span>
        )}
      </div>

      {!frozen && (
        <div className="mt-4 border-t border-line pt-3">
          <div className="flex gap-2">
            {(['credit', 'debit', 'reserve'] as Move[]).map((m) => (
              <button
                key={m}
                onClick={() => {
                  setOpen(open === m ? null : m)
                  setAmount('')
                }}
                className={
                  'mono rounded-md border px-2.5 py-1 text-[0.7rem] uppercase tracking-widest transition ' +
                  (open === m
                    ? 'border-gold/70 text-gold'
                    : 'border-line text-muted hover:border-gold/50')
                }
              >
                {m}
              </button>
            ))}
          </div>

          {open && (
            <div className="mt-3 flex flex-col gap-2">
              <Field
                label={`${open} amount`}
                inputMode="decimal"
                placeholder="0.0000"
                value={amount}
                onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))}
              />
              <div className="flex gap-2">
                <Button loading={busy} onClick={submit}>
                  Confirm {open}
                </Button>
                <Button variant="ghost" onClick={() => setOpen(null)}>
                  Cancel
                </Button>
              </div>
              <ErrorNote>{error}</ErrorNote>
            </div>
          )}
        </div>
      )}
    </NoteFrame>
  )
}
