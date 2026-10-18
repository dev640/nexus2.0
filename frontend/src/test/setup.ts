import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// React Testing Library only auto-cleans when test globals are exposed, and
// this project imports describe/it from 'vitest' explicitly instead.
afterEach(() => {
  cleanup()
})

// jsdom implements no media queries, and some components read
// `prefers-reduced-motion` at module scope — so importing them would throw
// before a single test runs. Reduced motion is reported as absent, which is the
// branch every animation-based component is written to take.
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  window.matchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  })
}
