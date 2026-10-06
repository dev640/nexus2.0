import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// React Testing Library only auto-cleans when test globals are exposed, and
// this project imports describe/it from 'vitest' explicitly instead.
afterEach(() => {
  cleanup()
})
