import axios from 'axios'

// One shared Axios instance for the entire app. Base URL is empty/relative
// ("/api/...") on purpose - in dev, vite.config.js's proxy forwards those
// requests to the Spring Boot backend on port 8080; in production, the
// frontend and backend can either share an origin or this can be swapped
// for an absolute URL via an environment variable (VITE_API_BASE_URL) -
// see the fallback below.
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
})

// ----------------------------------------------------------------------
// Request interceptor: attach the JWT access token to every outgoing
// request automatically, so individual API calls never have to remember
// to do this themselves.
// ----------------------------------------------------------------------
api.interceptors.request.use((config) => {
  const accessToken = localStorage.getItem('accessToken')
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

// ----------------------------------------------------------------------
// Response interceptor: if any request fails with 401 (access token
// expired/invalid), try ONE silent refresh using the stored refresh
// token, then retry the original request with the new access token.
// This is what lets a user stay "logged in" across many 15-minute access
// token expiries without ever having to log in again manually - matching
// the backend's token rotation design (AuthService.refresh()).
//
// The `_retry` flag prevents an infinite loop: if the retried request
// ALSO comes back 401 (meaning the refresh token itself is invalid/
// expired/already used), we give up and force a real logout instead of
// retrying forever.
// ----------------------------------------------------------------------
let refreshInFlight = null

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config
    const status = error.response?.status

    const isAuthEndpoint = originalRequest.url?.includes('/auth/')

    if (status === 401 && !originalRequest._retry && !isAuthEndpoint) {
      originalRequest._retry = true
      const refreshToken = localStorage.getItem('refreshToken')

      if (!refreshToken) {
        return Promise.reject(error)
      }

      try {
        // Guard against multiple simultaneous 401s each starting their
        // own refresh call - share one in-flight refresh promise instead.
        if (!refreshInFlight) {
          refreshInFlight = axios
            .post('/api/auth/refresh', { refreshToken })
            .finally(() => {
              refreshInFlight = null
            })
        }

        const { data } = await refreshInFlight
        localStorage.setItem('accessToken', data.accessToken)
        localStorage.setItem('refreshToken', data.refreshToken)

        originalRequest.headers.Authorization = `Bearer ${data.accessToken}`
        return api(originalRequest)
      } catch (refreshError) {
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        localStorage.removeItem('user')
        // Force a full reload to the login page so all in-memory state
        // (React context, component state) is wiped along with storage.
        window.location.href = '/login'
        return Promise.reject(refreshError)
      }
    }

    return Promise.reject(error)
  },
)

export default api
