import { useEffect } from 'react'
import { Bell, X } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import type { AppAlert } from '../../store/models'
import { useAppStore } from '../../store/useAppStore'

/** How long each alert stays on screen before dismissing itself. */
const VISIBLE_MS = 6000

function AlertToast({ id, title, body, link }: AppAlert) {
  const dismissAlert = useAppStore((s) => s.dismissAlert)
  const navigate = useNavigate()

  useEffect(() => {
    const timer = window.setTimeout(() => dismissAlert(id), VISIBLE_MS)
    return () => window.clearTimeout(timer)
  }, [id, dismissAlert])

  // An alert with a target opens it and gets out of the way; one without stays
  // purely informational, so it is not a button that does nothing.
  function open() {
    if (!link) return
    dismissAlert(id)
    navigate(link)
  }

  return (
    <div
      role="alert"
      className="pointer-events-auto flex w-80 items-start gap-3 rounded-lg border border-line bg-white p-3 shadow-lg"
    >
      <button
        type="button"
        onClick={open}
        disabled={!link}
        title={link ? 'Open' : undefined}
        className="flex min-w-0 flex-1 items-start gap-3 text-left disabled:cursor-default"
      >
        <Bell size={16} strokeWidth={1.5} className="mt-0.5 shrink-0 text-accent" />
        <div className="min-w-0 flex-1">
          <div className="text-sm font-semibold text-ink">{title}</div>
          <p className="mt-0.5 break-words text-xs text-ink/80">{body}</p>
        </div>
      </button>
      <button
        onClick={() => dismissAlert(id)}
        aria-label="Dismiss notification"
        className="shrink-0 text-mute hover:text-ink"
      >
        <X size={14} strokeWidth={1.5} />
      </button>
    </div>
  )
}

/** Bottom corner host for in-app alerts. Renders nothing while there are none. */
export function AlertToasts() {
  const alerts = useAppStore((s) => s.activeAlerts)
  if (alerts.length === 0) return null

  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-[70] flex flex-col gap-2">
      {alerts.map((alert) => (
        <AlertToast key={alert.id} {...alert} />
      ))}
    </div>
  )
}
