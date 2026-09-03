import { useEffect, useState } from 'react'
import type { RefObject } from 'react'

/** True only when the element is on-screen AND the tab is visible.
 *  Used to fully stop the 3D render loop when nothing can see it. */
export function useIsVisible(ref: RefObject<Element | null>): boolean {
  const [inView, setInView] = useState(true)
  const [tabVisible, setTabVisible] = useState(
    typeof document === 'undefined' ? true : document.visibilityState === 'visible',
  )

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver(([entry]) => setInView(entry.isIntersecting), {
      threshold: 0.1,
    })
    io.observe(el)
    const onVis = () => setTabVisible(document.visibilityState === 'visible')
    document.addEventListener('visibilitychange', onVis)
    return () => {
      io.disconnect()
      document.removeEventListener('visibilitychange', onVis)
    }
  }, [ref])

  return inView && tabVisible
}
