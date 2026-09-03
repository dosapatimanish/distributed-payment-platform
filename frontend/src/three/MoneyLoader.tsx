import { Suspense, lazy, useRef } from 'react'
import { useAppSelector } from '../app/hooks'
import { useIsVisible } from '../hooks/useIsVisible'
import { hasWebGL } from '../lib/webgl'
import { Monogram } from '../components/brand/Monogram'
import type { LoaderStatus } from './types'

export type { LoaderStatus }

const BanknoteScene = lazy(() =>
  import('./Banknote').then((m) => ({ default: m.BanknoteScene })),
)

const COPY: Record<LoaderStatus, string> = {
  idle: 'Loading',
  processing: 'Processing',
  success: 'Done',
  failure: 'Something went wrong',
}

interface Props {
  status?: LoaderStatus
  label?: string
  className?: string
  /** show the GB monogram beneath the fan instead of a status word */
  brand?: boolean
}

/** The app's single 3D surface and its loading / async-status primitive.
 *  Falls back to a CSS bill when motion is reduced, WebGL is missing,
 *  or nothing can see it (so the render loop never runs unwatched). */
export function MoneyLoader({
  status = 'idle',
  label,
  className = '',
  brand = false,
}: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const reduced = useAppSelector((s) => s.ui.reducedMotion)
  const visible = useIsVisible(ref)
  const use3D = visible && !reduced && hasWebGL()

  return (
    <div
      ref={ref}
      className={'flex flex-col items-center justify-center gap-3 ' + className}
      role="status"
      aria-live="polite"
    >
      <div
        className="relative h-48 w-72"
        style={{
          WebkitMaskImage:
            'radial-gradient(ellipse 84% 82% at 50% 52%, #000 64%, transparent 100%)',
          maskImage:
            'radial-gradient(ellipse 84% 82% at 50% 52%, #000 64%, transparent 100%)',
        }}
      >
        {use3D ? (
          <Suspense fallback={<CssBill status={status} />}>
            <BanknoteScene status={status} />
          </Suspense>
        ) : (
          <CssBill status={status} />
        )}
      </div>
      {brand ? (
        <Monogram className="h-20 w-20" />
      ) : (
        <span className="mono text-xs uppercase tracking-[0.2em] text-muted">
          {label ?? COPY[status]}
        </span>
      )}
    </div>
  )
}

function CssBill({ status }: { status: LoaderStatus }) {
  const tone =
    status === 'failure'
      ? 'border-gold/40 bg-[#8a5a2b]/15'
      : status === 'success'
        ? 'border-gold/70 bg-gold/15'
        : 'border-accent/40 bg-accent/10'
  return (
    <div className="grid h-full w-full place-items-center">
      <div
        className={[
          'relative h-24 w-44 rounded-lg border-2',
          tone,
          status === 'processing' ? 'motion-safe:animate-pulse' : '',
          status === 'failure' ? '-rotate-6' : '',
        ].join(' ')}
      >
        <span className="absolute inset-1.5 rounded border border-dashed border-current opacity-40" />
        <span className="absolute left-1/2 top-1/2 h-8 w-8 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-current opacity-40" />
      </div>
    </div>
  )
}
