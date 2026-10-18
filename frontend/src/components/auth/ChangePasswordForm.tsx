import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAppStore } from '../../store/useAppStore'
import { supabaseEnabled } from '../../lib/supabase'
import { MagneticButton } from '../ui/MagneticButton'

/** Shown on the sign-in screen after a change ended a Supabase session. */
export const PASSWORD_CHANGED_NOTICE =
  'Your password was updated. Sign in with your new password.'

/**
 * Current password plus a new one, confirmed.
 *
 * Shared by the forced-change screen and by Settings, so a person changing their
 * password voluntarily meets the same rules, the same wording and the same
 * server as one who was made to. The rules mirror the server's on purpose —
 * matching them here is what makes the screen's messages true rather than
 * optimistic; the server still decides.
 */
interface ChangePasswordFormProps {
  /** Runs once the server has accepted the change and the session is refreshed. */
  onDone?: () => void
  submitLabel?: string
}

const MIN_LENGTH = 8

export function ChangePasswordForm({
  onDone,
  submitLabel = 'Change password',
}: ChangePasswordFormProps) {
  const changePassword = useAppStore((s) => s.changePassword)
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    if (!current || !next || !confirm) {
      setError('Fill in all three fields.')
      return
    }
    if (next.length < MIN_LENGTH) {
      setError(`The new password must be at least ${MIN_LENGTH} characters.`)
      return
    }
    if (next !== confirm) {
      setError('The two passwords do not match.')
      return
    }
    if (next === current) {
      setError('Choose a password you have not used here before.')
      return
    }

    setBusy(true)
    const result = await changePassword(current, next)
    setBusy(false)
    if (!result.ok) {
      // The server's message is the useful one here: it is the only party that
      // knows whether the current password was right, or could be checked at all.
      setError(result.error ?? 'Could not change your password. Please try again.')
      return
    }
    // Under Supabase the change ended this session, so the person has to sign in
    // again — and this is the moment to say why they are being sent back to the
    // sign-in screen. Under built-in auth the session stands and the caller's
    // own after-action (if any) is all that is left to run.
    if (supabaseEnabled) {
      navigate('/login', { replace: true, state: { notice: PASSWORD_CHANGED_NOTICE } })
      return
    }
    onDone?.()
  }

  return (
    <form onSubmit={handleSubmit} className="flex w-full flex-col gap-4">
      <div>
        <label
          htmlFor="change-current"
          className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute"
        >
          Current password
        </label>
        <input
          id="change-current"
          name="current-password"
          type="password"
          required
          autoFocus
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? 'change-password-error' : undefined}
          className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
        />
      </div>
      <div>
        <label
          htmlFor="change-new"
          className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute"
        >
          New password
        </label>
        <input
          id="change-new"
          name="new-password"
          type="password"
          required
          autoComplete="new-password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? 'change-password-error' : undefined}
          className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
        />
        <p className="mt-1 text-xs text-mute">At least {MIN_LENGTH} characters.</p>
      </div>
      <div>
        <label
          htmlFor="change-confirm"
          className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute"
        >
          Confirm new password
        </label>
        <input
          id="change-confirm"
          name="confirm-password"
          type="password"
          required
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? 'change-password-error' : undefined}
          className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
        />
      </div>
      {error && (
        <p id="change-password-error" role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
      <MagneticButton
        type="submit"
        disabled={busy}
        className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-60"
      >
        {busy ? 'Please wait…' : submitLabel}
      </MagneticButton>
    </form>
  )
}
