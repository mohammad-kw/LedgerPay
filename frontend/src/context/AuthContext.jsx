import { createContext, useContext, useEffect, useState } from "react";
import * as authService from "../services/authService";

const AuthContext = createContext(null);

/**
 * Decodes a JWT's payload (the middle of its three dot-separated parts)
 * WITHOUT verifying its signature. This is safe and normal to do on the
 * frontend because:
 *   1. A JWT's payload is just Base64-encoded JSON - never encrypted - so
 *      there is no secret being "unlocked" here, just plain reading.
 *   2. We are not using this decoded data to make any security DECISION
 *      (like "let this user in") - we only use it to read {uid, email}
 *      to show the current user's identity in the UI. The one place that
 *      actually matters for security - verifying the signature - only
 *      ever happens on the server (see JwtService.parseAndValidate),
 *      which re-checks every single request no matter what this decoded
 *      value says.
 */
function decodeJwtPayload(token) {
  try {
    const base64Payload = token.split(".")[1];
    const jsonPayload = atob(
      base64Payload.replace(/-/g, "+").replace(/_/g, "/"),
    );
    return JSON.parse(jsonPayload);
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  // `loading` starts true so ProtectedRoute (see components/ProtectedRoute.jsx)
  // can wait for our "am I already logged in from a previous visit?" check
  // below to finish, instead of momentarily flashing the login page before
  // redirecting back to the dashboard.
  const [loading, setLoading] = useState(true);

  // On first app load, check localStorage for tokens left over from a
  // previous visit (e.g. the user refreshed the page, or closed and
  // reopened the tab). If found, rebuild the `user` state from the
  // access token's own claims - no extra network call needed.
  useEffect(() => {
    const accessToken = localStorage.getItem("accessToken");
    const storedUser = localStorage.getItem("user");

    if (accessToken && storedUser) {
      setUser(JSON.parse(storedUser));
    }
    setLoading(false);
  }, []);

  /**
   * Calls POST /api/auth/login, then stores both tokens plus a small
   * { userId, email } user object derived from the access token's claims
   * in localStorage (so it survives a page refresh) and in state (so the
   * UI re-renders immediately).
   */
  async function login(email, password) {
    const data = await authService.login({ email, password });
    const claims = decodeJwtPayload(data.accessToken);
    // `role` comes from the JWT's "role" claim (see JwtService). It's used
    // ONLY for client-side routing (show the admin dashboard or not) - the
    // backend independently re-checks the role on every /api/admin request,
    // so a tampered token can never actually grant admin access to data.
    const loggedInUser = {
      userId: claims?.uid,
      email: claims?.sub,
      role: claims?.role || "USER",
    };

    localStorage.setItem("accessToken", data.accessToken);
    localStorage.setItem("refreshToken", data.refreshToken);
    localStorage.setItem("user", JSON.stringify(loggedInUser));
    setUser(loggedInUser);

    return loggedInUser;
  }

  /** Calls POST /api/auth/register. Does NOT log the user in automatically - see RegisterResponse.java's javadoc for why registration and login are deliberately separate steps. */
  async function register({ name, email, password, phone }) {
    return authService.register({ name, email, password, phone });
  }

  /** Clears all stored auth state. No server call needed to "log out" of a stateless JWT - simply forgetting it client-side is enough to stop sending it. */
  function logout() {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("refreshToken");
    localStorage.removeItem("user");
    setUser(null);
  }

  const value = {
    user,
    isAuthenticated: !!user,
    isAdmin: user?.role === "ADMIN",
    loading,
    login,
    register,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** Convenience hook so components just call useAuth() instead of useContext(AuthContext) everywhere. */
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
