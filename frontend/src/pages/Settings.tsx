import { useState } from 'react'
import { useAppStore, type NotificationCategory } from '../store/useAppStore'
import { apiDeleteAvatar, apiErrorMessage, apiUploadAvatar, type ApiUserRole } from '../lib/api'
import { forgetAvatar, invalidateAvatar } from '../hooks/useAvatar'
import { UserAvatar } from '../components/user/UserAvatar'

const assignableRoles: ApiUserRole[] = ['ADMIN', 'MANAGER', 'MEMBER', 'DEVELOPER', 'VIEWER']

const categories: { key: NotificationCategory; label: string }[] = [
  { key: 'MENTIONS', label: 'Mentions' },
  { key: 'TASKS', label: 'Tasks' },
  { key: 'PROJECTS', label: 'Projects' },
  { key: 'AI', label: 'AI' },
  { key: 'SYSTEM', label: 'System' },
]

function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
}

export function Settings() {
  const settings = useAppStore((s) => s.settings)
  const members = useAppStore((s) => s.members)
  const updateSettings = useAppStore((s) => s.updateSettings)
  const toggleMutedCategory = useAppStore((s) => s.toggleMutedCategory)
  const resetWorkspace = useAppStore((s) => s.resetWorkspace)
  const saveProfile = useAppStore((s) => s.saveProfile)
  const changeUserRole = useAppStore((s) => s.changeUserRole)
  const userRole = useAppStore((s) => s.userRole)
  const currentUser = useAppStore((s) => s.currentUser)
  const isAdmin = userRole === 'ADMIN'

  const [displayName, setDisplayName] = useState(settings.displayName)
  const [saved, setSaved] = useState(false)
  const [profileError, setProfileError] = useState('')
  const [confirmingReset, setConfirmingReset] = useState(false)
  const [avatarError, setAvatarError] = useState('')
  const [avatarBusy, setAvatarBusy] = useState(false)
  const [hasAvatar, setHasAvatar] = useState(false)

  async function handleAvatarPick(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    // Reset first: without it, re-picking the same file after a failure fires
    // no change event and the retry silently does nothing.
    e.target.value = ''
    if (!file) return
    setAvatarError('')
    setAvatarBusy(true)
    try {
      await apiUploadAvatar(file)
      invalidateAvatar(currentUser?.id)
      setHasAvatar(true)
    } catch (err) {
      setAvatarError(apiErrorMessage(err, 'Could not upload that image'))
    } finally {
      setAvatarBusy(false)
    }
  }

  async function handleAvatarRemove() {
    setAvatarError('')
    setAvatarBusy(true)
    try {
      await apiDeleteAvatar()
      // forgetAvatar, not invalidateAvatar: the picture is gone because this
      // call removed it, so there is nothing to re-read — and re-reading here
      // could hand back the avatar the delete had just cleared.
      forgetAvatar(currentUser?.id)
      setHasAvatar(false)
    } catch (err) {
      setAvatarError(apiErrorMessage(err, 'Could not remove your avatar'))
    } finally {
      setAvatarBusy(false)
    }
  }

  async function handleSaveProfile(e: React.FormEvent) {
    e.preventDefault()
    setProfileError('')
    const result = await saveProfile(displayName.trim() || settings.displayName)
    if (!result.ok) {
      setProfileError(result.error ?? 'Failed to save')
      return
    }
    setSaved(true)
    setTimeout(() => setSaved(false), 2000)
  }

  function handleReset() {
    resetWorkspace()
    setConfirmingReset(false)
  }

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 text-xs font-medium uppercase tracking-widest text-mute">
        Preferences
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">
        Settings
      </h1>

      <div className="mt-10 flex max-w-2xl flex-col gap-8">
        <section className="border border-line bg-white p-6">
          <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
            Profile
          </h2>
          <form onSubmit={handleSaveProfile} className="flex flex-col gap-4">
            <div className="flex items-center gap-4">
              <UserAvatar
                memberId={`u-${currentUser?.id ?? ''}`}
                name={currentUser?.name ?? settings.displayName}
                initials={initialsOf(currentUser?.name ?? settings.displayName)}
                size="md"
              />
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium uppercase tracking-wide text-mute">
                  Avatar
                </span>
                <div className="flex flex-wrap items-center gap-3">
                  <label
                    className={`cursor-pointer rounded-md border border-line px-3 py-1.5 text-sm hover:bg-white ${
                      avatarBusy ? 'pointer-events-none opacity-50' : ''
                    }`}
                  >
                    {avatarBusy ? 'Uploading…' : 'Upload image'}
                    <input
                      type="file"
                      accept="image/png,image/jpeg"
                      className="sr-only"
                      onChange={(e) => void handleAvatarPick(e)}
                    />
                  </label>
                  {hasAvatar && (
                    <button
                      type="button"
                      onClick={() => void handleAvatarRemove()}
                      disabled={avatarBusy}
                      className="text-xs text-mute hover:text-danger"
                    >
                      Remove
                    </button>
                  )}
                </div>
                <span className="text-xs text-mute">PNG or JPEG, up to 256 KB.</span>
                {avatarError && <span className="text-xs text-red-600">{avatarError}</span>}
              </div>
            </div>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
                  Display Name
                </label>
                <input
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
                  Role
                </label>
                <div className="rounded-md border border-line bg-paper px-3 py-2 text-sm text-mute">
                  {settings.role || userRole}
                  <span className="ml-2 text-[10px] uppercase tracking-wide">
                    (managed by admins)
                  </span>
                </div>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <button
                type="submit"
                className="self-start rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
              >
                Save Changes
              </button>
              {saved && <span className="text-xs font-medium text-success">Saved</span>}
              {profileError && <span className="text-xs text-red-600">{profileError}</span>}
            </div>
          </form>
        </section>

        <section className="border border-line bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
            Members
          </h2>
          <p className="mb-4 text-sm text-mute">
            Everyone in the workspace roster, available as assignees and team members.
            There is no public signup — an admin creates each account from the Admin page.
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
                  </span>
                </div>
                {isAdmin ? (
                  <select
                    value={m.role}
                    onChange={(e) => void changeUserRole(m.id, e.target.value as ApiUserRole)}
                    className="rounded-md border border-line px-2 py-1 text-xs outline-none focus:border-ink"
                    aria-label={`Role for ${m.name}`}
                  >
                    {assignableRoles.map((r) => (
                      <option key={r} value={r}>
                        {r}
                      </option>
                    ))}
                  </select>
                ) : (
                  <span className="text-xs text-mute">{m.role}</span>
                )}
              </div>
            ))}
          </div>
        </section>

        <section className="border border-line bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
            Task Defaults
          </h2>
          <p className="mb-4 text-sm text-mute">
            Who new tasks are assigned to by default.
          </p>
          <select
            value={settings.defaultAssigneeId}
            onChange={(e) => updateSettings({ defaultAssigneeId: e.target.value })}
            className="w-full max-w-xs rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
          >
            {members.map((m) => (
              <option key={m.id} value={m.id}>
                {m.name} — {m.role}
              </option>
            ))}
          </select>
        </section>

        <section className="border border-line bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
            Notifications
          </h2>
          <p className="mb-4 text-sm text-mute">
            Choose which categories show up in your Inbox.
          </p>
          <div className="flex flex-col gap-3">
            {categories.map((c) => (
              <label key={c.key} className="flex items-center justify-between text-sm">
                <span>{c.label}</span>
                <input
                  type="checkbox"
                  checked={!settings.mutedCategories.includes(c.key)}
                  onChange={() => toggleMutedCategory(c.key)}
                  className="h-4 w-4 accent-ink"
                />
              </label>
            ))}
          </div>
        </section>

        <section className="border border-danger/30 bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-danger">
            Danger Zone
          </h2>
          <p className="mb-4 text-sm text-mute">
            Reloads every project, task, sprint and member from the server, discarding
            local-only changes. Server data is never deleted.
          </p>
          {confirmingReset ? (
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-sm font-medium text-ink">Reset everything?</span>
              <button
                onClick={handleReset}
                className="rounded-md bg-danger px-4 py-2 text-sm font-medium text-white hover:bg-danger/90"
              >
                Yes, reset everything
              </button>
              <button
                onClick={() => setConfirmingReset(false)}
                className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-paper"
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              onClick={() => setConfirmingReset(true)}
              className="rounded-md border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger/10"
            >
              Reset Workspace Data
            </button>
          )}
        </section>
      </div>
    </div>
  )
}
