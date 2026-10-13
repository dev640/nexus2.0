import { useEffect } from 'react'

/**
 * Sets the document title for the route being rendered.
 *
 * index.html carries the title and description that crawlers and link unfurls
 * see. This covers the other half: once client-side routing has taken over, the
 * tab would otherwise keep announcing whichever page was loaded first.
 */
export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = title
  }, [title])
}
