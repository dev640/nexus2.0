import { Link } from 'react-router-dom'
import { CONTACT_EMAIL, EFFECTIVE_DATE, GOVERNING_LAW, OPERATOR } from '../lib/legal'
import { LegalPage, Section } from '../components/legal/LegalPage'

/**
 * Terms written against the product as it behaves: accounts are provisioned by
 * an administrator rather than self-service, the AI output is not authoritative,
 * and the service is in beta. If any of that changes, this page changes with it.
 */
export function Terms() {
  return (
    <LegalPage title="Terms of Service" updated={EFFECTIVE_DATE}>
      <Section title="Agreeing to these terms">
        <p>
          These terms are an agreement between you and {OPERATOR} covering your use of this
          workspace. By signing in, you accept them. If you are using {OPERATOR} for an
          organisation, you are confirming you are allowed to accept them on its behalf.
        </p>
      </Section>

      <Section title="Accounts">
        <p>
          Accounts are created by an administrator of a workspace — there is no public sign-up. That
          administrator controls who has access and what role they hold. Keep your password to
          yourself: anything done through your account is treated as done by you, so tell an
          administrator immediately if you think someone else has it.
        </p>
      </Section>

      <Section title="Your content">
        <p>
          The projects, tasks, documents and messages you put into {OPERATOR} belong to you and your
          organisation. We store and display them so the service works, and that is the only
          permission you are giving us. We do not use your content to train AI models.
        </p>
        <p>
          You are responsible for having the right to upload what you upload, and for making sure
          your use of the workspace does not break anyone else&rsquo;s rights.
        </p>
      </Section>

      <Section title="AI features">
        <p>
          The Copilot and the AI assistant generate text you should treat as a draft, not as fact.
          They can be wrong, out of date, or confidently mistaken — check anything that matters
          against the underlying records before acting on it. Do not rely on them for legal,
          medical or financial decisions.
        </p>
        <p>
          When an AI provider is configured for your workspace, the text of your question and the
          workspace records selected to answer it are sent to that provider. If that is not
          acceptable for a given piece of content, do not ask about it — every other part of the
          product works without the AI features.
        </p>
      </Section>

      <Section title="Acceptable use">
        <p>Use the workspace for its purpose, and please do not:</p>
        <ul>
          <li>break the law, or try to break the service;</li>
          <li>access data belonging to someone who has not shared it with you;</li>
          <li>upload malware, or use the product to send spam or harass anyone;</li>
          <li>probe, scan or overload the API beyond the limits the service enforces;</li>
          <li>resell the service or present it as your own.</li>
        </ul>
        <p>
          We may suspend an account that puts the service or other customers at risk, and we will
          tell you why unless we are legally prevented from doing so.
        </p>
      </Section>

      <Section title="Availability, and changes to the product">
        <p>
          {OPERATOR} is offered as-is while in beta: features may change, and we do not promise any
          particular uptime. Keep your own copy of anything you cannot afford to lose.{' '}
          {OPERATOR} is free while in beta; if that changes we will give notice before charging
          anyone.
        </p>
      </Section>

      <Section title="Ending your use">
        <p>
          You may stop using {OPERATOR} at any time, and ask an administrator to delete your
          account. We may end your access if you breach these terms, or if we discontinue the
          service — in which case we will give reasonable notice so you can take your data with you.
        </p>
      </Section>

      <Section title="Limits on our liability">
        <p>
          To the extent the law allows, {OPERATOR} is not liable for indirect or consequential
          losses, or for lost profits, data or goodwill arising from your use of the service. Our
          total liability is limited to the greater of the amount you paid us in the previous twelve
          months, or the minimum the law requires. Nothing here excludes liability that cannot
          lawfully be excluded.
        </p>
      </Section>

      <Section title="Changes to these terms">
        <p>
          We will update the date at the top whenever these terms change, and give notice of any
          material change. Continuing to use the service afterwards means you accept the new terms.
        </p>
      </Section>

      <Section title="Governing law and contact">
        <p>
          These terms are governed by {GOVERNING_LAW}, and its courts have exclusive jurisdiction
          over any dispute. Questions about them:{' '}
          <a href={`mailto:${CONTACT_EMAIL}`} className="underline hover:text-ink">
            {CONTACT_EMAIL}
          </a>
          . See also our{' '}
          <Link to="/privacy" className="underline hover:text-ink">
            Privacy Policy
          </Link>
          .
        </p>
      </Section>
    </LegalPage>
  )
}
