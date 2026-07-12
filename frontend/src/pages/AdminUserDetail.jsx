import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { getUserDetail } from "../services/adminService";
import { formatCurrency, formatDateTime } from "../utils/format";
import { LogoutIcon } from "../components/icons";

/**
 * Admin-only detail view for a single user: their profile + wallet summary,
 * plus their full transaction history. Guarded by <ProtectedRoute requireAdmin>
 * and hasRole("ADMIN"). The backend returns 404 (UserNotFoundException) for an
 * unknown id, which we surface as a friendly message. No password data is
 * ever returned.
 */
export default function AdminUserDetail() {
  const { id } = useParams();
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setDetail(await getUserDetail(id));
    } catch (err) {
      setError(
        err.response?.status === 404
          ? "No user found with that id."
          : err.response?.data?.message || "Couldn't load this user.",
      );
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  function handleLogout() {
    logout();
    navigate("/login");
  }

  const profile = detail?.user;
  const transactions = detail?.transactions ?? [];

  return (
    <div className="app-shell">
      <nav className="navbar">
        <span className="navbar-brand">
          LedgerPay
          <span className="admin-badge">Admin</span>
        </span>
        <div className="navbar-actions">
          <button
            className="btn btn-ghost"
            onClick={() => navigate("/admin/users")}
          >
            ← All users
          </button>
          <span className="navbar-user">{user?.email}</span>
          <button className="btn btn-secondary" onClick={handleLogout}>
            <LogoutIcon size={16} />
            Log out
          </button>
        </div>
      </nav>

      <main className="page-content rise-in">
        {error && <div className="alert alert-error">{error}</div>}

        {loading ? (
          <p className="page-subheading">Loading…</p>
        ) : profile ? (
          <>
            <div>
              <h1 className="page-heading">{profile.name}</h1>
              <p className="page-subheading">{profile.email}</p>
            </div>

            <div className="card user-profile-grid">
              <div>
                <span className="page-subheading">Role</span>
                <strong>{profile.role}</strong>
              </div>
              <div>
                <span className="page-subheading">Wallet balance</span>
                <strong>{formatCurrency(profile.walletBalance)}</strong>
              </div>
              <div>
                <span className="page-subheading">Phone</span>
                <strong>{profile.phone || "—"}</strong>
              </div>
              <div>
                <span className="page-subheading">Transactions</span>
                <strong>{profile.transactionCount}</strong>
              </div>
              <div>
                <span className="page-subheading">Joined</span>
                <strong>{formatDateTime(profile.createdAt)}</strong>
              </div>
            </div>

            <h2 className="page-heading" style={{ marginTop: 32 }}>
              Transaction history
            </h2>

            <div className="card">
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>ID</th>
                      <th>Type</th>
                      <th>Status</th>
                      <th>Amount</th>
                      <th>From</th>
                      <th>To</th>
                      <th>When</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transactions.map((t) => (
                      <tr key={t.id}>
                        <td>{t.id}</td>
                        <td>{t.type}</td>
                        <td>{t.status}</td>
                        <td>{formatCurrency(t.amount)}</td>
                        <td>{t.senderWalletId ?? "—"}</td>
                        <td>{t.receiverWalletId ?? "—"}</td>
                        <td>{formatDateTime(t.createdAt)}</td>
                      </tr>
                    ))}
                    {transactions.length === 0 && (
                      <tr>
                        <td colSpan={7} className="page-subheading">
                          No transactions yet.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </>
        ) : null}
      </main>
    </div>
  );
}
