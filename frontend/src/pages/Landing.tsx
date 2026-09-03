import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { NoteFrame } from '../components/note/NoteFrame'
import { Reveal } from '../components/motion/Reveal'
import { MoneyLoader, type LoaderStatus } from '../three/MoneyLoader'
import { useAppSelector } from '../app/hooks'

const FX_SAMPLE = [
  { pair: 'USD / INR', rate: '83.0000' },
  { pair: 'USD / EUR', rate: '0.9200' },
  { pair: 'EUR / INR', rate: '90.0000' },
  { pair: 'USD / GBP', rate: '0.7900' },
]

const FLOWS = [
  {
    to: '/wallets',
    title: 'Wallets',
    note: 'Open, fund and inspect multi-currency wallets.',
    d: '01',
  },
  {
    to: '/fx',
    title: 'FX Rates',
    note: 'Live pair rates and short-lived rate locks.',
    d: '02',
  },
  {
    to: '/conversion',
    title: 'Conversion',
    note: 'Cross-currency transfer run as a saga.',
    d: '03',
  },
  {
    to: '/ledger',
    title: 'Ledger',
    note: 'Immutable double-entry account statement.',
    d: '04',
  },
]

const DEMO_STATUSES: LoaderStatus[] = ['idle', 'processing', 'success', 'failure']

export default function Landing() {
  const [params] = useSearchParams()
  const demo = params.get('loader')
  const demoStatus = DEMO_STATUSES.includes(demo as LoaderStatus)
    ? (demo as LoaderStatus)
    : null

  return (
    <div className="flex flex-col gap-12">
      {demoStatus && (
        <NoteFrame denomination="DEV" className="border-gold/50">
          <p className="mb-4 mono text-xs uppercase tracking-[0.2em] text-muted">
            MoneyLoader — status: {demoStatus}
          </p>
          <MoneyLoader status={demoStatus} />
        </NoteFrame>
      )}

      <section className="grid gap-6 md:grid-cols-[1.1fr_0.9fr] md:items-stretch">
        <SessionCard />
        <FxTicker />
      </section>

      <Reveal className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {FLOWS.map((f) => (
          <NoteFrame key={f.to} to={f.to} interactive denomination={f.d}>
            <h3 className="text-base">{f.title}</h3>
            <p className="mt-2 text-sm text-muted">{f.note}</p>
          </NoteFrame>
        ))}
      </Reveal>
    </div>
  )
}

function SessionCard() {
  const cif = useAppSelector((s) => s.session.cif)
  const navigate = useNavigate()

  return (
    <NoteFrame denomination="100" className="flex flex-col">
      <h1 className="text-2xl">Greenback</h1>
      <p className="mt-2 max-w-prose text-sm text-muted">
        A distributed multi-currency wallet &amp; FX platform. Wallets, live rates,
        cross-currency conversions run as a saga, and an immutable ledger.
      </p>

      {cif ? (
        <div className="mt-6 flex flex-col gap-3">
          <p className="mono text-xs uppercase tracking-[0.2em] text-muted">
            Signed in · CIF <span className="text-text">{cif}</span>
          </p>
          <button
            onClick={() => navigate('/wallets')}
            className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-green-deep transition hover:bg-accent-bright"
          >
            Open wallets →
          </button>
        </div>
      ) : (
        <div className="mt-6 flex flex-col gap-3">
          <p className="text-sm text-muted">Sign in to work with the platform.</p>
          <Link
            to="/login"
            className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-green-deep transition hover:bg-accent-bright"
          >
            Sign in
          </Link>
        </div>
      )}
    </NoteFrame>
  )
}

function FxTicker() {
  return (
    <NoteFrame denomination="FX" className="flex flex-col">
      <div className="flex items-baseline justify-between">
        <h2 className="text-base">Market rates</h2>
        <span className="mono text-[0.65rem] uppercase tracking-[0.2em] text-muted">
          sample
        </span>
      </div>
      <ul className="mt-4 flex flex-col divide-y divide-line">
        {FX_SAMPLE.map((r) => (
          <li key={r.pair} className="flex items-center justify-between py-2.5">
            <span className="mono text-sm text-muted">{r.pair}</span>
            <span className="mono text-sm font-medium">{r.rate}</span>
          </li>
        ))}
      </ul>
      <p className="mt-4 text-xs text-muted">
        Open <span className="text-text">FX</span> for the live-ticking feed.
      </p>
    </NoteFrame>
  )
}
