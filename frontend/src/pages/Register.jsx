import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import AuthLayout from "../components/AuthLayout";

export default function Register() {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [phone, setPhone] = useState("");

  const [fieldErrors, setFieldErrors] = useState({});
  const [formError, setFormError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const { register } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(e) {
    e.preventDefault();
    setFormError("");
    setFieldErrors({});
    setSubmitting(true);

    try {
      await register({ name, email, password, phone: phone || undefined });
      // RegisterResponse deliberately does NOT include tokens (see its
      // javadoc) - registration and login are separate steps - so we send
      // the user to the login page rather than straight to the dashboard.
      navigate("/login", { state: { justRegistered: true } });
    } catch (err) {
      const data = err.response?.data;

      if (err.response?.status === 400 && data?.fieldErrors) {
        // MethodArgumentNotValidException case: turn the fieldErrors array
        // into a { fieldName: message } map so each <input> below can show
        // its own specific problem.
        const map = {};
        data.fieldErrors.forEach((fe) => {
          map[fe.field] = fe.message;
        });
        setFieldErrors(map);
      } else if (err.response?.status === 409) {
        // DuplicateEmailException case.
        setFormError(
          data?.message || "An account with this email already exists.",
        );
      } else {
        setFormError(data?.message || "Registration failed. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      eyebrow="Get started"
      title="Create your account"
      subtitle="Set up your LedgerPay wallet in under a minute."
    >
      {formError && <div className="alert alert-error">{formError}</div>}

      <form onSubmit={handleSubmit} noValidate>
        <div className="form-field">
          <label htmlFor="name">Full name</label>
          <input
            id="name"
            className={`input${fieldErrors.name ? " input-error" : ""}`}
            type="text"
            placeholder="Ada Lovelace"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoComplete="name"
            required
          />
          {fieldErrors.name && (
            <small className="field-error">{fieldErrors.name}</small>
          )}
        </div>

        <div className="form-field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            className={`input${fieldErrors.email ? " input-error" : ""}`}
            type="email"
            placeholder="you@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            required
          />
          {fieldErrors.email && (
            <small className="field-error">{fieldErrors.email}</small>
          )}
        </div>

        <div className="form-field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            className={`input${fieldErrors.password ? " input-error" : ""}`}
            type="password"
            placeholder="At least 8 characters"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            minLength={8}
            maxLength={72}
            required
          />
          {fieldErrors.password ? (
            <small className="field-error">{fieldErrors.password}</small>
          ) : (
            <small className="field-hint">Use 8 or more characters.</small>
          )}
        </div>

        <div className="form-field">
          <label htmlFor="phone">Phone (optional)</label>
          <input
            id="phone"
            className={`input${fieldErrors.phone ? " input-error" : ""}`}
            type="tel"
            placeholder="9876543210"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            autoComplete="tel"
            maxLength={15}
          />
          {fieldErrors.phone && (
            <small className="field-error">{fieldErrors.phone}</small>
          )}
        </div>

        <button
          type="submit"
          className="btn btn-primary btn-block btn-lg"
          disabled={submitting}
        >
          {submitting ? "Creating account…" : "Create account"}
        </button>
      </form>

      <div className="auth-footer">
        Already have an account? <Link to="/login">Log in</Link>
      </div>
    </AuthLayout>
  );
}
