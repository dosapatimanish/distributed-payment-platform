import { Suspense, useEffect } from 'react'
import { Outlet } from 'react-router-dom'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { setReducedMotion } from '../../features/ui/uiSlice'
import { usePrefersReducedMotion } from '../../hooks/usePrefersReducedMotion'
import { useSystemTheme } from '../../hooks/useSystemTheme'
import { Guilloche } from '../note/Guilloche'
import { MoneyLoader } from '../../three/MoneyLoader'
import { ToastHost } from '../ui/ToastHost'
import { Header } from './Header'
import { SubNav } from './SubNav'
import { Footer } from './Footer'

export function AppShell() {
  const dispatch = useAppDispatch()
  const pref = useAppSelector((s) => s.ui.theme)
  const system = useSystemTheme()
  const prefersReduced = usePrefersReducedMotion()

  const resolved = pref === 'system' ? system : pref

  useEffect(() => {
    document.documentElement.dataset.theme = resolved
  }, [resolved])

  useEffect(() => {
    dispatch(setReducedMotion(prefersReduced))
  }, [prefersReduced, dispatch])

  return (
    <div className="relative flex min-h-full flex-col">
      {/* fixed old-bank background: guilloché + vignette that fades to page color */}
      <div aria-hidden="true" className="fixed inset-0 -z-10">
        <Guilloche className="h-full w-full text-muted opacity-[0.04]" />
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,transparent_35%,var(--bg)_100%)]" />
      </div>

      <Header />
      <SubNav />

      <main className="mx-auto w-full max-w-[1120px] flex-1 px-6 py-12">
        <Suspense
          fallback={
            <div className="grid min-h-[60vh] place-items-center">
              <MoneyLoader status="idle" label="Loading" />
            </div>
          }
        >
          <Outlet />
        </Suspense>
      </main>

      <Footer />
      <ToastHost />
    </div>
  )
}
