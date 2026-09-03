interface Props {
  className?: string
}

/** Greenback seal — a restrained GB mark in a plain ring with an arced wordmark. */
export function Monogram({ className = '' }: Props) {
  return (
    <svg
      className={className}
      viewBox="0 0 160 160"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label="Greenback"
    >
      <defs>
        <path id="gb-arc" d="M 32 88 A 48 48 0 0 0 128 88" />
      </defs>

      {/* rings */}
      <circle cx="80" cy="80" r="66" stroke="var(--gold)" strokeWidth="1.5" />
      <circle
        cx="80"
        cy="80"
        r="58"
        stroke="var(--text)"
        strokeWidth="1"
        opacity="0.55"
      />

      {/* GB — one colour, tight, upright */}
      <text
        x="80"
        y="86"
        textAnchor="middle"
        fontFamily="var(--font-display), Georgia, serif"
        fontWeight="600"
        fontSize="52"
        letterSpacing="-3"
        fill="var(--text)"
      >
        GB
      </text>

      {/* small rule between mark and wordmark */}
      <line x1="62" y1="102" x2="98" y2="102" stroke="var(--gold)" strokeWidth="1" />

      {/* arced wordmark along the lower ring */}
      <text
        fontFamily="var(--font-mono), ui-monospace, monospace"
        fontSize="8.5"
        letterSpacing="4.5"
        fill="var(--text)"
        opacity="0.6"
      >
        <textPath href="#gb-arc" startOffset="50%" textAnchor="middle">
          GREENBACK
        </textPath>
      </text>
    </svg>
  )
}
