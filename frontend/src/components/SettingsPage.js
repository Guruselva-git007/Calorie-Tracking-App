import React, { useEffect, useMemo, useRef, useState } from 'react';
import { toolsAPI, userAPI } from '../services/api';
import { buildFoodPlaceholderDataUrl, getFoodImageSrc } from '../utils/food';
import './SettingsPage.css';

const DIETARY_OPTIONS = ['No restrictions', 'Vegetarian', 'Vegan', 'Non-vegetarian'];
const BLOOD_GROUP_OPTIONS = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];
const PROFILE_AVATAR_PRESET_BUCKETS = ['sky', 'mint', 'coral', 'sunset', 'ocean', 'forest'];
const MAX_PROFILE_FILE_BYTES = 5 * 1024 * 1024;
const PROFILE_IMAGE_MAX_DIMENSION = 420;
const PROFILE_IMAGE_QUALITY = 0.84;

const SETTINGS_TABS = [
  { id: 'account', label: 'Account' },
  { id: 'goals', label: 'Goals' },
  { id: 'notifications', label: 'Notifications' },
  { id: 'integrations', label: 'Integrations' },
  { id: 'privacy', label: 'Data & Privacy' }
];

const NOTIFICATION_PREF_KEY = 'calorie_notification_preferences';
const DEFAULT_NOTIFICATION_PREFS = {
  mealReminders: true,
  hydrationReminders: true,
  weeklyDigest: true
};

const orderCurrencies = (list = []) => {
  const unique = Array.from(new Set((list || []).filter(Boolean).map((item) => String(item).trim().toUpperCase())));
  const withoutInr = unique.filter((item) => item !== 'INR');
  return unique.includes('INR') ? ['INR', ...withoutInr] : unique;
};

const toNullableNumber = (value, parser = Number) => {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const parsed = parser(value);
  return Number.isFinite(parsed) ? parsed : null;
};

const fileToDataUrl = (file) =>
  new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('Unable to read image file.'));
    reader.readAsDataURL(file);
  });

const loadImageFromDataUrl = (dataUrl) =>
  new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('Unable to decode image.'));
    image.src = dataUrl;
  });

const resizeProfileImage = async (file) => {
  const dataUrl = await fileToDataUrl(file);
  const image = await loadImageFromDataUrl(dataUrl);
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;

  if (!width || !height) {
    throw new Error('Invalid image dimensions.');
  }

  const scale = Math.min(1, PROFILE_IMAGE_MAX_DIMENSION / Math.max(width, height));
  const targetWidth = Math.max(1, Math.round(width * scale));
  const targetHeight = Math.max(1, Math.round(height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = targetWidth;
  canvas.height = targetHeight;

  const context = canvas.getContext('2d');
  if (!context) {
    throw new Error('Unable to process image.');
  }

  context.drawImage(image, 0, 0, targetWidth, targetHeight);
  return canvas.toDataURL('image/webp', PROFILE_IMAGE_QUALITY);
};

const getInitialNotificationPrefs = () => {
  if (typeof window === 'undefined') {
    return DEFAULT_NOTIFICATION_PREFS;
  }

  const raw = window.localStorage.getItem(NOTIFICATION_PREF_KEY);
  if (!raw) {
    return DEFAULT_NOTIFICATION_PREFS;
  }

  try {
    const parsed = JSON.parse(raw);
    return {
      mealReminders: parsed?.mealReminders !== false,
      hydrationReminders: parsed?.hydrationReminders !== false,
      weeklyDigest: parsed?.weeklyDigest !== false
    };
  } catch (error) {
    return DEFAULT_NOTIFICATION_PREFS;
  }
};

function SettingsPage({ user, onUserUpdated, preferences, onPreferencesChange }) {
  const profileImageInputRef = useRef(null);

  const [activeTab, setActiveTab] = useState('account');

  const [currencyOptions, setCurrencyOptions] = useState(orderCurrencies(['INR', 'USD', 'EUR', 'GBP']));
  const [prefRegion, setPrefRegion] = useState(preferences?.region || 'Global');
  const [prefDietary, setPrefDietary] = useState(preferences?.dietaryPreference || 'No restrictions');
  const [prefCurrencies, setPrefCurrencies] = useState(
    orderCurrencies(preferences?.currencies || ['INR', 'USD', 'EUR', 'GBP'])
  );
  const [voiceReviewEnabled, setVoiceReviewEnabled] = useState(preferences?.voiceRecognitionMode === 'manual');
  const [prefStatus, setPrefStatus] = useState('');

  const [notifications, setNotifications] = useState(getInitialNotificationPrefs);
  const [notificationStatus, setNotificationStatus] = useState('');

  const [goalInput, setGoalInput] = useState(String(user?.dailyCalorieGoal || 2200));
  const [goalBusy, setGoalBusy] = useState(false);
  const [goalStatus, setGoalStatus] = useState('');

  const [profileBusy, setProfileBusy] = useState(false);
  const [profileStatus, setProfileStatus] = useState('');
  const [profileForm, setProfileForm] = useState({
    name: '',
    nickname: '',
    profileImageUrl: '',
    age: '',
    bloodGroup: '',
    heightCm: '',
    weightKg: '',
    region: '',
    state: '',
    city: '',
    nutritionDeficiency: '',
    medicalIllness: '',
    likedFoods: ''
  });

  useEffect(() => {
    setPrefRegion(preferences?.region || 'Global');
    setPrefDietary(preferences?.dietaryPreference || 'No restrictions');
    setPrefCurrencies(orderCurrencies(preferences?.currencies || ['INR', 'USD', 'EUR', 'GBP']));
    setVoiceReviewEnabled(preferences?.voiceRecognitionMode === 'manual');
  }, [preferences]);

  useEffect(() => {
    if (!user) {
      return;
    }

    setGoalInput(String(user.dailyCalorieGoal || 2200));
    setProfileForm({
      name: user.name || '',
      nickname: user.nickname || '',
      profileImageUrl: user.profileImageUrl || '',
      age: user.age ?? '',
      bloodGroup: user.bloodGroup || '',
      heightCm: user.heightCm ?? '',
      weightKg: user.weightKg ?? '',
      region: user.region || '',
      state: user.state || '',
      city: user.city || '',
      nutritionDeficiency: user.nutritionDeficiency || '',
      medicalIllness: user.medicalIllness || '',
      likedFoods: user.likedFoods || ''
    });
  }, [user]);

  useEffect(() => {
    let active = true;

    const loadCurrencies = async () => {
      try {
        const response = await toolsAPI.getCurrencies();
        if (!active) {
          return;
        }
        const nextOptions = Object.keys(response.data || {});
        if (nextOptions.length) {
          setCurrencyOptions(orderCurrencies(nextOptions));
        }
      } catch (error) {
        if (active) {
          setCurrencyOptions(orderCurrencies(['INR', 'USD', 'EUR', 'GBP']));
        }
      }
    };

    loadCurrencies();
    return () => {
      active = false;
    };
  }, []);

  const profileBmi = useMemo(() => {
    const heightCm = Number(profileForm.heightCm);
    const weightKg = Number(profileForm.weightKg);
    if (!Number.isFinite(heightCm) || !Number.isFinite(weightKg) || heightCm <= 0 || weightKg <= 0) {
      return null;
    }

    const value = weightKg / ((heightCm / 100) * (heightCm / 100));
    return Number(value.toFixed(1));
  }, [profileForm.heightCm, profileForm.weightKg]);

  const profileAvatarLabel = useMemo(
    () => profileForm.nickname.trim() || profileForm.name.trim() || user?.name || 'User',
    [profileForm.nickname, profileForm.name, user?.name]
  );

  const profileAvatarFallback = useMemo(
    () => buildFoodPlaceholderDataUrl(profileAvatarLabel, 'profile-avatar'),
    [profileAvatarLabel]
  );

  const profileAvatarSrc = useMemo(
    () =>
      getFoodImageSrc(
        {
          name: profileAvatarLabel,
          imageUrl: profileForm.profileImageUrl
        },
        {
          label: profileAvatarLabel,
          bucket: 'profile-avatar'
        }
      ),
    [profileAvatarLabel, profileForm.profileImageUrl]
  );

  const profileAvatarPresets = useMemo(
    () =>
      PROFILE_AVATAR_PRESET_BUCKETS.map((bucket) =>
        buildFoodPlaceholderDataUrl(profileAvatarLabel, `profile-${bucket}`)
      ),
    [profileAvatarLabel]
  );

  const enabledNotificationsCount = useMemo(
    () => Object.values(notifications).filter(Boolean).length,
    [notifications]
  );

  const settingsOverviewCards = useMemo(
    () => [
      {
        id: 'profile',
        label: 'Profile',
        value: profileAvatarLabel,
        detail: user?.email || 'No email'
      },
      {
        id: 'goal',
        label: 'Daily goal',
        value: `${user?.dailyCalorieGoal || 2200} cal`,
        detail: profileBmi == null ? 'BMI: Not set' : `BMI: ${profileBmi}`
      },
      {
        id: 'preferences',
        label: 'Plan defaults',
        value: prefDietary,
        detail: prefRegion || 'Global'
      },
      {
        id: 'signals',
        label: 'Active alerts',
        value: `${enabledNotificationsCount}/3`,
        detail: `${prefCurrencies.length} currencies · Voice ${voiceReviewEnabled ? 'manual' : 'auto'}`
      }
    ],
    [
      enabledNotificationsCount,
      prefCurrencies.length,
      prefDietary,
      prefRegion,
      voiceReviewEnabled,
      profileAvatarLabel,
      profileBmi,
      user?.dailyCalorieGoal,
      user?.email
    ]
  );

  const updateProfileField = (field, value) => {
    setProfileForm((previous) => ({ ...previous, [field]: value }));
  };

  const togglePrefCurrency = (currency) => {
    setPrefCurrencies((previous) =>
      previous.includes(currency) ? previous.filter((item) => item !== currency) : [...previous, currency]
    );
  };

  const clearProfileAvatar = () => {
    updateProfileField('profileImageUrl', '');
    if (profileImageInputRef.current) {
      profileImageInputRef.current.value = '';
    }
    setProfileStatus('Profile picture removed. Save profile to apply.');
  };

  const selectPresetAvatar = (imageUrl) => {
    updateProfileField('profileImageUrl', imageUrl);
    setProfileStatus('Profile picture selected. Save profile to apply.');
  };

  const handleProfileImageUpload = async (event) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }

    if (!String(file.type || '').startsWith('image/')) {
      setProfileStatus('Choose a valid image file.');
      event.target.value = '';
      return;
    }

    if (file.size > MAX_PROFILE_FILE_BYTES) {
      setProfileStatus('Image is too large. Please use an image smaller than 5MB.');
      event.target.value = '';
      return;
    }

    try {
      const optimizedImage = await resizeProfileImage(file);
      updateProfileField('profileImageUrl', optimizedImage);
      setProfileStatus('Profile picture uploaded. Save profile to apply.');
    } catch (error) {
      setProfileStatus('Failed to process selected image.');
    } finally {
      event.target.value = '';
    }
  };

  const saveProfile = async (event) => {
    event.preventDefault();
    setProfileStatus('');

    if (!user?.id) {
      setProfileStatus('Login required to edit profile.');
      return;
    }

    if (!profileForm.name.trim()) {
      setProfileStatus('Name is required.');
      return;
    }

    try {
      setProfileBusy(true);

      const payload = {
        name: profileForm.name.trim(),
        nickname: profileForm.nickname.trim() || null,
        profileImageUrl: profileForm.profileImageUrl.trim() || null,
        age: toNullableNumber(profileForm.age, (value) => parseInt(value, 10)),
        bloodGroup: profileForm.bloodGroup || null,
        heightCm: toNullableNumber(profileForm.heightCm),
        weightKg: toNullableNumber(profileForm.weightKg),
        region: profileForm.region.trim() || null,
        state: profileForm.state.trim() || null,
        city: profileForm.city.trim() || null,
        nutritionDeficiency: profileForm.nutritionDeficiency.trim() || null,
        medicalIllness: profileForm.medicalIllness.trim() || null,
        likedFoods: profileForm.likedFoods.trim() || null
      };

      const response = await userAPI.updateProfile(user.id, payload);
      onUserUpdated?.(response.data);
      setProfileStatus('Profile saved.');
    } catch (error) {
      setProfileStatus(error?.response?.data?.message || 'Failed to save profile.');
    } finally {
      setProfileBusy(false);
    }
  };

  const saveGoal = async (event) => {
    event.preventDefault();
    setGoalStatus('');

    if (!user?.id) {
      setGoalStatus('Login required to edit goal.');
      return;
    }

    const nextGoal = Number(goalInput);
    if (!Number.isFinite(nextGoal) || nextGoal < 800) {
      setGoalStatus('Enter a valid goal (minimum 800 calories).');
      return;
    }

    try {
      setGoalBusy(true);
      const response = await userAPI.updateGoal(user.id, nextGoal);
      onUserUpdated?.(response.data);
      setGoalStatus('Goal saved.');
    } catch (error) {
      setGoalStatus(error?.response?.data?.message || 'Failed to save goal.');
    } finally {
      setGoalBusy(false);
    }
  };

  const savePreferences = (event) => {
    event.preventDefault();
    setPrefStatus('');

    if (!prefCurrencies.length) {
      setPrefStatus('Choose at least one currency.');
      return;
    }

    onPreferencesChange?.({
      region: prefRegion.trim() || 'Global',
      dietaryPreference: prefDietary,
      currencies: prefCurrencies,
      voiceRecognitionMode: voiceReviewEnabled ? 'manual' : 'auto'
    });

    setPrefStatus(`Integrations saved. Voice mode: ${voiceReviewEnabled ? 'manual review' : 'hands-free auto'}.`);
  };

  const toggleNotification = (field) => {
    setNotifications((previous) => ({ ...previous, [field]: !previous[field] }));
  };

  const saveNotifications = (event) => {
    event.preventDefault();
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(NOTIFICATION_PREF_KEY, JSON.stringify(notifications));
    }
    setNotificationStatus('Notification preferences saved.');
  };

  const exportProfileSnapshot = () => {
    if (typeof window === 'undefined') {
      return;
    }

    const payload = {
      exportedAt: new Date().toISOString(),
      user,
      preferences: {
        region: prefRegion,
        dietaryPreference: prefDietary,
        currencies: prefCurrencies,
        voiceRecognitionMode: voiceReviewEnabled ? 'manual' : 'auto'
      },
      notifications
    };

    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'calorie-tracker-settings.json';
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    window.URL.revokeObjectURL(url);
    setNotificationStatus('Settings export downloaded.');
  };

  return (
    <div className="page-stack settings-page settings-clean">
      <section className="panel settings-tabs-panel">
        <nav className="settings-tabs" aria-label="Settings sections">
          {SETTINGS_TABS.map((tab) => (
            <button
              key={tab.id}
              type="button"
              className={activeTab === tab.id ? 'settings-tab active' : 'settings-tab'}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </nav>
      </section>

      <section className="panel settings-overview-panel" aria-label="Settings overview">
        <div className="settings-overview-grid">
          {settingsOverviewCards.map((card) => (
            <article key={card.id} className="settings-overview-card">
              <span>{card.label}</span>
              <strong>{card.value}</strong>
              <small>{card.detail}</small>
            </article>
          ))}
        </div>
      </section>

      {activeTab === 'account' && (
        <section className="panel settings-group-panel">
          <div className="settings-group-header">
            <h2>Account</h2>
            <p>Profile identity and health details.</p>
          </div>

          <form onSubmit={saveProfile} className="settings-form settings-form-clean">
            <div className="settings-profile-photo">
              <div className="settings-avatar-preview settings-avatar-preview-large">
                <img
                  src={profileAvatarSrc}
                  alt={`${profileAvatarLabel} profile`}
                  data-fallback={profileAvatarFallback}
                  onError={(event) => {
                    event.currentTarget.onerror = null;
                    event.currentTarget.src = event.currentTarget.dataset.fallback || profileAvatarFallback;
                  }}
                />
              </div>

              <div className="settings-avatar-actions">
                <div className="settings-avatar-button-row">
                  <button type="button" className="secondary-btn" onClick={() => profileImageInputRef.current?.click()}>
                    Upload photo
                  </button>
                  <button type="button" className="ghost-btn" onClick={clearProfileAvatar}>
                    Remove
                  </button>
                  <input
                    ref={profileImageInputRef}
                    type="file"
                    accept="image/*"
                    className="settings-avatar-input-hidden"
                    onChange={handleProfileImageUpload}
                  />
                </div>

                <label>
                  <span className="settings-label">Profile image URL</span>
                  <input
                    value={profileForm.profileImageUrl}
                    onChange={(event) => updateProfileField('profileImageUrl', event.target.value)}
                    placeholder="https://example.com/profile.jpg"
                  />
                </label>

                <div className="settings-avatar-preset-grid">
                  {profileAvatarPresets.map((preset, index) => (
                    <button
                      key={`profile-preset-${index + 1}`}
                      type="button"
                      className="settings-avatar-preset"
                      onClick={() => selectPresetAvatar(preset)}
                      aria-label={`Select avatar preset ${index + 1}`}
                    >
                      <img src={preset} alt={`Preset ${index + 1}`} />
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="settings-grid-three">
              <label>
                <span className="settings-label">Name</span>
                <input value={profileForm.name} onChange={(event) => updateProfileField('name', event.target.value)} />
              </label>
              <label>
                <span className="settings-label">Nickname</span>
                <input
                  value={profileForm.nickname}
                  onChange={(event) => updateProfileField('nickname', event.target.value)}
                  placeholder="Display name"
                />
              </label>
              <label>
                <span className="settings-label">Age</span>
                <input
                  type="number"
                  min="1"
                  max="120"
                  value={profileForm.age}
                  onChange={(event) => updateProfileField('age', event.target.value)}
                />
              </label>
            </div>

            <div className="settings-grid-four">
              <label>
                <span className="settings-label">Blood group</span>
                <select
                  value={profileForm.bloodGroup}
                  onChange={(event) => updateProfileField('bloodGroup', event.target.value)}
                >
                  <option value="">Select</option>
                  {BLOOD_GROUP_OPTIONS.map((value) => (
                    <option key={value} value={value}>
                      {value}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span className="settings-label">Height (cm)</span>
                <input
                  type="number"
                  min="50"
                  max="280"
                  value={profileForm.heightCm}
                  onChange={(event) => updateProfileField('heightCm', event.target.value)}
                />
              </label>
              <label>
                <span className="settings-label">Weight (kg)</span>
                <input
                  type="number"
                  min="20"
                  max="500"
                  value={profileForm.weightKg}
                  onChange={(event) => updateProfileField('weightKg', event.target.value)}
                />
              </label>
              <label>
                <span className="settings-label">BMI</span>
                <input value={profileBmi == null ? '' : profileBmi} readOnly />
              </label>
            </div>

            <div className="settings-grid-three">
              <label>
                <span className="settings-label">Region</span>
                <input value={profileForm.region} onChange={(event) => updateProfileField('region', event.target.value)} />
              </label>
              <label>
                <span className="settings-label">State</span>
                <input value={profileForm.state} onChange={(event) => updateProfileField('state', event.target.value)} />
              </label>
              <label>
                <span className="settings-label">City</span>
                <input value={profileForm.city} onChange={(event) => updateProfileField('city', event.target.value)} />
              </label>
            </div>

            <div className="settings-grid-two">
              <label>
                <span className="settings-label">Nutrition deficiency</span>
                <input
                  value={profileForm.nutritionDeficiency}
                  onChange={(event) => updateProfileField('nutritionDeficiency', event.target.value)}
                  placeholder="Example: Iron deficiency"
                />
              </label>
              <label>
                <span className="settings-label">Medical illness</span>
                <input
                  value={profileForm.medicalIllness}
                  onChange={(event) => updateProfileField('medicalIllness', event.target.value)}
                  placeholder="Example: Diabetes"
                />
              </label>
            </div>

            <label>
              <span className="settings-label">Liked foods</span>
              <input
                value={profileForm.likedFoods}
                onChange={(event) => updateProfileField('likedFoods', event.target.value)}
                placeholder="Example: Dosa, Idli, Chicken noodles"
              />
            </label>

            <button type="submit" className="primary-btn" disabled={profileBusy}>
              {profileBusy ? 'Saving...' : 'Save account'}
            </button>
          </form>

          {profileStatus && <div className="status-line">{profileStatus}</div>}
        </section>
      )}

      {activeTab === 'goals' && (
        <section className="panel settings-group-panel">
          <div className="settings-group-header">
            <h2>Goals</h2>
            <p>Daily target and body metrics.</p>
          </div>

          <form onSubmit={saveGoal} className="settings-goal-form">
            <label>
              <span className="settings-label">Daily calorie goal</span>
              <input
                type="number"
                min="800"
                step="50"
                value={goalInput}
                onChange={(event) => setGoalInput(event.target.value)}
              />
            </label>
            <button type="submit" className="primary-btn" disabled={goalBusy}>
              {goalBusy ? 'Saving...' : 'Save goal'}
            </button>
          </form>

          <div className="settings-goal-grid">
            <article className="settings-note-card">
              <h3>Current goal</h3>
              <p>{user?.dailyCalorieGoal || 2200} calories/day</p>
            </article>
            <article className="settings-note-card">
              <h3>BMI</h3>
              <p>{profileBmi == null ? 'Not enough data' : profileBmi}</p>
            </article>
            <article className="settings-note-card">
              <h3>Primary diet</h3>
              <p>{prefDietary}</p>
            </article>
          </div>

          {goalStatus && <div className="status-line">{goalStatus}</div>}
        </section>
      )}

      {activeTab === 'notifications' && (
        <section className="panel settings-group-panel">
          <div className="settings-group-header">
            <h2>Notifications</h2>
            <p>Keep alerts focused and minimal.</p>
          </div>

          <form onSubmit={saveNotifications} className="settings-form settings-form-clean">
            <label className="settings-toggle-row">
              <span>
                <strong>Meal reminders</strong>
                <small>Gentle reminders to log meals.</small>
              </span>
              <input
                type="checkbox"
                checked={notifications.mealReminders}
                onChange={() => toggleNotification('mealReminders')}
              />
            </label>

            <label className="settings-toggle-row">
              <span>
                <strong>Hydration reminders</strong>
                <small>Water prompts during the day.</small>
              </span>
              <input
                type="checkbox"
                checked={notifications.hydrationReminders}
                onChange={() => toggleNotification('hydrationReminders')}
              />
            </label>

            <label className="settings-toggle-row">
              <span>
                <strong>Weekly digest</strong>
                <small>One weekly progress summary.</small>
              </span>
              <input
                type="checkbox"
                checked={notifications.weeklyDigest}
                onChange={() => toggleNotification('weeklyDigest')}
              />
            </label>

            <button type="submit" className="primary-btn">
              Save notifications
            </button>
          </form>

          {notificationStatus && <div className="status-line">{notificationStatus}</div>}
        </section>
      )}

      {activeTab === 'integrations' && (
        <section className="panel settings-group-panel">
          <div className="settings-group-header">
            <h2>Integrations</h2>
            <p>Regional defaults and currency display.</p>
          </div>

          <form onSubmit={savePreferences} className="settings-form settings-form-clean">
            <div className="settings-grid-two">
              <label>
                <span className="settings-label">Default region</span>
                <input value={prefRegion} placeholder="Global" onChange={(event) => setPrefRegion(event.target.value)} />
              </label>
              <label>
                <span className="settings-label">Dietary preference</span>
                <select value={prefDietary} onChange={(event) => setPrefDietary(event.target.value)}>
                  {DIETARY_OPTIONS.map((item) => (
                    <option key={item} value={item}>
                      {item}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div>
              <span className="settings-label">Display currencies</span>
              <div className="settings-chip-grid">
                {currencyOptions.map((currency) => (
                  <button
                    key={currency}
                    type="button"
                    className={prefCurrencies.includes(currency) ? 'settings-chip active' : 'settings-chip'}
                    onClick={() => togglePrefCurrency(currency)}
                  >
                    {currency}
                  </button>
                ))}
              </div>
            </div>

            <label className="settings-toggle-row">
              <span>
                <strong>Voice search manual review</strong>
                <small>When on, voice input shows suggestions first. When off, best match is selected hands-free.</small>
              </span>
              <input
                type="checkbox"
                checked={voiceReviewEnabled}
                onChange={() => setVoiceReviewEnabled((previous) => !previous)}
              />
            </label>

            <button type="submit" className="primary-btn">
              Save integrations
            </button>
          </form>

          {prefStatus && <div className="status-line">{prefStatus}</div>}
        </section>
      )}

      {activeTab === 'privacy' && (
        <section className="panel settings-group-panel">
          <div className="settings-group-header">
            <h2>Data & Privacy</h2>
            <p>Export, support, and credits.</p>
          </div>

          <div className="settings-note-grid">
            <article className="settings-note-card">
              <h3>Support</h3>
              <p>If backend is down, run the app launcher and refresh. Guest mode can still be used for demos.</p>
            </article>
            <article className="settings-note-card">
              <h3>Credits</h3>
              <p>Founder: Mr.G from Tuticorin.</p>
            </article>
            <article className="settings-note-card">
              <h3>Funding</h3>
              <p>Support the creator if you love this app and want to help future growth.</p>
            </article>
          </div>

          <div className="settings-privacy-actions">
            <button type="button" className="secondary-btn" onClick={exportProfileSnapshot}>
              Export data snapshot
            </button>
            <button type="button" className="ghost-btn" onClick={() => setNotificationStatus('Thanks for supporting the creator.') }>
              Donate to creator
            </button>
          </div>

          {notificationStatus && <div className="status-line">{notificationStatus}</div>}
        </section>
      )}
    </div>
  );
}

export default SettingsPage;
