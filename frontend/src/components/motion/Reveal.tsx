import { Children, useRef } from 'react'
import type { ReactNode } from 'react'
import { useGSAP } from '@gsap/react'
import { gsap } from '../../lib/gsap'
import { useAppSelector } from '../../app/hooks'

interface Props {
  children: ReactNode
  className?: string
  /** rise distance in px */
  y?: number
  stagger?: number
}

/** Fades direct children up in sequence the first time the block enters view.
 *  Job: establish reading order on long sections.
 *
 *  Uses an IntersectionObserver (not ScrollTrigger) so it can't get stuck
 *  half-faded when fonts/lazy chunks shift the layout after mount. Always
 *  resolves children to their natural state; no-ops under reduced motion. */
export function Reveal({ children, className = '', y = 24, stagger = 0.08 }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const reduced = useAppSelector((s) => s.ui.reducedMotion)
  const count = Children.count(children)

  useGSAP(
    () => {
      const el = ref.current
      if (!el) return
      const items = Array.from(el.children)
      if (reduced || items.length === 0) return

      gsap.set(items, { opacity: 0, y })

      let played = false
      const play = () => {
        if (played) return
        played = true
        gsap.to(items, {
          opacity: 1,
          y: 0,
          duration: 0.6,
          ease: 'power2.out',
          stagger,
          clearProps: 'transform',
        })
      }

      const io = new IntersectionObserver(
        (entries) => {
          if (entries.some((e) => e.isIntersecting)) {
            play()
            io.disconnect()
          }
        },
        { threshold: 0.12 },
      )
      io.observe(el)
      // safety: if IO never fires (e.g. layout quirks), reveal after a beat
      const fallback = window.setTimeout(play, 1200)

      return () => {
        io.disconnect()
        window.clearTimeout(fallback)
        gsap.set(items, { clearProps: 'opacity,transform' })
      }
    },
    { scope: ref, dependencies: [reduced, count] },
  )

  return (
    <div ref={ref} className={className}>
      {children}
    </div>
  )
}
