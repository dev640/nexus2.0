import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AlertToasts } from './AlertToasts'
import { useAppStore } from '../../store/useAppStore'

beforeEach(() => {
  useAppStore.setState({ activeAlerts: [] })
})

afterEach(() => {
  vi.useRealTimers()
})

/**
 * Toasts render inside the app shell, so they need the router the shell
 * provides: a click on one navigates.
 */
function renderToasts() {
  return render(
    <MemoryRouter initialEntries={['/home']}>
      <Routes>
        <Route path="/home" element={<AlertToasts />} />
        <Route path="/board" element={<div>Board page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AlertToasts', () => {
  it('shows nothing while there is nothing to say', () => {
    const { container } = renderToasts()
    expect(container).toBeEmptyDOMElement()
  })

  it('shows an alert with its text and dismisses it on request', () => {
    useAppStore.setState({
      activeAlerts: [{ id: 1, title: 'Nexus', body: 'Devendra added the task "Ship it"', link: null }],
    })
    renderToasts()

    expect(screen.getByRole('alert')).toHaveTextContent('Devendra added the task "Ship it"')

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss notification' }))

    expect(useAppStore.getState().activeAlerts).toEqual([])
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('opens the page an alert points at when the toast is clicked', () => {
    useAppStore.setState({
      activeAlerts: [
        { id: 3, title: 'Nexus', body: 'You were assigned to "Ship it"', link: '/board?task=42' },
      ],
    })
    renderToasts()

    fireEvent.click(screen.getByRole('alert').querySelector('button')!)

    expect(screen.getByText('Board page')).toBeInTheDocument()
    // The alert has been acted on, so it does not linger over the page it opened.
    expect(useAppStore.getState().activeAlerts).toEqual([])
  })

  it('stays inert when an alert has nowhere to go', () => {
    useAppStore.setState({
      activeAlerts: [{ id: 4, title: 'Nexus', body: 'Something happened', link: null }],
    })
    renderToasts()

    const open = screen.getByRole('alert').querySelector('button') as HTMLButtonElement
    expect(open).toBeDisabled()
  })

  it('dismisses itself after a few seconds', () => {
    vi.useFakeTimers()
    useAppStore.setState({
      activeAlerts: [{ id: 2, title: 'Vidhi in #general', body: 'Sprint review at 4', link: null }],
    })
    renderToasts()

    act(() => {
      vi.advanceTimersByTime(6000)
    })

    expect(screen.queryByRole('alert')).toBeNull()
    expect(useAppStore.getState().activeAlerts).toEqual([])
  })
})
