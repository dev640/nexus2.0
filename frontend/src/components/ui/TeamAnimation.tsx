import { Lottie } from 'lottie-react'
import teamAnimation from '../../assets/animations/team-loading.json'

/**
 * The animation itself, deliberately in its own module.
 *
 * Keeping the player and the artwork together here is what lets TeamLoader load
 * them *after* first paint: `lottie-web` plus this 159 KB of animation JSON were
 * being bundled into the entry chunk, and TeamLoader is the Suspense fallback for
 * every lazy route — so the heaviest thing in the app was on the path of the
 * first render, before any route had been asked for.
 *
 * `src` is passed the imported object, so there is no runtime fetch of the JSON
 * once this chunk has arrived. Loop and autoplay are on because a loader replays
 * until the real content lands.
 */
export function TeamAnimation({ className = 'h-52 w-52' }: { className?: string }) {
  return <Lottie src={teamAnimation} loop autoplay className={className} />
}
