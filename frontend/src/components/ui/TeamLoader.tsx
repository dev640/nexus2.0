import { Suspense, lazy } from 'react'

/**
 * The workspace loading animation, shown while a route chunk (or the session
 * bootstrap) is in flight.
 *
 * The player and its artwork are fetched as their own chunk rather than being
 * part of the entry bundle: this component is the fallback for *every* lazy
 * route, so anything it imports statically lands on the first-paint path. The
 * fallback below holds the same footprint, so the page does not shift when the
 * animation arrives a moment later.
 */
const TeamAnimation = lazy(() =>
  import('./TeamAnimation').then((m) => ({ default: m.TeamAnimation })),
)

export function TeamLoader({ className = 'h-52 w-52' }: { className?: string }) {
  return (
    <div role="status" aria-label="Loading" className="flex items-center justify-center">
      <Suspense fallback={<div className={`${className} animate-pulse rounded-lg bg-line/50`} />}>
        <TeamAnimation className={className} />
      </Suspense>
    </div>
  )
}
