import type { ReactNode } from 'react'

interface Props {
  children: ReactNode
  className?: string
}

/** Ornamental corner numeral, like the value printed on a banknote's corners. */
export function Denomination({ children, className = '' }: Props) {
  return (
    <span
      aria-hidden="true"
      className={
        'pointer-events-none select-none font-display text-[0.65rem] font-bold uppercase tracking-[0.25em] ' +
        className
      }
    >
      {children}
    </span>
  )
}
