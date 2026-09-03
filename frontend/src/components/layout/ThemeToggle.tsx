import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { cycleTheme, type ThemePref } from '../../features/ui/uiSlice'

const LABEL: Record<ThemePref, string> = {
  light: 'Light',
  dark: 'Dark',
  system: 'Auto',
}

function Icon({ pref }: { pref: ThemePref }) {
  if (pref === 'light') {
    return (
      <svg
        viewBox="0 0 24 24"
        className="h-4 w-4"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
      >
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4 12H2M22 12h-2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19" />
      </svg>
    )
  }
  if (pref === 'dark') {
    return (
      <svg
        viewBox="0 0 24 24"
        className="h-4 w-4"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
      >
        <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
      </svg>
    )
  }
  return (
    <svg
      viewBox="0 0 24 24"
      className="h-4 w-4"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <rect x="3" y="4" width="18" height="14" rx="2" />
      <path d="M8 21h8M12 18v3" />
    </svg>
  )
}

export function ThemeToggle() {
  const pref = useAppSelector((s) => s.ui.theme)
  const dispatch = useAppDispatch()
  return (
    <button
      type="button"
      onClick={() => dispatch(cycleTheme())}
      aria-label={`Theme: ${LABEL[pref]}. Click to change.`}
      className="inline-flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-1.5 text-xs font-medium text-muted transition hover:border-gold/60 hover:text-text"
    >
      <Icon pref={pref} />
      <span className="mono uppercase tracking-widest">{LABEL[pref]}</span>
    </button>
  )
}
