import { ChangeEvent, FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

const emptyForm = {
  fullName: '', email: '', password: '', israeliId: '', agencyName: '', mobile: '', agentNumber: '',
}

export default function AgentRegisterPage() {
  const [form, setForm] = useState(emptyForm)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const navigate = useNavigate()

  function update(field: keyof typeof form) {
    return (e: ChangeEvent<HTMLInputElement>) => setForm({ ...form, [field]: e.target.value })
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setErrors({})
    try {
      await ensureCsrfCookie()
      await apiClient.post('/auth/agent/register', form)
      navigate('/login')
    } catch (err: any) {
      setErrors(err?.response?.data?.errors ?? { general: 'ההרשמה נכשלה' })
    }
  }

  return (
    <div>
      <h1>מערכת טפסי ביטוח</h1>
      <h2>הרשמת סוכן</h2>
      <form onSubmit={handleSubmit}>
        <label>
          שם מלא
          <input value={form.fullName} onChange={update('fullName')} required />
        </label>
        {errors.fullName && <p role="alert">{errors.fullName}</p>}
        <label>
          אימייל
          <input type="email" value={form.email} onChange={update('email')} required />
        </label>
        {errors.email && <p role="alert">{errors.email}</p>}
        <label>
          סיסמה
          <input type="password" value={form.password} onChange={update('password')} required minLength={10} />
        </label>
        {errors.password && <p role="alert">{errors.password}</p>}
        <label>
          תעודת זהות
          <input value={form.israeliId} onChange={update('israeliId')} required />
        </label>
        {errors.israeliId && <p role="alert">{errors.israeliId}</p>}
        <label>
          שם הסוכנות
          <input value={form.agencyName} onChange={update('agencyName')} required />
        </label>
        <label>
          נייד
          <input value={form.mobile} onChange={update('mobile')} required />
        </label>
        <label>
          מספר סוכן
          <input value={form.agentNumber} onChange={update('agentNumber')} required />
        </label>
        {errors.agentNumber && <p role="alert">{errors.agentNumber}</p>}
        <button type="submit">הרשמה</button>
      </form>
    </div>
  )
}
