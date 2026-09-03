import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAppSelector } from '../../app/hooks'

/** Gate for the platform screens — bounces to /login when there is no CIF. */
export function RequireSession({ children }: { children: ReactNode }) {
  const cif = useAppSelector((s) => s.session.cif)
  const location = useLocation()
  if (!cif) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
}
