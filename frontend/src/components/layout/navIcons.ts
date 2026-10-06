import {
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
  Bot,
  Sparkles,
  Users,
  Settings,
  HelpCircle,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react'
import type { NavItem } from '../../lib/nav'

/**
 * Icon and admin-entry tables for the sidebar, kept out of Sidebar.tsx so that
 * file only exports components. Exported so a test can assert every nav entry
 * has an icon: NavRow renders `{Icon && ...}`, so a missing entry degrades to a
 * bare text row instead of failing — which is how the Copilot row lost its icon
 * unnoticed.
 */

/** Admin-only entry, kept out of the shared arrays so non-admins never see it. */
export const adminNavItem: NavItem = { label: 'Admin', path: '/admin' }

export const iconByPath: Record<string, LucideIcon> = {
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
  '/copilot': Bot,
  '/ai': Sparkles,
  '/team': Users,
  '/settings': Settings,
  '/help': HelpCircle,
  '/admin': ShieldCheck,
}