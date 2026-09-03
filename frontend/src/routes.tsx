import { lazy } from 'react'
import type { ReactNode } from 'react'
import { createBrowserRouter } from 'react-router-dom'
import { AppShell } from './components/layout/AppShell'
import { RequireSession } from './components/auth/RequireSession'

const Landing = lazy(() => import('./pages/Landing'))
const Login = lazy(() => import('./pages/Login'))
const Wallets = lazy(() => import('./pages/Wallets'))
const Fx = lazy(() => import('./pages/Fx'))
const Conversion = lazy(() => import('./pages/Conversion'))
const Ledger = lazy(() => import('./pages/Ledger'))

const guard = (el: ReactNode) => <RequireSession>{el}</RequireSession>

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Landing /> },
      { path: 'login', element: <Login /> },
      { path: 'wallets', element: guard(<Wallets />) },
      { path: 'fx', element: guard(<Fx />) },
      { path: 'conversion', element: guard(<Conversion />) },
      { path: 'ledger', element: guard(<Ledger />) },
    ],
  },
])
