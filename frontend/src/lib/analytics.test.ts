import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The consent gate is the part of the analytics setup that can fail silently and
 * the part a provider swap must not be able to break. A script that loads before
 * the visitor accepts is a privacy bug nobody notices, and a tag whose `src` is
 * wrong counts nothing while looking, in the code, exactly like it works.
 *
 * `analytics.ts` reads `import.meta.env` at module scope, so each case stubs the
 * environment and re-imports the module — the same thing a rebuild does.
 */

const SCRIPT = '/_vercel/insights/script.js'
const CONSENT_KEY = 'nexus-analytics-consent'

async function loadAnalytics(env: Record<string, string> = {}) {
  vi.resetModules()
  for (const [key, value] of Object.entries(env)) vi.stubEnv(key, value)
  return import('./analytics')
}

/** The injected tag(s) — the attribute is what makes the injection detectably ours. */
const injected = () => [...document.querySelectorAll<HTMLScriptElement>('script[data-nexus-analytics]')]

describe('consent-gated analytics', () => {
  beforeEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
    localStorage.clear()
    for (const tag of injected()) tag.remove()
  })

  it('ships and loads nothing at all when no provider is configured', async () => {
    // The default build. `''` rather than simply leaving it unset so a
    // developer's local .env cannot make this test mean something else.
    const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: '' })

    expect(analytics.analyticsConfigured).toBe(false)

    // Even an explicit acceptance must not conjure a script from nowhere.
    analytics.setAnalyticsConsent('accepted')

    expect(injected()).toHaveLength(0)
  })

  describe('with a provider configured', () => {
    it('loads nothing at boot before the visitor decides', async () => {
      const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT })

      analytics.initAnalytics()

      expect(analytics.analyticsConfigured).toBe(true)
      expect(injected()).toHaveLength(0)
    })

    it('loads nothing at boot when the visitor declined', async () => {
      localStorage.setItem(CONSENT_KEY, 'declined')
      const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT })

      analytics.initAnalytics()

      expect(injected()).toHaveLength(0)
    })

    it('loads on boot when the visitor already accepted', async () => {
      localStorage.setItem(CONSENT_KEY, 'accepted')
      const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT })

      analytics.initAnalytics()

      expect(injected()).toHaveLength(1)
    })

    it('injects the configured script exactly once, on acceptance', async () => {
      const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT })

      analytics.setAnalyticsConsent('accepted')
      // Both a second decision and an explicit load must not double-inject: two
      // tags would double-count every page view.
      analytics.setAnalyticsConsent('accepted')
      analytics.loadAnalytics()

      expect(injected()).toHaveLength(1)
      const [tag] = injected()
      // Compared with getAttribute: `.src` resolves against the document origin,
      // which would hide a relative/absolute mistake in the configured value.
      expect(tag.getAttribute('src')).toBe(SCRIPT)
      expect(tag.defer).toBe(true)
      expect(localStorage.getItem(CONSENT_KEY)).toBe('accepted')
    })

    it('records a decline and honours it without loading anything', async () => {
      const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT })

      analytics.setAnalyticsConsent('declined')

      expect(localStorage.getItem(CONSENT_KEY)).toBe('declined')
      expect(injected()).toHaveLength(0)
      expect(analytics.getAnalyticsConsent()).toBe('declined')
    })

    it('treats an unrecognised stored value as no decision, not as permission', async () => {
      // Failing open on a corrupted value would count someone who never agreed.
      localStorage.setItem(CONSENT_KEY, 'maybe')
      const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT })

      expect(analytics.getAnalyticsConsent()).toBeNull()

      analytics.initAnalytics()

      expect(injected()).toHaveLength(0)
    })
  })

  describe('provider attributes', () => {
    it('adds no provider-specific attribute for Vercel', async () => {
      const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT })
      analytics.setAnalyticsConsent('accepted')

      const [tag] = injected()
      expect(tag.getAttribute('data-domain')).toBeNull()
      expect(tag.getAttribute('data-website-id')).toBeNull()
      expect(tag.getAttribute('data-site')).toBeNull()
    })

    it('maps each alternative provider onto its documented attribute', async () => {
      // Annotated deliberately: without it TypeScript infers a union of object
      // shapes whose absent keys are `undefined`, which is not assignable to the
      // string-valued record the loader takes.
      const cases: Array<{ env: Record<string, string>; attribute: string; value: string }> = [
        { env: { VITE_ANALYTICS_WEBSITE_ID: 'abc' }, attribute: 'data-website-id', value: 'abc' },
        { env: { VITE_ANALYTICS_DOMAIN: 'nexus.app' }, attribute: 'data-domain', value: 'nexus.app' },
        { env: { VITE_ANALYTICS_SITE_ID: 'XYZ' }, attribute: 'data-site', value: 'XYZ' },
      ]

      for (const { env, attribute, value } of cases) {
        const analytics = await loadAnalytics({ VITE_ANALYTICS_SCRIPT_SRC: SCRIPT, ...env })
        analytics.setAnalyticsConsent('accepted')

        const [tag] = injected()
        expect(tag.getAttribute(attribute), `${attribute} for ${JSON.stringify(env)}`).toBe(value)
        tag.remove()
      }
    })
  })
})
