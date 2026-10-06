export interface NavItem {
  label: string
  path: string
}

export const primaryNav: NavItem[] = [
  { label: 'Home', path: '/' },
  { label: 'My Work', path: '/my-work' },
  { label: 'Inbox', path: '/inbox' },
  { label: 'Projects', path: '/projects' },
  { label: 'Sprints', path: '/sprints' },
  { label: 'Board', path: '/board' },
  { label: 'Backlog', path: '/backlog' },
  { label: 'Calendar', path: '/calendar' },
  { label: 'Wiki', path: '/wiki' },
  { label: 'Whiteboard', path: '/whiteboard' },
  { label: 'Analytics', path: '/analytics' },
  { label: 'Nexus AI', path: '/ai' },
]

export const secondaryNav: NavItem[] = [
  { label: 'Team', path: '/team' },
  { label: 'Settings', path: '/settings' },
  { label: 'Help', path: '/help' },
]
