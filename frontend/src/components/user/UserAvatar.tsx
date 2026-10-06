import { useAvatar } from '../../hooks/useAvatar'
import { numericUserId } from '../../store/useAppStore'

type Size = 'xs' | 'sm' | 'md'

const sizeClass: Record<Size, string> = {
  xs: 'h-5 w-5 text-[9px]',
  sm: 'h-6 w-6 text-[9px]',
  md: 'h-14 w-14 text-base',
}

interface Props {
  /** Member id in store form, e.g. "u-7". */
  memberId: string
  name: string
  initials: string
  size?: Size
  className?: string
}

/**
 * A user's picture, falling back to their initials.
 *
 * The initials are always rendered, not just while loading, because a user with
 * no avatar will never produce a URL to swap in — and a chip that flashes an
 * empty circle on every page load reads as broken.
 */
export function UserAvatar({ memberId, name, initials, size = 'sm', className = '' }: Props) {
  const url = useAvatar(numericUserId(memberId))
  const base =
    `flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-ink font-medium text-white ${sizeClass[size]} ${className}`

  if (url) {
    return <img src={url} alt={name} title={name} className={`${base} object-cover`} />
  }
  return (
    <span className={base} title={name}>
      {initials}
    </span>
  )
}