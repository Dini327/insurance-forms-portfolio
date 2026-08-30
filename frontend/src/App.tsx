import { Route, Routes, Navigate } from 'react-router-dom'
import AgentLoginPage from './pages/AgentLoginPage'
import AdminLoginPage from './pages/AdminLoginPage'
import AgentRegisterPage from './pages/AgentRegisterPage'
import AgentAccountPage from './pages/AgentAccountPage'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<AgentLoginPage />} />
      <Route path="/admin/login" element={<AdminLoginPage />} />
      <Route path="/register" element={<AgentRegisterPage />} />
      <Route path="/account" element={<AgentAccountPage />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
