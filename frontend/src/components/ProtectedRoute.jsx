import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

/**
 * Wrap any route element that should require login in this component.
 * Usage (see App.jsx):
 *   <Route path="/dashboard" element={<ProtectedRoute><Dashboard /></ProtectedRoute>} />
 *
 * Pass `requireAdmin` to additionally gate a route on the ADMIN role:
 *   <ProtectedRoute requireAdmin><AdminDashboard /></ProtectedRoute>
 * A logged-in non-admin who tries to reach an admin route is bounced to
 * their normal dashboard rather than the login page (they ARE authenticated,
 * just not authorized). This is only a UX guard - the backend still enforces
 * the real authorization on every /api/admin request.
 *
 * While AuthContext is still checking localStorage on first load
 * (`loading === true`), we render nothing rather than immediately
 * redirecting - otherwise a logged-in user refreshing the page would see
 * a flash of the login page before bouncing back to the dashboard.
 */
export default function ProtectedRoute({ children, requireAdmin = false }) {
  const { isAuthenticated, isAdmin, loading } = useAuth();

  if (loading) {
    return null;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (requireAdmin && !isAdmin) {
    return <Navigate to="/dashboard" replace />;
  }

  return children;
}
