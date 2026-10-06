import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

interface MarkdownProps {
  children: string
}

/**
 * Renders AI answers as Markdown: headings, lists, tables (GFM), fenced code
 * blocks, blockquotes and links — styled with the project's design tokens.
 */
export function Markdown({ children }: MarkdownProps) {
  return (
    <div className="text-sm leading-relaxed text-ink [&_a]:text-info [&_a]:underline [&_li]:my-0.5 [&_ol]:my-2 [&_ol]:list-decimal [&_ol]:pl-5 [&_p]:my-2 [&_p:first-child]:mt-0 [&_p:last-child]:mb-0 [&_ul]:my-2 [&_ul]:list-disc [&_ul]:pl-5">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ children }) => (
            <h3 className="mb-2 mt-3 text-base font-semibold tracking-tight first:mt-0">{children}</h3>
          ),
          h2: ({ children }) => (
            <h4 className="mb-2 mt-3 text-sm font-semibold tracking-tight first:mt-0">{children}</h4>
          ),
          h3: ({ children }) => (
            <h5 className="mb-1 mt-3 text-sm font-semibold first:mt-0">{children}</h5>
          ),
          pre: ({ children }) => (
            <pre className="mb-2 overflow-x-auto rounded-md border border-line bg-ink p-3 text-xs leading-relaxed text-white">
              {children}
            </pre>
          ),
          code: ({ className, children }) =>
            className ? (
              <code className={className}>{children}</code>
            ) : (
              <code className="rounded bg-paper px-1 py-0.5 text-[12px]">{children}</code>
            ),
          table: ({ children }) => (
            <div className="mb-2 overflow-x-auto">
              <table className="w-full border-collapse text-xs">{children}</table>
            </div>
          ),
          thead: ({ children }) => <thead className="bg-paper">{children}</thead>,
          th: ({ children }) => (
            <th className="border border-line px-2 py-1 text-left font-semibold">{children}</th>
          ),
          td: ({ children }) => <td className="border border-line px-2 py-1 align-top">{children}</td>,
          blockquote: ({ children }) => (
            <blockquote className="my-2 border-l-2 border-line pl-3 italic text-mute">
              {children}
            </blockquote>
          ),
          hr: () => <hr className="my-3 border-line" />,
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noreferrer noopener">
              {children}
            </a>
          ),
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  )
}
