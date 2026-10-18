import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App'
import { useAppStore } from './store/useAppStore'
import type { ApiUser } from './lib/api'

/**
 * The gate is a condition on the session, not a route — which is the point: it
 * has to hold for a reload on a deep link exactly as it does right after
 * sign-in, whatever the URL says. So this asserts what the screen *replaces*,
 * rather than that some path navigates to it.
 */
describe('App under must_change_password', () => {
  const user: ApiUser = {
    id: 9,
    name: 'Ada',
    email: 'ada@nexus.com',
    role: 'MEMBER',
    mustChangePassword: true,
  }

  beforeEach(() => {
    useAppStore.setState({
      isAuthenticated: true,
      isBootstrapped: true,
      currentUser: user,
      userRole: 'MEMBER',
    })
  })

  it('renders the forced change screen instead of the workspace', () => {
    render(
      <MemoryRouter initialEntries={['/board']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Set a new password' })).toBeTruthy()
    expect(screen.getByLabelText('Current password')).toBeTruthy()
    expect(screen.getByText('ada@nexus.com')).toBeTruthy()
    // A way out, so a person who cannot change it right now is not trapped here.
    expect(screen.getByRole('button', { name: 'Sign out instead' })).toBeTruthy()
  })

  it('offers the change screen whether the flag arrived at sign-in or on reload', () => {
    render(
      <MemoryRouter initialEntries={['/settings']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Set a new password' })).toBeTruthy()
  })

  it('renders normally once the flag is cleared', () => {
    useAppStore.setState({ currentUser: { ...user, mustChangePassword: false } })

    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('heading', { name: 'Set a new password' })).toBeNull()
  })
})
