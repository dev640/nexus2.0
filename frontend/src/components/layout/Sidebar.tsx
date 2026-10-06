import { NavLink, useNavigate } from 'react-router-dom'
import {
  X,
  Home,
  ListChecks,
  Inbox,
  FolderKanban,
  Rocket,
  Kanban,
  ListTodo,
  Calendar,
  BookOpen,
  PenTool,
  MessageCircle,
  BarChart3,
  Sparkles,
  Users,
  Settings,
  HelpCircle,
  LogOut,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react'
import { primaryNav, secondaryNav } from '../../lib/nav'
import type { NavItem } from '../../lib/nav'
import { useAppStore } from '../../store/useAppStore'
import { useUnreadCount } from '../../hooks/useUnreadCount'

/** Admin-only entry, kept out of the shared arrays so non-admins never see it. */
const adminNavItem: NavItem = { label: 'Admin', path: '/admin' }

const iconByPath: Record<string, LucideIcon> = {
  '/': Home,
  '/my-work': ListChecks,
  '/inbox': Inbox,
  '/projects': FolderKanban,
  '/sprints': Rocket,
  '/board': Kanban,
  '/backlog': ListTodo,
  '/calendar': Calendar,
  '/wiki': BookOpen,
  '/whiteboard': PenTool,
  '/slack': MessageCircle,
  '/analytics': BarChart3,
  '/ai': Sparkles,
  '/team': Users,
  '/settings': Settings,
  '/help': HelpCircle,
  '/admin': ShieldCheck,
}

function UnreadBadge({ count }: { count: number }) {
  return (
    <span
      aria-label={`${count} unread messages`}
      className="ml-auto inline-flex min-w-5 items-center justify-center rounded-full bg-red-500 px-1.5 py-0.5 text-[10px] font-semibold leading-none text-white"
    >
      {count > 99 ? '99+' : count}
    </span>
  )
}

function NavRow({
  item,
  onNavigate,
  unread,
}: {
  item: NavItem
  onNavigate?: () => void
  unread?: number
}) {
  const Icon = iconByPath[item.path]
  return (
    <NavLink
      to={item.path}
      end={item.path === '/'}
      onClick={onNavigate}
      className={({ isActive }) =>
        `flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors ${
          isActive
            ? 'bg-ink text-white'
            : 'text-ink/70 hover:bg-line/60 hover:text-ink'
        }`
      }
    >
      {Icon && <Icon size={16} strokeWidth={1.5} className="shrink-0" />}
      {item.label}
      {item.path === '/slack' && unread != null && unread > 0 && <UnreadBadge count={unread} />}
    </NavLink>
  )
}

export function Sidebar({
  mobileOpen,
  onClose,
}: {
  mobileOpen: boolean
  onClose: () => void
}) {
  const navigate = useNavigate()
  const userRole = useAppStore((s) => s.userRole)
  const logout = useAppStore((s) => s.logout)
  const unreadTotal = useUnreadCount()

  function handleLogout() {
    logout()
    onClose()
    navigate('/login')
  }

  return (
    <>
      {mobileOpen && (
        <div
          onClick={onClose}
          aria-hidden="true"
          className="fixed inset-0 z-40 bg-black/40 lg:hidden"
        />
      )}

      <aside
        className={
          `fixed inset-y-0 right-0 z-50 flex w-64 shrink-0 flex-col justify-between border-l border-line bg-white px-3 py-4 transition-transform duration-200 ease-out lg:static lg:z-auto lg:w-60 lg:translate-x-0 ${
            mobileOpen ? 'translate-x-0' : 'translate-x-full'
          }`
        }
      >
        <div className="flex flex-col gap-1 overflow-y-auto overscroll-contain touch-pan-y">
          <div>
            <div className="mb-6 flex items-center justify-between px-3">
              <NavLink
                to="/"
                end
                onClick={onClose}
                aria-label="Nexus home"
                className="rounded-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink"
              >
                <img src="/logo-wordmark.png" alt="Nexus" className="h-7 w-auto" />
              </NavLink>
              <button
                onClick={onClose}
                aria-label="Close menu"
                className="rounded-md p-1 text-mute hover:text-ink lg:hidden"
              >
                <X size={18} strokeWidth={1.5} />
              </button>
            </div>
            <nav className="flex flex-col gap-1">
              {primaryNav.map((item) => (
                <NavRow key={item.path} item={item} onNavigate={onClose} unread={unreadTotal} />
              ))}
            </nav>
          </div>
          <nav className="flex flex-col gap-1 border-t border-line pt-3">
            {secondaryNav.map((item) => (
              <NavRow key={item.path} item={item} onNavigate={onClose} />
            ))}
            {userRole === 'ADMIN' && (
              <NavRow item={adminNavItem} onNavigate={onClose} />
            )}
            <button
              onClick={handleLogout}
              className="flex items-center gap-2.5 rounded-md px-3 py-2 text-left text-sm text-ink/70 transition-colors hover:bg-line/60 hover:text-ink"
            >
              <LogOut size={16} strokeWidth={1.5} className="shrink-0" />
              Log out
            </button>
          </nav>
        </div>
      </aside>
    </>
  )
}
