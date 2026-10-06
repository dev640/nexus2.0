import { lazy, Suspense, useEffect } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Login } from './pages/Login'
import { Landing } from './pages/Landing'
import { useAppStore } from './store/useAppStore'

// The marketing site is the entry point for every visitor, so Landing and Login
// stay in the initial bundle and paint immediately. The workspace is only ever
// reached after a session is resolved, and it is eighteen pages deep -- pulling
// them all into one entry chunk shipped ~676 kB to people who only wanted the
// landing page, and tripped Vite's chunk-size warning on every deploy. Each is
// now fetched the first time its route is visited.
const AppShell = lazy(() =>
  import('./components/layout/AppShell').then((m) => ({ default: m.AppShell })),
)
const Home = lazy(() => import('./pages/Home').then((m) => ({ default: m.Home })))
const MyWork = lazy(() => import('./pages/MyWork').then((m) => ({ default: m.MyWork })))
const Inbox = lazy(() => import('./pages/Inbox').then((m) => ({ default: m.Inbox })))
const Projects = lazy(() => import('./pages/Projects').then((m) => ({ default: m.Projects })))
const Sprints = lazy(() => import('./pages/Sprints').then((m) => ({ default: m.Sprints })))
const Board = lazy(() => import('./pages/Board').then((m) => ({ default: m.Board })))
const Backlog = lazy(() => import('./pages/Backlog').then((m) => ({ default: m.Backlog })))
const Calendar = lazy(() => import('./pages/Calendar').then((m) => ({ default: m.Calendar })))
const Wiki = lazy(() => import('./pages/Wiki').then((m) => ({ default: m.Wiki })))
const Whiteboard = lazy(() =>
  import('./pages/Whiteboard').then((m) => ({ default: m.Whiteboard })),
)
const ChatPage = lazy(() => import('./pages/Chat').then((m) => ({ default: m.ChatPage })))
const Analytics = lazy(() => import('./pages/Analytics').then((m) => ({ default: m.Analytics })))
const AICopilot = lazy(() => import('./pages/AICopilot').then((m) => ({ default: m.AICopilot })))
const NexusAI = lazy(() => import('./pages/NexusAI').then((m) => ({ default: m.NexusAI })))
const Team = lazy(() => import('./pages/Team').then((m) => ({ default: m.Team })))
const Settings = lazy(() => import('./pages/Settings').then((m) => ({ default: m.Settings })))
const Help = lazy(() => import('./pages/Help').then((m) => ({ default: m.Help })))
const Admin = lazy(() => import('./pages/Admin').then((m) => ({ default: m.Admin })))

/**
 * Stands in while a route's chunk is in flight. Matches the shell's own
 * background and full-height layout so the wait reads as the page settling
 * rather than the app flashing white.
 */
function RouteFallback() {
  return (
    <div className="flex h-screen w-full items-center justify-center bg-paper" role="status" aria-label="Loading">
      <div className="h-0.5 w-24 overflow-hidden rounded-full bg-line">
        <div className="h-full w-1/2 animate-pulse bg-ink/40" />
      </div>
    </div>
  )
}

function App() {
  const isAuthenticated = useAppStore((s) => s.isAuthenticated)
  const isBootstrapped = useAppStore((s) => s.isBootstrapped)
  const userRole = useAppStore((s) => s.userRole)
  const bootstrapFromStoredToken = useAppStore((s) => s.bootstrapFromStoredToken)

  useEffect(() => {
    void bootstrapFromStoredToken()
  }, [bootstrapFromStoredToken])

  // A Supabase session is not visible to `isAuthenticated`'s initial value, which
  // only reads the stored Nexus token. Rendering the public routes during that
  // window sent deep links (e.g. a refresh on /admin) to the catch-all and then
  // to "/", losing the requested page. Wait for the session to be resolved first.
  if (!isBootstrapped) {
    return null
  }

  if (!isAuthenticated) {
    return (
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/login" element={<Login />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    )
  }

  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route path="/login" element={<Navigate to="/" replace />} />
        <Route
          path="/*"
          element={
            <AppShell>
              <Routes>
                <Route path="/" element={<Home />} />
                <Route path="/my-work" element={<MyWork />} />
                <Route path="/inbox" element={<Inbox />} />
                <Route path="/projects" element={<Projects />} />
                <Route path="/sprints" element={<Sprints />} />
                <Route path="/board" element={<Board />} />
                <Route path="/backlog" element={<Backlog />} />
                <Route path="/calendar" element={<Calendar />} />
                <Route path="/wiki" element={<Wiki />} />
                <Route path="/whiteboard" element={<Whiteboard />} />
                <Route path="/slack" element={<ChatPage />} />
                <Route path="/analytics" element={<Analytics />} />
                <Route path="/copilot" element={<AICopilot />} />
                <Route path="/ai" element={<NexusAI />} />
                <Route path="/team" element={<Team />} />
                <Route path="/settings" element={<Settings />} />
                <Route path="/help" element={<Help />} />
                <Route
                  path="/admin"
                  element={
                    userRole === 'ADMIN' ? (
                      <Admin />
                    ) : (
                      <Navigate to="/settings" replace />
                    )
                  }
                />
              </Routes>
            </AppShell>
          }
        />
      </Routes>
    </Suspense>
  )
}

export default App
