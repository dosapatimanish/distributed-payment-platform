import { useSyncExternalStore } from 'react'

const mql = () => window.matchMedia('(prefers-color-scheme: dark)')

function subscribe(cb: () => void) {
  const m = mql()
  m.addEventListener('change', cb)
  return () => m.removeEventListener('change', cb)
}

export function useSystemTheme(): 'light' | 'dark' {
  return useSyncExternalStore(
    subscribe,
    () => (mql().matches ? 'dark' : 'light'),
    () => 'light',
  )
}
