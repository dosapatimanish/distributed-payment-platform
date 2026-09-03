import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAppDispatch, useAppSelector } from '../app/hooks'
import { DUMMY_CIF, setCif } from '../features/session/sessionSlice'
import { NoteFrame } from '../components/note/NoteFrame'
import { Button, ErrorNote, Field } from '../components/ui/controls'
import { MoneyLoader } from '../three/MoneyLoader'
import { toast } from '../lib/toast'

const CIF_RE = /^\d{10}$/
const PASS_RE = /^\d{4,6}$/

interface FromState {
  from?: string
}

export default function Login() {
  const dispatch = useAppDispatch()
  const navigate = useNavigate()
  const location = useLocation()
  const alreadyIn = useAppSelector((s) => s.session.cif)
  const dest = (location.state as FromState | null)?.from ?? '/wallets'

  const [cif, setCifValue] = useState('')
  const [pass, setPass] = useState('')
  const [touched, setTouched] = useState(false)

  const cifOk = CIF_RE.test(cif)
  const passOk = PASS_RE.test(pass)
  const valid = cifOk && passOk

  function signIn(value: string) {
    dispatch(setCif(value))
    toast(`Signed in as CIF ${value}`)
    navigate(dest, { replace: true })
  }

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-8 py-6">
      <div className="grid gap-6 md:grid-cols-[1fr_0.8fr] md:items-center">
        <NoteFrame denomination="IN">
          <button
            type="button"
            onClick={() => navigate('/')}
            className="mono mb-3 inline-flex items-center gap-1.5 text-[0.7rem] uppercase tracking-[0.2em] text-muted transition hover:text-text"
          >
            <span aria-hidden="true">←</span> Back
          </button>
          <h1 className="text-2xl">Sign in</h1>
          <p className="mt-2 text-sm text-muted">
            The platform has no real accounts. Sign in with a 10-digit customer reference
            (CIF) and any 4–6 digit passcode — nothing leaves your browser.
          </p>

          {alreadyIn && (
            <p className="mt-4 rounded-lg border border-line px-3 py-2 text-xs text-muted">
              Already signed in as <span className="mono text-text">{alreadyIn}</span>.{' '}
              <button
                className="text-accent hover:text-accent-bright"
                onClick={() => navigate('/wallets')}
              >
                Go to wallets →
              </button>
            </p>
          )}

          <form
            className="mt-6 flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault()
              setTouched(true)
              if (valid) signIn(cif)
            }}
          >
            <Field
              label="Customer reference (CIF)"
              inputMode="numeric"
              autoComplete="username"
              placeholder="0000000000"
              value={cif}
              onChange={(e) =>
                setCifValue(e.target.value.replace(/\D/g, '').slice(0, 10))
              }
              onBlur={() => setTouched(true)}
            />
            <Field
              label="Passcode"
              type="password"
              inputMode="numeric"
              autoComplete="current-password"
              placeholder="••••"
              value={pass}
              onChange={(e) => setPass(e.target.value.replace(/\D/g, '').slice(0, 6))}
              onBlur={() => setTouched(true)}
            />

            {touched && !valid && (
              <ErrorNote>
                {!cifOk
                  ? 'CIF must be exactly 10 digits.'
                  : 'Passcode must be 4–6 digits.'}
              </ErrorNote>
            )}

            <div className="flex flex-wrap gap-2">
              <Button type="submit" disabled={!valid}>
                Sign in
              </Button>
              <Button type="button" variant="ghost" onClick={() => signIn(DUMMY_CIF)}>
                Use demo customer
              </Button>
            </div>
          </form>
        </NoteFrame>

        <div className="hidden md:block">
          <MoneyLoader status="idle" brand />
        </div>
      </div>
    </div>
  )
}
