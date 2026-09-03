import { NavLink } from 'react-router-dom'
import { useAppSelector } from '../../app/hooks'
import { platform } from '../../api/client'
import { toast } from '../../lib/toast'

const LINKS = [
  { to: '/', label: 'Home', end: true },
  { to: '/wallets', label: 'Wallets' },
  { to: '/fx', label: 'FX' },
  { to: '/conversion', label: 'Convert' },
  { to: '/ledger', label: 'Ledger' },
]

export function SubNav() {
  const cif = useAppSelector((s) => s.session.cif)
  if (!cif) return null

  return (
    <div className="border-b border-line bg-bg/60 backdrop-blur">
      <div className="mx-auto flex w-full max-w-[1120px] items-center justify-between gap-4 px-6 py-2">
        <nav className="flex flex-wrap gap-1">
          {LINKS.map((l) => (
            <NavLink
              key={l.to}
              to={l.to}
              end={l.end}
              className={({ isActive }) =>
                'mono rounded-md px-3 py-1.5 text-[0.7rem] uppercase tracking-widest transition ' +
                (isActive ? 'bg-surface text-text' : 'text-muted hover:text-text')
              }
            >
              {l.label}
            </NavLink>
          ))}
        </nav>
        <button
          onClick={() => {
            platform.reset()
            toast('Local record cleared')
          }}
          title="Forget the wallet / conversion ids this browser has created (backend is untouched)"
          className="mono text-[0.65rem] uppercase tracking-widest text-muted hover:text-gold"
        >
          clear local
        </button>
      </div>
    </div>
  )
}
