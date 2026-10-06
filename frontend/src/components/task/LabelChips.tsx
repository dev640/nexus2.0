export function LabelChips({
  labels,
  max,
  className = '',
}: {
  labels: string[]
  /** Extra chips beyond this are summarised as "+N" instead of listed. */
  max?: number
  className?: string
}) {
  if (labels.length === 0) return null
  const shown = max ? labels.slice(0, max) : labels
  const overflow = labels.length - shown.length

  return (
    <span className={`flex flex-wrap items-center gap-1 ${className}`}>
      {shown.map((label) => (
        <span
          key={label}
          className="max-w-[10rem] truncate rounded border border-line bg-paper px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-mute"
        >
          {label}
        </span>
      ))}
      {overflow > 0 && (
        <span className="text-[10px] font-medium uppercase tracking-wide text-mute">+{overflow}</span>
      )}
    </span>
  )
}