import { useSyncExternalStore } from 'react'

const mql = () => window.matchMedia('(prefers-reduced-motion: reduce)')

function subscribe(cb: () => void) {
  const m = mql()
  m.addEventListener('change', cb)
  return () => m.removeEventListener('change', cb)
}

export function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => mql().matches,
    () => false,
  )
}
