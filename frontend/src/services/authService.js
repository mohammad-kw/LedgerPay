import api from './api'

/**
 * Thin wrapper functions around the three auth endpoints
 * (PROJECT_SPEC.md Section 5). Kept deliberately free of any React code -
 * these are plain functions that just call the backend and return/throw -
 * so AuthContext (or anything else) can call them without caring how the
 * HTTP request itself works.
 */

/**
 * POST /api/auth/register
 * Backend: creates the user + auto-creates their wallet (balance 0).
 * Returns { userId, name, email } - NOT tokens (see RegisterResponse.java's
 * javadoc: registering and logging in are deliberately separate steps).
 */
export function register({ name, email, password, phone }) {
  return api
    .post('/auth/register', { name, email, password, phone })
    .then((res) => res.data)
}

/**
 * POST /api/auth/login
 * Returns { accessToken, refreshToken, expiresInSeconds }.
 */
export function login({ email, password }) {
  return api.post('/auth/login', { email, password }).then((res) => res.data)
}

/**
 * POST /api/auth/refresh
 * Exchanges a still-valid refresh token for a brand new token pair
 * (the backend immediately revokes the old refresh token - "rotation").
 * Normally called automatically by the interceptor in api.js, but exposed
 * here too in case a component ever needs to trigger it directly.
 */
export function refresh(refreshToken) {
  return api.post('/auth/refresh', { refreshToken }).then((res) => res.data)
}
