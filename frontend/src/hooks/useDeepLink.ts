import { useEffect, useReducer, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'

/** What a page decided about an alert's deep link. */
export type DeepLinkOutcome =
  /** The target was found and opened; the link has done its job. */
  | 'open'
  /** The page cannot tell yet (data still loading), so the link is kept. */
  | 'wait'
  /** The target is not in the data this page has. */
  | 'gone'

export interface DeepLinkOptions {
  /**
   * Fetches the data the link may refer to. Called at most once per distinct
   * link when the target was not found — an alert can easily outrun the
   * workspace the viewer loaded — and if it returns a promise, the link is kept
   * until that promise settles before being written off.
   */
  refresh?: () => unknown
}

/**
 * Consumes an alert's deep link — the query parameters a clicked notification
 * puts on the URL — once the page has acted on it.
 *
 * The handler is re-run after every render on purpose: whether the target exists
 * depends on store data that arrives asynchronously, so a single pass on mount
 * would drop a link that outran its own data. The parameters are stripped as
 * soon as the outcome is settled, so a reload or a Back does not reopen the item.
 */
export function useDeepLink(
  keys: string[],
  handle: (query: URLSearchParams) => DeepLinkOutcome,
  options: DeepLinkOptions = {},
): void {
  const [searchParams, setSearchParams] = useSearchParams()
  const handleRef = useRef(handle)
  const optionsRef = useRef(options)
  useEffect(() => {
    handleRef.current = handle
    optionsRef.current = options
  })

  const link = keys.map((key) => searchParams.get(key) ?? '').join('|')
  const present = keys.some((key) => searchParams.get(key) != null)
  const refreshedFor = useRef<string | null>(null)
  const pendingRefresh = useRef<Promise<unknown> | null>(null)
  const [, rerender] = useReducer((count: number) => count + 1, 0)

  useEffect(() => {
    if (!present) return

    const outcome = handleRef.current(searchParams)
    if (outcome === 'open') {
      setSearchParams({}, { replace: true })
      return
    }
    // Still loading: leave the link alone and let a later render decide.
    if (outcome === 'wait') return

    // Not found. Give the page one chance to fetch what it is missing, and keep
    // the link while that fetch is out; only once it comes back empty-handed is
    // the link treated as pointing at something that no longer exists.
    const { refresh } = optionsRef.current
    if (refresh && refreshedFor.current !== link) {
      refreshedFor.current = link
      const result = refresh()
      if (result instanceof Promise) {
        pendingRefresh.current = result
        void result.finally(() => {
          pendingRefresh.current = null
          rerender()
        })
      }
      return
    }
    if (pendingRefresh.current) return

    setSearchParams({}, { replace: true })
  })
}
