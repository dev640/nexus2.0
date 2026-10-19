import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse } from 'axios'
import { useAppStore } from '../useAppStore'
import { api, apiChangePassword, apiLogin, getStoredToken, type ApiAuthResponse, type ApiUser } from '../../lib/api'

vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>()
  return {
    ...actual,
    apiLogin: vi.fn(),
    apiChangePassword: vi.fn(),
    // `api` is deliberately the real instance (spread by reference), so requests
    // go through the real interceptor that watches for the gate's marker.
  }
})

const gated: ApiUser = { id: 9, name: 'Ada', email: 'ada@nexus.com', role: 'MEMBER', mustChangePassword: true }
const ungated: ApiUser = { ...gated, mustChangePassword: false }

const loginResponse: ApiAuthResponse = { token: 't', refreshToken: 'r', user: gated }
const changedResponse: ApiAuthResponse = {
  token: 'fresh-token',
  refreshToken: 'fresh-refresh',
  user: ungated,
}

/** A profile response, minus the transport metadata nothing here reads. */
function profileResponse(user: ApiUser): AxiosResponse<ApiUser> {
  return { data: user, status: 200, statusText: 'OK', headers: {}, config: {} } as unknown as AxiosResponse<ApiUser>
}

function refusal(status: number, data: Record<string, unknown>) {
  return Object.assign(new Error(`HTTP ${status}`), {
    isAxiosError: true,
    response: { status, data },
  })
}

/**
 * Answers `/users/me` with a profile and every other path with the server's
 * refusal. The adapter is the innermost seam axios has, so the request that
 * reaches it has already passed through the response interceptors — which is
 * the half of the feature being asserted here.
 */
function serveUsersMe(user: ApiUser, otherPathError?: unknown) {
  api.defaults.adapter = (config) =>
    config.url === '/users/me'
      ? Promise.resolve(profileResponse(user))
      : Promise.reject(otherPathError ?? refusal(500, { message: 'stubbed' }))
}

/**
 * The sign-in half of the feature: what the app does with the flag, and what it
 * refuses to do while it is set.
 */
describe('gated session', () => {
  const loadWorkspace = vi.fn(async () => undefined)
  const realAdapter = api.defaults.adapter

  beforeEach(() => {
    loadWorkspace.mockClear()
    vi.mocked(apiLogin).mockReset().mockResolvedValue(loginResponse)
    vi.mocked(apiChangePassword).mockReset().mockResolvedValue(changedResponse)
    useAppStore.setState({
      isAuthenticated: false,
      isBootstrapped: false,
      currentUser: null,
      userRole: 'MEMBER',
      loadWorkspace,
    })
  })

  afterEach(() => {
    api.defaults.adapter = realAdapter
    vi.restoreAllMocks()
  })

  it('signs a gated account in without asking for the workspace it may not read', async () => {
    const result = await useAppStore.getState().login('ada@nexus.com', 'issued-pass')

    expect(result.ok).toBe(true)
    expect(useAppStore.getState().currentUser?.mustChangePassword).toBe(true)
    // Every workspace endpoint would refuse this account, so nothing is fetched
    // until the password has been replaced.
    expect(loadWorkspace).not.toHaveBeenCalled()
  })

  it('signs a SUPER_ADMIN in without requesting tenant workspace data', async () => {
    const operator: ApiUser = { ...gated, role: 'SUPER_ADMIN', mustChangePassword: false }
    vi.mocked(apiLogin).mockResolvedValue({ ...loginResponse, user: operator })

    const result = await useAppStore.getState().login('ada@nexus.com', 'operator-pass')

    expect(result.ok).toBe(true)
    expect(useAppStore.getState().currentUser?.role).toBe('SUPER_ADMIN')
    expect(loadWorkspace).not.toHaveBeenCalled()
  })

  it('loads the workspace once the password has been changed', async () => {
    await useAppStore.getState().login('ada@nexus.com', 'issued-pass')
    expect(loadWorkspace).not.toHaveBeenCalled()

    const result = await useAppStore.getState().changePassword('issued-pass', 'brand-new-pass')

    expect(result.ok).toBe(true)
    expect(apiChangePassword).toHaveBeenCalledWith('issued-pass', 'brand-new-pass')
    // The profile is read back rather than patched, so the flag the screen is
    // gated on is the flag the server just cleared.
    expect(useAppStore.getState().currentUser?.mustChangePassword).toBe(false)
    expect(useAppStore.getState().currentUser?.email).toBe('ada@nexus.com')
    expect(getStoredToken()).toBe('fresh-token')
    expect(loadWorkspace).toHaveBeenCalledTimes(1)
  })

  it('leaves the account gated when the server refuses the change', async () => {
    await useAppStore.getState().login('ada@nexus.com', 'issued-pass')
    vi.mocked(apiChangePassword).mockRejectedValue(
      refusal(400, { status: 400, message: 'That is not your current password.' }),
    )

    const result = await useAppStore.getState().changePassword('wrong-pass', 'brand-new-pass')

    expect(result.ok).toBe(false)
    expect(result.error).toBe('That is not your current password.')
    expect(useAppStore.getState().currentUser?.mustChangePassword).toBe(true)
    expect(loadWorkspace).not.toHaveBeenCalled()
  })

  /**
   * A stale client can find out it is gated only by being refused: the profile it
   * holds has no flag on it yet. The refusal has to come back through the real
   * client, since the thing being checked is the client's own handling of it.
   */
  it('refreshes the profile when the server refuses with the gate marker', async () => {
    await useAppStore.getState().login('ada@nexus.com', 'issued-pass')

    // The client's copy is stale: no flag, as a profile served before the change
    // (or by an older server) would be.
    useAppStore.setState({ currentUser: { ...ungated, mustChangePassword: undefined } })
    serveUsersMe(gated, refusal(403, { status: 403, code: 'PASSWORD_CHANGE_REQUIRED', message: 'gated' }))

    await expect(api.get('/tasks')).rejects.toBeDefined()

    // Only the handler could have produced this: the refusal itself carried no
    // profile, and nothing else in the client writes one.
    await vi.waitFor(() =>
      expect(useAppStore.getState().currentUser?.mustChangePassword).toBe(true),
    )
    expect(useAppStore.getState().currentUser?.email).toBe('ada@nexus.com')
  })
})
