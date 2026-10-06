import { useCallback, useEffect, useState } from 'react'
import { useAppStore } from '../store/useAppStore'
import { UserAvatar } from '../components/user/UserAvatar'
import {
  apiCreateUser,
  apiDismissPasswordReset,
  apiErrorMessage,
  apiListPasswordResets,
  apiResolvePasswordReset,
  type ApiPasswordResetRequest,
  type ApiUserRole,
} from '../lib/api'

const assignableRoles: ApiUserRole[] = ['ADMIN', 'MEMBER', 'DEVELOPER', 'VIEWER']

/**
 * Admin-only console: member roles, account creation and the password-reset
 * queue. There is no public signup, and users cannot set their own password —
 * both routes through here.
 *
 * Role changes and provisioning hit ADMIN-guarded endpoints, so the API rejects
 * them for anyone who reaches this page without the role; App.tsx also
 * redirects non-admins away.
 */
export function Admin() {
  const members = useAppStore((s) => s.members)
  const currentUser = useAppStore((s) => s.currentUser)
  const changeUserRole = useAppStore((s) => s.changeUserRole)
  const removeMember = useAppStore((s) => s.removeMember)
  const loadWorkspace = useAppStore((s) => s.loadWorkspace)

  const [error, setError] = useState('')
  const [pendingId, setPendingId] = useState<string | null>(null)
  const [confirmRemoveId, setConfirmRemoveId] = useState<string | null>(null)

  // Create user
  const [newName, setNewName] = useState('')
  const [newEmail, setNewEmail] = useState('')
  const [newRole, setNewRole] = useState<ApiUserRole>('MEMBER')
  const [creating, setCreating] = useState(false)
  const [createdPassword, setCreatedPassword] = useState<{ email: string; password: string } | null>(null)

  // Password resets
  const [requests, setRequests] = useState<ApiPasswordResetRequest[]>([])
  const [requestsError, setRequestsError] = useState('')
  const [issued, setIssued] = useState<{ email: string; password: string } | null>(null)
  const [busyRequest, setBusyRequest] = useState<number | null>(null)

  const refreshRequests = useCallback(async () => {
    try {
      setRequests(await apiListPasswordResets(true))
      setRequestsError('')
    } catch (err) {
      setRequestsError(apiErrorMessage(err, 'Could not load password reset requests'))
    }
  }, [])

  useEffect(() => {
    void refreshRequests()
  }, [refreshRequests])

  async function handleRoleChange(memberId: string, role: ApiUserRole) {
    setError('')
    setPendingId(memberId)
    const result = await changeUserRole(memberId, role)
    setPendingId(null)
    if (!result.ok) setError(result.error ?? 'Failed to change role')
  }

  /**
   * Removing an account is permanent and takes their work with them: tasks
   * become unassigned rather than deleted, but the person is gone for good.
   */
  async function handleRemove(memberId: string) {
    setError('')
    setPendingId(memberId)
    const result = await removeMember(memberId)
    setPendingId(null)
    setConfirmRemoveId(null)
    if (!result.ok) setError(result.error ?? 'Failed to remove member')
  }

  async function handleCreateUser(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setCreatedPassword(null)
    setCreating(true)
    try {
      const created = await apiCreateUser({
        name: newName.trim(),
        email: newEmail.trim(),
        role: newRole,
      })
      if (created.password) {
        setCreatedPassword({ email: created.email, password: created.password })
      }
      setNewName('')
      setNewEmail('')
      setNewRole('MEMBER')
      // Pull the new member into the roster.
      void loadWorkspace()
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not create the user'))
    } finally {
      setCreating(false)
    }
  }

  async function handleResolve(id: number) {
    setRequestsError('')
    setBusyRequest(id)
    try {
      const result = await apiResolvePasswordReset(id)
      setIssued({ email: result.email, password: result.password ?? '' })
      await refreshRequests()
    } catch (err) {
      setRequestsError(apiErrorMessage(err, 'Could not issue a new password'))
    } finally {
      setBusyRequest(null)
    }
  }

  async function handleDismiss(id: number) {
    setRequestsError('')
    setBusyRequest(id)
    try {
      await apiDismissPasswordReset(id)
      await refreshRequests()
    } catch (err) {
      setRequestsError(apiErrorMessage(err, 'Could not dismiss the request'))
    } finally {
      setBusyRequest(null)
    }
  }

  const adminCount = members.filter((m) => m.role === 'ADMIN').length
  // Member ids use the store's `u-<id>` form, while currentUser carries the
  // raw numeric id the API returns.
  const selfMemberId = currentUser?.id != null ? `u-${currentUser.id}` : null

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 text-xs font-medium uppercase tracking-widest text-mute">
        Administration
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">
        Admin
      </h1>
      <p className="mt-3 max-w-2xl text-base text-mute">
        Create accounts, manage what people can do, and issue passwords. There is no
        public signup — everyone enters through here.
      </p>

      <div className="mt-10 flex max-w-2xl flex-col gap-8">
        {error && (
          <p className="border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            {error}
          </p>
        )}

        {/* ---------------------------------------------- create an account */}
        <section className="border border-line bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
            Create an account
          </h2>
          <p className="mb-4 text-sm text-mute">
            A strong password is generated for you and shown once — hand it over to
            the new member.
          </p>
          <form onSubmit={handleCreateUser} className="flex flex-col gap-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
                  Name
                </label>
                <input
                  required
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
                  placeholder="Ada Lovelace"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
                  Email
                </label>
                <input
                  required
                  type="email"
                  value={newEmail}
                  onChange={(e) => setNewEmail(e.target.value)}
                  className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
                  placeholder="ada@nexus.com"
                />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
                Role
              </label>
              <select
                value={newRole}
                onChange={(e) => setNewRole(e.target.value as ApiUserRole)}
                className="w-full max-w-xs rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
              >
                {assignableRoles.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="submit"
              disabled={creating}
              className="self-start rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-60"
            >
              {creating ? 'Creating…' : 'Create account'}
            </button>
          </form>

          {createdPassword && (
            <div className="mt-4 border border-accent/40 bg-accent/10 p-4">
              <p className="text-sm font-medium text-ink">
                Password for {createdPassword.email}
              </p>
              <p className="mt-1 font-mono text-sm text-ink/80">{createdPassword.password}</p>
              <p className="mt-2 text-xs text-mute">
                Shown once and never stored. Copy it now and hand it over.
              </p>
            </div>
          )}
        </section>

        {/* -------------------------------------------- password reset queue */}
        <section className="border border-line bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
            Password requests
          </h2>
          <p className="mb-4 text-sm text-mute">
            Members who cannot sign in ask for a new password here. Issuing one replaces
            their current password and signs out their existing sessions.
          </p>

          {requestsError && <p className="mb-3 text-xs text-red-600">{requestsError}</p>}

          {requests.length === 0 ? (
            <p className="text-sm text-mute">No open requests.</p>
          ) : (
            <div className="flex flex-col gap-3">
              {requests.map((r) => (
                <div
                  key={r.id}
                  className="flex flex-wrap items-center justify-between gap-3 border-b border-line pb-3 text-sm last:border-0"
                >
                  <div>
                    <span className="font-medium">{r.userName}</span>
                    <span className="ml-2 text-mute">{r.userEmail}</span>
                    {r.note && <p className="mt-1 text-xs text-mute">“{r.note}”</p>}
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => void handleResolve(r.id)}
                      disabled={busyRequest === r.id}
                      className="rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-white hover:bg-black disabled:opacity-60"
                    >
                      {busyRequest === r.id ? 'Working…' : 'Generate new password'}
                    </button>
                    <button
                      type="button"
                      onClick={() => void handleDismiss(r.id)}
                      disabled={busyRequest === r.id}
                      className="rounded-md border border-line px-3 py-1.5 text-xs hover:bg-paper disabled:opacity-60"
                    >
                      Dismiss
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {issued && (
            <div className="mt-4 border border-accent/40 bg-accent/10 p-4">
              <p className="text-sm font-medium text-ink">New password for {issued.email}</p>
              <p className="mt-1 font-mono text-sm text-ink/80">{issued.password}</p>
              <p className="mt-2 text-xs text-mute">
                Shown once and never stored. Hand it over, then close this panel.
              </p>
            </div>
          )}
        </section>

        {/* --------------------------------------------------------- roles */}
        <section className="border border-line bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
            Access
          </h2>
          <p className="mb-4 text-sm text-mute">
            {members.length} member{members.length === 1 ? '' : 's'} · {adminCount} admin
            {adminCount === 1 ? '' : 's'}
            {currentUser ? ` · signed in as ${currentUser.name}` : ''}
          </p>
          <div className="flex flex-col gap-2">
            {members.map((m) => (
              <div key={m.id} className="flex items-center justify-between gap-3 text-sm">
                <div className="flex items-center gap-2">
                  <UserAvatar memberId={m.id} name={m.name} initials={m.initials} />
                  <span>
                    {m.name}
                    {m.employeeCode && (
                      <span className="ml-2 font-mono text-xs text-mute">{m.employeeCode}</span>
                    )}
                    {selfMemberId === m.id && (
                      <span className="ml-2 text-xs text-mute">(you)</span>
                    )}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  {confirmRemoveId === m.id ? (
                    <>
                      <span className="text-xs text-danger">
                        Remove {m.name} permanently?
                      </span>
                      <button
                        onClick={() => setConfirmRemoveId(null)}
                        disabled={pendingId === m.id}
                        className="rounded-md border border-line px-2 py-1 text-xs hover:bg-paper"
                      >
                        Cancel
                      </button>
                      <button
                        onClick={() => void handleRemove(m.id)}
                        disabled={pendingId === m.id}
                        className="rounded-md bg-danger px-2 py-1 text-xs font-medium text-white hover:opacity-90"
                      >
                        {pendingId === m.id ? 'Removing…' : 'Remove'}
                      </button>
                    </>
                  ) : (
                    <>
                      {/* Never offer to delete yourself: the server refuses, and
                          being signed into an account that no longer exists has
                          no way back. */}
                      {selfMemberId !== m.id && (
                        <button
                          onClick={() => setConfirmRemoveId(m.id)}
                          disabled={pendingId === m.id}
                          className="rounded-md border border-line px-2 py-1 text-xs text-danger hover:bg-paper disabled:opacity-50"
                        >
                          Remove
                        </button>
                      )}
                      <select
                        value={m.role}
                        disabled={pendingId === m.id}
                        onChange={(e) => void handleRoleChange(m.id, e.target.value as ApiUserRole)}
                        className="rounded-md border border-line px-2 py-1 text-xs outline-none focus:border-ink disabled:opacity-50"
                        aria-label={`Role for ${m.name}`}
                      >
                        {assignableRoles.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
          {adminCount === 1 && (
            <p className="mt-4 text-xs text-mute">
              Only one admin remains. Removing it would leave the workspace without
              anyone able to create accounts or issue passwords.
            </p>
          )}
        </section>
      </div>
    </div>
  )
}