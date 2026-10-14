import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
// Latin subsets only. The default entry points pull in cyrillic, greek,
// vietnamese and latin-ext for all four weights — 56 files and 960 KB, almost
// none of which an English-language workspace ever requests. A character outside
// latin (a name in Cyrillic, say) still renders, in the system fallback rather
// than Inter; add a subset import back if the product needs it.
import '@fontsource/inter/latin-400.css'
import '@fontsource/inter/latin-500.css'
import '@fontsource/inter/latin-600.css'
import '@fontsource/inter/latin-700.css'
// DM Sans and Noto Serif are imported here, not with @import in index.css:
// index.css runs through the Tailwind pipeline, which inlines these packages'
// CSS without rewriting their `url(./files/…)` references, so the build ships
// URLs like /assets/files/dm-sans-latin-wght-normal.woff2 that 404 and the page
// silently falls back to the system sans. As JS imports Vite fingerprints each
// woff2 and rewrites the URL — the same path the Inter imports above take.
import '@fontsource-variable/dm-sans'
import '@fontsource-variable/noto-serif'
import './index.css'
import App from './App.tsx'
import { CookieConsent } from './components/legal/CookieConsent'
import { initAnalytics } from './lib/analytics'

// Runs before the first render so an accepted visitor never sees the banner
// flash on a later visit. A build with no analytics configured does nothing.
initAnalytics()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
      <CookieConsent />
    </BrowserRouter>
  </StrictMode>,
)
