import { useState } from "react";
import { useNavigate, useLocation, Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import AuthLayout from "../components/AuthLayout";

export default function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  // Register navigates here with { justRegistered: true } after a
  // successful signup (see Register.jsx) - show a friendly confirmation.
  const justRegistered = location.state?.justRegistered;

  async function handleSubmit(e) {
    e.preventDefault();
    setError("");
    setSubmitting(true);

    try {
      await login(email, password);
      navigate("/dashboard");
    } catch (err) {
      // GlobalExceptionHandler returns 401 with a plain `message` field
      // for wrong credentials (BadCredentialsException). Fall back to a
      // generic message if the backend is unreachable entirely.
      const message =
        err.response?.data?.message || "Login failed. Please try again.";
      setError(message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      eyebrow="Welcome back"
      title="Log in to LedgerPay"
      subtitle="Enter your credentials to access your wallet."
    >
      {justRegistered && (
        <div className="alert alert-success">
          Account created successfully. Please log in to continue.
        </div>
      )}

      {error && <div className="alert alert-error">{error}</div>}

      <form onSubmit={handleSubmit} noValidate>
        <div className="form-field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            className="input"
            type="email"
            placeholder="you@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            required
          />
        </div>

        <div className="form-field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            className="input"
            type="password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </div>

        <button
          type="submit"
          className="btn btn-primary btn-block btn-lg"
          disabled={submitting}
        >
          {submitting ? "Logging in…" : "Log In"}
        </button>
      </form>

      <div className="auth-footer">
        Don&apos;t have an account? <Link to="/register">Create one</Link>
      </div>
    </AuthLayout>
  );
}
