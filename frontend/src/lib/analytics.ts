/**
 * Consent-gated, cookieless analytics.
 *
 * Deliberately provider-shaped rather than provider-specific: a script tag is the
 * integration path for every cookieless option that fits this app (Vercel Web
 * Analytics, Umami, Plausible, Fathom), so choosing a provider is an environment
 * change rather than a code change — and no vendor SDK is pulled into the bundle.
 *
 * Three things are true by construction:
 *  - nothing loads until the visitor accepts, so the banner is a real gate and
 *    not decoration;
 *  - nothing loads at all when the variables below are unset, which is the
 *    default, so a build with no analytics configured ships no third-party code;
 *  - no cookies are set, which is what keeps this a disclosure rather than a
 *    consent requirement in most jurisdictions.
 *
 * Vite inlines VITE_* at build time, so these are set on the host (Vercel), not
 * at runtime. See OPERATIONS.md for the per-provider values.
 */
const SCRIPT_SRC = import.meta.env.VITE_ANALYTICS_SCRIPT_SRC as string | undefined
const DOMAIN = import.meta.env.VITE_ANALYTICS_DOMAIN as string | undefined
const WEBSITE_ID = import.meta.env.VITE_ANALYTICS_WEBSITE_ID as string | undefined
const SITE_ID = import.meta.env.VITE_ANALYTICS_SITE_ID as string | undefined

/** True when this build has somewhere to send events. */
export const analyticsConfigured = Boolean(SCRIPT_SRC)

export type ConsentValue = 'accepted' | 'declined'

const CONSENT_KEY = 'nexus-analytics-consent'

/**
 * The stored decision, or null when the visitor has not been asked yet.
 * Anything unrecognised is treated as unasked, so a corrupted value cannot be
 * mistaken for permission.
 */
export function getAnalyticsConsent(): ConsentValue | null {
  try {
    const stored = localStorage.getItem(CONSENT_KEY)
    return stored === 'accepted' || stored === 'declined' ? stored : null
  } catch {
    // Storage can be unavailable (private mode, blocked cookies). Failing closed
    // means we ask again rather than assuming consent.
    return null
  }
}

export function setAnalyticsConsent(value: ConsentValue) {
  try {
    localStorage.setItem(CONSENT_KEY, value)
  } catch {
    // Nothing to do: the choice still applies to this page view.
  }
  if (value === 'accepted') loadAnalytics()
}

/**
 * Injects the provider's script, once. SPA route changes are counted by the
 * providers themselves (each of them observes the History API), so there is no
 * per-navigation call to keep in sync with the router.
 */
export function loadAnalytics() {
  if (!SCRIPT_SRC) return
  if (document.querySelector('script[data-nexus-analytics]')) return

  const script = document.createElement('script')
  script.src = SCRIPT_SRC
  script.defer = true
  script.dataset.nexusAnalytics = 'true'
  if (DOMAIN) script.dataset.domain = DOMAIN // Plausible
  if (WEBSITE_ID) script.dataset.websiteId = WEBSITE_ID // Umami
  if (SITE_ID) script.dataset.site = SITE_ID // Fathom
  script.dataset.spa = 'auto' // Fathom: count client-side navigation
  document.head.appendChild(script)
}

/** Called once on boot: honours a decision already made. */
export function initAnalytics() {
  if (getAnalyticsConsent() === 'accepted') loadAnalytics()
}
