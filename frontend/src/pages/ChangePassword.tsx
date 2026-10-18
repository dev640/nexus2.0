import { useNavigate } from 'react-router-dom'
import { useAppStore } from '../store/useAppStore'
import { ChangePasswordForm } from '../components/auth/ChangePasswordForm'
import { MagneticButton } from '../components/ui/MagneticButton'
import { MarqueeBand } from '../components/ui/MarqueeBand'

/**
 * The full-screen stand-in for the workspace while an account is still on a
 * password an administrator issued.
 *
 * It renders in place of {@code AppShell} rather than as a route inside it: the
 * sidebar, the pages and the chat socket all reach endpoints the server is
 * refusing for this account, so showing any of them would only show a shell
 * full of failures. The server is the enforcement (MustChangePasswordFilter
 * refuses every request it makes anyway) — this screen is what turns that
 * refusal into something a person can act on.
 *
 * Nothing on this screen is a route the person can navigate away from: App.tsx
 * gates on the flag itself, so the screen stays until the password changes or
 * the person signs out.
 */
export function ChangePassword() {
  const navigate = useNavigate()
  const user = useAppStore((s) => s.currentUser)
  const logout = useAppStore((s) => s.logout)

  function handleSignOut() {
    // Both in one turn: the store update unmounts this screen, so the
    // navigation has to be scheduled in the same batch rather than after it.
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="grid min-h-screen grid-cols-1 bg-white md:grid-cols-2">
      <div className="flex flex-col justify-center bg-ink px-6 py-10 text-white sm:px-16 sm:py-12">
        <img
          src="/logo-wordmark.png"
          alt="Nexus"
          className="mb-10 h-8 w-auto self-start brightness-0 invert"
        />
        <h1 className="text-4xl font-semibold leading-[1.05] tracking-tight sm:text-6xl">
          ONE MORE
          <br />
          STEP.
        </h1>
        <p className="mt-6 max-w-md text-base text-white/70">
          Your password was issued by an administrator. Choose your own before you continue —
          they no longer need to know it, and neither does anybody else.
        </p>
        <MarqueeBand
          className="mt-12 border-y border-white/15 py-3 text-white/60"
          items={['SECURE', 'YOURS', 'PRIVATE']}
        />
      </div>
      <div className="flex flex-col justify-center px-6 py-10 sm:px-16 sm:py-12">
        <div className="mx-auto w-full max-w-sm">
          <img src="/logo-mark.png" alt="Nexus" className="mb-2 h-12 w-auto self-start" />
          <h2 className="text-xl font-semibold tracking-tight">Set a new password</h2>
          <p className="mt-2 text-sm text-mute">
            Signed in as{' '}
            <span className="font-medium text-ink">{user?.email ?? 'your account'}</span>.
          </p>
          <div className="mt-6">
            <ChangePasswordForm submitLabel="Set new password" />
          </div>
          <p className="mt-4 text-xs text-mute">
            Your current password is asked for again so that anyone who has only a session
            — not your password — cannot take over your account with it.
          </p>
          <div className="mt-6 border-t border-line pt-4">
            <MagneticButton
              type="button"
              onClick={handleSignOut}
              className="rounded-md border border-line px-4 py-2 text-sm font-medium text-ink hover:bg-paper"
            >
              Sign out instead
            </MagneticButton>
          </div>
        </div>
      </div>
    </div>
  )
}
