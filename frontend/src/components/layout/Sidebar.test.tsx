import { describe, expect, it } from 'vitest'
import { primaryNav, secondaryNav } from '../../lib/nav'
import { adminNavItem, iconByPath } from './navIcons'

// The routes declared in App.tsx. Kept here by hand so adding a nav entry
// without adding its route fails the build rather than producing a 404.
const appRoutes = [
  '/',
  '/my-work',
  '/inbox',
  '/projects',
  '/sprints',
  '/board',
  '/backlog',
  '/calendar',
  '/wiki',
  '/whiteboard',
  '/slack',
  '/analytics',
  '/copilot',
  '/ai',
  '/team',
  '/settings',
  '/help',
  '/admin',
]

const everyEntry = [...primaryNav, ...secondaryNav, adminNavItem]

describe('sidebar navigation integrity', () => {
  it('gives every navigation entry an icon', () => {
    const missing = everyEntry.filter((item) => !iconByPath[item.path]).map((i) => i.path)
    expect(missing).toEqual([])
  })

  it('routes every navigation entry to a registered page', () => {
    const unrouted = everyEntry.filter((item) => !appRoutes.includes(item.path)).map((i) => i.path)
    expect(unrouted).toEqual([])
  })

  it('keeps the two copilots as separate destinations', () => {
    const paths = everyEntry.map((i) => i.path)
    // The grounded Copilot and the RAG assistant answer different questions,
    // so neither may replace the other.
    expect(paths).toContain('/copilot')
    expect(paths).toContain('/ai')
    expect(new Set(paths).size).toBe(paths.length)
  })
})