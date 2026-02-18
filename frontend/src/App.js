import React, { Suspense, lazy, useCallback, useEffect, useMemo, useState } from 'react';
import AddEntry from './components/AddEntry';
import Dashboard from './components/Dashboard';
import FoodLibrary from './components/FoodLibrary';
import IngredientCalculator from './components/IngredientCalculator';
import LoginPage from './components/LoginPage';
import { authAPI, describeApiError, dishAPI, getAuthToken, ingredientAPI, setAuthToken, statsAPI } from './services/api';
import './App.css';

const ToolsPage = lazy(() => import('./components/ToolsPage'));
const SettingsPage = lazy(() => import('./components/SettingsPage'));

const THEME_MODE_KEY = 'calorie_theme_mode';
const THEME_KEY = 'calorie_theme';
const PAGE_KEY = 'calorie_active_page';
const PREF_KEY = 'calorie_preferences';

const PAGE_OPTIONS = [
  { id: 'home', label: 'Home' },
  { id: 'tools', label: 'Tools' },
  { id: 'settings', label: 'Settings' }
];

const ThemeSunIcon = ({ className = '' }) => (
  <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
    <circle cx="12" cy="12" r="4.3" />
    <path d="M12 1.8v2.4M12 19.8v2.4M4.2 4.2l1.7 1.7M18.1 18.1l1.7 1.7M1.8 12h2.4M19.8 12h2.4M4.2 19.8l1.7-1.7M18.1 5.9l1.7-1.7" />
  </svg>
);

const ThemeMoonIcon = ({ className = '' }) => (
  <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
    <path d="M20.8 13.1a8.9 8.9 0 1 1-9.9-9.9 7.5 7.5 0 1 0 9.9 9.9Z" />
  </svg>
);

const ThemeAutoIcon = ({ className = '' }) => (
  <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 7.4v5l3.4 1.8" />
  </svg>
);

const resolveAutoTheme = (date = new Date()) => {
  const hour = date.getHours();
  return hour >= 19 || hour < 7 ? 'dark' : 'light';
};

const getInitialThemeMode = () => {
  if (typeof window === 'undefined') {
    return 'auto';
  }

  const storedThemeMode = window.localStorage.getItem(THEME_MODE_KEY);
  if (storedThemeMode === 'auto' || storedThemeMode === 'light' || storedThemeMode === 'dark') {
    return storedThemeMode;
  }

  const legacyTheme = window.localStorage.getItem(THEME_KEY);
  if (legacyTheme === 'light' || legacyTheme === 'dark') {
    return legacyTheme;
  }

  return 'auto';
};

const getInitialPage = () => {
  if (typeof window === 'undefined') {
    return 'home';
  }

  const storedPage = window.localStorage.getItem(PAGE_KEY);
  if (storedPage === 'home' || storedPage === 'tools' || storedPage === 'settings') {
    return storedPage;
  }

  return 'home';
};

const getInitialPreferences = () => {
  if (typeof window === 'undefined') {
    return {
      region: 'Global',
      dietaryPreference: 'No restrictions',
      currencies: ['INR', 'USD', 'EUR', 'GBP']
    };
  }

  const raw = window.localStorage.getItem(PREF_KEY);
  if (!raw) {
    return {
      region: 'Global',
      dietaryPreference: 'No restrictions',
      currencies: ['INR', 'USD', 'EUR', 'GBP']
    };
  }

  try {
    const parsed = JSON.parse(raw);
    return {
      region: parsed?.region || 'Global',
      dietaryPreference: parsed?.dietaryPreference || 'No restrictions',
      currencies: Array.isArray(parsed?.currencies) && parsed.currencies.length
        ? parsed.currencies
        : ['INR', 'USD', 'EUR', 'GBP']
    };
  } catch (error) {
    return {
      region: 'Global',
      dietaryPreference: 'No restrictions',
      currencies: ['INR', 'USD', 'EUR', 'GBP']
    };
  }
};

function App() {
  const [authLoading, setAuthLoading] = useState(true);
  const [currentUser, setCurrentUser] = useState(null);
  const [sessionRestoreStatus, setSessionRestoreStatus] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);

  const [ingredientsCount, setIngredientsCount] = useState(0);
  const [dishesCount, setDishesCount] = useState(0);

  const [activePage, setActivePage] = useState(getInitialPage);
  const [themeMode, setThemeMode] = useState(getInitialThemeMode);
  const [timeTick, setTimeTick] = useState(() => Date.now());
  const [preferences, setPreferences] = useState(getInitialPreferences);

  const activeTheme = themeMode === 'auto' ? resolveAutoTheme(new Date(timeTick)) : themeMode;

  const restoreSession = useCallback(async () => {
    const token = getAuthToken();
    if (!token) {
      setCurrentUser(null);
      setSessionRestoreStatus('');
      setAuthLoading(false);
      return;
    }

    try {
      setAuthLoading(true);
      const response = await authAPI.me();
      const user = response?.data?.user;
      if (user?.id) {
        setCurrentUser(user);
        setSessionRestoreStatus('');
      } else {
        setCurrentUser(null);
        setSessionRestoreStatus('');
        setAuthToken('');
      }
    } catch (errorResponse) {
      const statusCode = errorResponse?.response?.status;
      if (statusCode === 401 || statusCode === 403) {
        setCurrentUser(null);
        setSessionRestoreStatus('');
        setAuthToken('');
      } else {
        setCurrentUser(null);
        setSessionRestoreStatus(
          describeApiError(errorResponse, 'Unable to restore session right now. Retry backend and try again.')
        );
      }
    } finally {
      setAuthLoading(false);
    }
  }, []);

  const loadDatasetStats = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const statsRes = await statsAPI.get();
      const ingredients = Number(statsRes?.data?.ingredients);
      const dishes = Number(statsRes?.data?.dishes);

      if (Number.isFinite(ingredients) && Number.isFinite(dishes)) {
        setIngredientsCount(ingredients);
        setDishesCount(dishes);
      } else {
        const [ingredientRes, dishRes] = await Promise.all([ingredientAPI.list(), dishAPI.list()]);
        setIngredientsCount((ingredientRes.data || []).length);
        setDishesCount((dishRes.data || []).length);
      }
    } catch (loadError) {
      setError(describeApiError(loadError, 'Unable to load backend data. Check backend status and API base URL.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    restoreSession();
  }, [restoreSession]);

  useEffect(() => {
    if (!currentUser?.id) {
      return;
    }
    loadDatasetStats();
  }, [currentUser?.id, loadDatasetStats]);

  useEffect(() => {
    if (themeMode !== 'auto') {
      return undefined;
    }

    const timer = window.setInterval(() => setTimeTick(Date.now()), 60 * 1000);
    return () => window.clearInterval(timer);
  }, [themeMode]);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', activeTheme);
    document.documentElement.setAttribute('data-theme-mode', themeMode);
    window.localStorage.setItem(THEME_MODE_KEY, themeMode);
    window.localStorage.setItem(THEME_KEY, activeTheme);
  }, [activeTheme, themeMode]);

  useEffect(() => {
    window.localStorage.setItem(PAGE_KEY, activePage);
  }, [activePage]);

  useEffect(() => {
    window.localStorage.setItem(PREF_KEY, JSON.stringify(preferences));
  }, [preferences]);

  useEffect(() => {
    if (!window.matchMedia || !window.matchMedia('(hover: hover)').matches) {
      return undefined;
    }

    let rafId = 0;
    const handlePointerMove = (event) => {
      window.cancelAnimationFrame(rafId);
      rafId = window.requestAnimationFrame(() => {
        document.documentElement.style.setProperty('--pointer-x', `${event.clientX}px`);
        document.documentElement.style.setProperty('--pointer-y', `${event.clientY}px`);
      });
    };

    window.addEventListener('pointermove', handlePointerMove, { passive: true });
    return () => {
      window.cancelAnimationFrame(rafId);
      window.removeEventListener('pointermove', handlePointerMove);
    };
  }, []);

  const onEntriesChanged = () => {
    setRefreshKey((previous) => previous + 1);
  };

  const onDatasetChanged = async () => {
    await loadDatasetStats();
    setRefreshKey((previous) => previous + 1);
  };

  const setThemeModeFromAction = (nextMode) => {
    setThemeMode(nextMode);
    setTimeTick(Date.now());
  };

  const handlePreferencesChange = (nextPreferences) => {
    setPreferences(nextPreferences);
  };

  const handleAuthenticated = (user) => {
    setSessionRestoreStatus('');
    setCurrentUser(user);
    setRefreshKey((previous) => previous + 1);
  };

  const handleUserUpdated = (nextUser) => {
    if (!nextUser?.id) {
      return;
    }
    setCurrentUser(nextUser);
  };

  const handleLogout = async () => {
    try {
      await authAPI.logout();
    } catch (errorResponse) {
      // No-op. Logging out locally is sufficient for UX.
    } finally {
      setAuthToken('');
      setCurrentUser(null);
      setSessionRestoreStatus('');
      setError('');
      setLoading(false);
    }
  };

  const autoThemeHint = activeTheme === 'dark' ? 'Auto now: Night' : 'Auto now: Day';
  const themeModeIndex = themeMode === 'light' ? 0 : themeMode === 'auto' ? 1 : 2;

  const pageHeaderSubtitle = useMemo(() => {
    if (activePage === 'tools') {
      return 'Deficiency analyzer, medical support tools, and nutrition recommendation engine';
    }
    if (activePage === 'settings') {
      return 'Profile, data import, custom foods, support, and app preferences';
    }
    return 'Log meals, customize ingredients, and keep your nutrition organized';
  }, [activePage]);

  const userDisplayName = useMemo(() => {
    if (!currentUser) {
      return '';
    }
    return currentUser.nickname || currentUser.name || 'User';
  }, [currentUser]);

  const isGuestMode = useMemo(() => {
    const email = String(currentUser?.email || '').toLowerCase();
    return email === 'guest@calorietracker.local';
  }, [currentUser?.email]);

  if (authLoading) {
    return <div className="app-shell status">Checking session...</div>;
  }

  if (!currentUser?.id) {
    return (
      <LoginPage
        onAuthenticated={handleAuthenticated}
        initialStatus={sessionRestoreStatus}
        onRetryBackend={restoreSession}
      />
    );
  }

  return (
    <div className="app-shell">
      <header className="hero hero-modern">
        <div className="hero-left">
          <p className="eyebrow">Global Nutrition Studio</p>
          <h1>Calorie Tracker</h1>
          <p className="tagline">{pageHeaderSubtitle}</p>
          <p className="hero-greeting">
            Welcome, <strong>{userDisplayName}</strong>
            {isGuestMode ? <span className="guest-pill">Guest Mode</span> : null}
          </p>

          <nav className="top-nav" aria-label="Main pages">
            {PAGE_OPTIONS.map((item) => (
              <button
                key={item.id}
                type="button"
                className={activePage === item.id ? 'active' : ''}
                onClick={() => setActivePage(item.id)}
              >
                {item.label}
              </button>
            ))}
          </nav>
        </div>

        <div className="hero-right">
          <div className="hero-counts">
            <div>
              <span>Ingredients</span>
              <strong>{ingredientsCount}</strong>
            </div>
            <div>
              <span>Dishes</span>
              <strong>{dishesCount}</strong>
            </div>
          </div>

          <div className="hero-user-actions">
            <button type="button" className="hero-logout-btn" onClick={handleLogout}>
              {isGuestMode ? 'Exit Guest' : 'Logout'}
            </button>
          </div>

          <div className="theme-orb-wrap">
            <div
              className={`theme-triple-toggle theme-${activeTheme}`}
              role="group"
              aria-label="Theme mode"
              style={{ '--mode-index': themeModeIndex }}
            >
              <span className="theme-toggle-indicator" aria-hidden="true" />
              <button
                type="button"
                className={themeMode === 'light' ? 'active' : ''}
                onClick={() => setThemeModeFromAction('light')}
                title="Day mode"
                aria-label="Day mode"
              >
                <ThemeSunIcon />
              </button>
              <button
                type="button"
                className={themeMode === 'auto' ? 'active' : ''}
                onClick={() => setThemeModeFromAction('auto')}
                title={autoThemeHint}
                aria-label="Auto mode"
              >
                <ThemeAutoIcon />
              </button>
              <button
                type="button"
                className={themeMode === 'dark' ? 'active' : ''}
                onClick={() => setThemeModeFromAction('dark')}
                title="Night mode"
                aria-label="Night mode"
              >
                <ThemeMoonIcon />
              </button>
            </div>
          </div>
        </div>
      </header>

      {error && (
        <div className="status status-error">
          {error}
          <div className="status-actions">
            <button type="button" className="secondary-btn" onClick={loadDatasetStats}>
              Retry backend load
            </button>
          </div>
        </div>
      )}
      {loading && <div className="status">Loading backend data...</div>}

      {!loading && (
        <main className="page-wrap">
          {activePage === 'home' && (
            <div className="home-grid">
              <section className="home-slot">
                <AddEntry userId={currentUser.id} onEntryAdded={onEntriesChanged} />
              </section>

              <section className="home-slot">
                <Dashboard
                  refreshKey={refreshKey}
                  userId={currentUser.id}
                  user={currentUser}
                  onUserUpdated={handleUserUpdated}
                />
              </section>

              <section className="home-slot">
                <FoodLibrary />
              </section>

              <section className="home-slot">
                <IngredientCalculator />
              </section>
            </div>
          )}

          {activePage === 'tools' && (
            <Suspense fallback={<div className="status">Loading tools...</div>}>
              <ToolsPage preferences={preferences} userProfile={currentUser} />
            </Suspense>
          )}

          {activePage === 'settings' && (
            <Suspense fallback={<div className="status">Loading settings...</div>}>
              <SettingsPage
                user={currentUser}
                onUserUpdated={handleUserUpdated}
                preferences={preferences}
                onPreferencesChange={handlePreferencesChange}
                onDatasetChanged={onDatasetChanged}
              />
            </Suspense>
          )}
        </main>
      )}
    </div>
  );
}

export default App;
