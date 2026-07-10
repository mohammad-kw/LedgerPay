/**
 * Small display-formatting helpers shared across wallet UI. Kept as plain
 * functions (no React) so any component can use them.
 */

/**
 * Format a rupee amount for display, e.g. 1234.5 -> "₹1,234.50".
 *
 * The backend sends `amount`/`balance` as strings (it serializes Java
 * BigDecimal to preserve exact precision), so we parse to a Number only for
 * formatting via Intl.NumberFormat. For real money math you'd avoid float
 * entirely, but for read-only DISPLAY of already-computed values this is
 * safe and gives us proper thousands separators and two decimal places.
 */
export function formatCurrency(value, currency = "INR") {
  const amount = typeof value === "number" ? value : Number(value ?? 0);
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number.isFinite(amount) ? amount : 0);
}

/**
 * Format an ISO timestamp (e.g. "2026-07-09T12:30:00") into a compact,
 * human-friendly string like "9 Jul 2026, 12:30". Returns an em dash for
 * missing/invalid values so a row never renders "Invalid Date".
 */
export function formatDateTime(iso) {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

/**
 * Map a backend TransactionStatus (CREATED, PENDING, SUCCESS, FAILED,
 * REVERSED) to the CSS pill class defined in index.css. Anything
 * unrecognized falls back to the neutral grey pill.
 */
export function statusPillClass(status) {
  switch (status) {
    case "SUCCESS":
      return "pill pill-success";
    case "PENDING":
    case "CREATED":
      return "pill pill-pending";
    case "FAILED":
      return "pill pill-failed";
    default:
      return "pill pill-neutral";
  }
}

/** Human-friendly label for a transaction type + direction, e.g. "Top-up", "Sent", "Received". */
export function transactionLabel(type, direction) {
  if (type === "TOPUP") return "Top-up";
  if (type === "WITHDRAWAL") return "Withdrawal";
  if (type === "TRANSFER") return direction === "CREDIT" ? "Received" : "Sent";
  return type;
}
