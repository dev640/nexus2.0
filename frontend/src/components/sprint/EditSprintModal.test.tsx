import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EditSprintModal } from './EditSprintModal'
import { useAppStore } from '../../store/useAppStore'
import { apiDeleteSprint, apiUpdateSprint } from '../../lib/api'

vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>()
  return {
    ...actual,
    apiUpdateSprint: vi.fn(),
    apiDeleteSprint: vi.fn(),
  }
})

const sprintId = 's-3'

function seedStore() {
  useAppStore.setState({
    projects: [
      {
        id: 'p-1',
        name: 'AI Commerce Platform',
        description: '',
        status: 'ACTIVE',
        health: 'ON_TRACK',
        progress: 40,
        sprintNumber: 3,
        memberCount: 4,
      },
    ],
    sprints: [
      {
        id: sprintId,
        projectId: 'p-1',
        number: 3,
        goal: 'Ship checkout',
        startDate: '2026-10-01',
        endDate: '2026-10-14',
        committedPoints: 24,
        status: 'ACTIVE',
      },
    ],
    tasks: [],
  })
}

function goalInput() {
  return screen.getByDisplayValue('Ship checkout')
}

describe('EditSprintModal', () => {
  beforeEach(() => {
    seedStore()
    vi.mocked(apiUpdateSprint).mockReset()
    vi.mocked(apiDeleteSprint).mockReset()
  })

  it('renders nothing when the sprint no longer exists', () => {
    const { container } = render(<EditSprintModal sprintId="s-404" onClose={() => {}} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the project read-only, because sprints cannot change project', () => {
    render(<EditSprintModal sprintId={sprintId} onClose={() => {}} />)
    expect(screen.getByText('AI Commerce Platform')).toBeInTheDocument()
    expect(screen.getByText('(cannot be changed)')).toBeInTheDocument()
  })

  it('refuses to save without a goal', async () => {
    const onClose = vi.fn()
    render(<EditSprintModal sprintId={sprintId} onClose={onClose} />)

    fireEvent.change(goalInput(), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }))

    expect(await screen.findByText('Goal is required')).toBeInTheDocument()
    expect(apiUpdateSprint).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('refuses an end date before the start date', async () => {
    const onClose = vi.fn()
    render(<EditSprintModal sprintId={sprintId} onClose={onClose} />)

    fireEvent.change(screen.getByDisplayValue('2026-10-14'), {
      target: { value: '2026-09-01' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }))

    expect(await screen.findByText('End date must be after the start date')).toBeInTheDocument()
    expect(apiUpdateSprint).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('saves a valid edit and closes', async () => {
    vi.mocked(apiUpdateSprint).mockResolvedValue({
      id: 3,
      projectId: 1,
      projectName: 'AI Commerce Platform',
      number: 3,
      goal: 'Ship checkout',
      startDate: '2026-10-01',
      endDate: '2026-10-21',
      committedPoints: 30,
      status: 'ACTIVE',
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-10-01T00:00:00Z',
    })
    const onClose = vi.fn()
    render(<EditSprintModal sprintId={sprintId} onClose={onClose} />)

    fireEvent.change(screen.getByDisplayValue('2026-10-14'), {
      target: { value: '2026-10-21' },
    })
    fireEvent.change(screen.getByDisplayValue('24'), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(apiUpdateSprint).toHaveBeenCalledWith(3, {
      projectId: 1,
      goal: 'Ship checkout',
      startDate: '2026-10-01',
      endDate: '2026-10-21',
      committedPoints: 30,
      status: 'ACTIVE',
    })
    // The store keeps the server's response, not just the local form state.
    expect(useAppStore.getState().sprints[0].endDate).toBe('2026-10-21')
  })

  it('surfaces the server refusal when a save is rejected', async () => {
    vi.mocked(apiUpdateSprint).mockRejectedValue(
      new Error('A sprint cannot be moved to a different project'),
    )
    render(<EditSprintModal sprintId={sprintId} onClose={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }))

    expect(
      await screen.findByText('A sprint cannot be moved to a different project'),
    ).toBeInTheDocument()
  })

  it('requires a confirmation click before deleting, then deletes and closes', async () => {
    vi.mocked(apiDeleteSprint).mockResolvedValue(undefined)
    const onClose = vi.fn()
    render(<EditSprintModal sprintId={sprintId} onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(screen.getByText('Delete this sprint?')).toBeInTheDocument()
    expect(apiDeleteSprint).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Delete for good' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(apiDeleteSprint).toHaveBeenCalledWith(3)
    expect(useAppStore.getState().sprints).toHaveLength(0)
  })

  it('keeps the modal open and explains when the server refuses a delete', async () => {
    vi.mocked(apiDeleteSprint).mockRejectedValue(
      new Error('Sprint still has 4 tasks'),
    )
    const onClose = vi.fn()
    render(<EditSprintModal sprintId={sprintId} onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete for good' }))

    expect(await screen.findByText('Sprint still has 4 tasks')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(useAppStore.getState().sprints).toHaveLength(1)
  })
})
