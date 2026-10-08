import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { clearAvatarCache, forgetAvatar, invalidateAvatar, useAvatar } from './useAvatar'
import { api } from '../lib/api'

vi.mock('../lib/api', () => ({ api: { get: vi.fn() } }))

const get = vi.mocked(api.get)

/** A promise whose settlement the test controls, so reads can overlap on purpose. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

describe('useAvatar', () => {
  beforeEach(() => {
    clearAvatarCache()
    get.mockReset()
    // jsdom implements neither, and the hook needs both.
    URL.createObjectURL = vi.fn((blob: Blob) => `blob:${blob.size}`) as typeof URL.createObjectURL
    URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    cleanup()
    clearAvatarCache()
  })

  it('reads the avatar once and reuses the cached object URL', async () => {
    get.mockResolvedValue({ data: new Blob(['image-bytes']) } as never)

    const { result } = renderHook(() => useAvatar(7))

    await waitFor(() => expect(result.current).toBe('blob:11'))
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/users/7/avatar', { responseType: 'blob' })
  })

  it('treats a removed avatar as final instead of refetching it', async () => {
    get.mockResolvedValue({ data: new Blob(['image-bytes']) } as never)
    const { result } = renderHook(() => useAvatar(7))
    await waitFor(() => expect(result.current).toBe('blob:11'))
    expect(get).toHaveBeenCalledTimes(1)

    act(() => forgetAvatar(7))

    // The picture goes away and, crucially, no read is started that could
    // report it back — a refetch here is what used to restore it.
    expect(result.current).toBeNull()
    await new Promise((r) => setTimeout(r, 0))
    expect(get).toHaveBeenCalledTimes(1)
  })

  it('ignores a read that a later change superseded', async () => {
    const stale = deferred<{ data: Blob }>()
    const fresh = deferred<{ data: Blob }>()
    get.mockReturnValueOnce(stale.promise as never).mockReturnValueOnce(fresh.promise as never)

    const { result, rerender } = renderHook(() => useAvatar(7))
    expect(get).toHaveBeenCalledTimes(1)

    // An upload or a delete lands while the first read is still in flight.
    act(() => invalidateAvatar(7))
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))

    // The pre-change bytes arriving late must not be cached. The re-render is
    // what makes a polluted cache visible: the hook reads it during render, so
    // without it the assertion would only restate the previous render.
    await act(async () => {
      stale.resolve({ data: new Blob(['the-old-image']) })
      await stale.promise
    })
    rerender()
    expect(result.current).toBeNull()

    // ...while the read started after the change is the one that counts.
    await act(async () => {
      fresh.resolve({ data: new Blob(['new']) })
      await fresh.promise
    })
    await waitFor(() => expect(result.current).toBe('blob:3'))
  })
})
