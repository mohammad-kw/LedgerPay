import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { getUsers } from "../services/adminService";
import { formatCurrency, formatDateTime } from "../utils/format";
import { LogoutIcon } from "../components/icons";

/**
 * Admin-only page listing every user with their wallet balance and
 * transaction count. Guarded by <ProtectedRoute requireAdmin> and by
 * hasRole("ADMIN") on the backend. Each row links to that user's detail
 * page. Read-only oversight - no user data is ever mutated here, and the
 * backend response never includes password hashes.
 */
export default function AdminUsers() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setUsers(await getUsers());
    } catch (err) {
      setError(
        err.response?.data?.message || "Couldn't load users. Please retry.",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <div className="app-shell">
      <nav className="navbar">
        <span className="navbar-brand">
          LedgerPay
          <span className="admin-badge">Admin</span>
        </span>
        <div className="navbar-actions">
          <button className="btn btn-ghost" onClick={() => navigate("/admin")}>
            ← Dashboard
          </button>
          <span className="navbar-user">{user?.email}</span>
          <button className="btn btn-secondary" onClick={handleLogout}>
            <LogoutIcon size={16} />
            Log out
          </button>
        </div>
      </nav>

      <main className="page-content rise-in">
        <div>
          <h1 className="page-heading">All users</h1>
          <p className="page-subheading">{users.length} registered</p>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        {loading ? (
          <p className="page-subheading">Loading users…</p>
        ) : (
          <div className="card">
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Name</th>
                    <th>Email</th>
                    <th>Role</th>
                    <th>Balance</th>
                    <th>Txns</th>
                    <th>Joined</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((u) => (
                    <tr key={u.id}>
                      <td>{u.id}</td>
                      <td>{u.name}</td>
                      <td>{u.email}</td>
                      <td>
                        <span
                          className={
                            u.role === "ADMIN"
                              ? "admin-badge"
                              : "pill pill-neutral"
                          }
                        >
                          {u.role}
                        </span>
                      </td>
                      <td>{formatCurrency(u.walletBalance)}</td>
                      <td>{u.transactionCount}</td>
                      <td>{formatDateTime(u.createdAt)}</td>
                      <td>
                        <button
                          className="btn btn-ghost"
                          onClick={() => navigate(`/admin/users/${u.id}`)}
                        >
                          View
                        </button>
                      </td>
                    </tr>
                  ))}
                  {users.length === 0 && (
                    <tr>
                      <td colSpan={8} className="page-subheading">
                        No users yet.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
