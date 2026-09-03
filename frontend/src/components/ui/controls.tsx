import type {
  ButtonHTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
} from 'react'

/* -------------------------------------------------------------- Button --- */

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'ghost' | 'danger'
  loading?: boolean
}

export function Button({
  variant = 'primary',
  loading = false,
  disabled,
  className = '',
  children,
  ...rest
}: ButtonProps) {
  const styles = {
    primary: 'bg-accent text-green-deep hover:bg-accent-bright disabled:opacity-40',
    ghost: 'border border-line text-text hover:border-gold/60 disabled:opacity-40',
    danger: 'border border-gold/60 text-gold hover:bg-gold/10 disabled:opacity-40',
  }[variant]
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold transition ${styles} ${className}`}
    >
      {loading ? '…' : children}
    </button>
  )
}

/* --------------------------------------------------------------- Field --- */

type FieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string
  hint?: string
}

export function Field({ label, hint, className = '', id, ...rest }: FieldProps) {
  const fieldId = id ?? label.toLowerCase().replace(/\s+/g, '-')
  return (
    <label htmlFor={fieldId} className="flex flex-col gap-1">
      <span className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
        {label}
      </span>
      <input
        id={fieldId}
        {...rest}
        className={`mono rounded-lg border border-line bg-bg px-3 py-2 text-sm outline-none focus:border-gold ${className}`}
      />
      {hint && <span className="text-[0.7rem] text-muted">{hint}</span>}
    </label>
  )
}

/* -------------------------------------------------------------- Select --- */

type SelectProps = SelectHTMLAttributes<HTMLSelectElement> & {
  label: string
  options: { value: string; label: string }[]
}

export function Select({ label, options, id, className = '', ...rest }: SelectProps) {
  const fieldId = id ?? label.toLowerCase().replace(/\s+/g, '-')
  return (
    <label htmlFor={fieldId} className="flex flex-col gap-1">
      <span className="mono text-[0.7rem] uppercase tracking-[0.2em] text-muted">
        {label}
      </span>
      <select
        id={fieldId}
        {...rest}
        className={`mono rounded-lg border border-line bg-bg px-3 py-2 text-sm outline-none focus:border-gold ${className}`}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

/* ------------------------------------------------------------- feedback --- */

export function ErrorNote({ children }: { children: ReactNode }) {
  if (!children) return null
  return (
    <p className="rounded-lg border border-gold/50 bg-gold/10 px-3 py-2 text-xs text-gold">
      {children}
    </p>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-muted">{children}</p>
}
