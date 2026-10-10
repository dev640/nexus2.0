import { Lottie } from 'lottie-react'
import teamAnimation from '../../assets/animations/team-loading.json'

/**
 * The workspace loading animation: a small team at their desks while a route
 * chunk (or the session bootstrap) is in flight.
 *
 * `team-loading.json` is imported directly, so Vite bundles it — no runtime
 * fetch, and the component renders the parsed animation through `src`.
 * `loop` and `autoplay` are on because a loader replays until the real
 * content arrives; the component unmounts when the chunk lands.
 */
export function TeamLoader({ className = 'h-52 w-52' }: { className?: string }) {
  return (
    <div role="status" aria-label="Loading" className="flex items-center justify-center">
      <Lottie src={teamAnimation} loop autoplay className={className} />
    </div>
  )
}
