import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import {
  api,
  authClient,
  clearStoredTokens,
  getStoredRefreshToken,
  getStoredToken,
  registerUnauthenticatedHandler,
  storeRefreshToken,
  storeToken,
  type ApiAuthResponse,
} from './api'

/**
 * Silent refresh, in the three shapes that matter.
 *
 * An access token lives 24 h and a refresh token a week, so before this a tab
 * left open overnight was signed out while a perfectly good credential sat in
 * storage. The recovery is only worth having if it is exactly once per burst
 * (the server rotates the refresh token, so a second exchange spends a token
 * that no longer exists) and if a refused exchange genuinely ends the session
 * rather than leaving every page failing with 401.
 *
 * Adapters are replaced rather than methods spied on, so requests run through
 * axios's real interceptor chain and only the network is faked.
 */
function ok<T>(config: InternalAxiosRequestConfig, data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}

function unauthorized(config: InternalAxiosRequestConfig): unknown {
  return Object.assign(new Error('HTTP 401'), {
    isAxiosError: true,
    config,
    response: { status: 401, data: {} },
  })
}

function bearer(config: InternalAxiosRequestConfig): string | undefined {
  const value = config.headers?.Authorization
  return typeof value === 'string' ? value : undefined
}

const REFRESHED: ApiAuthResponse = {
  token: 'fresh-token',
  refreshToken: 'fresh-refresh',
  user: { id: 1, name: 'Ada', email: 'ada@example.test', role: 'MEMBER' },
}

describe('silent access-token refresh', () => {
  const expiredHandler = vi.fn()

  afterEach(() => {
    registerUnauthenticatedHandler(null)
    clearStoredTokens()
    api.defaults.adapter = ['xhr', 'http', 'fetch']
    authClient.defaults.adapter = ['xhr', 'http', 'fetch']
    vi.clearAllMocks()
  })

  it('exchanges the refresh token once and replays the original request', async () => {
    const authorizations: Array<string | undefined> = []
    let profileCalls = 0

    api.defaults.adapter = async (config) => {
      authorizations.push(bearer(config))
      profileCalls += 1
      if (profileCalls === 1) throw unauthorized(config)
      return ok(config, { id: 1 })
    }
    authClient.defaults.adapter = async (config) => {
      expect(config.url).toBe('/auth/refresh')
      expect(String(config.data)).toContain('refresh-1')
      return ok(config, REFRESHED)
    }

    registerUnauthenticatedHandler(expiredHandler)
    storeToken('expired-token')
    storeRefreshToken('refresh-1')

    const { data } = await api.get('/users/me')

    expect(data).toEqual({ id: 1 })
    // The first attempt carried the dead token, the replay carried the new one.
    expect(authorizations).toEqual(['Bearer expired-token', 'Bearer fresh-token'])
    expect(getStoredToken()).toBe('fresh-token')
    expect(getStoredRefreshToken()).toBe('fresh-refresh')
    expect(expiredHandler).not.toHaveBeenCalled()
  })

  it('performs one exchange for a burst of simultaneous 401s', async () => {
    let exchanges = 0

    api.defaults.adapter = async (config) => {
      if (bearer(config) === 'Bearer expired-token') throw unauthorized(config)
      return ok(config, { url: config.url })
    }
    authClient.defaults.adapter = async (config) => {
      exchanges += 1
      // Yield, so every caller has reached refreshOnce before it settles.
      await Promise.resolve()
      return ok(config, REFRESHED)
    }

    storeToken('expired-token')
    storeRefreshToken('refresh-1')

    const results = await Promise.all([
      api.get('/projects'),
      api.get('/tasks'),
      api.get('/notifications'),
    ])

    expect(exchanges).toBe(1)
    expect(results.map((r) => (r.data as { url: string }).url)).toEqual([
      '/projects',
      '/tasks',
      '/notifications',
    ])
  })

  it('drops the session when the exchange is refused', async () => {
    api.defaults.adapter = async (config) => {
      throw unauthorized(config)
    }
    authClient.defaults.adapter = async (config) => {
      throw unauthorized(config)
    }

    registerUnauthenticatedHandler(expiredHandler)
    storeToken('expired-token')
    storeRefreshToken('revoked-refresh')

    await expect(api.get('/users/me')).rejects.toBeDefined()

    expect(expiredHandler).toHaveBeenCalledTimes(1)
    expect(getStoredToken()).toBeNull()
    expect(getStoredRefreshToken()).toBeNull()
  })

  it('does not try to exchange anything when no refresh token was stored', async () => {
    let exchanges = 0

    api.defaults.adapter = async (config) => {
      throw unauthorized(config)
    }
    authClient.defaults.adapter = async (config) => {
      exchanges += 1
      return ok(config, REFRESHED)
    }

    registerUnauthenticatedHandler(expiredHandler)
    storeToken('expired-token')

    await expect(api.get('/users/me')).rejects.toBeDefined()

    expect(exchanges).toBe(0)
    expect(expiredHandler).toHaveBeenCalledTimes(1)
  })

  it('leaves a 403 alone, since authorization is not a dead credential', async () => {
    let exchanges = 0

    api.defaults.adapter = async (config) => {
      throw Object.assign(new Error('HTTP 403'), {
        isAxiosError: true,
        config,
        response: { status: 403, data: { message: 'not allowed' } },
      })
    }
    authClient.defaults.adapter = async (config) => {
      exchanges += 1
      return ok(config, REFRESHED)
    }

    registerUnauthenticatedHandler(expiredHandler)
    storeToken('live-token')
    storeRefreshToken('refresh-1')

    await expect(api.get('/tasks')).rejects.toBeDefined()

    expect(exchanges).toBe(0)
    expect(expiredHandler).not.toHaveBeenCalled()
  })
})
