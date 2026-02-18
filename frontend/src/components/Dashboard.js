import React, { useEffect, useMemo, useState } from 'react';
import { entryAPI, userAPI } from '../services/api';
import './Dashboard.css';

function Dashboard({ refreshKey, userId, user: sessionUser, onUserUpdated }) {
  const [user, setUser] = useState(sessionUser || null);
  const [entries, setEntries] = useState([]);
  const [summary, setSummary] = useState(null);
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [goalInput, setGoalInput] = useState('2200');
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState('');

  const loadData = async () => {
    if (!userId) {
      setLoading(false);
      setStatus('Please login to view dashboard.');
      return;
    }

    try {
      setLoading(true);
      setStatus('');
      const [userRes, entriesRes, summaryRes] = await Promise.all([
        userAPI.get(userId),
        entryAPI.getByDate(date, userId),
        entryAPI.getSummary(userId, date)
      ]);

      setUser(userRes.data);
      setGoalInput(String(userRes.data?.dailyCalorieGoal || 2200));
      setEntries(entriesRes.data || []);
      setSummary(summaryRes.data);
      onUserUpdated?.(userRes.data);
    } catch (error) {
      setStatus(error?.response?.data?.message || 'Failed to load dashboard data.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setUser(sessionUser || null);
  }, [sessionUser]);

  useEffect(() => {
    loadData();
  }, [refreshKey, date, userId]);

  const totalCalories = useMemo(() => {
    if (summary?.totalCalories != null) {
      return Number(summary.totalCalories);
    }
    return entries.reduce((sum, entry) => sum + Number(entry.totalCalories || 0), 0);
  }, [entries, summary]);

  const dailyGoal = user?.dailyCalorieGoal || 2200;
  const remaining = dailyGoal - totalCalories;
  const progress = Math.min(Math.max((totalCalories / dailyGoal) * 100, 0), 100);
  const progressText = progress >= 100 ? 'Goal reached' : 'On track';

  const handleDelete = async (entryId) => {
    setStatus('');
    try {
      await entryAPI.delete(entryId);
      await loadData();
    } catch (error) {
      setStatus(error?.response?.data?.message || 'Failed to delete entry.');
    }
  };

  const handleGoalUpdate = async (event) => {
    event.preventDefault();
    const nextGoal = Number(goalInput);
    if (!Number.isFinite(nextGoal) || nextGoal < 800) {
      setStatus('Please enter a valid goal (minimum 800).');
      return;
    }

    setStatus('');
    try {
      const response = await userAPI.updateGoal(userId, nextGoal);
      setUser(response.data);
      onUserUpdated?.(response.data);
      setStatus('Daily goal updated.');
      await loadData();
    } catch (error) {
      setStatus(error?.response?.data?.message || 'Failed to update goal.');
    }
  };

  if (loading) {
    return <div className="panel dashboard-panel">Loading dashboard...</div>;
  }

  return (
    <div className="panel dashboard-panel">
      <div className="dashboard-top-row">
        <h2>Dashboard</h2>
        <label className="date-filter">
          Date
          <input type="date" value={date} onChange={(event) => setDate(event.target.value)} />
        </label>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <span>Consumed</span>
          <strong>{Math.round(totalCalories)}</strong>
        </div>
        <div className="stat-card">
          <span>Goal</span>
          <strong>{dailyGoal}</strong>
        </div>
        <div className="stat-card">
          <span>Remaining</span>
          <strong className={remaining < 0 ? 'danger' : 'ok'}>{Math.round(remaining)}</strong>
        </div>
        <div className="stat-card">
          <span>Entries</span>
          <strong>{entries.length}</strong>
        </div>
      </div>

      <div className="progress-wrap">
        <div className="progress-bar">
          <div className="progress-fill" style={{ width: `${progress}%` }} />
        </div>
        <div className="progress-meta">
          <strong>{progress.toFixed(0)}%</strong>
          <small>{progressText}</small>
        </div>
      </div>

      <form onSubmit={handleGoalUpdate} className="goal-row">
        <label>
          Daily goal
          <input
            type="number"
            min="800"
            step="50"
            value={goalInput}
            onChange={(event) => setGoalInput(event.target.value)}
          />
        </label>
        <button type="submit" className="primary-btn">
          Update Goal
        </button>
      </form>

      <div className="entry-feed">
        {entries.map((entry) => (
          <div key={entry.id} className="entry-card">
            <div className="entry-card-main">
              <h4>{entry.displayName}</h4>
              <div className="entry-meta-row">
                <span className="entry-chip">{entry.type}</span>
                <span className="entry-chip">
                  {entry.quantity} {entry.quantityUnit}
                </span>
                <span className="entry-chip">
                  {entry.createdAt
                    ? new Date(entry.createdAt).toLocaleTimeString([], {
                        hour: '2-digit',
                        minute: '2-digit'
                      })
                    : '-'}
                </span>
              </div>
            </div>
            <div className="entry-card-side">
              <strong>{Math.round(entry.totalCalories)} cal</strong>
              <button type="button" onClick={() => handleDelete(entry.id)}>
                Remove
              </button>
            </div>
          </div>
        ))}

        {entries.length === 0 && <div className="empty-line">No entries for this date.</div>}
      </div>

      {status && <div className="status-line">{status}</div>}
    </div>
  );
}

export default Dashboard;
