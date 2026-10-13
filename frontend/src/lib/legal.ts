/**
 * Legal and operator details, in one place.
 *
 * These are the only values a launch needs to change, and they are deliberately
 * not scattered through the page copy: a policy that names the wrong entity, or
 * an address nobody monitors, is worse than one that is obviously a draft.
 *
 * TODO before launch: replace OPERATOR and CONTACT_EMAIL with the real operating
 * entity and a monitored inbox, and set GOVERNING_LAW to the jurisdiction you
 * actually operate from. The pages are written to read correctly once those are
 * filled in; nothing else needs editing.
 */
export const OPERATOR = 'Nexus'
/** Shown on the Help page too, so support has one address rather than two. */
export const CONTACT_EMAIL = 'support@nexus.dev'
export const EFFECTIVE_DATE = 'October 13, 2026'
export const GOVERNING_LAW = 'the laws of the jurisdiction in which the operator is established'
/** Where the production site lives — used for canonical and sitemap URLs. */
export const SITE_ORIGIN = 'https://nexus-2-0-omega.vercel.app'
