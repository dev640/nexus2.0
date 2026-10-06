import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { LabelChips } from './LabelChips'

describe('LabelChips', () => {
  it('renders nothing when the task has no labels', () => {
    const { container } = render(<LabelChips labels={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders every label when no limit is given', () => {
    render(<LabelChips labels={['billing', 'mobile', 'urgent']} />)
    expect(screen.getByText('billing')).toBeInTheDocument()
    expect(screen.getByText('mobile')).toBeInTheDocument()
    expect(screen.getByText('urgent')).toBeInTheDocument()
    expect(screen.queryByText(/^\+\d+$/)).not.toBeInTheDocument()
  })

  it('summarises labels beyond the max instead of listing them', () => {
    render(<LabelChips labels={['billing', 'mobile', 'urgent', 'deprecation']} max={2} />)
    expect(screen.getByText('billing')).toBeInTheDocument()
    expect(screen.getByText('mobile')).toBeInTheDocument()
    expect(screen.queryByText('urgent')).not.toBeInTheDocument()
    expect(screen.queryByText('deprecation')).not.toBeInTheDocument()
    expect(screen.getByText('+2')).toBeInTheDocument()
  })

  it('shows no overflow chip when everything fits within the max', () => {
    render(<LabelChips labels={['billing', 'mobile']} max={3} />)
    expect(screen.getByText('billing')).toBeInTheDocument()
    expect(screen.getByText('mobile')).toBeInTheDocument()
    expect(screen.queryByText(/^\+\d+$/)).not.toBeInTheDocument()
  })
})
