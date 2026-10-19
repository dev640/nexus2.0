import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import App from '../App'
import {
  apiCreateCompany,
  apiListAdminAccounts,
  apiListAdminCompanies,
  apiResumeCompany,
  apiSuspendCompany,
} from '../lib/api'
import { useAppStore } from '../store/useAppStore'

vi.mock('../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/api')>()
  return {
    ...actual,
    apiCreateCompany: vi.fn(),
    apiListAdminAccounts: vi.fn(),
    apiListAdminCompanies: vi.fn(),
    apiResumeCompany: vi.fn(),
    apiSuspendCompany: vi.fn(),
  }
})

const companies = [{
  id: 4,
  name: 'North Company',
  status: 'ACTIVE' as const,
  employeeCount: 1,
  createdAt: '2026-01-02T00:00:00',
}]

const accounts = [{
  id: 8,
  name: 'Ada Lovelace',
  email: 'ada@north.test',
  role: 'ADMIN' as const,
  employeeCode: 'NX-0001',
  mustChangePassword: false,
  companyId: 4,
  companyName: 'North Company',
  companyStatus: 'ACTIVE' as const,
}]

function renderApp(path = '/platform-admin') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('SUPER_ADMIN operator surface', () => {
  beforeEach(() => {
    vi.mocked(apiListAdminCompanies).mockResolvedValue(companies)
    vi.mocked(apiListAdminAccounts).mockResolvedValue(accounts)
    vi.mocked(apiSuspendCompany).mockResolvedValue(undefined)
    vi.mocked(apiResumeCompany).mockResolvedValue(undefined)
    vi.mocked(apiCreateCompany).mockResolvedValue({
      companyId: 5,
      companyName: 'South Company',
      adminId: 9,
      adminName: 'Grace Hopper',
      adminEmail: 'grace@south.test',
      role: 'ADMIN',
      generatedPassword: 'one-time-secret',
    })
    useAppStore.setState({
      isAuthenticated: true,
      isBootstrapped: true,
      userRole: 'SUPER_ADMIN',
      currentUser: {
        id: 1,
        name: 'Operator',
        email: 'operator@nexus.test',
        role: 'SUPER_ADMIN',
      },
    })
  })

  it('redirects the operator to the isolated console from tenant routes', async () => {
    renderApp('/board')

    expect(await screen.findByRole('heading', { name: 'Platform administration' })).toBeTruthy()
    expect(await screen.findByText('North Company')).toBeTruthy()
  })

  it('filters cross-company accounts and confirms suspension', async () => {
    renderApp()
    await screen.findByText('North Company')

    fireEvent.click(screen.getByRole('button', { name: 'Accounts' }))
    expect(await screen.findByText('Ada Lovelace')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Company'), { target: { value: '4' } })
    await waitFor(() => expect(apiListAdminAccounts).toHaveBeenLastCalledWith(4))

    fireEvent.click(screen.getByRole('button', { name: 'Companies' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Suspend' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(apiSuspendCompany).toHaveBeenCalledWith(4))
  })

  it('creates a company and shows its initial password only until dismissed', async () => {
    renderApp()
    fireEvent.click(screen.getByRole('button', { name: 'New company' }))

    fireEvent.change(screen.getByLabelText('Company name'), { target: { value: 'South Company' } })
    fireEvent.change(screen.getByLabelText('First admin name'), { target: { value: 'Grace Hopper' } })
    fireEvent.change(screen.getByLabelText('First admin email'), { target: { value: 'grace@south.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create company' }))

    expect(await screen.findByText('one-time-secret')).toBeTruthy()
    expect(apiCreateCompany).toHaveBeenCalledWith({
      name: 'South Company',
      adminName: 'Grace Hopper',
      adminEmail: 'grace@south.test',
    })
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss initial password' }))
    expect(screen.queryByText('one-time-secret')).toBeNull()
  })

  it('does not expose the operator console to a tenant ADMIN', async () => {
    useAppStore.setState({ userRole: 'ADMIN' })
    renderApp()

    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Platform administration' })).toBeNull())
  })
})