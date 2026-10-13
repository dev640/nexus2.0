// This test reads vercel.json and App.tsx from disk, so it needs Node's types.
// tsconfig.app.json deliberately lists only `vite/client` — application code has
// no business importing node:fs — so they are pulled in for this file alone
// rather than widening the config every worker faces.
/// <reference types="node" />
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Guards the one thing that makes real 404s safe: the enumerated SPA rewrites
 * must cover every route the app declares.
 *
 * `vercel.json` used to rewrite *every* path to `index.html`, so an unknown URL
 * answered 200 with the app shell — a mistyped link looked like a working page
 * to a visitor, and to a crawler. The fix is to rewrite only the routes the app
 * actually declares and let everything else fall through to the static
 * `404.html`. The cost of that fix is exactly this failure mode: add a route,
 * forget the rewrite, and the deep link 404s in production while working
 * perfectly under `npm run dev`, because Vite serves `index.html` for any path.
 *
 * What this test does *not* prove: that Vercel answers 404 for an unknown path.
 * That is platform behaviour and only a deployment can show it —
 * `curl -o /dev/null -w '%{http_code}' <url>/not-a-route` should print 404.
 * This test proves the configuration cannot silently fall behind the app.
 *
 * Two consequences are accepted rather than engineered around:
 *
 *  - **Case.** The router matches case-insensitively but the CDN does not, so
 *    `/Privacy` answers 404 where it used to render the policy. Every URL the
 *    product produces is lower-case, and enumerating case variants is a
 *    combinatorial mess for a URL nobody links to.
 *  - **Unknown sub-paths.** `/projects/123` is now a 404. No page has a
 *    parameterised route, and the app only ever links the paths above.
 */

// Paths are resolved from the working directory rather than import.meta.url:
// under jsdom that URL is http://, not file://, so fileURLToPath throws. Vitest
// runs with the frontend package as its working directory (`npm test` from
// frontend/). The first test below fails loudly if that stops being true, so a
// future move breaks with an explanation instead of a confusing ENOENT.
const FRONTEND = process.cwd()
const APP = resolve(FRONTEND, 'src/App.tsx')
const FE_CONFIG = resolve(FRONTEND, 'vercel.json')
const ROOT_CONFIG = resolve(FRONTEND, '..', 'vercel.json')
const STATIC_404 = resolve(FRONTEND, 'public', '404.html')
const REACT_404 = resolve(FRONTEND, 'src', 'pages', 'NotFound.tsx')

/** Either config file; the one Vercel uses depends on the project's root directory. */
const CONFIGS = [
  { name: 'vercel.json', path: ROOT_CONFIG },
  { name: 'frontend/vercel.json', path: FE_CONFIG },
]

interface Rewrite {
  source: string
  destination: string
}

/**
 * The app's own route table, read from the one place routes are declared.
 *
 * Derived rather than hand-listed so this test cannot drift with the app: adding
 * a `<Route path>` is what makes the assertion below fail.
 */
function declaredRoutes(): string[] {
  const source = readFileSync(APP, 'utf8')
  const paths = [...source.matchAll(/path="([^"]+)"/g)].map((match) => match[1])
  return [
    ...new Set(
      paths.filter(
        // `*` and `/*` are catch-alls handled inside the app, not server routes.
        (path) => path.startsWith('/') && !path.includes('*'),
      ),
    ),
  ].sort()
}

function rewritesOf(path: string): Rewrite[] {
  const config = JSON.parse(readFileSync(path, 'utf8')) as { rewrites?: Rewrite[] }
  return config.rewrites ?? []
}

describe('SPA routing in vercel.json', () => {
  const routes = declaredRoutes()

  it('reads a plausible route table from App.tsx', () => {
    // A regex that silently stopped matching would make every test below vacuous.
    expect(routes.length).toBeGreaterThan(15)
    expect(routes).toContain('/')
    expect(routes).toContain('/login')
    expect(routes).toContain('/privacy')
  })

  it('reads the real files (guards the working-directory assumption)', () => {
    for (const path of [APP, FE_CONFIG, ROOT_CONFIG, STATIC_404, REACT_404]) {
      expect(existsSync(path), `expected ${path} to exist`).toBe(true)
    }
  })

  describe.each(CONFIGS)('$name', ({ path }) => {
    const rewrites = rewritesOf(path)
    const sources = rewrites.map((r) => r.source)

    it('rewrites every declared route to the app shell', () => {
      for (const route of routes) {
        expect(sources, `missing rewrite for ${route}`).toContain(route)
      }
    })

    it('also rewrites the trailing-slash form of every route', () => {
      // Vercel answers /privacy/ with 200 and no redirect, so the request reaches
      // these rules with its slash intact. A literal "/privacy" rule alone would
      // 404 a URL the client-side router resolves perfectly well.
      for (const route of routes.filter((r) => r !== '/')) {
        expect(sources, `missing rewrite for ${route}/`).toContain(`${route}/`)
      }
    })

    it('only ever sends matched routes to the app shell', () => {
      for (const rewrite of rewrites) {
        expect(rewrite.destination, `${rewrite.source} →`).toBe('/index.html')
      }
    })

    it('has no pattern that would swallow an unknown path', () => {
      // A wildcard, capture group or `:param` here reintroduces the 200-for-
      // everything bug and makes the static 404 unreachable.
      for (const source of sources) {
        expect(source, `${source} is a catch-all`).not.toMatch(/[*(:]|\.\.|\.\*/)
      }
    })

    it('has no rewrite for a path the app does not declare', () => {
      // Catches a typo like /setings, which would 404 the real /settings by
      // matching nothing, and silently serve the app shell for a dead URL.
      for (const source of sources) {
        const base = source.endsWith('/') && source !== '/' ? source.slice(0, -1) : source
        expect(routes, `${source} is not a declared route`).toContain(base)
      }
    })

    it('lists each route once, with no duplicates', () => {
      expect(sources).toHaveLength(new Set(sources).size)
    })
  })

  it('keeps both config files identical, since either may be the one deployed', () => {
    const root = JSON.stringify(JSON.parse(readFileSync(ROOT_CONFIG, 'utf8')).rewrites)
    const nested = JSON.stringify(JSON.parse(readFileSync(FE_CONFIG, 'utf8')).rewrites)
    expect(nested).toBe(root)
  })

  describe('the static 404 page', () => {
    const html = readFileSync(STATIC_404, 'utf8')

    it('exists where the build will pick it up', () => {
      // Vite copies public/ to the output root, which is where Vercel looks.
      expect(STATIC_404.replace(/\\/g, '/')).toMatch(/frontend\/public\/404\.html$/)
    })

    it('sends the visitor somewhere useful', () => {
      expect(html).toContain('href="/"')
      expect(html).toContain('href="/login"')
    })

    it('carries its own styles and needs nobody to load the app', () => {
      // Hashed bundle names change every build, so referencing them here would
      // turn a wrong-URL 404 into a broken page. Only stable assets may appear.
      expect(html).toContain('<style>')
      expect(html).not.toMatch(/\/assets\/[A-Za-z0-9-]+\.(js|css)/)
      expect(html).toContain('/logo-mark.png')
      // A 404 should not be indexed, and it should say which URL failed.
      expect(html).toContain('name="robots" content="noindex"')
      expect(html).toContain('<title>Page not found · Nexus</title>')
    })

    it('says the same thing as the in-app 404', () => {
      // The two pages are a deliberate duplication: this one is served when the
      // request never reaches the app, the React one when it does. Copy that
      // drifts apart makes the site look broken differently depending on how
      // the visitor arrived, so pin the one line that must match.
      const react = readFileSync(REACT_404, 'utf8')
      const headline = 'We could not find that page'
      expect(html).toContain(headline)
      expect(react).toContain(headline)
    })
  })
})
