import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * Shared shell for the public legal pages.
 *
 * These are reachable signed-in and signed-out, so they deliberately do not sit
 * inside the workspace shell: a policy behind a login is not much of a policy.
 */
export function LegalPage({
  title,
  updated,
  children,
}: {
  title: string
  updated: string
  children: ReactNode
}) {
  return (
    <div className="min-h-screen bg-paper">
      <header className="border-b border-line bg-white">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-6 py-4">
          <Link to="/" className="flex items-center">
            <img src="/logo-wordmark.png" alt="Nexus home" className="h-6 w-auto" />
          </Link>
          <Link to="/" className="text-sm text-ink/70 transition-colors hover:text-ink">
            Back to site
          </Link>
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-6 py-12">
        <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl">{title}</h1>
        <p className="mt-3 text-sm text-mute">Last updated {updated}</p>
        <div className="mt-10 flex flex-col gap-9">{children}</div>
      </main>

      <footer className="border-t border-line bg-white">
        <div className="mx-auto flex max-w-3xl flex-col gap-2 px-6 py-6 text-xs text-mute sm:flex-row sm:justify-between">
          <span>&copy; {new Date().getFullYear()} Nexus. All rights reserved.</span>
          <span className="flex gap-5">
            <Link to="/privacy" className="transition-colors hover:text-ink">
              Privacy
            </Link>
            <Link to="/terms" className="transition-colors hover:text-ink">
              Terms
            </Link>
          </span>
        </div>
      </footer>
    </div>
  )
}

/** One numbered heading with its prose, so every policy reads the same way. */
export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2 className="text-lg font-semibold tracking-tight text-ink">{title}</h2>
      <div className="mt-3 flex flex-col gap-3 text-sm leading-relaxed text-ink/80 [&_a]:text-ink [&_code]:rounded [&_code]:bg-line/60 [&_code]:px-1 [&_code]:py-0.5 [&_code]:font-mono [&_code]:text-xs [&_li]:ml-4 [&_li]:list-disc [&_strong]:font-semibold [&_strong]:text-ink [&_ul]:flex [&_ul]:flex-col [&_ul]:gap-2">
        {children}
      </div>
    </section>
  )
}
