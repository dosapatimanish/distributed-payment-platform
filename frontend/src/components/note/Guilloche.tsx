import { useId } from 'react'

interface Props {
  className?: string
}

/** Tiling guilloché line-work — the "old bank / royal" register.
 *  stroke = currentColor, so callers set color + opacity via utilities. */
export function Guilloche({ className = '' }: Props) {
  const id = useId().replace(/:/g, '')
  return (
    <svg className={className} aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <pattern
          id={`guilloche-${id}`}
          width="140"
          height="140"
          patternUnits="userSpaceOnUse"
          patternTransform="rotate(9)"
        >
          <g fill="none" stroke="currentColor" strokeWidth="0.6">
            <circle cx="70" cy="70" r="60" />
            <circle cx="70" cy="70" r="46" />
            <circle cx="70" cy="70" r="32" />
            <circle cx="70" cy="70" r="18" />
            <ellipse cx="70" cy="70" rx="60" ry="24" />
            <ellipse cx="70" cy="70" rx="24" ry="60" />
            <ellipse cx="70" cy="70" rx="50" ry="50" transform="rotate(45 70 70)" />
            <ellipse cx="70" cy="70" rx="50" ry="18" transform="rotate(30 70 70)" />
            <ellipse cx="70" cy="70" rx="50" ry="18" transform="rotate(-30 70 70)" />
          </g>
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill={`url(#guilloche-${id})`} />
    </svg>
  )
}
