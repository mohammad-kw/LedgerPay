import { LogoGlyph, CheckIcon } from "./icons";

/**
 * The shared two-column shell for the Login and Register pages: a branded
 * gradient panel on the left (hidden on small screens - see the .auth-brand
 * rules in index.css) and the actual form on the right.
 *
 * Keeping this in one component means both auth pages stay visually
 * identical and we don't repeat the marketing/brand markup twice. The form
 * itself is passed in as `children`, and the small heading block above the
 * form is driven by the `eyebrow`/`title`/`subtitle` props.
 */
export default function AuthLayout({ eyebrow, title, subtitle, children }) {
  return (
    <div className="auth-shell">
      <div className="auth-card">
        {/* ---- Left: brand / value-proposition panel ---- */}
        <aside className="auth-brand">
          <div className="auth-brand-logo">
            <LogoGlyph size={24} />
            <span>LedgerPay</span>
          </div>

          <div>
            <h2 className="auth-brand-headline">Money that always adds up.</h2>
            <p className="auth-brand-sub">
              A wallet built on double-entry ledgers, idempotent transfers, and
              daily reconciliation - so every rupee is traceable.
            </p>

            <ul className="auth-brand-points">
              <li>
                <CheckIcon /> Instant top-ups via Razorpay
              </li>
              <li>
                <CheckIcon /> Send money to anyone in seconds
              </li>
              <li>
                <CheckIcon /> A full, auditable transaction history
              </li>
            </ul>
          </div>

          <span
            style={{
              position: "relative",
              zIndex: 1,
              fontSize: "0.8rem",
              color: "rgba(255,255,255,0.7)",
            }}
          >
            Test mode - no real money is ever moved.
          </span>
        </aside>

        {/* ---- Right: the form ---- */}
        <section className="auth-form-panel">
          <header className="auth-header">
            {eyebrow && <div className="auth-eyebrow">{eyebrow}</div>}
            <h1 className="auth-title">{title}</h1>
            {subtitle && <p className="auth-subtitle">{subtitle}</p>}
          </header>

          {children}
        </section>
      </div>
    </div>
  );
}
