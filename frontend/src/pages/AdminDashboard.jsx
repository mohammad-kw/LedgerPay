import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  PieChart,
  Pie,
  Cell,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts";
import { useAuth } from "../context/AuthContext";
import {
  getMetrics,
  getRecentTransactions,
  getReconciliationLogs,
  runReconciliation,
} from "../services/adminService";
import { formatCurrency, formatDateTime } from "../utils/format";
import { LogoGlyph, LogoutIcon } from "../components/icons";

// Chart colours pulled from the design-system palette (index.css tokens).
const STATUS_COLORS = {
  SUCCESS: "#10b981",
  PENDING: "#f59e0b",
  CREATED: "#0ea5e9",
  FAILED: "#f43f5e",
  REVERSED: "#94a3b8",
};
const TYPE_COLORS = ["#4f46e5", "#7c3aed", "#0ea5e9"];

/**
 * The admin-only oversight dashboard. Guarded by <ProtectedRoute requireAdmin>
 * on the frontend AND by hasRole("ADMIN") on every /api/admin call in the
 * backend. It shows system-wide metrics (with charts), the reconciliation
 * control panel (the project's key differentiator), and a global transaction
 * feed - all READ-ONLY except the "run reconciliation" button, which only
 * writes an audit log.
 */
export default function AdminDashboard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [metrics, setMetrics] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // Reconciliation "run" control state.
  const [runDate, setRunDate] = useState("");
  const [running, setRunning] = useState(false);
  const [runNotice, setRunNotice] = useState("");

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [metricsData, txns, logData] = await Promise.all([
        getMetrics(),
        getRecentTransactions(25),
        getReconciliationLogs(),
      ]);
      setMetrics(metricsData);
      setTransactions(txns);
      setLogs(logData);
    } catch (err) {
      const message =
        err.response?.data?.message ||
        "Couldn't load admin data. Please try again.";
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  async function handleRunReconciliation() {
    setRunning(true);
    setRunNotice("");
    setError("");
    try {
      const log = await runReconciliation(runDate || undefined);
      setRunNotice(
        `Run for ${log.runDate} finished: ${log.status}, ` +
          `${log.mismatchesFound} mismatch(es) out of ${log.totalChecked} checked.`,
      );
      // Refresh the logs table (and metrics, since a run may reveal drift).
      const [metricsData, logData] = await Promise.all([
        getMetrics(),
        getReconciliationLogs(),
      ]);
      setMetrics(metricsData);
      setLogs(logData);
    } catch (err) {
      const message =
        err.response?.data?.message || "Reconciliation run failed.";
      setError(message);
    } finally {
      setRunning(false);
    }
  }

  function handleLogout() {
    logout();
    navigate("/login");
  }

  // Transform the metrics maps into the array shape recharts expects,
  // dropping zero-count slices so the charts stay clean.
  const statusChartData = useMemo(() => {
    if (!metrics) return [];
    return Object.entries(metrics.transactionsByStatus)
      .filter(([, count]) => count > 0)
      .map(([name, value]) => ({ name, value }));
  }, [metrics]);

  const typeChartData = useMemo(() => {
    if (!metrics) return [];
    return Object.entries(metrics.transactionsByType)
      .filter(([, count]) => count > 0)
      .map(([name, value]) => ({ name, value }));
  }, [metrics]);

  return (
    <div className="app-shell">
      <nav className="navbar">
        <span className="navbar-brand">
          <span className="logo-mark">
            <LogoGlyph size={20} />
          </span>
          LedgerPay
          <span className="admin-badge">Admin</span>
        </span>

        <div className="navbar-actions">
          <button
            className="btn btn-ghost"
            onClick={() => navigate("/dashboard")}
          >
            My wallet
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
          <h1 className="page-heading">Admin dashboard</h1>
          <p className="page-subheading">
            System-wide metrics, reconciliation control, and global activity.
          </p>
        </div>

        {error && (
          <div className="alert alert-error" style={{ marginTop: 24 }}>
            {error}
          </div>
        )}

        {loading ? (
          <p className="page-subheading" style={{ marginTop: 32 }}>
            Loading admin data…
          </p>
        ) : (
          <>
            {/* ---- Metric cards ---- */}
            {metrics && (
              <section className="metric-cards">
                <MetricCard label="Total users" value={metrics.totalUsers} />
                <MetricCard
                  label="Total wallets"
                  value={metrics.totalWallets}
                />
                <MetricCard
                  label="Money held (float)"
                  value={formatCurrency(metrics.totalBalanceHeld)}
                />
                <MetricCard
                  label="Transactions today"
                  value={metrics.transactionsToday}
                />
                <MetricCard
                  label="Top-up volume today"
                  value={formatCurrency(metrics.topupVolumeToday)}
                />
                <MetricCard
                  label="Transfer volume today"
                  value={formatCurrency(metrics.transferVolumeToday)}
                />
              </section>
            )}

            {/* ---- Charts ---- */}
            <section className="admin-charts">
              <div className="card card-padded">
                <h2 className="section-title">Transactions by status</h2>
                {statusChartData.length === 0 ? (
                  <p className="empty-hint">No transactions yet.</p>
                ) : (
                  <ResponsiveContainer width="100%" height={260}>
                    <PieChart>
                      <Pie
                        data={statusChartData}
                        dataKey="value"
                        nameKey="name"
                        innerRadius={55}
                        outerRadius={90}
                        paddingAngle={2}
                      >
                        {statusChartData.map((entry) => (
                          <Cell
                            key={entry.name}
                            fill={STATUS_COLORS[entry.name] || "#94a3b8"}
                          />
                        ))}
                      </Pie>
                      <Tooltip />
                      <Legend />
                    </PieChart>
                  </ResponsiveContainer>
                )}
              </div>

              <div className="card card-padded">
                <h2 className="section-title">Transactions by type</h2>
                {typeChartData.length === 0 ? (
                  <p className="empty-hint">No transactions yet.</p>
                ) : (
                  <ResponsiveContainer width="100%" height={260}>
                    <BarChart data={typeChartData}>
                      <XAxis dataKey="name" tickLine={false} />
                      <YAxis allowDecimals={false} tickLine={false} />
                      <Tooltip cursor={{ fill: "#f1f5f9" }} />
                      <Bar dataKey="value" radius={[6, 6, 0, 0]}>
                        {typeChartData.map((entry, i) => (
                          <Cell
                            key={entry.name}
                            fill={TYPE_COLORS[i % TYPE_COLORS.length]}
                          />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </div>
            </section>

            {/* ---- Reconciliation control panel ---- */}
            <section className="card card-padded" style={{ marginTop: 28 }}>
              <h2 className="section-title">Reconciliation</h2>
              <p className="page-subheading" style={{ marginTop: 0 }}>
                Compare local transactions against the payment gateway and flag
                any drift. Leave the date blank to reconcile yesterday.
              </p>

              <div className="recon-controls">
                <input
                  type="date"
                  className="input"
                  value={runDate}
                  onChange={(e) => setRunDate(e.target.value)}
                  style={{ maxWidth: 200 }}
                />
                <button
                  className="btn btn-primary"
                  onClick={handleRunReconciliation}
                  disabled={running}
                >
                  {running ? "Running…" : "Run reconciliation"}
                </button>
              </div>

              {runNotice && (
                <div className="alert alert-info" style={{ marginTop: 16 }}>
                  {runNotice}
                </div>
              )}

              <div className="table-wrap" style={{ marginTop: 20 }}>
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Run date</th>
                      <th>Checked</th>
                      <th>Mismatches</th>
                      <th>Status</th>
                      <th>When</th>
                    </tr>
                  </thead>
                  <tbody>
                    {logs.length === 0 ? (
                      <tr>
                        <td colSpan={5} className="empty-hint">
                          No reconciliation runs yet.
                        </td>
                      </tr>
                    ) : (
                      logs.map((log) => (
                        <tr key={log.id}>
                          <td>{log.runDate}</td>
                          <td>{log.totalChecked}</td>
                          <td>
                            <span
                              className={
                                log.mismatchesFound > 0
                                  ? "pill pill-failed"
                                  : "pill pill-success"
                              }
                            >
                              {log.mismatchesFound}
                            </span>
                          </td>
                          <td>{log.status}</td>
                          <td>{formatDateTime(log.createdAt)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </section>

            {/* ---- Global transaction feed ---- */}
            <section className="card card-padded" style={{ marginTop: 28 }}>
              <h2 className="section-title">Recent activity (all users)</h2>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>ID</th>
                      <th>Type</th>
                      <th>Status</th>
                      <th>Amount</th>
                      <th>From → To</th>
                      <th>When</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transactions.length === 0 ? (
                      <tr>
                        <td colSpan={6} className="empty-hint">
                          No transactions yet.
                        </td>
                      </tr>
                    ) : (
                      transactions.map((t) => (
                        <tr key={t.id}>
                          <td>#{t.id}</td>
                          <td>{t.type}</td>
                          <td>
                            <span
                              className={
                                t.status === "SUCCESS"
                                  ? "pill pill-success"
                                  : t.status === "FAILED"
                                    ? "pill pill-failed"
                                    : "pill pill-pending"
                              }
                            >
                              {t.status}
                            </span>
                          </td>
                          <td>{formatCurrency(t.amount)}</td>
                          <td>
                            {(t.senderWalletId ?? "—") +
                              " → " +
                              (t.receiverWalletId ?? "—")}
                          </td>
                          <td>{formatDateTime(t.createdAt)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          </>
        )}
      </main>
    </div>
  );
}

/** Small metric summary card used in the dashboard header grid. */
function MetricCard({ label, value }) {
  return (
    <div className="card metric-card">
      <span className="metric-card-label">{label}</span>
      <span className="metric-card-value">{value}</span>
    </div>
  );
}
