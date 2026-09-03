import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { getVersion, subscribe } from './client'

/** Re-renders whenever an API mutation (or the rate ticker) fires. */
export function usePlatformVersion(): number {
  return useSyncExternalStore(subscribe, getVersion, getVersion)
}

interface AsyncState<T> {
  data: T | undefined
  error: string | undefined
  loading: boolean
}

/** Runs `fn` on mount and whenever the API changes or `deps` change. */
export function usePlatformQuery<T>(
  fn: () => Promise<T>,
  deps: unknown[] = [],
): AsyncState<T> & { refetch: () => void } {
  const version = usePlatformVersion()
  const [state, setState] = useState<AsyncState<T>>({
    data: undefined,
    error: undefined,
    loading: true,
  })
  const fnRef = useRef(fn)
  fnRef.current = fn

  const run = useCallback(() => {
    let cancelled = false
    setState((s) => ({ ...s, loading: true }))
    fnRef
      .current()
      .then((data) => {
        if (!cancelled) setState({ data, error: undefined, loading: false })
      })
      .catch((err: unknown) => {
        if (!cancelled)
          setState((s) => ({
            ...s,
            error: err instanceof Error ? err.message : String(err),
            loading: false,
          }))
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    const cancel = run()
    return cancel
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run, version])

  return { ...state, refetch: run }
}

interface MutationState {
  loading: boolean
  error: string | undefined
}

/** Wraps a one-shot API call with loading / error state. */
export function usePlatformMutation<Args extends unknown[], T>(
  fn: (...args: Args) => Promise<T>,
): [(...args: Args) => Promise<T | undefined>, MutationState] {
  const [state, setState] = useState<MutationState>({
    loading: false,
    error: undefined,
  })

  const call = useCallback(
    async (...args: Args) => {
      setState({ loading: true, error: undefined })
      try {
        const result = await fn(...args)
        setState({ loading: false, error: undefined })
        return result
      } catch (err) {
        setState({
          loading: false,
          error: err instanceof Error ? err.message : String(err),
        })
        return undefined
      }
    },
    [fn],
  )

  return [call, state]
}
