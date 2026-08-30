import { ChangeEvent, FormEvent, useEffect, useState } from 'react'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

interface Profile {
  id: string
  fullName: string
  email: string
  agencyName: string
  mobile: string
  agentNumber: string
  logoUploaded: boolean
}

export default function AgentAccountPage() {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [logoError, setLogoError] = useState<string | null>(null)
  const [logoJustUploaded, setLogoJustUploaded] = useState(false)

  useEffect(() => {
    apiClient.get<Profile>('/agent/me').then((res) => setProfile(res.data))
  }, [])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!profile) return
    await ensureCsrfCookie()
    const res = await apiClient.patch<Profile>('/agent/me', {
      fullName: profile.fullName,
      agencyName: profile.agencyName,
      mobile: profile.mobile,
    })
    setProfile(res.data)
    setMessage('הפרטים עודכנו בהצלחה')
  }

  async function handleLogoUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file || !profile) return
    setLogoError(null)
    try {
      await ensureCsrfCookie()
      const body = new FormData()
      body.append('file', file)
      await apiClient.post('/agent/logo', body, { headers: { 'Content-Type': 'multipart/form-data' } })
      setProfile({ ...profile, logoUploaded: true })
      setLogoJustUploaded(true)
    } catch {
      setLogoJustUploaded(false)
      setLogoError('העלאת הלוגו נכשלה. ודא שהקובץ תמונת PNG או JPEG עד 2MB.')
    }
  }

  if (!profile) {
    return <p>טוען...</p>
  }

  return (
    <div>
      <h1>מערכת טפסי ביטוח</h1>
      <h2>החשבון שלי</h2>
      <form onSubmit={handleSubmit}>
        <label>
          שם מלא
          <input value={profile.fullName} onChange={(e) => setProfile({ ...profile, fullName: e.target.value })} />
        </label>
        <label>
          שם הסוכנות
          <input value={profile.agencyName} onChange={(e) => setProfile({ ...profile, agencyName: e.target.value })} />
        </label>
        <label>
          נייד
          <input value={profile.mobile} onChange={(e) => setProfile({ ...profile, mobile: e.target.value })} />
        </label>
        <button type="submit">שמירה</button>
      </form>
      {message && <p role="status">{message}</p>}
      <label>
        לוגו
        <input type="file" accept="image/png,image/jpeg" onChange={handleLogoUpload} />
      </label>
      {logoError && <p role="alert">{logoError}</p>}
      {logoJustUploaded && <p>לוגו הועלה בהצלחה</p>}
      {profile.logoUploaded && <p>קיים לוגו שמור בחשבון</p>}
    </div>
  )
}
