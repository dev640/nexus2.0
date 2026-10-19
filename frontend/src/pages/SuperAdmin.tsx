import { useEffect, useState } from 'react'
import { Building2, LogOut, RefreshCw, ShieldCheck, Users } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import {
  apiCreateCompany,
  apiErrorMessage,
  apiListAdminAccounts,
  apiListAdminCompanies,
  apiResumeCompany,
  apiSuspendCompany,
  type ApiAdminAccount,
  type ApiAdminCompany,
  type ApiCreatedCompany,
} from '../lib/api'
import { useAppStore } from '../store/useAppStore'

type View = 'companies' | 'accounts' | 'create'

export function SuperAdmin() {
  const navigate = useNavigate()
  const logout = useAppStore((state) => state.logout)
  const [view, setView] = useState<View>('companies')
  const [companies, setCompanies] = useState<ApiAdminCompany[]>([])
  const [accounts, setAccounts] = useState<ApiAdminAccount[]>([])
  const [companyFilter, setCompanyFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [confirmAction, setConfirmAction] = useState<{
    id: number
    action: 'suspend' | 'resume'
  } | null>(null)
  const [companyName, setCompanyName] = useState('')
  const [adminName, setAdminName] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [created, setCreated] = useState<ApiCreatedCompany | null>(null)

  async function refreshCompanies() {
    setLoading(true)
    setError('')
    try {
      setCompanies(await apiListAdminCompanies())
    } catch (cause) {
      setError(apiErrorMessage(cause, 'Could not load companies'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void refreshCompanies()
  }, [])

  useEffect(() => {
    if (view !== 'accounts') return
    let active = true
    setLoading(true)
    setError('')
    apiListAdminAccounts(companyFilter ? Number(companyFilter) : undefined)
      .then((result) => {
        if (active) setAccounts(result)
      })
      .catch((cause: unknown) => {
        if (active) setError(apiErrorMessage(cause, 'Could not load accounts'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [view, companyFilter])

  async function handleCompanyAction(id: number, action: 'suspend' | 'resume') {
    setWorking(true)
    setError('')
    try {
      if (action === 'suspend') await apiSuspendCompany(id)
      else await apiResumeCompany(id)
      setConfirmAction(null)
      await refreshCompanies()
    } catch (cause) {
      setError(apiErrorMessage(cause, `Could not ${action} company`))
    } finally {
      setWorking(false)
    }
  }

  async function handleCreateCompany(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setWorking(true)
    setError('')
    setCreated(null)
    try {
      const result = await apiCreateCompany({
        name: companyName.trim(),
        adminName: adminName.trim(),
        adminEmail: adminEmail.trim(),
      })
      setCreated(result)
      setCompanyName('')
      setAdminName('')
      setAdminEmail('')
      await refreshCompanies()
    } catch (cause) {
      setError(apiErrorMessage(cause, 'Could not create the company'))
    } finally {
      setWorking(false)
    }
  }

  function handleLogout() {
    logout()
    navigate('/', { replace: true })
  }

  return (
    <main className="min-h-screen bg-paper text-ink">
      <header className="flex items-center justify-between border-b border-line bg-white px-5 py-4 sm:px-8">
        <div className="flex items-center gap-3">
          <ShieldCheck size={20} strokeWidth={1.6} />
          <div>
            <p className="text-xs font-medium uppercase text-mute">Nexus</p>
            <h1 className="text-lg font-semibold">Platform administration</h1>
          </div>
        </div>
        <button
          type="button"
          onClick={handleLogout}
          className="flex items-center gap-2 rounded-md border border-line px-3 py-2 text-sm hover:bg-paper"
        >
          <LogOut size={16} />
          Sign out
        </button>
      </header>

      <div className="mx-auto max-w-6xl px-5 py-7 sm:px-8 sm:py-10">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs font-medium uppercase text-mute">Global access</p>
            <h2 className="mt-1 text-2xl font-semibold">Companies and accounts</h2>
          </div>
          <button
            type="button"
            onClick={() => void refreshCompanies()}
            disabled={loading || working}
            aria-label="Refresh companies"
            title="Refresh companies"
            className="rounded-md border border-line p-2 hover:bg-white disabled:opacity-50"
          >
            <RefreshCw size={17} />
          </button>
        </div>

        <nav aria-label="Platform administration views" className="mt-6 flex border-b border-line">
          {([
            ['companies', 'Companies', Building2],
            ['accounts', 'Accounts', Users],
            ['create', 'New company', Building2],
          ] as const).map(([key, label, Icon]) => (
            <button
              key={key}
              type="button"
              aria-current={view === key ? 'page' : undefined}
              onClick={() => setView(key)}
              className={`flex items-center gap-2 border-b-2 px-4 py-3 text-sm ${
                view === key
                  ? 'border-ink font-medium text-ink'
                  : 'border-transparent text-mute hover:text-ink'
              }`}
            >
              <Icon size={16} />
              {label}
            </button>
          ))}
        </nav>

        {error && (
          <p role="alert" className="mt-5 border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            {error}
          </p>
        )}

        {view === 'companies' && (
          <section aria-label="Companies" className="mt-5">
            {loading ? (
              <p role="status" className="py-8 text-sm text-mute">Loading companies...</p>
            ) : companies.length === 0 ? (
              <p className="py-8 text-sm text-mute">No companies found.</p>
            ) : (
              <div className="divide-y divide-line border-y border-line bg-white">
                {companies.map((company) => (
                  <article key={company.id} className="flex flex-wrap items-center justify-between gap-4 px-4 py-4 sm:px-5">
                    <div className="min-w-0">
                      <h3 className="truncate font-medium">{company.name}</h3>
                      <p className="mt-1 text-sm text-mute">
                        {company.employeeCount} account{company.employeeCount === 1 ? '' : 's'}
                        <span className="mx-2">·</span>
                        Created {new Date(company.createdAt).toLocaleDateString()}
                      </p>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className={`text-xs font-semibold ${company.status === 'ACTIVE' ? 'text-emerald-700' : 'text-danger'}`}>
                        {company.status}
                      </span>
                      {confirmAction?.id === company.id ? (
                        <div className="flex items-center gap-2 text-sm">
                          <span>Confirm {confirmAction.action}?</span>
                          <button
                            type="button"
                            onClick={() => setConfirmAction(null)}
                            disabled={working}
                            className="rounded-md border border-line px-2.5 py-1.5 hover:bg-paper"
                          >Cancel</button>
                          <button
                            type="button"
                            onClick={() => void handleCompanyAction(company.id, confirmAction.action)}
                            disabled={working}
                            className="rounded-md bg-ink px-2.5 py-1.5 text-white hover:bg-black disabled:opacity-50"
                          >{working ? 'Working...' : 'Confirm'}</button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setConfirmAction({
                            id: company.id,
                            action: company.status === 'ACTIVE' ? 'suspend' : 'resume',
                          })}
                          className="rounded-md border border-line px-3 py-1.5 text-sm hover:bg-paper"
                        >
                          {company.status === 'ACTIVE' ? 'Suspend' : 'Resume'}
                        </button>
                      )}
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>
        )}

        {view === 'accounts' && (
          <section aria-label="Accounts" className="mt-5">
            <label className="mb-4 flex max-w-sm items-center gap-3 text-sm">
              <span className="shrink-0 text-mute">Company</span>
              <select
                value={companyFilter}
                onChange={(event) => setCompanyFilter(event.target.value)}
                className="min-w-0 flex-1 rounded-md border border-line bg-white px-3 py-2 outline-none focus:border-ink"
              >
                <option value="">All companies</option>
                {companies.map((company) => (
                  <option key={company.id} value={company.id}>{company.name}</option>
                ))}
              </select>
            </label>
            {loading ? (
              <p role="status" className="py-8 text-sm text-mute">Loading accounts...</p>
            ) : (
              <div className="overflow-x-auto border-y border-line bg-white">
                <table className="w-full min-w-[680px] border-collapse text-left text-sm">
                  <thead>
                    <tr className="border-b border-line text-xs uppercase text-mute">
                      <th className="px-4 py-3 font-medium">Account</th>
                      <th className="px-4 py-3 font-medium">Company</th>
                      <th className="px-4 py-3 font-medium">Role</th>
                      <th className="px-4 py-3 font-medium">Company status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {accounts.map((account) => (
                      <tr key={account.id}>
                        <td className="px-4 py-3">
                          <p className="font-medium">{account.name}</p>
                          <p className="text-xs text-mute">{account.email}</p>
                        </td>
                        <td className="px-4 py-3">{account.companyName ?? 'No company'}</td>
                        <td className="px-4 py-3">{account.role}</td>
                        <td className="px-4 py-3">{account.companyStatus ?? 'N/A'}</td>
                      </tr>
                    ))}
                    {accounts.length === 0 && (
                      <tr><td colSpan={4} className="px-4 py-8 text-center text-mute">No accounts found.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        )}

        {view === 'create' && (
          <section aria-label="Create company" className="mt-5 max-w-2xl">
            <form onSubmit={(event) => void handleCreateCompany(event)} className="grid gap-4 border-y border-line bg-white px-4 py-5 sm:grid-cols-2 sm:px-5">
              <label className="grid gap-1 text-sm">
                Company name
                <input required minLength={2} maxLength={200} value={companyName} onChange={(event) => setCompanyName(event.target.value)} className="rounded-md border border-line px-3 py-2 outline-none focus:border-ink" />
              </label>
              <span className="hidden sm:block" />
              <label className="grid gap-1 text-sm">
                First admin name
                <input required minLength={2} maxLength={100} value={adminName} onChange={(event) => setAdminName(event.target.value)} className="rounded-md border border-line px-3 py-2 outline-none focus:border-ink" />
              </label>
              <label className="grid gap-1 text-sm">
                First admin email
                <input required type="email" value={adminEmail} onChange={(event) => setAdminEmail(event.target.value)} className="rounded-md border border-line px-3 py-2 outline-none focus:border-ink" />
              </label>
              <button type="submit" disabled={working} className="flex items-center justify-center gap-2 justify-self-start rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-50 sm:col-span-2">
                <Building2 size={16} />
                {working ? 'Creating...' : 'Create company'}
              </button>
            </form>
            {created && (
              <div role="status" className="mt-5 border border-emerald-300 bg-emerald-50 p-4">
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <h3 className="font-medium">{created.companyName} created</h3>
                    <p className="mt-1 text-sm text-ink/75">Initial password for {created.adminEmail}</p>
                    <p className="mt-2 select-all font-mono text-sm">{created.generatedPassword}</p>
                    <p className="mt-2 text-xs text-mute">This password is shown once. The new admin must change it at first sign-in.</p>
                  </div>
                  <button type="button" onClick={() => setCreated(null)} aria-label="Dismiss initial password" className="rounded-md border border-line px-2.5 py-1.5 text-sm hover:bg-white">Dismiss</button>
                </div>
              </div>
            )}
          </section>
        )}
      </div>
    </main>
  )
}