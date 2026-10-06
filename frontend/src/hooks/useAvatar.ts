import { useEffect, useState, useSyncExternalStore } from 'react'
import axios from 'axios'
import { api } from '../lib/api'

/**
 * Avatar loading for user chips across the app.
 *
 * A bare `<img src="/api/users/7/avatar">` cannot be used here: the endpoint is
 * authenticated, and an <img> request carries no Authorization header, so it
 * would come back 403. The bytes therefore have to be fetched through axios
 * (which attaches the bearer token) and handed to <img> as an object URL.
 *
 * The cache is module-level rather than per-hook because the same avatar is
 * rendered in several places at once, and without a shared cache each mount
 * would issue its own request.
 */
type CacheEntry = { url: string | null }

const cache = new Map<number, CacheEntry>()

/**
 * Object URLs whose replacement has not been fetched yet. They are revoked
 * once a newer image arrives, never at invalidation time: a component that is
 * still rendering the old URL would show a broken image icon.
 */
const pendingRevoke: string[] = []

// Bumped by invalidateAvatar so mounted hooks refetch. Without this a cache
// invalidation would be invisible until the component happened to remount.
let version = 0
const listeners = new Set<() => void>()

function subscribe(onChange: () => void): () => void {
  listeners.add(onChange)
  return () => {
    listeners.delete(onChange)
  }
}

function getVersion(): number {
  return version
}

/**
 * Returns an object URL for the user's avatar, or null when they have none.
 *
 * Only a 404 is remembered as "no avatar". Anything else — an expired token, a
 * network blip — is transient, and caching that as a negative result would
 * leave a permanently blank avatar until the page was reloaded.
 *
 * The cache is read during render rather than copied into state. That keeps a
 * warm avatar visible on the very first paint instead of flashing initials and
 * filling in a frame later, and it means an invalidation takes effect on the
 * next render without any synchronous state update to schedule.
 */
export function useAvatar(userId: number | null | undefined): string | null {
  // Subscribing to the version is what makes an invalidation a re-render.
  const cacheVersion = useSyncExternalStore(subscribe, getVersion)
  const [fetched, setFetched] = useState<{ userId: number; url: string | null } | null>(null)

  useEffect(() => {
    if (userId == null || cache.has(userId)) return

    let cancelled = false
    void (async () => {
      let entry: CacheEntry = { url: null }
      let loaded = false
      try {
        const response = await api.get(`/users/${userId}/avatar`, { responseType: 'blob' })
        entry = { url: URL.createObjectURL(response.data as Blob) }
        loaded = true
      } catch (error) {
        const status = axios.isAxiosError(error) ? error.response?.status : undefined
        if (status !== 404) {
          // Transient failure: leave the cache clean so the next mount retries.
          return
        }
      }

      // A newer image now exists, so anything invalidated earlier is unused.
      if (loaded) flushPendingRevoke()

      cache.set(userId, entry)
      if (!cancelled) setFetched({ userId, url: entry.url })
    })()

    return () => {
      // Revoking here would break any sibling still rendering the same URL, so
      // object URLs live until a replacement arrives or the cache is cleared.
      cancelled = true
    }
    // cacheVersion is a dependency so invalidateAvatar() refetches in place.
  }, [userId, cacheVersion])

  if (userId == null) return null
  const cached = cache.get(userId)
  if (cached) return cached.url
  // Only trust state that was fetched for the user currently being rendered.
  return fetched && fetched.userId === userId ? fetched.url : null
}

/**
 * Drops one cached avatar, or all of them when called with no argument. Call
 * this after an upload or a delete so every mounted avatar refetches.
 */
export function invalidateAvatar(userId?: number | null): void {
  if (userId == null) {
    for (const entry of cache.values()) revoke(entry.url)
    cache.clear()
  } else {
    revoke(cache.get(userId)?.url ?? null)
    cache.delete(userId)
  }
  version += 1
  for (const listener of listeners) listener()
}

/** Drops every cached avatar and revokes its object URL. Used on sign-out. */
export function clearAvatarCache(): void {
  invalidateAvatar()
  flushPendingRevoke()
}

function revoke(url: string | null): void {
  if (!url) return
  pendingRevoke.push(url)
  if (pendingRevoke.length > 32) revokeOldest()
}

/** Safety valve so an unused object URL is never retained indefinitely. */
function revokeOldest(): void {
  const oldest = pendingRevoke.shift()
  if (oldest) URL.revokeObjectURL(oldest)
}

function flushPendingRevoke(): void {
  while (pendingRevoke.length > 0) {
    const url = pendingRevoke.pop()
    if (url) URL.revokeObjectURL(url)
  }
}