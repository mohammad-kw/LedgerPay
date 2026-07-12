import { useState } from "react";
import { initiateTopUp, mockTopUp } from "../services/walletService";

/**
 * The "Add money" dialog: the frontend half of the Razorpay top-up flow
 * (PROJECT_SPEC.md Section 5). It:
 *
 *   1. lets the user type (or quick-pick) a rupee amount,
 *   2. calls our backend POST /api/wallet/topup/initiate, which creates a
 *      Razorpay order + a CREATED transaction and returns the order details,
 *   3. opens the Razorpay Checkout popup (window.Razorpay, loaded from the
 *      script tag in index.html) using that order id.
 *
 * DELIBERATELY, this does NOT confirm the payment as successful. Razorpay's
 * `handler` callback fires in the browser after the user pays, but the
 * browser is not a trustworthy source (a user can tamper with it, or the
 * callback can simply never fire if they close the tab mid-payment). The
 * ONLY thing that marks a top-up SUCCESS and credits the wallet is Razorpay's
 * signed server-to-server WEBHOOK, which is built in Phase 3
 * (PROJECT_SPEC.md Section 3.4 - "webhooks as the source of truth, not the
 * browser redirect"). So here, once the popup closes we just refresh the
 * wallet and tell the user their payment is being confirmed.
 *
 * Preset amounts offered as quick-pick chips.
 *
 * @param {object}   props
 * @param {string}   props.userEmail  - prefilled into Checkout for convenience
 * @param {function} props.onClose    - close the modal (no top-up happened)
 * @param {function} props.onCompleted- called after the popup closes so the
 *                                       Dashboard can refresh balance/txns
 */
const PRESET_AMOUNTS = [100, 500, 1000, 2000];

// When true, the modal uses the backend's mock top-up (instant credit,
// no Razorpay) instead of the real Razorpay checkout. Enabled automatically
// in local `vite dev`, and in a deployed build when VITE_ENABLE_MOCK_TOPUP
// is set to "true" (used for the live demo, since the Razorpay account is
// not activated). The matching backend route requires MOCK_TOPUP_ENABLED=true.
const MOCK_MODE =
  import.meta.env.DEV || import.meta.env.VITE_ENABLE_MOCK_TOPUP === "true";

export default function AddMoneyModal({ userEmail, onClose, onCompleted }) {
  const [amount, setAmount] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  // Only allow digits + a single dot with up to 2 decimals, matching the
  // backend's DECIMAL(15,2) / @Digits(fraction = 2) constraint. This keeps
  // the input from ever holding something the server would 400 on.
  function handleAmountChange(e) {
    const value = e.target.value;
    if (value === "" || /^\d{0,7}(\.\d{0,2})?$/.test(value)) {
      setAmount(value);
    }
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setError("");

    // Client-side mirror of the backend validation (@DecimalMin 1.00 /
    // @DecimalMax 100000.00). The server is still the real authority - this
    // just gives instant feedback instead of a round-trip for obvious cases.
    const numeric = Number(amount);
    if (!amount || Number.isNaN(numeric) || numeric < 1) {
      setError("Enter an amount of at least ₹1.");
      return;
    }
    if (numeric > 100000) {
      setError("Amount can be at most ₹1,00,000.");
      return;
    }

    // Guard: if Razorpay's script failed to load (ad blocker, offline...),
    // there's no point calling the backend and creating a dangling order.
    if (typeof window.Razorpay !== "function") {
      setError(
        "Payment library failed to load. Check your connection and try again.",
      );
      return;
    }

    setSubmitting(true);
    try {
      // STEP 1: backend creates the Razorpay order + CREATED transaction.
      const order = await initiateTopUp(numeric);
      // STEP 2: hand the order to Razorpay Checkout and open the popup.
      openRazorpayCheckout(order);
    } catch (err) {
      const message =
        err.response?.data?.message ||
        "Couldn't start the payment. Please try again.";
      setError(message);
      setSubmitting(false);
    }
  }

  /**
   * DEV / DEMO ONLY handler. Instantly credits the wallet via the backend's
   * mock endpoint (POST /api/wallet/topup/mock), bypassing Razorpay entirely.
   * Rendered only under import.meta.env.DEV (see the button below), so it never
   * ships in a production build. Reuses the same amount validation as the real
   * flow, then reports the completed top-up back to the Dashboard.
   */
  async function handleMockTopUp() {
    setError("");
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
      const result = await mockTopUp(numeric);
      // The wallet is ALREADY credited (unlike the real flow), so we can show
      // the new balance immediately rather than a "confirming…" message.
      onCompleted({
        message: `₹${numeric.toLocaleString("en-IN")} added (dev mock). New balance: ₹${Number(
          result.balanceAfter,
        ).toLocaleString("en-IN")}.`,
      });
    } catch (err) {
      const message =
        err.response?.status === 404
          ? "Mock top-up isn't enabled on the server (set MOCK_TOPUP_ENABLED=true)."
          : err.response?.data?.message ||
            "Mock top-up failed. Please try again.";
      setError(message);
      setSubmitting(false);
    }
  }

  function openRazorpayCheckout(order) {
    const options = {
      // key = our PUBLIC Razorpay key id (rzp_test_...), returned by the
      // backend. It's safe to expose; the secret never leaves the server.
      key: order.razorpayKeyId,
      // amount/currency must match the order exactly; both come straight
      // from the backend (amount already in paise) so they can't drift.
      amount: order.amountInPaise,
      currency: order.currency,
      // order_id ties this checkout to the order we created server-side -
      // this is what makes the payment verifiable later via the webhook.
      order_id: order.razorpayOrderId,
      name: "LedgerPay",
      description: "Wallet top-up",
      prefill: {
        email: userEmail,
      },
      theme: {
        // Match our brand indigo (--color-primary).
        color: "#4f46e5",
      },
      // Fires after the user completes payment in the popup. NOTE: we do
      // NOT treat this as "confirmed money". The webhook (Phase 3) is the
      // real confirmation. We just close up and refresh - the transaction
      // stays CREATED until the webhook promotes it.
      handler: function () {
        setSubmitting(false);
        onCompleted({
          pending: true,
          message:
            "Payment received! We're confirming it - your balance will update once it's verified.",
        });
      },
      modal: {
        // Fires if the user dismisses the popup without paying. The order +
        // CREATED transaction still exist on our side (harmless; they'll
        // simply never be promoted to SUCCESS). We just re-enable the form.
        ondismiss: function () {
          setSubmitting(false);
        },
      },
    };

    const razorpay = new window.Razorpay(options);
    // If Razorpay reports a payment failure, surface it but keep our
    // transaction as-is (again, the webhook is the source of truth).
    razorpay.on("payment.failed", function () {
      setSubmitting(false);
      setError("The payment failed or was cancelled. You can try again.");
    });
    razorpay.open();
  }

  return (
    // Clicking the dim overlay closes the modal; stopPropagation on the panel
    // prevents a click INSIDE the panel from bubbling up and closing it.
    <div className="modal-overlay" onClick={submitting ? undefined : onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <div className="modal-title">Add money</div>
            <div className="modal-subtitle">
              Top up your wallet via Razorpay
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
            <label htmlFor="topup-amount">Amount</label>
            <div className="amount-input">
              <span className="amount-input-symbol">₹</span>
              <input
                id="topup-amount"
                className="input"
                type="text"
                inputMode="decimal"
                placeholder="0.00"
                value={amount}
                onChange={handleAmountChange}
                autoFocus
                disabled={submitting}
              />
            </div>

            <div className="amount-presets">
              {PRESET_AMOUNTS.map((preset) => (
                <button
                  key={preset}
                  type="button"
                  className={
                    Number(amount) === preset
                      ? "amount-preset amount-preset-active"
                      : "amount-preset"
                  }
                  onClick={() => setAmount(String(preset))}
                  disabled={submitting}
                >
                  ₹{preset.toLocaleString("en-IN")}
                </button>
              ))}
            </div>

            <p className="field-hint">
              Test mode - use Razorpay&apos;s test cards. No real money moves.
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
            {!MOCK_MODE && (
              <button
                type="submit"
                className="btn btn-primary btn-block"
                disabled={submitting}
              >
                {submitting ? "Opening…" : "Continue to payment"}
              </button>
            )}
          </div>

          {/*
           * Mock top-up path. Shown in local `vite dev` and in a deployed
           * build when VITE_ENABLE_MOCK_TOPUP=true (the live demo, since the
           * Razorpay account is not activated). It credits the wallet directly
           * via the backend, reusing the same ledger + state-machine logic as
           * the real webhook path. Backend route requires MOCK_TOPUP_ENABLED=true.
           */}
          {MOCK_MODE && (
            <div className="mock-topup">
              <button
                type="button"
                className="btn btn-primary btn-block"
                onClick={handleMockTopUp}
                disabled={submitting}
              >
                {submitting ? "Processing…" : "Add money"}
              </button>
              <p className="field-hint">
                Demo mode: credits your wallet instantly. The real flow uses
                Razorpay Checkout + a signature-verified webhook (disabled here
                because the Razorpay account isn't activated).
              </p>
            </div>
          )}
        </form>
      </div>
    </div>
  );
}
