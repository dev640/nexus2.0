import { useState } from 'react'
import { Link } from 'react-router-dom'
import { analyticsConfigured, getAnalyticsConsent, setAnalyticsConsent } from '../../lib/analytics'

/**
 * The consent banner for the one optional thing on the site: analytics.
 *
 * It deliberately does not pretend to gate the sign-in tokens, which are
 * strictly necessary and cannot be switched off — asking permission for those
 * would be theatre. If this build has no analytics configured there is nothing
 * optional to accept, so no banner is shown at all rather than a notice that
 * asks for consent to nothing.
 */
export function CookieConsent() {
  // Resolved once at mount: a build-time constant plus the stored decision.
  const [dismissed, setDismissed] = useState(
    () => !analyticsConfigured || getAnalyticsConsent() !== null,
  )

  if (dismissed) return null

  const decide = (value: 'accepted' | 'declined') => {
    setAnalyticsConsent(value)
    setDismissed(true)
  }

  return (
    <div
      role="dialog"
      aria-label="Analytics consent"
      className="fixed inset-x-4 bottom-4 z-[60] mx-auto max-w-2xl rounded-lg border border-line bg-white p-4 shadow-[0_20px_50px_-20px_rgba(17,17,17,0.35)] sm:flex sm:items-center sm:gap-5 sm:p-5"
    >
      <p className="text-sm leading-relaxed text-ink/80">
        We keep only what is needed to sign you in. Analytics, if you allow it, is cookieless and
        counts page views in aggregate — no advertising, no cross-site tracking. Read the{' '}
        <Link to="/privacy" className="underline hover:text-ink">
          privacy policy
        </Link>
        .
      </p>
      <div className="mt-4 flex shrink-0 gap-2 sm:mt-0">
        <button
          type="button"
          onClick={() => decide('accepted')}
          className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-black"
        >
          Allow analytics
        </button>
        <button
          type="button"
          onClick={() => decide('declined')}
          className="rounded-md border border-line px-4 py-2 text-sm font-medium text-ink transition-colors hover:border-ink"
        >
          Decline
        </button>
      </div>
    </div>
  )
}
