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
