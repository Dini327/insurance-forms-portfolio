import axios from 'axios'

const apiClient = axios.create({
  baseURL: '/api',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

export async function ensureCsrfCookie(): Promise<void> {
  await apiClient.get('/csrf-token')
}

export default apiClient
