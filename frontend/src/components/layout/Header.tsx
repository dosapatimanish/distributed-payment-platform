import { Link, useNavigate } from 'react-router-dom'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { clearCif } from '../../features/session/sessionSlice'
import { toast } from '../../lib/toast'
import { ThemeToggle } from './ThemeToggle'

export function Header() {
  const cif = useAppSelector((s) => s.session.cif)
  const dispatch = useAppDispatch()
  const navigate = useNavigate()

  function signOut() {
    dispatch(clearCif())
    toast('Signed out')
    navigate('/')
  }

  return (
    <header className="sticky top-0 z-20 border-b border-line bg-bg/80 backdrop-blur-md">
      <div className="mx-auto flex h-16 w-full max-w-[1120px] items-center justify-between px-6">
        <Link to="/" className="group flex items-center gap-3">
          <span className="grid h-8 w-8 place-items-center rounded-md border border-gold/50 font-display text-sm font-bold text-gold">
            $
          </span>
          <span className="font-display text-lg font-semibold tracking-tight">
            Greenback
          </span>
        </Link>
        <div className="flex items-center gap-3">
          {cif ? (
            <>
              <span className="mono hidden rounded-full border border-line px-3 py-1.5 text-[0.7rem] uppercase tracking-widest text-muted sm:inline">
                CIF {cif}
              </span>
              <button
                onClick={signOut}
                className="mono rounded-full border border-line px-3 py-1.5 text-[0.7rem] uppercase tracking-widest text-muted transition hover:border-gold/60 hover:text-text"
              >
                Sign out
              </button>
            </>
          ) : (
            <Link
              to="/login"
              className="mono rounded-full border border-line px-3 py-1.5 text-[0.7rem] uppercase tracking-widest text-muted transition hover:border-gold/60 hover:text-text"
            >
              Sign in
            </Link>
          )}
          <ThemeToggle />
        </div>
      </div>
    </header>
  )
}
