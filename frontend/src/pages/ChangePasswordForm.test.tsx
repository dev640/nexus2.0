import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse } from 'axios'
import { ChangePasswordForm } from '../components/auth/ChangePasswordForm'
import { useAppStore } from '../store/useAppStore'
import { api, apiChangePassword } from '../lib/api'
import type { ApiAuthResponse, ApiUser } from '../lib/api'

vi.mock('../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/api')>()
  return {
    ...actual,
    apiChangePassword: vi.fn(),
  }
})

const gated: ApiUser = { id: 9, name: 'Ada', email: 'ada@nexus.com', role: 'MEMBER', mustChangePassword: true }
const ungated: ApiUser = { ...gated, mustChangePassword: false }
const changedSession: ApiAuthResponse = {
  token: 'new-access-token',
  refreshToken: 'new-refresh-token',
  user: ungated,
}

/**
 * An axios-shaped refusal carrying a server message, which is exactly what the
 * endpoint returns for a wrong or unverifiable current password.
 */
function refused(message: string): unknown {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 400, data: { status: 400, message } },
  })
}

describe('ChangePasswordForm', () => {
  const onDone = vi.fn()
  // Workspace loading is out of scope here: it is what runs *after* the gate
  // lifts, and driving it would need every workspace endpoint stubbed.
  const loadWorkspace = vi.fn(async () => undefined)

  beforeEach(() => {
    onDone.mockReset()
    loadWorkspace.mockClear()
    vi.mocked(apiChangePassword).mockReset().mockResolvedValue(changedSession)
    vi.spyOn(api, 'get').mockResolvedValue({ data: ungated } as unknown as AxiosResponse)
    useAppStore.setState({
      isAuthenticated: true,
      isBootstrapped: true,
      currentUser: gated,
      userRole: 'MEMBER',
      loadWorkspace,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  function fill(current: string, next: string, confirm: string) {
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: current } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: next } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: confirm } })
  }

  function submit(current: string, next: string, confirm: string) {
    // A router is required because a successful change ends the Supabase session
    // by sending the person back to the sign-in screen.
    render(
      <MemoryRouter>
        <ChangePasswordForm onDone={onDone} />
      </MemoryRouter>,
    )
    fill(current, next, confirm)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
  }

  function resubmit(current: string, next: string, confirm: string) {
    fill(current, next, confirm)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
  }

  it('refuses a confirmation that does not match, without spending a request', async () => {
    submit('issued-pass', 'brand-new-pass', 'brand-new-passs')

    expect(await screen.findByRole('alert')).toHaveTextContent('The two passwords do not match.')
    expect(apiChangePassword).not.toHaveBeenCalled()
    expect(onDone).not.toHaveBeenCalled()
  })

  it('refuses a new password shorter than the rule the server applies', async () => {
    submit('issued-pass', 'short', 'short')

    expect(await screen.findByRole('alert')).toHaveTextContent('at least 8 characters')
    expect(apiChangePassword).not.toHaveBeenCalled()
  })

  it('sends the current and new password, then reports completion', async () => {
    submit('issued-pass', 'brand-new-pass', 'brand-new-pass')

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1))
    expect(apiChangePassword).toHaveBeenCalledWith('issued-pass', 'brand-new-pass')
    // The store's answer is what the screen waits on, so the flag is already
    // cleared by the time onDone runs.
    expect(useAppStore.getState().currentUser?.mustChangePassword).toBe(false)
    expect(await screen.queryByRole('alert')).toBeNull()
  })

  /**
   * The server is the only party that knows whether the current password was
   * right — or, for a Supabase account, whether it could be checked at all. A
   * generic "could not change your password" here would leave the person retrying
   * a correct password.
   */
  it("shows the server's own refusal and does not report completion", async () => {
    vi.mocked(apiChangePassword).mockRejectedValue(refused('That is not your current password.'))

    submit('wrong-pass', 'brand-new-pass', 'brand-new-pass')

    expect(await screen.findByRole('alert')).toHaveTextContent('That is not your current password.')
    expect(onDone).not.toHaveBeenCalled()
    expect(useAppStore.getState().currentUser?.mustChangePassword).toBe(true)
  })

  it('keeps the form usable after a refusal, so the correction can be resubmitted', async () => {
    vi.mocked(apiChangePassword).mockRejectedValueOnce(
      refused('That is not your current password.'),
    )
    submit('wrong-pass', 'brand-new-pass', 'brand-new-pass')
    await screen.findByRole('alert')

    resubmit('issued-pass', 'brand-new-pass', 'brand-new-pass')

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1))
    expect(apiChangePassword).toHaveBeenLastCalledWith('issued-pass', 'brand-new-pass')
  })
})
