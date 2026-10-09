import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Menu, TriangleAlert } from 'lucide-react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { AlertToasts } from './AlertToasts'
import { useDeviceAlerts } from '../../hooks/useDeviceAlerts'
import { setAlertNavigator } from '../../lib/deviceAlerts'
import { useAppStore } from '../../store/useAppStore'

export function AppShell({ children }: { children: ReactNode }) {
  // Mounted here rather than on a page: an alert about a task, a wiki page or a
  // chat message has to reach the user wherever they happen to be.
  useDeviceAlerts()
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const navigate = useNavigate()

  // A system popup is clicked outside the React tree, so the alert layer needs a
  // way back in: it gets the router's navigate, and clicking a popup then moves
  // the app to the page it points at instead of reloading it.
  useEffect(() => {
    setAlertNavigator((path) => navigate(path))
    return () => setAlertNavigator(null)
  }, [navigate])
  const location = useLocation()
  const syncError = useAppStore((s) => s.syncError)
  const isLoading = useAppStore((s) => s.isLoading)

  useEffect(() => {
    document.body.style.overflow = mobileNavOpen ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [mobileNavOpen])

  return (
    <div className="flex h-screen w-full flex-col bg-paper lg:flex-row">
      <header className="flex shrink-0 items-center justify-between border-b border-line bg-white px-4 py-3 lg:hidden">
        <img src="/logo-wordmark.png" alt="Nexus" className="h-7 w-auto" />
        <button
          onClick={() => setMobileNavOpen(true)}
          aria-label="Open menu"
          className="rounded-md border border-line p-2 text-ink hover:bg-paper"
        >
          <Menu size={18} strokeWidth={1.5} />
        </button>
      </header>

      <Sidebar mobileOpen={mobileNavOpen} onClose={() => setMobileNavOpen(false)} />

      <main key={location.pathname} className="animate-page-in flex-1 overflow-y-auto">
        {syncError && (
          <div className="flex items-center gap-2 border-b border-red-200 bg-red-50 px-4 py-2 text-xs text-red-700">
            <TriangleAlert size={14} strokeWidth={1.5} />
            <span>{syncError}</span>
            <button
              onClick={() => useAppStore.setState({ syncError: null })}
              className="ml-auto font-medium hover:underline"
            >
              Dismiss
            </button>
          </div>
        )}
        {isLoading && (
          <div className="h-0.5 w-full overflow-hidden bg-paper">
            <div className="h-full w-1/3 animate-pulse bg-ink/40" />
          </div>
        )}
        {children}
      </main>

      <AlertToasts />
    </div>
  )
}
