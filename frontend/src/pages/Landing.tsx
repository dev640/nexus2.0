import { Link, useNavigate } from 'react-router-dom'
import {
  ArrowRight,
  Rocket,
  Kanban,
  BookOpen,
  PenTool,
  BarChart3,
  Sparkles,
  GitBranch,
  MessageSquare,
  Layers,
} from 'lucide-react'
import { MarqueeBand } from '../components/ui/MarqueeBand'
import { MagneticButton } from '../components/ui/MagneticButton'
import { Reveal } from '../components/marketing/Reveal'

const features = [
  {
    icon: Rocket,
    title: 'Backlog & Sprints',
    body: 'Groom the backlog, commit to a sprint, and watch velocity settle over time.',
  },
  {
    icon: Kanban,
    title: 'Kanban Board',
    body: 'Drag work across lanes. Every card stays linked to its sprint and owner.',
  },
  {
    icon: BookOpen,
    title: 'Wiki',
    body: 'Specs, decisions and runbooks living next to the work they describe.',
  },
  {
    icon: PenTool,
    title: 'Whiteboard',
    body: 'Sketch architecture and plan sprints on an infinite canvas with your team.',
  },
  {
    icon: BarChart3,
    title: 'Analytics',
    body: 'Burndown, throughput and team load — no spreadsheet exports required.',
  },
  {
    icon: Sparkles,
    title: 'Nexus AI',
    body: 'Ask anything in plain language. Answers cite your wiki, tasks and projects as sources.',
  },
]

const workflow = [
  {
    eyebrow: 'Plan',
    title: 'Break the work down.',
    body: 'Turn a rough idea into estimated stories, then pull the top of the backlog into a sprint in one move.',
    visual: <PlanVisual />,
  },
  {
    eyebrow: 'Build',
    title: 'See the whole board.',
    body: 'A single board for the sprint. Status, blockers and ownership are obvious at a glance — to everyone.',
    visual: <BoardVisual />,
  },
  {
    eyebrow: 'Ship',
    title: 'Ask your workspace.',
    body: 'Before standup, ask what slipped and why. Nexus AI reads your permission-filtered knowledge so you do not have to.',
    visual: <CopilotVisual />,
  },
]

export function Landing() {
  const navigate = useNavigate()

  return (
    <div className="min-h-screen bg-paper text-ink">
      <SiteNav />

      {/* Hero */}
      <section className="relative isolate overflow-hidden border-b border-line">
        <div aria-hidden className="hero-grid pointer-events-none absolute inset-0 -z-10" />
        <div
          aria-hidden
          className="animate-drift-a pointer-events-none absolute -left-24 top-16 -z-10 h-72 w-72 rounded-full bg-accent/30 blur-[90px]"
        />
        <div
          aria-hidden
          className="animate-drift-b pointer-events-none absolute -right-20 top-2 -z-10 h-64 w-64 rounded-full bg-ink/10 blur-[90px]"
        />
        <img
          src="/logo-mark.png"
          alt=""
          aria-hidden
          className="pointer-events-none absolute -right-10 bottom-[-3rem] -z-10 w-[360px] opacity-[0.05] sm:w-[520px]"
        />

        <div className="mx-auto max-w-6xl px-6 pb-16 pt-28 text-center sm:pt-36">
          <Reveal>
            <span className="inline-flex items-center gap-2 rounded-full border border-line bg-white/70 px-3 py-1 text-xs font-medium uppercase tracking-widest text-mute backdrop-blur">
              <span className="h-1.5 w-1.5 rounded-full bg-accent" />
              Now in open beta — free
            </span>
          </Reveal>
          <Reveal delay={60}>
            <h1 className="mx-auto mt-6 max-w-4xl text-4xl font-semibold leading-[1.02] tracking-tight sm:text-6xl lg:text-[5rem]">
              PLAN, BUILD, AND{' '}
              <span className="relative inline-block">
                <span
                  aria-hidden
                  className="absolute inset-x-[-4px] bottom-1 h-3 -skew-x-6 bg-accent sm:bottom-1.5 sm:h-4"
                />
                <span className="relative">SHIP</span>
              </span>
              <br />
              IN ONE PLACE.
            </h1>
          </Reveal>
          <Reveal delay={120}>
            <p className="mx-auto mt-6 max-w-xl text-base text-mute sm:text-lg">
              Nexus folds your backlog, sprints, docs, whiteboards and a Nexus AI
              assistant into one fast workspace — so the plan and the work never drift apart.
            </p>
          </Reveal>
          <Reveal delay={180}>
            <div className="mt-9 flex flex-wrap items-center justify-center gap-3">
              <MagneticButton
                onClick={() => navigate('/login')}
                className="inline-flex items-center gap-2 rounded-md bg-ink px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-black"
              >
                Start for free
                <ArrowRight size={16} strokeWidth={1.75} />
              </MagneticButton>
              <a
                href="#workflow"
                className="inline-flex items-center gap-2 rounded-md border border-ink px-5 py-2.5 text-sm font-medium text-ink transition-colors hover:bg-white"
              >
                See how it works
              </a>
            </div>
          </Reveal>
          <Reveal delay={220}>
            <div className="mt-7 flex flex-wrap items-center justify-center gap-2 text-xs text-mute">
              {['Backlog & Sprints', 'Kanban Board', 'Whiteboard', 'Nexus AI'].map((t) => (
                <span
                  key={t}
                  className="rounded-full border border-line bg-white/60 px-3 py-1 backdrop-blur"
                >
                  {t}
                </span>
              ))}
            </div>
          </Reveal>
          <p className="mt-4 text-xs text-mute">
            Every feature. No seat limits. No credit card.
          </p>
        </div>

        <Reveal delay={120} className="relative mx-auto max-w-5xl px-6">
          <div
            aria-hidden
            className="pointer-events-none absolute inset-x-10 top-10 -z-10 h-3/4 rounded-[40px] bg-accent/20 blur-[100px]"
          />
          <ProductMock />
        </Reveal>

        <div className="mt-16 bg-ink py-3 text-white">
          <MarqueeBand
            items={['PLAN', 'GROOM', 'ESTIMATE', 'SPRINT', 'BUILD', 'REVIEW', 'SHIP', 'ITERATE']}
            className="text-white/70"
          />
        </div>
      </section>

      {/* Features */}
      <section id="features" className="mx-auto max-w-6xl px-6 py-24">
        <Reveal>
          <p className="text-xs font-semibold uppercase tracking-widest text-mute">
            Everything, connected
          </p>
          <h2 className="mt-3 max-w-2xl text-3xl font-semibold tracking-tight sm:text-4xl">
            ONE WORKSPACE. EVERY STAGE.
          </h2>
        </Reveal>

        <div className="mt-12 grid grid-cols-1 gap-px border border-line bg-line sm:grid-cols-2 lg:grid-cols-3">
          {features.map((f, i) => (
            <Reveal key={f.title} delay={i * 50} className="h-full">
              <article className="flex h-full flex-col bg-paper p-8">
                <f.icon size={22} strokeWidth={1.5} className="text-ink" />
                <h3 className="mt-5 text-base font-semibold tracking-tight">{f.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-mute">{f.body}</p>
              </article>
            </Reveal>
          ))}
        </div>
      </section>

      {/* Workflow */}
      <section id="workflow" className="border-y border-line bg-white">
        <div className="mx-auto max-w-6xl px-6 py-24">
          <Reveal>
            <p className="text-xs font-semibold uppercase tracking-widest text-mute">How it works</p>
            <h2 className="mt-3 max-w-2xl text-3xl font-semibold tracking-tight sm:text-4xl">
              FROM ROUGH IDEA TO SHIPPED.
            </h2>
          </Reveal>

          <div className="mt-16 flex flex-col gap-20">
            {workflow.map((row, i) => (
              <Reveal key={row.title}>
                <div
                  className={`grid grid-cols-1 items-center gap-10 lg:grid-cols-2 ${
                    i % 2 === 1 ? 'lg:[&>*:first-child]:order-2' : ''
                  }`}
                >
                  <div>
                    <span className="text-xs font-semibold uppercase tracking-widest text-mute">
                      {String(i + 1).padStart(2, '0')} — {row.eyebrow}
                    </span>
                    <h3 className="mt-3 text-2xl font-semibold tracking-tight sm:text-3xl">
                      {row.title}
                    </h3>
                    <p className="mt-4 max-w-md text-sm leading-relaxed text-mute">{row.body}</p>
                  </div>
                  <div className="border border-line bg-paper p-5">{row.visual}</div>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* Stats */}
      <section className="bg-ink py-20 text-white">
        <div className="mx-auto grid max-w-6xl grid-cols-1 gap-10 px-6 text-center sm:grid-cols-3">
          {[
            ['12+', 'Point tools it replaces'],
            ['1', 'Place your team actually looks'],
            ['~5 min', 'To onboard a new engineer'],
          ].map(([n, l]) => (
            <Reveal key={l}>
              <div className="text-4xl font-semibold tracking-tight sm:text-5xl">{n}</div>
              <div className="mt-2 text-sm text-white/60">{l}</div>
            </Reveal>
          ))}
        </div>
      </section>

      {/* Quote */}
      <section className="mx-auto max-w-3xl px-6 py-24 text-center">
        <Reveal>
          <blockquote className="text-2xl font-medium leading-snug tracking-tight sm:text-3xl">
            &ldquo;We cancelled four subscriptions the week we moved to Nexus. Planning
            went from a Monday ritual to something that just happens.&rdquo;
          </blockquote>
          <figcaption className="mt-6 text-sm text-mute">
            Priya Nair — Engineering Lead, Northwind
          </figcaption>
        </Reveal>
      </section>

      {/* Final CTA */}
      <section className="bg-ink py-24 text-white">
        <div className="mx-auto max-w-3xl px-6 text-center">
          <Reveal>
            <h2 className="text-4xl font-semibold leading-[1.05] tracking-tight sm:text-6xl">
              BUILD BETTER.
              <br />
              TOGETHER.
            </h2>
            <p className="mx-auto mt-5 max-w-md text-sm text-white/60">
              Free while Nexus is in beta — every feature, no seat limits, no card.
            </p>
            <div className="mt-8 flex justify-center">
              <MagneticButton
                onClick={() => navigate('/login')}
                className="inline-flex items-center gap-2 rounded-md bg-white px-6 py-3 text-sm font-medium text-ink transition-colors hover:bg-white/90"
              >
                Get started
                <ArrowRight size={16} strokeWidth={1.75} />
              </MagneticButton>
            </div>
          </Reveal>
        </div>
      </section>

      <SiteFooter />
    </div>
  )
}

function SiteNav() {
  return (
    <header className="fixed inset-x-0 top-0 z-50 px-6">
      <div className="mx-auto mt-4 flex h-16 max-w-6xl items-center justify-between rounded-full border border-line bg-white/75 pl-7 pr-4 shadow-[0_10px_40px_-14px_rgba(17,17,17,0.22)] backdrop-blur-md sm:pr-5">
        <Link to="/" className="flex items-center">
          <img src="/logo-wordmark.png" alt="Nexus" className="h-7 w-auto" />
        </Link>
        <nav className="hidden items-center gap-8 text-sm text-ink/70 md:flex">
          <a href="#features" className="transition-colors hover:text-ink">
            Features
          </a>
          <a href="#workflow" className="transition-colors hover:text-ink">
            How it works
          </a>
        </nav>
        <div className="flex items-center gap-2 sm:gap-3">
          <Link
            to="/login"
            className="hidden px-3 py-2 text-sm font-medium text-ink/70 transition-colors hover:text-ink sm:block"
          >
            Log in
          </Link>
          <Link
            to="/login"
            className="inline-flex items-center gap-1.5 rounded-md bg-ink px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-black"
          >
            Get started
            <ArrowRight size={15} strokeWidth={1.75} />
          </Link>
        </div>
      </div>
    </header>
  )
}

function SiteFooter() {
  const columns = [
    { title: 'Product', links: ['Features', 'How it works', 'Roadmap', 'Changelog'] },
    { title: 'Company', links: ['About', 'Careers', 'Blog', 'Contact'] },
    { title: 'Resources', links: ['Docs', 'API', 'Community', 'Status'] },
  ]
  return (
    <footer className="border-t border-line bg-paper">
      <div className="mx-auto max-w-6xl px-6 py-14">
        <div className="grid grid-cols-2 gap-10 sm:grid-cols-4">
          <div className="col-span-2 sm:col-span-1">
            <img src="/logo-wordmark.png" alt="Nexus" className="h-5 w-auto" />
            <p className="mt-3 max-w-[14rem] text-xs leading-relaxed text-mute">
              One intelligent workspace for planning, building, documenting and shipping software.
            </p>
          </div>
          {columns.map((col) => (
            <div key={col.title}>
              <h4 className="text-xs font-semibold uppercase tracking-widest text-mute">
                {col.title}
              </h4>
              <ul className="mt-4 flex flex-col gap-2.5">
                {col.links.map((link) => (
                  <li key={link}>
                    <a href="#" className="text-sm text-ink/70 transition-colors hover:text-ink">
                      {link}
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <div className="mt-12 flex flex-col gap-3 border-t border-line pt-6 text-xs text-mute sm:flex-row sm:items-center sm:justify-between">
          <span>&copy; {new Date().getFullYear()} Nexus. All rights reserved.</span>
          <div className="flex gap-5">
            <a href="#" className="transition-colors hover:text-ink">
              Privacy
            </a>
            <a href="#" className="transition-colors hover:text-ink">
              Terms
            </a>
            <a href="#" className="transition-colors hover:text-ink">
              Security
            </a>
          </div>
        </div>
      </div>
    </footer>
  )
}

function ProductMock() {
  const greetingForHour = (hour: number) => {
    if (hour < 5) return 'GOOD NIGHT'
    if (hour < 12) return 'GOOD MORNING'
    if (hour < 17) return 'GOOD AFTERNOON'
    if (hour < 21) return 'GOOD EVENING'
    return 'GOOD NIGHT'
  }
  const greeting = greetingForHour(new Date().getHours())
  const nav = [
    { label: 'Home', active: true },
    { label: 'My Work', active: false },
    { label: 'Projects', active: false },
    { label: 'Sprints', active: false },
    { label: 'Board', active: false },
    { label: 'Analytics', active: false },
  ]
  const focus = [
    ['TASK-186', 'Implement JWT authentication', 'In Progress'],
    ['TASK-187', 'Build project dashboard', 'In Review'],
    ['TASK-188', 'Integrate payment API', 'Blocked'],
  ]
  return (
    <div className="animate-float overflow-hidden rounded-lg border border-line bg-white shadow-[0_40px_90px_-30px_rgba(17,17,17,0.28)]">
      <div className="flex items-center gap-1.5 border-b border-line px-4 py-3">
        <span className="h-2.5 w-2.5 rounded-full bg-line" />
        <span className="h-2.5 w-2.5 rounded-full bg-line" />
        <span className="h-2.5 w-2.5 rounded-full bg-line" />
        <span className="ml-3 text-[11px] text-mute">nexus.app / home</span>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-[180px_1fr]">
        <aside className="hidden flex-col gap-1 border-r border-line p-3 sm:flex">
          <div className="px-2 pb-2">
            <img src="/logo-wordmark.png" alt="" className="h-4 w-auto" />
          </div>
          {nav.map((item) => (
            <div
              key={item.label}
              className={`flex items-center gap-2 rounded-md px-2 py-1.5 text-xs ${
                item.active ? 'bg-ink text-white' : 'text-ink/60'
              }`}
            >
              <span
                className={`h-1.5 w-1.5 rounded-full ${
                  item.active ? 'bg-accent' : 'bg-line'
                }`}
              />
              {item.label}
            </div>
          ))}
        </aside>
        <div className="p-5 sm:p-7">
          <p className="text-[10px] font-semibold uppercase tracking-widest text-mute">Overview</p>
          <h3 className="mt-1 text-xl font-semibold tracking-tight sm:text-2xl">
            {greeting}, USER.
          </h3>
          <div className="mt-5 grid grid-cols-1 gap-4 lg:grid-cols-3">
            <div className="border border-line p-4 lg:col-span-2">
              <p className="text-[10px] font-semibold uppercase tracking-widest text-mute">
                Today&apos;s Focus
              </p>
              <div className="mt-3 flex flex-col">
                {focus.map(([id, title, status]) => (
                  <div
                    key={id}
                    className="flex items-center justify-between border-b border-line py-2 last:border-0"
                  >
                    <span className="truncate text-xs">
                      <span className="mr-2 font-mono text-[10px] text-mute">{id}</span>
                      {title}
                    </span>
                    <span
                      className={`ml-3 shrink-0 text-[10px] ${
                        status === 'Blocked' ? 'text-danger' : 'text-mute'
                      }`}
                    >
                      {status}
                    </span>
                  </div>
                ))}
              </div>
            </div>
            <div className="border border-line bg-ink p-4 text-white">
              <p className="text-[10px] font-semibold uppercase tracking-widest text-white/50">
                Sprint Health
              </p>
              <p className="mt-1 text-lg font-semibold tracking-tight">SPRINT 8</p>
              <div className="mt-3 h-1 w-full bg-white/15">
                <div className="h-full w-1/3 bg-accent" />
              </div>
              <div className="mt-3 space-y-1 text-[10px] text-white/60">
                <div className="flex justify-between">
                  <span>Completed</span>
                  <span>14</span>
                </div>
                <div className="flex justify-between">
                  <span>Remaining</span>
                  <span>28</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function PlanVisual() {
  const rows = [
    ['Auth service hardening', '5'],
    ['Billing webhook retries', '3'],
    ['Onboarding empty states', '2'],
    ['Search relevance pass', '8'],
  ]
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-widest text-mute">
        <Layers size={13} strokeWidth={1.75} /> Backlog → Sprint 9
      </div>
      {rows.map(([title, pts], i) => (
        <div
          key={title}
          className={`flex items-center justify-between border px-3 py-2 text-xs ${
            i < 2 ? 'border-ink bg-white' : 'border-line bg-paper text-mute'
          }`}
        >
          <span className="flex items-center gap-2">
            <span
              className={`h-1.5 w-1.5 rounded-full ${i < 2 ? 'bg-accent' : 'bg-line'}`}
            />
            {title}
          </span>
          <span className="font-mono text-[10px]">{pts} pt</span>
        </div>
      ))}
    </div>
  )
}

function BoardVisual() {
  const columns: Array<[string, string[]]> = [
    ['To Do', ['TASK-190', 'TASK-193']],
    ['In Progress', ['TASK-186', 'TASK-188']],
    ['Done', ['TASK-181']],
  ]
  return (
    <div className="grid grid-cols-3 gap-2">
      {columns.map(([name, cards]) => (
        <div key={name} className="flex flex-col gap-2">
          <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-mute">
            <Kanban size={12} strokeWidth={1.75} />
            {name}
          </div>
          {cards.map((c) => (
            <div key={c} className="border border-line bg-white px-2 py-2">
              <div className="font-mono text-[9px] text-mute">{c}</div>
              <div className="mt-1 h-1 w-full bg-line" />
              <div className="mt-1 h-1 w-2/3 bg-line" />
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}

function CopilotVisual() {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-widest text-mute">
        <Sparkles size={13} strokeWidth={1.75} /> Nexus AI
      </div>
      <div className="ml-auto max-w-[85%] rounded-lg rounded-br-sm bg-ink px-3 py-2 text-xs text-white">
        What&apos;s at risk in Sprint 8?
      </div>
      <div className="max-w-[90%] rounded-lg rounded-bl-sm border border-line bg-white px-3 py-2 text-xs leading-relaxed text-ink/80">
        <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-mute">
          <MessageSquare size={11} strokeWidth={1.75} /> Nexus
        </span>
        <span className="mt-1 block">
          TASK-188 (payment API) is blocked and on the critical path. Two review items
          are stale &gt; 2 days. Velocity trend suggests a 4-point overshoot.
        </span>
      </div>
      <div className="flex items-center gap-1.5 text-[10px] text-mute">
        <GitBranch size={11} strokeWidth={1.75} /> Grounded in this sprint&apos;s data
      </div>
    </div>
  )
}
