import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Guilloche } from './Guilloche'
import { Denomination } from './Denomination'

interface Props {
  children: ReactNode
  className?: string
  /** Only interactive cards lift on hover/focus. */
  interactive?: boolean
  /** Corner value, like a bill's printed denomination. */
  denomination?: ReactNode
  /** When set, the whole note is a router link. */
  to?: string
}

/** The banknote-shaped panel. Every card / form / result surface uses it,
 *  so the whole app reads as one system of notes. */
export function NoteFrame({
  children,
  className = '',
  interactive = false,
  denomination,
  to,
}: Props) {
  const classes = [
    'group relative isolate block overflow-hidden rounded-2xl bg-surface p-6',
    'border border-line',
    interactive
      ? 'transition duration-200 ease-out hover:-translate-y-0.5 hover:border-gold/60'
      : '',
    className,
  ].join(' ')

  const inner = (
    <>
      {/* faint guilloché watermark behind content */}
      <Guilloche className="pointer-events-none absolute inset-0 -z-10 h-full w-full text-muted opacity-[0.05]" />
      {/* inset dashed rule — the double-edge of a bill */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-[5px] rounded-xl border border-dashed border-line/70"
      />
      {denomination != null && (
        <>
          <Denomination className="absolute left-3.5 top-3 text-gold/70">
            {denomination}
          </Denomination>
          <Denomination className="absolute bottom-3 right-3.5 text-gold/70">
            {denomination}
          </Denomination>
        </>
      )}
      <div className="relative">{children}</div>
    </>
  )

  return to ? (
    <Link to={to} className={classes}>
      {inner}
    </Link>
  ) : (
    <div className={classes}>{inner}</div>
  )
}
