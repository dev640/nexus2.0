import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useDeepLink, type DeepLinkOptions, type DeepLinkOutcome } from './useDeepLink'

/** Every query the hook handed to the page, in order. */
const handled: string[] = []

beforeEach(() => {
  handled.length = 0
})

/**
 * Stands in for a page: reports the URL the hook left behind and answers with
 * whatever the test wants the page's data to be doing.
 */
function DeepLinkPage({
  decide,
  options,
}: {
  decide: (query: URLSearchParams) => DeepLinkOutcome
  options?: DeepLinkOptions
}) {
  useDeepLink(
    ['task', 'message'],
    (query) => {
      handled.push(query.toString())
      return decide(query)
    },
    options,
  )
  const location = useLocation()

  return <span data-testid="search">{location.search}</span>
}

function page(decide: (query: URLSearchParams) => DeepLinkOutcome, options?: DeepLinkOptions) {
  return (
    <MemoryRouter initialEntries={['/board?task=42']}>
      <Routes>
        <Route path="*" element={<DeepLinkPage decide={decide} options={options} />} />
      </Routes>
    </MemoryRouter>
  )
}

describe('useDeepLink', () => {
  it('hands the link to the page and takes it out of the URL once it is open', () => {
    render(page((query) => (query.get('task') === '42' ? 'open' : 'gone')))

    expect(handled).toEqual(['task=42'])
    // Consumed exactly once: the stripped URL must not re-trigger the page.
    expect(screen.getByTestId('search').textContent).toBe('')
  })

  it('keeps a link the page is still loading data for', () => {
    render(page(() => 'wait'))

    expect(screen.getByTestId('search').textContent).toBe('?task=42')
    expect(handled.length).toBeGreaterThan(0)
  })

  it('drops a link pointing at something that is really gone', async () => {
    const refresh = vi.fn(async () => {})
    render(page(() => 'gone', { refresh }))

    // One refresh, then the link is written off rather than left in the URL.
    expect(refresh).toHaveBeenCalledTimes(1)
    await act(async () => {})
    expect(screen.getByTestId('search').textContent).toBe('')
  })

  it('waits for the refresh before giving up, and opens once the data lands', async () => {
    let settle: (() => void) | null = null
    const refresh = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          settle = resolve
        }),
    )
    let loaded = false

    render(page(() => (loaded ? 'open' : 'gone'), { refresh }))

    expect(refresh).toHaveBeenCalledTimes(1)
    // The fetch is still out, so the link survives even though the page missed it.
    expect(screen.getByTestId('search').textContent).toBe('?task=42')

    loaded = true
    await act(async () => {
      settle?.()
    })

    expect(screen.getByTestId('search').textContent).toBe('')
  })

  it('refreshes once per link, not on every render', async () => {
    const refresh = vi.fn(async () => {})
    const { rerender } = render(page(() => 'gone', { refresh }))

    rerender(page(() => 'gone', { refresh }))

    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('does nothing at all when there is no link', () => {
    render(
      <MemoryRouter initialEntries={['/board']}>
        <Routes>
          <Route path="*" element={<DeepLinkPage decide={() => 'open'} />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(handled).toEqual([])
  })
})
