import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import AgentAccountPage from './AgentAccountPage'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

// The backend/server boundary is mocked here on purpose -- this proves our own
// component logic (error state -> Hebrew alert, no false "success" message),
// not the network or the real /agent/logo endpoint's actual rejection behavior.
vi.mock('../api/apiClient', () => ({
  default: { get: vi.fn(), patch: vi.fn(), post: vi.fn() },
  ensureCsrfCookie: vi.fn(),
}))

const profile = {
  id: '11111111-1111-1111-1111-111111111111',
  fullName: 'רות כהן',
  email: 'ruth@example.com',
  agencyName: 'סוכנות כהן',
  mobile: '0501234567',
  agentNumber: 'AG-1',
  logoUploaded: false,
}

describe('AgentAccountPage logo upload failure', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: profile })
    vi.mocked(ensureCsrfCookie).mockResolvedValue(undefined)
  })

  it('shows a Hebrew error and does not claim success when the backend rejects the file', async () => {
    // The exact rejection reason (bad format, over the size limit, etc.) is the
    // backend's business -- this test only cares that a 400 of any kind surfaces
    // as a Hebrew message to the user, not as silence.
    vi.mocked(apiClient.post).mockRejectedValue({
      response: { status: 400, data: { error: 'File rejected' } },
    })

    render(<AgentAccountPage />)

    const fileInput = await screen.findByLabelText('לוגו')
    const file = new File(['file-contents'], 'logo.png', { type: 'image/png' })
    fireEvent.change(fileInput, { target: { files: [file] } })

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(/[֐-׿]/) // Hebrew, not the backend's English message
    expect(screen.queryByText('לוגו הועלה בהצלחה')).not.toBeInTheDocument()
  })

  it('hides the success message but keeps the "logo exists" state after a later failed upload', async () => {
    // Success message and "a logo exists" are two different pieces of state: the
    // message is per-attempt (logoJustUploaded) and must clear on the next failure,
    // while "exists" reflects profile.logoUploaded from the server and must NOT
    // clear, because a failed re-upload never deletes the previously stored logo
    // (LogoUploadService only replaces on a successful process()).
    vi.mocked(apiClient.post)
      .mockResolvedValueOnce({ data: { logoUrl: '/logos/22222222-2222-2222-2222-222222222222' } })
      .mockRejectedValueOnce({ response: { status: 400, data: { error: 'Unsupported format — upload a PNG or JPEG file' } } })

    render(<AgentAccountPage />)

    const fileInput = await screen.findByLabelText('לוגו')

    const goodFile = new File(['file-contents'], 'logo.png', { type: 'image/png' })
    fireEvent.change(fileInput, { target: { files: [goodFile] } })
    await screen.findByText('לוגו הועלה בהצלחה')
    await screen.findByText('קיים לוגו שמור בחשבון')

    const badFile = new File(['file-contents'], 'logo.gif', { type: 'image/gif' })
    fireEvent.change(fileInput, { target: { files: [badFile] } })
    await screen.findByRole('alert')

    expect(screen.queryByText('לוגו הועלה בהצלחה')).not.toBeInTheDocument()
    expect(screen.queryByText('קיים לוגו שמור בחשבון')).toBeInTheDocument()
  })
})
