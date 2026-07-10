import { Routes, Route, Navigate } from "react-router-dom";
import Login from "./pages/Login";
import Register from "./pages/Register";
import Dashboard from "./pages/Dashboard";
import AdminDashboard from "./pages/AdminDashboard";
import ProtectedRoute from "./components/ProtectedRoute";

/**
 * The full route table for the app (PROJECT_SPEC.md Section 10, Phase 1:
 * "Set up React project with routing"). Only three real pages exist so
 * far - Login, Register, and the protected Dashboard - matching this
 * phase's "auth only, no wallet features yet" scope exactly.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <Dashboard />
          </ProtectedRoute>
        }
      />

      <Route
        path="/admin"
        element={
          <ProtectedRoute requireAdmin>
            <AdminDashboard />
          </ProtectedRoute>
        }
      />

      {/* Visiting "/" (or any unknown path) just sends you to the
          dashboard, which will itself bounce you to /login if you're not
          actually authenticated yet - see ProtectedRoute. */}
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
