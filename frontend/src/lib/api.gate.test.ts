import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import {
  api,
  registerPasswordChangeRequiredHandler,
  registerUnauthenticatedHandler,
  storeToken,
  PASSWORD_CHANGE_REQUIRED,
} from './api'

/**
 * The dispatch half of the stale-client safety net: a refusal carrying the
 * server's marker reaches the registered handler, and nothing else does.
 *
 * The handler itself (refresh the profile) is tested where it is written —
 * store/slices/authGate.test.ts. What is at stake here is axios actually
 * consulting the interceptor at all, which is the part a future refactor of the
 * client could drop without any other test noticing.
 *
 * The adapter is replaced rather than a method spied on, so the request goes
 * through axios's real chain — interceptors included — and only the network is
 * faked.
 */
function refuseWith(error: unknown) {
  api.defaults.adapter = () => Promise.reject(error)
}

function refusal(status: number, data?: Record<string, unknown>) {
  return Object.assign(new Error(`HTTP ${status}`), {
    isAxiosError: true,
    response: { status, data: data ?? {} },
  })
}

describe('password-change gate dispatch', () => {
  const handler = vi.fn()

  afterEach(() => {
    registerPasswordChangeRequiredHandler(null)
    api.defaults.adapter = ['xhr', 'http', 'fetch']
    vi.clearAllMocks()
  })

  it('calls the registered handler for a refusal carrying the marker', async () => {
    registerPasswordChangeRequiredHandler(handler)
    refuseWith(refusal(403, { status: 403, code: PASSWORD_CHANGE_REQUIRED, message: 'gated' }))

    await expect(api.get('/tasks')).rejects.toBeDefined()

    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('leaves an ordinary 403 alone, since it is not this feature speaking', async () => {
    registerPasswordChangeRequiredHandler(handler)
    refuseWith(refusal(403, { status: 403, message: 'You do not have permission' }))

    await expect(api.get('/tasks')).rejects.toBeDefined()

    expect(handler).not.toHaveBeenCalled()
  })

  it('leaves every other status alone', async () => {
    registerPasswordChangeRequiredHandler(handler)
    refuseWith(refusal(400, { status: 400, code: PASSWORD_CHANGE_REQUIRED }))

    await expect(api.get('/tasks')).rejects.toBeDefined()

    expect(handler).not.toHaveBeenCalled()
  })

  it('does nothing at all when no handler is registered', async () => {
    refuseWith(refusal(403, { status: 403, code: PASSWORD_CHANGE_REQUIRED }))

    await expect(api.get('/tasks')).rejects.toBeDefined()
  })
})

/**
 * A 401 is a statement about the credential, not about the caller's role.
 *
 * It became reachable when ApiAuthenticationEntryPoint replaced Spring
 * Security's 403 fallback: a token invalidated by a password change (V22 bumps
 * the account's token version) is now answered 401, and this client has to stop
 * pretending it has a session. A 401 with no token attached is a different
 * thing — a failed sign-in — and must not sign anybody out.
 */
describe('invalidated-session dispatch', () => {
  const handler = vi.fn()

  afterEach(() => {
    registerUnauthenticatedHandler(null)
    storeToken(null)
    api.defaults.adapter = ['xhr', 'http', 'fetch']
    vi.clearAllMocks()
  })

  /** Rejects with the config the request actually carried, header included. */
  function refuseWithStatus(status: number) {
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      throw Object.assign(new Error(`HTTP ${status}`), {
        isAxiosError: true,
        config,
        response: { status, data: {} },
      })
    }
  }

  it('drops the session when a token-carrying request is answered 401', async () => {
    registerUnauthenticatedHandler(handler)
    storeToken('stale-token')
    refuseWithStatus(401)

    await expect(api.get('/users/me')).rejects.toBeDefined()

    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('leaves a 401 alone when the request carried no token', async () => {
    registerUnauthenticatedHandler(handler)
    refuseWithStatus(401)

    await expect(api.get('/auth/login')).rejects.toBeDefined()

    expect(handler).not.toHaveBeenCalled()
  })

  it('leaves a 403 alone, which is authorization rather than a dead session', async () => {
    registerUnauthenticatedHandler(handler)
    storeToken('live-token')
    refuseWithStatus(403)

    await expect(api.get('/users/me')).rejects.toBeDefined()

    expect(handler).not.toHaveBeenCalled()
  })

  it('does nothing at all when no handler is registered', async () => {
    storeToken('stale-token')
    refuseWithStatus(401)

    await expect(api.get('/users/me')).rejects.toBeDefined()
  })
})
