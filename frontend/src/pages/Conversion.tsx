import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAppSelector } from '../app/hooks'
import { NoteFrame } from '../components/note/NoteFrame'
import { Button, Empty, ErrorNote, Field, Select } from '../components/ui/controls'
import { StatusPill } from '../components/ui/StatusPill'
import { formatDateTime, formatMoney } from '../lib/format'
import { platform } from '../api/client'
import { TERMINAL_SAGA, type Conversion as Conv } from '../api/types'
import { usePlatformMutation, usePlatformQuery } from '../api/hooks'
import { SagaStepper } from '../features/conversion/SagaStepper'

export default function Conversion() {
  const cif = useAppSelector((s) => s.session.cif)
  const wallets = usePlatformQuery(() => platform.listWallets(cif ?? ''), [cif])
  const history = usePlatformQuery(() => platform.listConversions(cif ?? ''), [cif])

  const active = (wallets.data ?? []).filter((w) => w.status === 'ACTIVE')
  const [sourceAccountNo, setSource] = useState('')
  const [destAccountNo, setDest] = useState('')
  const [amount, setAmount] = useState('100')
  const [merchantId, setMerchantId] = useState('')
  const [txnId, setTxnId] = useState<string | null>(null)

  const src = active.find((w) => w.accountNo === sourceAccountNo) ?? active[0]
  const dst =
    active.find((w) => w.accountNo === destAccountNo) ??
    active.find((w) => w.accountNo !== src?.accountNo)

  const rate = usePlatformQuery(
    () =>
      src && dst ? platform.getRate(src.currency, dst.currency) : Promise.resolve(null),
    [src?.currency, dst?.currency],
  )
  const estDest = useMemo(() => {
    if (!rate.data || !amount) return null
    return (Number(amount) * rate.data.rate).toFixed(4)
  }, [rate.data, amount])

  const [start, startState] = usePlatformMutation(platform.startConversion)

  if (!cif) {
    return (
      <NoteFrame denomination="—">
        <h1 className="text-2xl">No session</h1>
        <Link to="/" className="mt-4 inline-block text-sm text-accent">
          ← Home
        </Link>
      </NoteFrame>
    )
  }

  async function run() {
    if (!src || !dst) return
    const res = await start({
      cif: cif!,
      sourceAccountNo: src.accountNo,
      destAccountNo: dst.accountNo,
      sourceCurrency: src.currency,
      destCurrency: dst.currency,
      sourceAmount: amount,
      merchantId: merchantId || null,
    })
    if (res) setTxnId(res.transactionId)
  }

  return (
    <div className="flex flex-col gap-8">
      <header>
        <h1 className="text-2xl">Conversion</h1>
        <p className="mono mt-1 text-xs uppercase tracking-[0.2em] text-muted">
          wallet → wallet saga · CIF {cif}
        </p>
      </header>

      <div className="grid gap-4 lg:grid-cols-[1fr_1fr]">
        <NoteFrame denomination="new">
          <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
            Start a conversion
          </p>
          {active.length < 2 ? (
            <Empty>Need at least two active wallets.</Empty>
          ) : (
            <div className="mt-4 flex flex-col gap-3">
              <Select
                label="Source wallet"
                value={src?.accountNo ?? ''}
                onChange={(e) => setSource(e.target.value)}
                options={active.map((w) => ({
                  value: w.accountNo,
                  label: `${w.currency} · ${w.accountNo} · ${w.balance}`,
                }))}
              />
              <Select
                label="Destination wallet"
                value={dst?.accountNo ?? ''}
                onChange={(e) => setDest(e.target.value)}
                options={active
                  .filter((w) => w.accountNo !== src?.accountNo)
                  .map((w) => ({
                    value: w.accountNo,
                    label: `${w.currency} · ${w.accountNo} · ${w.balance}`,
                  }))}
              />
              <Field
                label={`Amount (${src?.currency ?? ''})`}
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))}
              />
              <Field
                label="Merchant id (optional)"
                placeholder="leave blank, or acct-decline to force compensation"
                value={merchantId}
                onChange={(e) => setMerchantId(e.target.value)}
              />
              <p className="text-xs text-muted">
                rate {rate.data ? rate.data.rate.toFixed(4) : '—'} · est. credit{' '}
                {estDest && dst ? formatMoney(estDest, dst.currency) : '—'}
              </p>
              <Button loading={startState.loading} onClick={run}>
                Run conversion
              </Button>
              <ErrorNote>{startState.error}</ErrorNote>
            </div>
          )}
        </NoteFrame>

        <NoteFrame denomination="saga">
          <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
            Progress
          </p>
          <div className="mt-4">
            {txnId ? (
              <SagaView txnId={txnId} />
            ) : (
              <Empty>Run a conversion to watch the saga.</Empty>
            )}
          </div>
        </NoteFrame>
      </div>

      <MerchantPanel />

      <NoteFrame denomination="log">
        <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
          Conversion history
        </p>
        {(history.data ?? []).length === 0 ? (
          <Empty>No conversions yet.</Empty>
        ) : (
          <ul className="mt-3 flex flex-col divide-y divide-line">
            {(history.data ?? []).map((c) => (
              <li
                key={c.transactionId}
                className="flex flex-wrap items-center justify-between gap-2 py-3"
              >
                <button className="text-left" onClick={() => setTxnId(c.transactionId)}>
                  <p className="mono text-sm underline decoration-line underline-offset-4">
                    {c.transactionId}
                  </p>
                  <p className="mono text-xs text-muted">
                    {c.sourceAmount} {c.sourceCurrency} → {c.destAmount ?? '—'}{' '}
                    {c.destCurrency} · {formatDateTime(c.createdAt)}
                  </p>
                </button>
                <StatusPill status={c.sagaState} />
              </li>
            ))}
          </ul>
        )}
      </NoteFrame>
    </div>
  )
}

function SagaView({ txnId }: { txnId: string }) {
  const [conv, setConv] = useState<Conv>()
  const [error, setError] = useState<string>()

  useEffect(() => {
    setConv(undefined)
    setError(undefined)
    let alive = true
    let timer: ReturnType<typeof setTimeout>
    const tick = async () => {
      try {
        const c = await platform.getConversion(txnId)
        if (!alive) return
        setConv(c)
        if (!TERMINAL_SAGA.includes(c.sagaState)) timer = setTimeout(tick, 1200)
      } catch (e) {
        if (alive) setError(e instanceof Error ? e.message : String(e))
      }
    }
    tick()
    return () => {
      alive = false
      clearTimeout(timer)
    }
  }, [txnId])

  if (error) return <ErrorNote>{error}</ErrorNote>
  if (!conv) return <Empty>loading…</Empty>
  return <SagaStepper conv={conv} />
}

function MerchantPanel() {
  const payments = usePlatformQuery(() => platform.listPayments(), [])
  const [merchantId, setMerchantId] = useState('store-001')
  const [amount, setAmount] = useState('49.99')
  const [currency, setCurrency] = useState('USD')
  const [charge, chargeState] = usePlatformMutation(platform.charge)
  const [refund, refundState] = usePlatformMutation(platform.refund)

  const list = (payments.data ?? []).slice().reverse()

  return (
    <NoteFrame denomination="pay">
      <p className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
        Direct merchant charge
      </p>
      <div className="mt-4 grid gap-3 sm:grid-cols-4 sm:items-end">
        <Field
          label="Merchant id"
          value={merchantId}
          onChange={(e) => setMerchantId(e.target.value)}
          hint="acct-decline → declined"
        />
        <Field
          label="Amount"
          inputMode="decimal"
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))}
        />
        <Select
          label="Currency"
          value={currency}
          onChange={(e) => setCurrency(e.target.value)}
          options={['USD', 'EUR', 'INR', 'GBP'].map((c) => ({
            value: c,
            label: c,
          }))}
        />
        <Button
          loading={chargeState.loading}
          onClick={() => charge({ merchantId, amount, currency })}
        >
          Charge
        </Button>
      </div>
      <ErrorNote>{chargeState.error || refundState.error}</ErrorNote>

      <div className="mt-5">
        {list.length === 0 ? (
          <Empty>No payments yet.</Empty>
        ) : (
          <ul className="flex flex-col divide-y divide-line">
            {list.map((p) => (
              <li
                key={p.paymentId}
                className="flex flex-wrap items-center justify-between gap-2 py-3"
              >
                <div>
                  <p className="mono text-sm">{p.paymentId}</p>
                  <p className="mono text-xs text-muted">
                    {p.merchantId} · {formatMoney(p.amount, p.currency)} ·{' '}
                    {p.acquirerRef ?? 'no ref'}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <StatusPill status={p.status} />
                  {p.status === 'COMPLETED' && (
                    <Button
                      variant="danger"
                      loading={refundState.loading}
                      onClick={() => refund(p.paymentId)}
                    >
                      Refund
                    </Button>
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
