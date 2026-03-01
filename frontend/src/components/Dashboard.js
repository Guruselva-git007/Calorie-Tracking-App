import React, { useEffect, useMemo, useState } from 'react';
import { entryAPI, userAPI } from '../services/api';
import './Dashboard.css';

const WATER_GOAL_ML = 2800;
const STEPS_GOAL = 8000;

const toNumber = (value, fallback = 0) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const readLocalNumber = (key, fallback = 0) => {
  if (typeof window === 'undefined') {
    return fallback;
  }
  const parsed = Number(window.localStorage.getItem(key));
  return Number.isFinite(parsed) ? parsed : fallback;
};

const writeLocalNumber = (key, value) => {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.setItem(key, String(value));
};

const dailyMetricKey = (prefix, userId, date) => `${prefix}:${userId || 'guest'}:${date}`;

function Dashboard({ refreshKey, userId, user: sessionUser, onUserUpdated }) {
  const [user, setUser] = useState(sessionUser || null);
  const [entries, setEntries] = useState([]);
  const [summary, setSummary] = useState(null);
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));

  const [goalInput, setGoalInput] = useState('2200');
  const [goalEditing, setGoalEditing] = useState(false);

  const [waterMl, setWaterMl] = useState(0);
  const [stepsCount, setStepsCount] = useState(0);

  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState('');

  const waterKey = useMemo(() => dailyMetricKey('calorie_water_ml', userId, date), [userId, date]);
  const stepsKey = useMemo(() => dailyMetricKey('calorie_steps', userId, date), [userId, date]);

  const loadData = async () => {
    if (!userId) {
      setLoading(false);
      setStatus('Please login to view dashboard.');
      return;
    }

    try {
      setLoading(true);
      setStatus('');
      const [entriesRes, summaryRes] = await Promise.all([entryAPI.getByDate(date, userId), entryAPI.getSummary(userId, date)]);

      setEntries(entriesRes?.data || []);
      setSummary(summaryRes?.data || null);
    } catch (error) {
      setStatus(error?.response?.data?.message || 'Failed to load dashboard data.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!userId) {
      return;
    }

    if (sessionUser?.id === userId) {
      setUser(sessionUser);
      setGoalInput(String(sessionUser?.dailyCalorieGoal || 2200));
      return;
    }

    let active = true;
    const loadUser = async () => {
      try {
        const userRes = await userAPI.get(userId);
        if (!active) {
          return;
        }
        setUser(userRes.data);
        setGoalInput(String(userRes.data?.dailyCalorieGoal || 2200));
        onUserUpdated?.(userRes.data);
      } catch (error) {
        if (active) {
          setStatus((previous) => previous || error?.response?.data?.message || 'Failed to load profile.');
        }
      }
    };

    loadUser();
    return () => {
      active = false;
    };
  }, [sessionUser, userId, onUserUpdated]);

  useEffect(() => {
    loadData();
  }, [refreshKey, date, userId]);

  useEffect(() => {
    setWaterMl(Math.max(0, readLocalNumber(waterKey, 0)));
    setStepsCount(Math.max(0, readLocalNumber(stepsKey, 0)));
  }, [waterKey, stepsKey]);

  useEffect(() => {
    writeLocalNumber(waterKey, Math.max(0, Math.round(waterMl)));
  }, [waterKey, waterMl]);

  useEffect(() => {
    writeLocalNumber(stepsKey, Math.max(0, Math.round(stepsCount)));
  }, [stepsKey, stepsCount]);

  const totalCalories = useMemo(() => {
    if (summary?.totalCalories != null) {
      return toNumber(summary.totalCalories, 0);
    }
    return entries.reduce((sum, entry) => sum + toNumber(entry.totalCalories, 0), 0);
  }, [entries, summary]);

  const dailyGoal = toNumber(user?.dailyCalorieGoal, 2200);
  const remaining = dailyGoal - totalCalories;
  const progress = Math.min(Math.max((totalCalories / Math.max(dailyGoal, 1)) * 100, 0), 100);

  const macroTotals = useMemo(
    () => ({
      protein: Math.max(0, toNumber(summary?.totalProtein, 0)),
      carbs: Math.max(0, toNumber(summary?.totalCarbs, 0)),
      fats: Math.max(0, toNumber(summary?.totalFats, 0)),
      fiber: Math.max(0, toNumber(summary?.totalFiber, 0))
    }),
    [summary]
  );

  const recentEntries = useMemo(() => entries.slice(0, 5), [entries]);

  const entryCount = summary?.entryCount ?? entries.length;
  const averagePerMeal = entryCount > 0 ? totalCalories / entryCount : 0;

  const waterProgress = Math.min(100, (waterMl / WATER_GOAL_ML) * 100);
  const stepsProgress = Math.min(100, (stepsCount / STEPS_GOAL) * 100);

  const progressLabel = progress >= 100 ? 'Goal reached' : progress >= 70 ? 'On track' : 'Keep going';

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
      setGoalEditing(false);
      setStatus('Daily goal updated.');
    } catch (error) {
      setStatus(error?.response?.data?.message || 'Failed to update goal.');
    }
  };

  const adjustWater = (delta) => {
    setWaterMl((previous) => Math.max(0, previous + delta));
  };

  const adjustSteps = (delta) => {
    setStepsCount((previous) => Math.max(0, previous + delta));
  };

  if (loading) {
    return <div className="panel dashboard-panel">Loading dashboard...</div>;
  }

  return (
    <div className="panel dashboard-panel dashboard-clean">
      <div className="dashboard-head-row">
        <div>
          <p className="dashboard-eyebrow">Daily overview</p>
          <h2>Home Snapshot</h2>
        </div>
        <label className="date-filter">
          Date
          <input type="date" value={date} onChange={(event) => setDate(event.target.value)} />
        </label>
      </div>

      <section className="dashboard-hero-metrics">
        <article className="dashboard-remaining-card">
          <span>Calories remaining</span>
          <strong className={remaining < 0 ? 'danger' : 'ok'}>{Math.round(remaining)} cal</strong>
          <small>
            {Math.round(totalCalories)} consumed of {dailyGoal}
          </small>
        </article>

        <article className="dashboard-progress-card">
          <div className="dashboard-progress-ring" style={{ '--progress': `${progress}%` }}>
            <div>
              <strong>{Math.round(progress)}%</strong>
              <small>{progressLabel}</small>
            </div>
          </div>
          <div className="dashboard-progress-meta">
            <div>
              <span>Meals</span>
              <strong>{entryCount}</strong>
            </div>
            <div>
              <span>Avg / meal</span>
              <strong>{Math.round(averagePerMeal)} cal</strong>
            </div>
          </div>
        </article>
      </section>

      <section className="dashboard-macro-grid" aria-label="Macro summary">
        <article className="dashboard-macro-card">
          <span>Protein</span>
          <strong>{Math.round(macroTotals.protein)}g</strong>
        </article>
        <article className="dashboard-macro-card">
          <span>Carbs</span>
          <strong>{Math.round(macroTotals.carbs)}g</strong>
        </article>
        <article className="dashboard-macro-card">
          <span>Fat</span>
          <strong>{Math.round(macroTotals.fats)}g</strong>
        </article>
        <article className="dashboard-macro-card">
          <span>Fibre</span>
          <strong>{Math.round(macroTotals.fiber)}g</strong>
        </article>
      </section>

      <section className="dashboard-middle-grid">
        <article className="dashboard-water-card">
          <div className="dashboard-card-head">
            <h3>Water intake</h3>
            <small>{Math.round(waterProgress)}%</small>
          </div>
          <strong>{Math.round(waterMl)} ml</strong>
          <div className="mini-progress-bar" role="presentation">
            <div style={{ width: `${waterProgress}%` }} />
          </div>
          <div className="dashboard-mini-actions">
            <button type="button" className="secondary-btn" onClick={() => adjustWater(250)}>
              +250 ml
            </button>
            <button type="button" className="secondary-btn" onClick={() => adjustWater(500)}>
              +500 ml
            </button>
            <button type="button" className="ghost-btn" onClick={() => setWaterMl(0)}>
              Reset
            </button>
          </div>
        </article>

        <article className="dashboard-activity-card">
          <div className="dashboard-card-head">
            <h3>Steps</h3>
            <small>{Math.round(stepsProgress)}%</small>
          </div>
          <strong>{Math.round(stepsCount)}</strong>
          <div className="mini-progress-bar" role="presentation">
            <div style={{ width: `${stepsProgress}%` }} />
          </div>
          <div className="dashboard-step-editor">
            <input
              type="number"
              min="0"
              step="100"
              value={stepsCount}
              onChange={(event) => setStepsCount(Math.max(0, toNumber(event.target.value, 0)))}
            />
            <button type="button" className="secondary-btn" onClick={() => adjustSteps(500)}>
              +500
            </button>
          </div>
        </article>
      </section>

      <section className="dashboard-section-card" aria-label="Recent meals">
        <div className="dashboard-card-head">
          <h3>Recent meals</h3>
          <small>{entries.length} logged</small>
        </div>

        {recentEntries.length ? (
          <div className="dashboard-meal-list">
            {recentEntries.map((entry) => (
              <article key={entry.id} className="dashboard-meal-row">
                <div className="dashboard-meal-main">
                  <strong>{entry.displayName}</strong>
                  <small>
                    {entry.quantity} {entry.quantityUnit} ·{' '}
                    {entry.createdAt
                      ? new Date(entry.createdAt).toLocaleTimeString([], {
                          hour: '2-digit',
                          minute: '2-digit'
                        })
                      : '-'}
                  </small>
                </div>
                <div className="dashboard-meal-side">
                  <strong>{Math.round(toNumber(entry.totalCalories, 0))} cal</strong>
                  <button type="button" className="ghost-btn" onClick={() => handleDelete(entry.id)}>
                    Remove
                  </button>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <div className="empty-line">No meals logged for this date.</div>
        )}
      </section>

      <section className="dashboard-summary-row" aria-label="Daily summary">
        <article className="dashboard-summary-chip">
          <span>Total calories</span>
          <strong>{Math.round(totalCalories)}</strong>
        </article>
        <article className="dashboard-summary-chip">
          <span>Remaining</span>
          <strong className={remaining < 0 ? 'danger' : 'ok'}>{Math.round(remaining)}</strong>
        </article>
        <article className="dashboard-summary-chip">
          <span>Water</span>
          <strong>{Math.round(waterMl)} ml</strong>
        </article>
        <article className="dashboard-summary-chip">
          <span>Steps</span>
          <strong>{Math.round(stepsCount)}</strong>
        </article>
      </section>

      <section className="dashboard-goal-panel">
        <div className="dashboard-card-head">
          <h3>Daily goal</h3>
          <button type="button" className="secondary-btn" onClick={() => setGoalEditing((previous) => !previous)}>
            {goalEditing ? 'Close' : 'Edit'}
          </button>
        </div>

        {goalEditing ? (
          <form onSubmit={handleGoalUpdate} className="dashboard-goal-form">
            <input
              type="number"
              min="800"
              step="50"
              value={goalInput}
              onChange={(event) => setGoalInput(event.target.value)}
            />
            <button type="submit" className="primary-btn">
              Save goal
            </button>
          </form>
        ) : (
          <small className="dashboard-goal-text">Current goal: {dailyGoal} calories</small>
        )}
      </section>

      {status && <div className="status-line">{status}</div>}
    </div>
  );
}

export default Dashboard;
