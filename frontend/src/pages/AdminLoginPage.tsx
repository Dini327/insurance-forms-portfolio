import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

export default function AdminLoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await ensureCsrfCookie()
      await apiClient.post('/auth/admin/login', { email, password })
      navigate('/admin/agents')
    } catch {
      setError('אימייל או סיסמה שגויים')
    }
  }

  return (
    <div>
      <h1>מערכת טפסי ביטוח</h1>
      <h2>כניסת מנהל</h2>
      <form onSubmit={handleSubmit}>
        <label>
          אימייל
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          סיסמה
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {error && <p role="alert">{error}</p>}
        <button type="submit">כניסה</button>
      </form>
    </div>
  )
}
