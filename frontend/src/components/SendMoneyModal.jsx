import { useState } from "react";
import { sendMoney } from "../services/walletService";

/**
 * The "Send money" dialog: the frontend for wallet-to-wallet transfers
 * (PROJECT_SPEC.md Section 5). The user enters a recipient email + an amount,
 * and we POST to /api/wallet/transfer.
 *
 * Unlike the top-up flow, a transfer completes SYNCHRONOUSLY: when the request
 * succeeds, the backend has already moved the money (written the double-entry
 * ledger and updated both balances atomically). So on success we can
 * immediately tell the user it's done and refresh the dashboard - there's no
 * webhook to wait for here (the money never leaves our system, it just moves
 * between two of our wallets).
 *
 * Error handling maps the backend's precise statuses to friendly messages:
 *   400 -> invalid transfer (sending to yourself, unknown recipient, bad body)
 *   422 -> insufficient balance
 * In both cases the backend supplies a message we can show directly.
 *
 * @param {object}   props
 * @param {function} props.onClose     - close the modal (no transfer happened)
 * @param {function} props.onCompleted - called after a successful transfer so
 *                                        the Dashboard can refresh + show a note
 */
export default function SendMoneyModal({ onClose, onCompleted }) {
  const [receiverEmail, setReceiverEmail] = useState("");
  const [amount, setAmount] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  // Only allow digits + a single dot with up to 2 decimals, matching the
  // backend's DECIMAL(15,2) / @Digits(fraction = 2) constraint.
  function handleAmountChange(e) {
    const value = e.target.value;
    if (value === "" || /^\d{0,7}(\.\d{0,2})?$/.test(value)) {
      setAmount(value);
    }
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setError("");

    // Client-side mirror of the backend validation - instant feedback for the
    // obvious cases. The server remains the real authority.
    const trimmedEmail = receiverEmail.trim();
    if (!trimmedEmail) {
      setError("Enter the recipient's email.");
      return;
    }
    // Simple email shape check (the backend's @Email is authoritative).
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
      setError("Enter a valid email address.");
      return;
    }
    const numeric = Number(amount);
    if (!amount || Number.isNaN(numeric) || numeric < 1) {
      setError("Enter an amount of at least ₹1.");
      return;
    }
    if (numeric > 100000) {
      setError("Amount can be at most ₹1,00,000.");
      return;
    }

    setSubmitting(true);
    try {
      const result = await sendMoney(trimmedEmail, numeric);
      // Success: the money has already moved server-side. Hand the outcome
      // back so the Dashboard can refresh balances/history and show a note.
      onCompleted({
        message: `₹${numeric.toLocaleString("en-IN")} sent to ${trimmedEmail}.`,
        senderBalanceAfter: result.senderBalanceAfter,
      });
    } catch (err) {
      // The backend returns 400 (invalid transfer) or 422 (insufficient
      // balance) with a message field we can show directly.
      const message =
        err.response?.data?.message ||
        "Couldn't complete the transfer. Please try again.";
      setError(message);
      setSubmitting(false);
    }
  }

  return (
    // Clicking the dim overlay closes the modal; stopPropagation on the panel
    // prevents a click INSIDE the panel from bubbling up and closing it.
    <div className="modal-overlay" onClick={submitting ? undefined : onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <div className="modal-title">Send money</div>
            <div className="modal-subtitle">
              Transfer to another LedgerPay user
            </div>
          </div>
          <button
            className="modal-close"
            onClick={onClose}
            disabled={submitting}
            aria-label="Close"
          >
            ×
          </button>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        <form onSubmit={handleSubmit} noValidate>
          <div className="form-field">
            <label htmlFor="transfer-email">Recipient email</label>
            <input
              id="transfer-email"
              className="input"
              type="email"
              placeholder="name@example.com"
              value={receiverEmail}
              onChange={(e) => setReceiverEmail(e.target.value)}
              autoFocus
              disabled={submitting}
            />
          </div>

          <div className="form-field">
            <label htmlFor="transfer-amount">Amount</label>
            <div className="amount-input">
              <span className="amount-input-symbol">₹</span>
              <input
                id="transfer-amount"
                className="input"
                type="text"
                inputMode="decimal"
                placeholder="0.00"
                value={amount}
                onChange={handleAmountChange}
                disabled={submitting}
              />
            </div>
            <p className="field-hint">
              The recipient must already have a LedgerPay account.
            </p>
          </div>

          <div className="modal-actions">
            <button
              type="button"
              className="btn btn-secondary btn-block"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary btn-block"
              disabled={submitting}
            >
              {submitting ? "Sending…" : "Send money"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
