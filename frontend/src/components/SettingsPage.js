import React, { useEffect, useMemo, useState } from 'react';
import { toolsAPI, userAPI } from '../services/api';
import './SettingsPage.css';

const DIETARY_OPTIONS = ['No restrictions', 'Vegetarian', 'Vegan', 'Non-vegetarian'];
const BLOOD_GROUP_OPTIONS = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];

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

function SettingsSection({ title, children, isOpen, onToggle }) {
  return (
    <section className={`panel settings-panel settings-accordion ${isOpen ? 'open' : ''}`}>
      <button type="button" className="settings-accordion-toggle" aria-expanded={isOpen} onClick={onToggle}>
        <span>{title}</span>
        <span className="settings-accordion-icon" aria-hidden="true">
          {isOpen ? '−' : '+'}
        </span>
      </button>

      {isOpen && <div className="settings-accordion-body">{children}</div>}
    </section>
  );
}

function SettingsPage({ user, onUserUpdated, preferences, onPreferencesChange }) {
  const [openSectionKey, setOpenSectionKey] = useState('profile');

  const [currencyOptions, setCurrencyOptions] = useState(orderCurrencies(['INR', 'USD', 'EUR', 'GBP']));
  const [prefRegion, setPrefRegion] = useState(preferences?.region || 'Global');
  const [prefDietary, setPrefDietary] = useState(preferences?.dietaryPreference || 'No restrictions');
  const [prefCurrencies, setPrefCurrencies] = useState(
    orderCurrencies(preferences?.currencies || ['INR', 'USD', 'EUR', 'GBP'])
  );
  const [prefStatus, setPrefStatus] = useState('');

  const [profileBusy, setProfileBusy] = useState(false);
  const [profileStatus, setProfileStatus] = useState('');
  const [profileForm, setProfileForm] = useState({
    name: '',
    nickname: '',
    age: '',
    bloodGroup: '',
    heightCm: '',
    weightKg: '',
    region: '',
    state: '',
    city: '',
    nutritionDeficiency: '',
    medicalIllness: ''
  });

  useEffect(() => {
    setPrefRegion(preferences?.region || 'Global');
    setPrefDietary(preferences?.dietaryPreference || 'No restrictions');
    setPrefCurrencies(orderCurrencies(preferences?.currencies || ['INR', 'USD', 'EUR', 'GBP']));
  }, [preferences]);

  useEffect(() => {
    if (!user) {
      return;
    }

    setProfileForm({
      name: user.name || '',
      nickname: user.nickname || '',
      age: user.age ?? '',
      bloodGroup: user.bloodGroup || '',
      heightCm: user.heightCm ?? '',
      weightKg: user.weightKg ?? '',
      region: user.region || '',
      state: user.state || '',
      city: user.city || '',
      nutritionDeficiency: user.nutritionDeficiency || '',
      medicalIllness: user.medicalIllness || ''
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

  const updateProfileField = (field, value) => {
    setProfileForm((previous) => ({ ...previous, [field]: value }));
  };

  const toggleSection = (sectionKey) => {
    setOpenSectionKey((previous) => (previous === sectionKey ? '' : sectionKey));
  };

  const togglePrefCurrency = (currency) => {
    setPrefCurrencies((previous) =>
      previous.includes(currency) ? previous.filter((item) => item !== currency) : [...previous, currency]
    );
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
      currencies: prefCurrencies
    });

    setPrefStatus('Preferences saved.');
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
        age: toNullableNumber(profileForm.age, (value) => parseInt(value, 10)),
        bloodGroup: profileForm.bloodGroup || null,
        heightCm: toNullableNumber(profileForm.heightCm),
        weightKg: toNullableNumber(profileForm.weightKg),
        region: profileForm.region.trim() || null,
        state: profileForm.state.trim() || null,
        city: profileForm.city.trim() || null,
        nutritionDeficiency: profileForm.nutritionDeficiency.trim() || null,
        medicalIllness: profileForm.medicalIllness.trim() || null
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

  return (
    <div className="page-stack settings-page">
      <SettingsSection title="Profile" isOpen={openSectionKey === 'profile'} onToggle={() => toggleSection('profile')}>
        <form onSubmit={saveProfile} className="settings-form">
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

          <button type="submit" className="primary-btn" disabled={profileBusy}>
            {profileBusy ? 'Saving...' : 'Save Profile'}
          </button>
        </form>

        {profileStatus && <div className="status-line">{profileStatus}</div>}
      </SettingsSection>

      <SettingsSection
        title="Preferences"
        isOpen={openSectionKey === 'preferences'}
        onToggle={() => toggleSection('preferences')}
      >
        <form onSubmit={savePreferences} className="settings-form">
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
            <span className="settings-label">Currencies</span>
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

          <button type="submit" className="primary-btn">
            Save Preferences
          </button>
        </form>

        {prefStatus && <div className="status-line">{prefStatus}</div>}
      </SettingsSection>

      <SettingsSection title="Help" isOpen={openSectionKey === 'help'} onToggle={() => toggleSection('help')}>
        <div className="settings-note-grid">
          <div className="settings-note-card">
            <h3>Support</h3>
            <p>If the app does not load, run <code>./scripts/start-all.sh</code> and refresh the browser.</p>
          </div>
          <div className="settings-note-card">
            <h3>Feedback</h3>
            <p>Use guest mode to demo quickly, then sign in to save profile and preferences.</p>
          </div>
        </div>
      </SettingsSection>

      <SettingsSection title="Credits" isOpen={openSectionKey === 'credits'} onToggle={() => toggleSection('credits')}>
        <div className="settings-note-card">
          <h3>Founder</h3>
          <p>Mr.G from Tuticorin</p>
        </div>
      </SettingsSection>
    </div>
  );
}

export default SettingsPage;
