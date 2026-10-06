/**
 * Labels are stored as a string[] on the task. The editor collects them as a
 * single comma-separated field, so parsing lives here to keep every call site
 * behaving identically.
 */
export function parseLabels(raw: string): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const part of raw.split(',')) {
    const label = part.trim()
    if (!label) continue
    const key = label.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    out.push(label)
  }
  return out
}

export function labelsToInput(labels: string[]): string {
  return labels.join(', ')
}