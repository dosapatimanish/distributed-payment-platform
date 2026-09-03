import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAppSelector } from '../app/hooks'
import { NoteFrame } from '../components/note/NoteFrame'
import { Reveal } from '../components/motion/Reveal'
import { Button, Empty, ErrorNote, Select } from '../components/ui/controls'
import { StatusPill } from '../components/ui/StatusPill'
import { formatDateTime, formatMoney, totalsByCurrency } from '../lib/format'
import type { Reservation } from '../api/types'
import { platform } from '../api/client'
import { usePlatformMutation, usePlatformQuery } from '../api/hooks'
import { WalletCard } from '../features/wallet/WalletCard'
import { toast } from '../lib/toast'

const CCY = ['USD', 'EUR', 'INR', 'GBP']

export default function Wallets() {
  const cif = useAppSelector((s) => s.session.cif)

  const wallets = usePlatformQuery(() => platform.listWallets(cif ?? ''), [cif])
  const holds = usePlatformQuery(() => platform.listReservations(), [])

  const [newCcy, setNewCcy] = useState('USD')
  const [highContention, setHighContention] = useState(false)
  const [createWallet, createState] = usePlatformMutation(platform.createWallet)

  if (!cif) {
    return (
      <NoteFrame denomination="—">
        <h1 className="text-2xl">No session</h1>
        <p className="mt-2 text-sm text-muted">
          Enter a CIF on the home page to load wallets.
        </p>
        <Link
          to="/"
          className="mt-6 inline-block text-sm font-semibold text-accent hover:text-accent-bright"
        >
          ← Home
        </Link>
      </NoteFrame>
    )
  }

  const list = wallets.data ?? []
  const totals = totalsByCurrency(list)
  const active = list.filter((w) => w.status === 'ACTIVE').length
  const heldReservations = (holds.data ?? []).filter((r) => r.status === 'HELD')

  async function onCreate() {
    const w = await createWallet({ cif: cif!, currency: newCcy, highContention })
    if (w) toast(`Opened ${w.currency} wallet ${w.accountNo}`)
  }

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl">Wallets</h1>
          <p className="mono mt-1 text-xs uppercase tracking-[0.2em] text-muted">
            CIF {cif} · {list.length} wallets · {active} active
          </p>
        </div>
        <span className="mono rounded-full border border-gold/50 px-3 py-1 text-[0.65rem] uppercase tracking-widest text-gold">
          live · wallet-service
        </span>
      </header>

      <section className="grid gap-4 md:grid-cols-[1fr_1fr]">
        <NoteFrame denomination="Σ">
          <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
            Holdings by currency
          </p>
          <div className="mt-4 grid grid-cols-2 gap-x-8 gap-y-3">
            {totals.length === 0 && <Empty>No wallets yet.</Empty>}
            {totals.map((t) => (
              <div key={t.currency} className="flex flex-col">
                <span className="mono text-xs text-muted">{t.currency}</span>
                <span className="mono text-base font-medium">
                  {formatMoney(t.total, t.currency)}
                </span>
              </div>
            ))}
          </div>
        </NoteFrame>

        <NoteFrame denomination="+">
          <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
            Open a wallet
          </p>
          <div className="mt-4 flex flex-col gap-3">
            <Select
              label="Currency"
              value={newCcy}
              onChange={(e) => setNewCcy(e.target.value)}
              options={CCY.map((c) => ({ value: c, label: c }))}
            />
            <label className="flex items-center gap-2 text-sm text-muted">
              <input
                type="checkbox"
                checked={highContention}
                onChange={(e) => setHighContention(e.target.checked)}
              />
              high-contention wallet
            </label>
            <Button loading={createState.loading} onClick={onCreate}>
              Create wallet
            </Button>
            <ErrorNote>{createState.error}</ErrorNote>
          </div>
        </NoteFrame>
      </section>

      {wallets.error && <ErrorNote>{wallets.error}</ErrorNote>}

      <Reveal className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {list.map((w) => (
          <WalletCard key={w.accountNo} wallet={w} />
        ))}
      </Reveal>

      <NoteFrame denomination="hold">
        <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
          Active holds
        </p>
        {heldReservations.length === 0 ? (
          <Empty>No active reservations.</Empty>
        ) : (
          <ul className="mt-3 flex flex-col divide-y divide-line">
            {heldReservations.map((r) => (
              <ReservationRow key={r.reservationId} r={r} />
            ))}
          </ul>
        )}
      </NoteFrame>
    </div>
  )
}

function ReservationRow({ r }: { r: Reservation }) {
  const [capture, capState] = usePlatformMutation(platform.captureReservation)
  const [release, relState] = usePlatformMutation(platform.releaseReservation)
  return (
    <li className="flex flex-wrap items-center justify-between gap-2 py-3">
      <div>
        <p className="mono text-sm">{r.reservationId}</p>
        <p className="mono text-xs text-muted">
          {r.accountNo} · {r.amount} · exp {formatDateTime(r.expiresAt)}
        </p>
      </div>
      <div className="flex items-center gap-2">
        <StatusPill status={r.status} />
        <Button
          variant="ghost"
          loading={capState.loading}
          onClick={() => capture(r.reservationId)}
        >
          Capture
        </Button>
        <Button
          variant="danger"
          loading={relState.loading}
          onClick={() => release(r.reservationId)}
        >
          Release
        </Button>
      </div>
    </li>
  )
}
