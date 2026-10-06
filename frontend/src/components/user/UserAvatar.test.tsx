import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { UserAvatar } from './UserAvatar'
import { useAvatar } from '../../hooks/useAvatar'

vi.mock('../../hooks/useAvatar', () => ({
  useAvatar: vi.fn(),
  invalidateAvatar: vi.fn(),
  clearAvatarCache: vi.fn(),
}))

describe('UserAvatar', () => {
  beforeEach(() => {
    vi.mocked(useAvatar).mockReset()
  })

  it('shows the person’s initials when they have no avatar', () => {
    vi.mocked(useAvatar).mockReturnValue(null)
    render(<UserAvatar memberId="u-7" name="Priya Verma" initials="PV" />)
    const chip = screen.getByText('PV')
    expect(chip).toHaveAttribute('title', 'Priya Verma')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('looks the avatar up by the numeric id behind the member id', () => {
    vi.mocked(useAvatar).mockReturnValue(null)
    render(<UserAvatar memberId="u-7" name="Priya Verma" initials="PV" />)
    expect(useAvatar).toHaveBeenCalledWith(7)
  })

  it('swaps in the picture once a URL is available', () => {
    vi.mocked(useAvatar).mockReturnValue('blob:avatar-7')
    render(<UserAvatar memberId="u-7" name="Priya Verma" initials="PV" />)
    const img = screen.getByRole('img', { name: 'Priya Verma' })
    expect(img).toHaveAttribute('src', 'blob:avatar-7')
    expect(screen.queryByText('PV')).not.toBeInTheDocument()
  })

  it('passes null through for ids that are not numeric', () => {
    vi.mocked(useAvatar).mockReturnValue(null)
    render(<UserAvatar memberId="local-1" name="Offline Ola" initials="OO" />)
    expect(useAvatar).toHaveBeenCalledWith(null)
  })
})
