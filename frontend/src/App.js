import React, { Suspense, lazy, useCallback, useEffect, useMemo, useState } from 'react';
import { authAPI, describeApiError, getAuthToken, setAuthToken, statsAPI } from './services/api';
import { buildFoodPlaceholderDataUrl, getFoodImageSrc } from './utils/food';
import './App.css';

const LoginPage = lazy(() => import('./components/LoginPage'));
const AddEntry = lazy(() => import('./components/AddEntry'));
const Dashboard = lazy(() => import('./components/Dashboard'));
const ToolsPage = lazy(() => import('./components/ToolsPage'));
const SettingsPage = lazy(() => import('./components/SettingsPage'));
const FoodLibrary = lazy(() => import('./components/FoodLibrary'));
const IngredientCalculator = lazy(() => import('./components/IngredientCalculator'));
const ChatAssistant = lazy(() => import('./components/ChatAssistant'));

const THEME_MODE_KEY = 'calorie_theme_mode';
const THEME_KEY = 'calorie_theme';
const PAGE_KEY = 'calorie_active_page';
const PREF_KEY = 'calorie_preferences';
const MOBILE_PREVIEW_KEY = 'calorie_force_mobile_preview';
const HOME_UTILITY_KEY = 'calorie_home_utility_tab';
const HOME_MOBILE_SECTION_KEY = 'calorie_home_mobile_section';

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

const MobileNav = ({ currentPage, onPageChange }) => (
  <nav className="mobile-nav-bar" aria-label="Mobile navigation">
    <button type="button" onClick={() => onPageChange('home')} className={currentPage === 'home' ? 'active' : ''}>
      <span aria-hidden="true">🏠</span>
      <small>Home</small>
    </button>
    <button type="button" onClick={() => onPageChange('tools')} className={currentPage === 'tools' ? 'active' : ''}>
      <span aria-hidden="true">🛠️</span>
      <small>Tools</small>
    </button>
    <button
      type="button"
      onClick={() => onPageChange('settings')}
      className={currentPage === 'settings' ? 'active' : ''}
    >
      <span aria-hidden="true">⚙️</span>
      <small>Settings</small>
    </button>
  </nav>
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
  const defaults = {
    region: 'Global',
    dietaryPreference: 'No restrictions',
    currencies: ['INR', 'USD', 'EUR', 'GBP'],
    voiceRecognitionMode: 'auto'
  };

  if (typeof window === 'undefined') {
    return defaults;
  }

  const raw = window.localStorage.getItem(PREF_KEY);
  if (!raw) {
    return defaults;
  }

  try {
    const parsed = JSON.parse(raw);
    const voiceRecognitionMode = parsed?.voiceRecognitionMode === 'manual' ? 'manual' : 'auto';
    return {
      region: parsed?.region || defaults.region,
      dietaryPreference: parsed?.dietaryPreference || defaults.dietaryPreference,
      currencies: Array.isArray(parsed?.currencies) && parsed.currencies.length
        ? parsed.currencies
        : defaults.currencies,
      voiceRecognitionMode
    };
  } catch (error) {
    return defaults;
  }
};

const getInitialMobilePreview = () => {
  if (typeof window === 'undefined') {
    return false;
  }
  return window.localStorage.getItem(MOBILE_PREVIEW_KEY) === '1';
};

const getInitialHomeUtilityTab = () => {
  if (typeof window === 'undefined') {
    return 'search';
  }
  const stored = window.localStorage.getItem(HOME_UTILITY_KEY);
  return stored === 'ingredients' ? 'ingredients' : 'search';
};

const getInitialHomeMobileSection = () => {
  if (typeof window === 'undefined') {
    return 'log';
  }
  const stored = window.localStorage.getItem(HOME_MOBILE_SECTION_KEY);
  return stored === 'today' || stored === 'log' || stored === 'utility' ? stored : 'log';
};

const getInitialCompactViewport = () => {
  if (typeof window === 'undefined') {
    return false;
  }
  return window.innerWidth <= 920;
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
  const [mobilePreviewEnabled, setMobilePreviewEnabled] = useState(getInitialMobilePreview);
  const [homeUtilityTab, setHomeUtilityTab] = useState(getInitialHomeUtilityTab);
  const [homeMobileSection, setHomeMobileSection] = useState(getInitialHomeMobileSection);
  const [isCompactViewport, setIsCompactViewport] = useState(getInitialCompactViewport);

  const isCompactHomeLayout = mobilePreviewEnabled || isCompactViewport;

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
    if (!currentUser?.id) {
      return undefined;
    }

    const preloadHomeModules = () => {
      void import('./components/AddEntry');
      void import('./components/Dashboard');
      void import('./components/FoodLibrary');
      void import('./components/IngredientCalculator');
      void import('./components/ToolsPage');
      void import('./components/SettingsPage');
    };

    if ('requestIdleCallback' in window) {
      const idleId = window.requestIdleCallback(preloadHomeModules, { timeout: 1200 });
      return () => window.cancelIdleCallback(idleId);
    }

    const timeoutId = window.setTimeout(preloadHomeModules, 280);
    return () => window.clearTimeout(timeoutId);
  }, [currentUser?.id]);

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
    window.localStorage.setItem(MOBILE_PREVIEW_KEY, mobilePreviewEnabled ? '1' : '0');
  }, [mobilePreviewEnabled]);

  useEffect(() => {
    window.localStorage.setItem(HOME_UTILITY_KEY, homeUtilityTab);
  }, [homeUtilityTab]);

  useEffect(() => {
    window.localStorage.setItem(HOME_MOBILE_SECTION_KEY, homeMobileSection);
  }, [homeMobileSection]);

  useEffect(() => {
    const handleResize = () => {
      setIsCompactViewport(window.innerWidth <= 920);
    };

    window.addEventListener('resize', handleResize, { passive: true });
    return () => window.removeEventListener('resize', handleResize);
  }, []);

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
    setPreferences((previous) => ({
      ...previous,
      ...(nextPreferences || {}),
      voiceRecognitionMode: nextPreferences?.voiceRecognitionMode === 'manual' ? 'manual' : 'auto'
    }));
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

  const handleOpenQuickLogger = useCallback(() => {
    setActivePage('home');
    window.setTimeout(() => {
      document.getElementById('quick-add-section')?.scrollIntoView({
        behavior: 'smooth',
        block: 'start'
      });
    }, 90);
  }, []);

  const autoThemeHint = activeTheme === 'dark' ? 'Auto now: Night' : 'Auto now: Day';
  const themeModeIndex = themeMode === 'light' ? 0 : themeMode === 'auto' ? 1 : 2;

  const pageHeaderSubtitle = useMemo(() => {
    if (activePage === 'tools') {
      return 'Deficiency tools, BMI support, and personalized diet guidance';
    }
    if (activePage === 'settings') {
      return 'Profile, preferences, support, and data controls';
    }
    return 'Quick log meals, scan foods, and track nutrition clearly';
  }, [activePage]);

  const userDisplayName = useMemo(() => {
    if (!currentUser) {
      return '';
    }
    return currentUser.nickname || currentUser.name || 'User';
  }, [currentUser]);

  const userAvatarFallback = useMemo(
    () => buildFoodPlaceholderDataUrl(userDisplayName || 'User', 'app-user-avatar'),
    [userDisplayName]
  );

  const userAvatarSrc = useMemo(
    () =>
      getFoodImageSrc(
        {
          name: userDisplayName || 'User',
          imageUrl: currentUser?.profileImageUrl || ''
        },
        { label: userDisplayName || 'User', bucket: 'app-user-avatar' }
      ),
    [currentUser?.profileImageUrl, userDisplayName]
  );

  const isGuestMode = useMemo(() => {
    const email = String(currentUser?.email || '').toLowerCase();
    return email === 'guest@calorietracker.local';
  }, [currentUser?.email]);

  if (authLoading) {
    return <div className="app-shell status">Checking session...</div>;
  }

  if (!currentUser?.id) {
    return (
      <Suspense fallback={<div className="app-shell status">Loading login...</div>}>
        <LoginPage
          onAuthenticated={handleAuthenticated}
          initialStatus={sessionRestoreStatus}
          onRetryBackend={restoreSession}
          mobilePreviewEnabled={mobilePreviewEnabled}
          onToggleMobilePreview={setMobilePreviewEnabled}
        />
      </Suspense>
    );
  }

  return (
    <div className={`app-shell${mobilePreviewEnabled ? ' force-mobile-preview' : ''}`}>
      <header className="hero hero-modern">
        <div className="hero-left">
          <p className="eyebrow">Global Nutrition Studio</p>
          <h1>Calorie Tracker</h1>
          <p className="tagline">{pageHeaderSubtitle}</p>
          <p className="hero-greeting">
            <span className="hero-avatar">
              <img
                src={userAvatarSrc}
                alt={`${userDisplayName} profile`}
                data-fallback={userAvatarFallback}
                onError={(event) => {
                  event.currentTarget.onerror = null;
                  event.currentTarget.src = event.currentTarget.dataset.fallback || userAvatarFallback;
                }}
              />
            </span>
            <span>
              Welcome, <strong>{userDisplayName}</strong>
            </span>
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
            <div className="home-grid home-grid-refined home-grid-lifesum">
              {isCompactHomeLayout && (
                <div className="home-mobile-section-switch mode-switch" role="tablist" aria-label="Home sections">
                  <button
                    type="button"
                    role="tab"
                    aria-selected={homeMobileSection === 'today'}
                    className={homeMobileSection === 'today' ? 'active' : ''}
                    onClick={() => setHomeMobileSection('today')}
                  >
                    Today
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={homeMobileSection === 'log'}
                    className={homeMobileSection === 'log' ? 'active' : ''}
                    onClick={() => setHomeMobileSection('log')}
                  >
                    Quick Log
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={homeMobileSection === 'utility'}
                    className={homeMobileSection === 'utility' ? 'active' : ''}
                    onClick={() => setHomeMobileSection('utility')}
                  >
                    Utilities
                  </button>
                </div>
              )}

              {(!isCompactHomeLayout || homeMobileSection === 'log') && (
                <section id="quick-add-section" className="home-slot home-slot-log">
                  <Suspense fallback={<div className="status">Loading quick logger...</div>}>
                    <AddEntry userId={currentUser.id} onEntryAdded={onEntriesChanged} preferences={preferences} />
                  </Suspense>
                </section>
              )}

              {(!isCompactHomeLayout || homeMobileSection === 'today') && (
                <section className="home-slot home-slot-today">
                  <Suspense fallback={<div className="status">Loading dashboard...</div>}>
                    <Dashboard
                      refreshKey={refreshKey}
                      userId={currentUser.id}
                      user={currentUser}
                      onUserUpdated={handleUserUpdated}
                    />
                  </Suspense>
                </section>
              )}

              {(!isCompactHomeLayout || homeMobileSection === 'utility') && (
                <section className="home-slot home-slot-utility home-utility-slot">
                  <div className="panel home-utility-header">
                    <div className="panel-title-row">
                      <h2>{homeUtilityTab === 'search' ? 'Food Search' : 'Ingredient Calculator'}</h2>
                      <div className="mode-switch home-utility-switch">
                        <button
                          type="button"
                          className={homeUtilityTab === 'search' ? 'active' : ''}
                          onClick={() => setHomeUtilityTab('search')}
                        >
                          Search
                        </button>
                        <button
                          type="button"
                          className={homeUtilityTab === 'ingredients' ? 'active' : ''}
                          onClick={() => setHomeUtilityTab('ingredients')}
                        >
                          Ingredients
                        </button>
                      </div>
                    </div>
                  </div>

                  <Suspense
                    fallback={
                      <div className="status">
                        {homeUtilityTab === 'search' ? 'Loading food search...' : 'Loading ingredient calculator...'}
                      </div>
                    }
                  >
                    {homeUtilityTab === 'search' ? <FoodLibrary /> : <IngredientCalculator />}
                  </Suspense>
                </section>
              )}
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

      <Suspense fallback={null}>
        <ChatAssistant
          user={currentUser}
          activePage={activePage}
          onNavigate={setActivePage}
          onOpenQuickLogger={handleOpenQuickLogger}
        />
      </Suspense>

      <MobileNav currentPage={activePage} onPageChange={setActivePage} />
    </div>
  );
}

export default App;
