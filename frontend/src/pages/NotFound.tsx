import { Link } from 'react-router-dom'
import { useDocumentTitle } from '../hooks/useDocumentTitle'

/**
 * Shown for any route that matches nothing.
 *
 * It replaces a catch-all redirect to "/", which was worse than it looked: a
 * stale or mistyped link silently landed on the marketing page, so the visitor
 * could not tell whether the page had moved or their link was wrong.
 *
 * This is the in-app half of the story, and it is reached two ways: a signed-out
 * visitor deep-linking to a route that exists but is not theirs, and in-app
 * navigation to a path the router cannot match.
 *
 * The other half is the server. An unknown path never reaches the app at all —
 * vercel.json enumerates the declared routes, and anything else falls through to
 * the static public/404.html with a real 404 status, so crawlers and uptime
 * probes see the truth instead of a 200 render. That enumeration is only safe
 * because src/lib/vercelRouting.test.ts fails when a route is added and the
 * rewrite list is not — otherwise the deep link would work in `npm run dev` and
 * 404 in production. Keep the headline below in step with the static page; the
 * same test pins them together.
 */
export function NotFound({
  secondaryTo = '/login',
  secondaryLabel = 'Sign in',
}: {
  /** Where the second button goes. Signed-in visitors get Help; everyone else, sign-in. */
  secondaryTo?: string
  secondaryLabel?: string
}) {
  useDocumentTitle('Page not found · Nexus')

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-paper px-6 py-16 text-center">
      <img src="/logo-mark.png" alt="" aria-hidden="true" className="h-12 w-auto" />
      <p className="mt-8 text-xs font-medium uppercase tracking-widest text-mute">404</p>
      <h1 className="mt-3 text-3xl font-semibold tracking-tight text-ink sm:text-4xl">
        We could not find that page
      </h1>
      <p className="mt-4 max-w-md text-sm leading-relaxed text-ink/70">
        The link may be out of date, or the page may have moved. Nothing in your workspace has
        changed.
      </p>
      <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
        <Link
          to="/"
          className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-black"
        >
          Back to home
        </Link>
        <Link
          to={secondaryTo}
          className="rounded-md border border-line bg-white px-4 py-2 text-sm font-medium text-ink transition-colors hover:border-ink"
        >
          {secondaryLabel}
        </Link>
      </div>
    </div>
  )
}
