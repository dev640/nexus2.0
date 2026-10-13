import { Link } from 'react-router-dom'
import { CONTACT_EMAIL, EFFECTIVE_DATE, OPERATOR } from '../lib/legal'
import { LegalPage, Section } from '../components/legal/LegalPage'

/**
 * The policy describes what this application actually does — the fields it
 * stores, the third parties it calls, and what it keeps in the browser — so it
 * can be edited against the code rather than kept as aspirational copy. Anything
 * that changes those facts (a new integration, a new cookie) changes this page.
 */
export function Privacy() {
  return (
    <LegalPage title="Privacy Policy" updated={EFFECTIVE_DATE}>
      <Section title="The short version">
        <p>
          {OPERATOR} is a workspace for planning software: you sign in, and the projects, tasks,
          docs and messages you write are stored so your team can see them. We do not sell your
          data, we do not run advertising trackers, and the AI features only see the workspace
          records they need to answer the question you asked.
        </p>
      </Section>

      <Section title="What we collect">
        <p>
          <strong>Account details.</strong> Your name, email address and role. Administrators can
          also store an employee code and a profile picture. Passwords are stored only as a
          one-way bcrypt hash — never as text, and never in a form we can read back.
        </p>
        <p>
          <strong>Workspace content.</strong> What you and your team put in the product: projects,
          sprints, tasks and their comments, wiki pages, whiteboard notes, chat channels, direct
          messages, notifications, and the calendar events derived from them.
        </p>
        <p>
          <strong>AI questions and answers.</strong> When you use the Copilot or the Nexus AI
          assistant, your question, the workspace records retrieved to answer it, and the answer
          are stored so the conversation history works.
        </p>
        <p>
          <strong>Technical data.</strong> Your IP address and request timestamps, used for
          rate limiting, brute-force protection and diagnosing failures. Session tokens are issued
          to your browser so you stay signed in.
        </p>
      </Section>

      <Section title="Why we hold it">
        <ul>
          <li>To run the service: showing your workspace to you and your team.</li>
          <li>To keep it secure: authentication, abuse and rate limiting, and audit trails.</li>
          <li>To answer your questions when you use an AI feature.</li>
          <li>To provide support when you ask for it.</li>
        </ul>
        <p>
          Where consent is the right basis — analytics, for example — we ask for it and you can
          withdraw it at any time.
        </p>
      </Section>

      <Section title="Who else processes it">
        <p>
          We use a small number of infrastructure providers to run the service, and they handle
          data only on our instructions:
        </p>
        <ul>
          <li>
            <strong>Hosting and database.</strong> The application and its PostgreSQL database run
            on managed cloud infrastructure.
          </li>
          <li>
            <strong>Identity.</strong> When single sign-on is enabled, authentication is handled by
            Supabase, which stores the credentials for the accounts you use to sign in.
          </li>
          <li>
            <strong>AI providers.</strong> If an AI provider is configured for your workspace, the
            text of your question and the retrieved workspace records are sent to that provider to
            generate the answer. The application works without this: leave the AI key unset and the
            assistant answers from your own workspace data without calling anyone.
          </li>
          <li>
            <strong>Analytics.</strong> If enabled, we use a cookieless analytics service that
            records page views in aggregate. It does not build a profile of you, and it is loaded
            only after you accept it.
          </li>
        </ul>
        <p>We do not sell personal data, and we do not share it for advertising.</p>
      </Section>

      <Section title="Cookies and browser storage">
        <p>
          The application does not use advertising or tracking cookies. It does keep a small amount
          of data in your browser&rsquo;s local storage, because the product cannot work without it:
        </p>
        <ul>
          <li>
            <strong>Sign-in tokens</strong> (<code>nexus-auth-token</code>,{' '}
            <code>nexus-auth-refresh-token</code>) so a page reload does not sign you out.
          </li>
          <li>
            <strong>Your preferences</strong> (<code>nexus-alert-preferences</code>) such as whether
            you want desktop alerts.
          </li>
        </ul>
        <p>
          These are strictly necessary and are not shared with anyone. Optional analytics is a
          separate choice: it stays switched off until you accept it, and you can change your mind
          by clearing the site&rsquo;s data in your browser.
        </p>
      </Section>

      <Section title="How long we keep it">
        <p>
          Workspace content is kept for as long as your workspace is active. When an administrator
          deletes an account, the account record is removed; content that person created may remain
          in the workspace because it belongs to the team&rsquo;s shared history. Ask us and we will
          delete your personal data and anything attributable to you.
        </p>
      </Section>

      <Section title="Your rights">
        <p>
          Depending on where you live, you may have the right to see the data we hold about you,
          correct it, delete it, receive a copy in a portable format, or object to certain
          processing. You can update your own name and preferences in Settings, and an
          administrator can correct your role and details. For anything else, write to{' '}
          <a href={`mailto:${CONTACT_EMAIL}`} className="underline hover:text-ink">
            {CONTACT_EMAIL}
          </a>{' '}
          and we will respond within the period the law allows.
        </p>
      </Section>

      <Section title="Security">
        <p>
          Passwords are hashed with bcrypt, sessions use signed tokens, and the API is rate limited
          and served over HTTPS. No system is perfect: if we ever become aware of a breach
          affecting your data, we will tell you and the relevant authority as required.
        </p>
      </Section>

      <Section title="Changes to this policy">
        <p>
          If this policy changes in a way that affects you, we will say so here and update the date
          at the top. Continuing to use {OPERATOR} after a change means you accept the updated
          policy.
        </p>
      </Section>

      <Section title="Contact">
        <p>
          Questions, requests and complaints:{' '}
          <a href={`mailto:${CONTACT_EMAIL}`} className="underline hover:text-ink">
            {CONTACT_EMAIL}
          </a>
          . See also our{' '}
          <Link to="/terms" className="underline hover:text-ink">
            Terms of Service
          </Link>
          .
        </p>
      </Section>
    </LegalPage>
  )
}
