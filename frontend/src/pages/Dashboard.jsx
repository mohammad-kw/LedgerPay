import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { getBalance, getTransactions } from "../services/walletService";
import AddMoneyModal from "../components/AddMoneyModal";
import SendMoneyModal from "../components/SendMoneyModal";
import {
  formatCurrency,
  formatDateTime,
  statusPillClass,
  transactionLabel,
} from "../utils/format";
import {
  LogoGlyph,
  LogoutIcon,
  TopUpIcon,
  TransferIcon,
  ReceiptIcon,
} from "../components/icons";

/**
 * The authenticated home screen. It fetches the logged-in user's wallet
 * balance and recent transactions (GET /api/wallet/balance and
 * /api/wallet/transactions - PROJECT_SPEC.md Section 5) on mount and renders
 * them. "Add money" opens the Razorpay top-up flow (AddMoneyModal); "Send
 * money" opens the wallet-to-wallet transfer flow (SendMoneyModal).
 */
export default function Dashboard() {
  const { user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();
  const [balance, setBalance] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [totalTransactions, setTotalTransactions] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // ---- Top-up (Add money) UI state ----
  const [showAddMoney, setShowAddMoney] = useState(false);
  // ---- Transfer (Send money) UI state ----
  const [showSendMoney, setShowSendMoney] = useState(false);
  // A transient banner shown after returning from Razorpay Checkout (e.g.
  // "payment received, confirming..."). Cleared when the user dismisses it.
  const [notice, setNotice] = useState("");

  // Load balance and the first page of transactions together. useCallback
  // so the reference is stable and we can also call it from a "Retry" button.
  const loadWallet = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      // Fire both requests in parallel - they're independent, so there's no
      // reason to wait for one before starting the other.
      const [balanceData, txnPage] = await Promise.all([
        getBalance(),
        getTransactions({ page: 0, size: 10 }),
      ]);
      setBalance(balanceData);
      setTransactions(txnPage.content);
      setTotalTransactions(txnPage.totalElements);
    } catch (err) {
      // The api.js interceptor already handles 401 by refreshing/redirecting,
      // so anything that reaches here is a genuine failure worth showing.
      const message =
        err.response?.data?.message ||
        "Couldn't load your wallet. Please try again.";
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadWallet();
  }, [loadWallet]);

  function handleLogout() {
    logout();
    navigate("/login");
  }

  // Called by AddMoneyModal after the Razorpay popup closes. We show a
  // "confirming" banner and refresh the wallet so the newly-created (CREATED
  // status) transaction appears in the list. The balance itself won't change
  // until the webhook confirms the payment (Phase 3) - so this refresh is
  // mainly to surface the pending transaction, not to reflect new funds.
  function handleTopUpCompleted({ message }) {
    setShowAddMoney(false);
    if (message) {
      setNotice(message);
    }
    loadWallet();
  }

  // Called by SendMoneyModal after a SUCCESSFUL transfer. Unlike top-up, the
  // money has already moved server-side, so refreshing the wallet here will
  // show the reduced balance and the new TRANSFER row immediately.
  function handleTransferCompleted({ message }) {
    setShowSendMoney(false);
    if (message) {
      setNotice(message);
    }
    loadWallet();
  }

  // Derive a friendly first name from the logged-in user's email (the
  // balance endpoint doesn't return the profile name).
  const emailName = user?.email ? user.email.split("@")[0] : "there";

  return (
    <div className="app-shell">
      {/* ---- Top navigation bar ---- */}
      <nav className="navbar">
        <span className="navbar-brand">
          <span className="logo-mark">
            <LogoGlyph size={20} />
          </span>
          LedgerPay
        </span>

        <div className="navbar-actions">
          <span className="navbar-user">{user?.email}</span>
          {isAdmin && (
            <button
              className="btn btn-ghost"
              onClick={() => navigate("/admin")}
            >
              Admin
            </button>
          )}
          <button className="btn btn-secondary" onClick={handleLogout}>
            <LogoutIcon size={16} />
            Log out
          </button>
        </div>
      </nav>

      <main className="page-content rise-in">
        <div>
          <h1 className="page-heading">Welcome back, {emailName} 👋</h1>
          <p className="page-subheading">
            Here&apos;s an overview of your wallet.
          </p>
        </div>

        {error && (
          <div className="alert alert-error" style={{ marginTop: 24 }}>
            <span>{error}</span>
            <button
              className="btn btn-ghost"
              style={{ marginLeft: "auto" }}
              onClick={loadWallet}
            >
              Retry
            </button>
          </div>
        )}

        {notice && (
          <div className="alert alert-success" style={{ marginTop: 24 }}>
            <span>{notice}</span>
            <button
              className="btn btn-ghost"
              style={{ marginLeft: "auto" }}
              onClick={() => setNotice("")}
            >
              Dismiss
            </button>
          </div>
        )}

        {/* ---- Balance hero + quick actions ---- */}
        <div className="dashboard-grid">
          <div className="balance-card">
            <div className="balance-label">Available balance</div>
            <div className="balance-amount">
              {loading
                ? "…"
                : formatCurrency(balance?.balance, balance?.currency)}
            </div>
            <div className="balance-meta">
              {balance?.currency ?? "INR"} wallet
              {balance?.walletId ? ` · #${balance.walletId}` : ""}
            </div>
          </div>

          <div className="action-stack">
            <button
              className="action-card"
              onClick={() => {
                setNotice("");
                setShowAddMoney(true);
              }}
            >
              <span className="action-icon action-icon-topup">
                <TopUpIcon />
              </span>
              <span>
                <span className="action-title">Add money</span>
                <span className="action-desc">Top up via Razorpay</span>
              </span>
            </button>

            <button
              className="action-card"
              onClick={() => {
                setNotice("");
                setShowSendMoney(true);
              }}
            >
              <span className="action-icon action-icon-transfer">
                <TransferIcon />
              </span>
              <span>
                <span className="action-title">Send money</span>
                <span className="action-desc">Transfer to another user</span>
              </span>
            </button>
          </div>
        </div>

        {/* ---- Recent transactions ---- */}
        <section className="card section-card">
          <div className="section-header">
            <h2 className="section-title">Recent transactions</h2>
            <span className="pill pill-neutral">{totalTransactions} total</span>
          </div>

          {loading ? (
            <div className="empty-state">
              <p>Loading transactions…</p>
            </div>
          ) : transactions.length === 0 ? (
            <div className="empty-state">
              <span className="empty-state-icon">
                <ReceiptIcon />
              </span>
              <p className="empty-state-title">No transactions yet</p>
              <p>Once you add or send money, your history will show up here.</p>
            </div>
          ) : (
            <table className="txn-table">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Date</th>
                  <th>Status</th>
                  <th style={{ textAlign: "right" }}>Amount</th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((txn) => {
                  const isCredit = txn.direction === "CREDIT";
                  return (
                    <tr key={txn.id}>
                      <td>{transactionLabel(txn.type, txn.direction)}</td>
                      <td>{formatDateTime(txn.createdAt)}</td>
                      <td>
                        <span className={statusPillClass(txn.status)}>
                          {txn.status.toLowerCase()}
                        </span>
                      </td>
                      <td
                        style={{ textAlign: "right" }}
                        className={isCredit ? "amount-credit" : "amount-debit"}
                      >
                        {isCredit ? "+" : "−"}
                        {formatCurrency(txn.amount, txn.currency)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </section>
      </main>

      {showAddMoney && (
        <AddMoneyModal
          userEmail={user?.email}
          onClose={() => setShowAddMoney(false)}
          onCompleted={handleTopUpCompleted}
        />
      )}

      {showSendMoney && (
        <SendMoneyModal
          onClose={() => setShowSendMoney(false)}
          onCompleted={handleTransferCompleted}
        />
      )}
    </div>
  );
}
