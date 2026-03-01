import React, { useEffect, useMemo, useState } from 'react';
import { toolsAPI } from '../services/api';
import { formatAmount } from '../utils/food';
import './ToolsPage.css';

const FALLBACK_VITAMIN_DEFICIENCIES = [
  'Vitamin A Deficiency',
  'Vitamin B1 Deficiency',
  'Vitamin B2 Deficiency',
  'Vitamin B3 Deficiency',
  'Vitamin B5 Deficiency',
  'Vitamin B6 Deficiency',
  'Vitamin B7 Deficiency',
  'Vitamin B9 Deficiency',
  'Vitamin B12 Deficiency',
  'Vitamin C Deficiency',
  'Vitamin D Deficiency',
  'Vitamin E Deficiency',
  'Vitamin K Deficiency'
];

const FALLBACK_MINERAL_DEFICIENCIES = [
  'Iron Deficiency',
  'Calcium Deficiency',
  'Magnesium Deficiency',
  'Zinc Deficiency',
  'Potassium Deficiency',
  'Phosphorus Deficiency',
  'Iodine Deficiency',
  'Selenium Deficiency',
  'Copper Deficiency',
  'Manganese Deficiency',
  'Chromium Deficiency',
  'Molybdenum Deficiency',
  'Sodium Deficiency',
  'Chloride Deficiency',
  'Fluoride Deficiency'
];

const FALLBACK_OTHER_DEFICIENCIES = ['Protein Deficiency', 'Fiber Deficiency', 'Omega-3 Deficiency'];

const MEDICAL_OPTIONS = [
  'Diabetes',
  'Hypertension',
  'High Cholesterol',
  'PCOS',
  'Thyroid',
  'Anemia',
  'Fatty Liver',
  'IBS'
];

const DIETARY_OPTIONS = ['No restrictions', 'Vegetarian', 'Vegan', 'Non-vegetarian'];
const CHEAT_DAY_LEVEL_LABELS = {
  1: 'Light',
  2: 'Relaxed',
  3: 'Moderate',
  4: 'Heavy',
  5: 'Festival'
};
const DEFAULT_CHEAT_TITLE = 'Cheat Day';

const todayIsoDate = () => new Date().toISOString().slice(0, 10);

const parseCommaList = (value) =>
  String(value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);

const firstItems = (list, max = 6) => (Array.isArray(list) ? list.slice(0, max) : []);

const toNullableInteger = (value) => {
  if (value === '' || value == null) {
    return null;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return null;
  }
  return Math.round(parsed);
};

const orderCurrencies = (list = []) => {
  const unique = Array.from(new Set((list || []).filter(Boolean).map((item) => String(item).trim().toUpperCase())));
  const withoutInr = unique.filter((item) => item !== 'INR');
  return unique.includes('INR') ? ['INR', ...withoutInr] : unique;
};

const bmiCategory = (bmi) => {
  if (!Number.isFinite(bmi) || bmi <= 0) {
    return 'N/A';
  }
  if (bmi < 18.5) {
    return 'Underweight';
  }
  if (bmi < 25) {
    return 'Healthy';
  }
  if (bmi < 30) {
    return 'Overweight';
  }
  return 'Obese';
};

function ToolsPage({ preferences, userProfile }) {
  const [activeToolsView, setActiveToolsView] = useState('plan');
  const [activeDeficiencyGroup, setActiveDeficiencyGroup] = useState('vitamins');
  const [planAdvancedOpen, setPlanAdvancedOpen] = useState(false);
  const [deficiencies, setDeficiencies] = useState([]);
  const [customDeficiency, setCustomDeficiency] = useState('');
  const [medicalConditions, setMedicalConditions] = useState([]);
  const [customMedical, setCustomMedical] = useState('');
  const [region, setRegion] = useState(preferences?.region || 'Global');
  const [dietaryPreference, setDietaryPreference] = useState(
    preferences?.dietaryPreference || 'No restrictions'
  );
  const [includeSupplements, setIncludeSupplements] = useState(true);
  const [currencyOptions, setCurrencyOptions] = useState([]);
  const [currencies, setCurrencies] = useState(orderCurrencies(preferences?.currencies || ['INR', 'USD', 'EUR', 'GBP']));
  const [vitaminDeficiencyOptions, setVitaminDeficiencyOptions] = useState(FALLBACK_VITAMIN_DEFICIENCIES);
  const [mineralDeficiencyOptions, setMineralDeficiencyOptions] = useState(FALLBACK_MINERAL_DEFICIENCIES);
  const [otherDeficiencyOptions, setOtherDeficiencyOptions] = useState(FALLBACK_OTHER_DEFICIENCIES);
  const [heightCm, setHeightCm] = useState(userProfile?.heightCm || '');
  const [weightKg, setWeightKg] = useState(userProfile?.weightKg || '');
  const [goalInput, setGoalInput] = useState(userProfile?.dailyCalorieGoal || 2200);
  const [likedFoodsInput, setLikedFoodsInput] = useState(userProfile?.likedFoods || '');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState('');
  const [result, setResult] = useState(null);
  const [cheatDayDate, setCheatDayDate] = useState(todayIsoDate());
  const [cheatDayTitle, setCheatDayTitle] = useState(DEFAULT_CHEAT_TITLE);
  const [cheatDayNote, setCheatDayNote] = useState('');
  const [cheatDayLevel, setCheatDayLevel] = useState(3);
  const [cheatDayExtraCalories, setCheatDayExtraCalories] = useState('');
  const [cheatDayBusy, setCheatDayBusy] = useState(false);
  const [cheatDayStatus, setCheatDayStatus] = useState('');
  const [cheatDays, setCheatDays] = useState([]);

  useEffect(() => {
    let active = true;

    const loadToolsOptions = async () => {
      try {
        const [currencyResponse, deficienciesResponse] = await Promise.all([
          toolsAPI.getCurrencies(),
          toolsAPI.getDeficiencies()
        ]);
        if (!active) {
          return;
        }

        const nextOptions = orderCurrencies(Object.keys(currencyResponse.data || {}));
        setCurrencyOptions(nextOptions);
        if (!currencies.length && nextOptions.length) {
          setCurrencies(nextOptions.slice(0, 4));
        }

        const vitamins = Array.isArray(deficienciesResponse?.data?.vitamins)
          ? deficienciesResponse.data.vitamins
          : FALLBACK_VITAMIN_DEFICIENCIES;
        const minerals = Array.isArray(deficienciesResponse?.data?.minerals)
          ? deficienciesResponse.data.minerals
          : FALLBACK_MINERAL_DEFICIENCIES;
        const other = Array.isArray(deficienciesResponse?.data?.other)
          ? deficienciesResponse.data.other
          : FALLBACK_OTHER_DEFICIENCIES;

        setVitaminDeficiencyOptions(vitamins.length ? vitamins : FALLBACK_VITAMIN_DEFICIENCIES);
        setMineralDeficiencyOptions(minerals.length ? minerals : FALLBACK_MINERAL_DEFICIENCIES);
        setOtherDeficiencyOptions(other.length ? other : FALLBACK_OTHER_DEFICIENCIES);
      } catch (error) {
        if (active) {
          setCurrencyOptions(orderCurrencies(['INR', 'USD', 'EUR', 'GBP']));
          setVitaminDeficiencyOptions(FALLBACK_VITAMIN_DEFICIENCIES);
          setMineralDeficiencyOptions(FALLBACK_MINERAL_DEFICIENCIES);
          setOtherDeficiencyOptions(FALLBACK_OTHER_DEFICIENCIES);
        }
      }
    };

    loadToolsOptions();

    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    setRegion(preferences?.region || 'Global');
    setDietaryPreference(preferences?.dietaryPreference || 'No restrictions');
    if (preferences?.currencies?.length) {
      setCurrencies(orderCurrencies(preferences.currencies));
    }
  }, [preferences]);

  useEffect(() => {
    if (!userProfile) {
      return;
    }
    if (userProfile.region && (!region || region === 'Global')) {
      setRegion(userProfile.region);
    }
    if (userProfile.heightCm != null) {
      setHeightCm(userProfile.heightCm);
    }
    if (userProfile.weightKg != null) {
      setWeightKg(userProfile.weightKg);
    }
    if (userProfile.dailyCalorieGoal != null) {
      setGoalInput(userProfile.dailyCalorieGoal);
    }
    if (userProfile.likedFoods != null) {
      setLikedFoodsInput(userProfile.likedFoods);
    }

    const deficiencyFromProfile = parseCommaList(userProfile.nutritionDeficiency);
    if (deficiencyFromProfile.length) {
      setDeficiencies((previous) => Array.from(new Set([...previous, ...deficiencyFromProfile])));
    }

    const conditionFromProfile = parseCommaList(userProfile.medicalIllness);
    if (conditionFromProfile.length) {
      setMedicalConditions((previous) => Array.from(new Set([...previous, ...conditionFromProfile])));
    }
  }, [userProfile]);

  useEffect(() => {
    let active = true;

    const loadCheatDays = async () => {
      if (!userProfile?.id) {
        if (active) {
          setCheatDays([]);
        }
        return;
      }

      try {
        const response = await toolsAPI.getCheatDays(userProfile.id, 36);
        if (!active) {
          return;
        }
        const items = Array.isArray(response?.data) ? response.data : [];
        setCheatDays(items);
      } catch (error) {
        if (active) {
          setCheatDays([]);
        }
      }
    };

    loadCheatDays();

    return () => {
      active = false;
    };
  }, [userProfile?.id]);

  useEffect(() => {
    if (activeToolsView !== 'plan') {
      setPlanAdvancedOpen(false);
    }
  }, [activeToolsView]);

  const toggleDeficiency = (deficiency) => {
    setDeficiencies((previous) =>
      previous.includes(deficiency)
        ? previous.filter((item) => item !== deficiency)
        : [...previous, deficiency]
    );
  };

  const addCustomDeficiency = () => {
    const next = customDeficiency.trim();
    if (!next) {
      return;
    }

    if (!deficiencies.includes(next)) {
      setDeficiencies((previous) => [...previous, next]);
    }

    setCustomDeficiency('');
  };

  const toggleMedical = (condition) => {
    setMedicalConditions((previous) =>
      previous.includes(condition)
        ? previous.filter((item) => item !== condition)
        : [...previous, condition]
    );
  };

  const addCustomMedical = () => {
    const next = customMedical.trim();
    if (!next) {
      return;
    }

    if (!medicalConditions.includes(next)) {
      setMedicalConditions((previous) => [...previous, next]);
    }

    setCustomMedical('');
  };

  const toggleCurrency = (currency) => {
    setCurrencies((previous) =>
      previous.includes(currency)
        ? previous.filter((item) => item !== currency)
        : [...previous, currency]
    );
  };

  const deficiencyOptionsByGroup = useMemo(
    () => ({
      vitamins: vitaminDeficiencyOptions,
      minerals: mineralDeficiencyOptions,
      other: otherDeficiencyOptions
    }),
    [mineralDeficiencyOptions, otherDeficiencyOptions, vitaminDeficiencyOptions]
  );

  const activeDeficiencyOptions = useMemo(
    () => deficiencyOptionsByGroup[activeDeficiencyGroup] || [],
    [activeDeficiencyGroup, deficiencyOptionsByGroup]
  );

  const deficiencyGroupCounts = useMemo(() => {
    const vitaminSet = new Set(vitaminDeficiencyOptions);
    const mineralSet = new Set(mineralDeficiencyOptions);
    const counts = { vitamins: 0, minerals: 0, other: 0 };

    deficiencies.forEach((item) => {
      if (vitaminSet.has(item)) {
        counts.vitamins += 1;
        return;
      }
      if (mineralSet.has(item)) {
        counts.minerals += 1;
        return;
      }
      counts.other += 1;
    });

    return counts;
  }, [deficiencies, mineralDeficiencyOptions, vitaminDeficiencyOptions]);

  const parsedHeight = Number(heightCm);
  const parsedWeight = Number(weightKg);
  const bmi = useMemo(() => {
    if (!Number.isFinite(parsedHeight) || !Number.isFinite(parsedWeight) || parsedHeight <= 0 || parsedWeight <= 0) {
      return null;
    }
    const value = parsedWeight / ((parsedHeight / 100) * (parsedHeight / 100));
    return Number(value.toFixed(1));
  }, [parsedHeight, parsedWeight]);

  const handleRun = async (event) => {
    event.preventDefault();
    setStatus('');

    if (!deficiencies.length && !medicalConditions.length) {
      setStatus('Select at least one vitamin/mineral deficiency or medical condition.');
      return;
    }

    if (!currencies.length) {
      setStatus('Select currency.');
      return;
    }

    const parsedGoal = Number(goalInput);
    if (!Number.isFinite(parsedGoal) || parsedGoal < 1000 || parsedGoal > 5000) {
      setStatus('Calorie goal must be between 1000 and 5000 kcal.');
      return;
    }

    try {
      setLoading(true);
      const response = await toolsAPI.getRecommendations({
        deficiencies,
        medicalConditions,
        region,
        dietaryPreference,
        includeSupplements,
        currencies,
        dailyCalorieGoal: Math.round(parsedGoal),
        likedFoods: parseCommaList(likedFoodsInput)
      });
      setResult(response.data);
      setActiveToolsView('results');
    } catch (error) {
      setResult(null);
      setStatus(error?.response?.data?.message || 'Unable to generate results.');
    } finally {
      setLoading(false);
    }
  };

  const recommendationCount = useMemo(() => (result?.recommendations || []).length, [result]);
  const deficiencyInsightCount = useMemo(
    () => (Array.isArray(result?.deficiencyInsights) ? result.deficiencyInsights.length : 0),
    [result]
  );
  const dietPlanSectionsCount = useMemo(
    () => (Array.isArray(result?.dietPlan) ? result.dietPlan.length : 0),
    [result]
  );
  const naturalRecommendations = useMemo(
    () => (result?.recommendations || []).filter((item) => String(item?.type || '').toUpperCase() !== 'SUPPLEMENT'),
    [result]
  );
  const supplementRecommendations = useMemo(
    () => (result?.recommendations || []).filter((item) => String(item?.type || '').toUpperCase() === 'SUPPLEMENT'),
    [result]
  );
  const activeCheatDay = useMemo(
    () => cheatDays.find((entry) => String(entry?.date || '') === cheatDayDate) || null,
    [cheatDayDate, cheatDays]
  );
  const cheatDaysThisMonth = useMemo(() => {
    const monthPrefix = todayIsoDate().slice(0, 7);
    return cheatDays.filter((entry) => String(entry?.date || '').startsWith(monthPrefix)).length;
  }, [cheatDays]);
  const selectedSignalsCount = deficiencies.length + medicalConditions.length;
  const selectedCurrenciesCount = currencies.length;

  useEffect(() => {
    if (activeCheatDay) {
      setCheatDayTitle(activeCheatDay.title || DEFAULT_CHEAT_TITLE);
      setCheatDayNote(activeCheatDay.note || '');
      setCheatDayLevel(Number(activeCheatDay.indulgenceLevel) || 3);
      setCheatDayExtraCalories(
        activeCheatDay.estimatedExtraCalories == null ? '' : String(activeCheatDay.estimatedExtraCalories)
      );
      return;
    }

    setCheatDayTitle(DEFAULT_CHEAT_TITLE);
    setCheatDayNote('');
    setCheatDayLevel(3);
    setCheatDayExtraCalories('');
  }, [activeCheatDay, cheatDayDate]);

  const upsertCheatDayInState = (entry) => {
    const dateKey = String(entry?.date || '');
    if (!dateKey) {
      return;
    }

    setCheatDays((previous) =>
      [...previous.filter((item) => String(item?.date || '') !== dateKey), entry].sort((left, right) =>
        String(right?.date || '').localeCompare(String(left?.date || ''))
      )
    );
  };

  const handleSaveCheatDay = async (event) => {
    event.preventDefault();
    setCheatDayStatus('');

    if (!userProfile?.id) {
      setCheatDayStatus('Login required to save cheat day.');
      return;
    }

    if (!cheatDayDate) {
      setCheatDayStatus('Choose date.');
      return;
    }

    const payload = {
      userId: userProfile.id,
      date: cheatDayDate,
      title: cheatDayTitle.trim() || DEFAULT_CHEAT_TITLE,
      note: cheatDayNote.trim() || null,
      indulgenceLevel: Number(cheatDayLevel) || 3,
      estimatedExtraCalories: toNullableInteger(cheatDayExtraCalories)
    };

    try {
      setCheatDayBusy(true);
      const response = await toolsAPI.saveCheatDay(payload);
      const saved = response?.data;
      if (saved?.date) {
        upsertCheatDayInState(saved);
      }
      setCheatDayStatus('Cheat day saved.');
    } catch (error) {
      setCheatDayStatus(error?.response?.data?.message || 'Unable to save cheat day.');
    } finally {
      setCheatDayBusy(false);
    }
  };

  const handleDeleteCheatDay = async (date) => {
    const dateValue = String(date || '').trim();
    if (!dateValue) {
      return;
    }
    if (!userProfile?.id) {
      setCheatDayStatus('Login required to delete cheat day.');
      return;
    }

    try {
      setCheatDayBusy(true);
      await toolsAPI.deleteCheatDay(userProfile.id, dateValue);
      setCheatDays((previous) => previous.filter((item) => String(item?.date || '') !== dateValue));
      setCheatDayStatus('Cheat day removed.');
    } catch (error) {
      setCheatDayStatus(error?.response?.data?.message || 'Unable to remove cheat day.');
    } finally {
      setCheatDayBusy(false);
    }
  };

  const jumpToTodayCheatDay = () => {
    setCheatDayDate(todayIsoDate());
    setCheatDayStatus('');
  };

  return (
    <div className="page-stack tools-page">
      <section className="panel tools-config-panel">
        <div className="panel-title-row tools-title-row">
          <div>
            <h2>Tools</h2>
            <p>Plan and track in a simple flow</p>
          </div>
        </div>

        <div className="tools-overview-grid" aria-label="Plan summary">
          <article className="tools-overview-card">
            <span>Health targets</span>
            <strong>{selectedSignalsCount}</strong>
          </article>
          <article className="tools-overview-card">
            <span>Region</span>
            <strong>{region || 'Global'}</strong>
          </article>
          <article className="tools-overview-card">
            <span>Diet type</span>
            <strong>{dietaryPreference}</strong>
          </article>
          <article className="tools-overview-card">
            <span>Currencies</span>
            <strong>{selectedCurrenciesCount}</strong>
          </article>
        </div>

        <div className="tools-view-switch" role="tablist" aria-label="Tools views">
          <button
            type="button"
            role="tab"
            aria-selected={activeToolsView === 'plan'}
            className={activeToolsView === 'plan' ? 'active' : ''}
            onClick={() => setActiveToolsView('plan')}
          >
            Plan Setup
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeToolsView === 'cheat'}
            className={activeToolsView === 'cheat' ? 'active' : ''}
            onClick={() => setActiveToolsView('cheat')}
          >
            Cheat Day
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeToolsView === 'results'}
            className={activeToolsView === 'results' ? 'active' : ''}
            onClick={() => setActiveToolsView('results')}
          >
            Results
          </button>
        </div>

        {activeToolsView !== 'cheat' && (
          <form className="tools-form tools-form-minimal" onSubmit={handleRun}>
            <div className="tools-flow-strip" aria-label="Tools flow">
              <span>1. Select health signals</span>
              <span>2. Set goal</span>
              <span>3. Generate plan</span>
            </div>

            <section className="tools-form-section">
              <div className="tools-section-head">
                <h3>Health signals</h3>
                <small>Deficiencies and medical conditions</small>
              </div>

              <div className="tools-columns">
                <div className="tools-signal-card">
                  <div className="tools-field-head">
                    <label className="tools-label">Deficiency</label>
                    {!!deficiencies.length && (
                      <button type="button" className="ghost-link-btn" onClick={() => setDeficiencies([])}>
                        Clear
                      </button>
                    )}
                  </div>

                  <div className="tools-deficiency-tabs" role="tablist" aria-label="Deficiency groups">
                    <button
                      type="button"
                      role="tab"
                      aria-selected={activeDeficiencyGroup === 'vitamins'}
                      className={activeDeficiencyGroup === 'vitamins' ? 'active' : ''}
                      onClick={() => setActiveDeficiencyGroup('vitamins')}
                    >
                      Vitamins ({deficiencyGroupCounts.vitamins})
                    </button>
                    <button
                      type="button"
                      role="tab"
                      aria-selected={activeDeficiencyGroup === 'minerals'}
                      className={activeDeficiencyGroup === 'minerals' ? 'active' : ''}
                      onClick={() => setActiveDeficiencyGroup('minerals')}
                    >
                      Minerals ({deficiencyGroupCounts.minerals})
                    </button>
                    <button
                      type="button"
                      role="tab"
                      aria-selected={activeDeficiencyGroup === 'other'}
                      className={activeDeficiencyGroup === 'other' ? 'active' : ''}
                      onClick={() => setActiveDeficiencyGroup('other')}
                    >
                      Other ({deficiencyGroupCounts.other})
                    </button>
                  </div>

                  <div className="chip-grid chip-grid-dense">
                    {activeDeficiencyOptions.map((item) => (
                      <button
                        key={item}
                        type="button"
                        className={deficiencies.includes(item) ? 'chip active' : 'chip'}
                        onClick={() => toggleDeficiency(item)}
                      >
                        {item}
                      </button>
                    ))}
                  </div>

                  <div className="inline-input-row">
                    <input
                      value={customDeficiency}
                      placeholder="Add deficiency"
                      onChange={(event) => setCustomDeficiency(event.target.value)}
                    />
                    <button type="button" className="secondary-btn" onClick={addCustomDeficiency}>
                      Add
                    </button>
                  </div>
                </div>

                <div className="tools-signal-card">
                  <div className="tools-field-head">
                    <label className="tools-label">Medical</label>
                    {!!medicalConditions.length && (
                      <button type="button" className="ghost-link-btn" onClick={() => setMedicalConditions([])}>
                        Clear
                      </button>
                    )}
                  </div>
                  <div className="chip-grid chip-grid-dense">
                    {MEDICAL_OPTIONS.map((item) => (
                      <button
                        key={item}
                        type="button"
                        className={medicalConditions.includes(item) ? 'chip active' : 'chip'}
                        onClick={() => toggleMedical(item)}
                      >
                        {item}
                      </button>
                    ))}
                  </div>
                  <div className="inline-input-row">
                    <input
                      value={customMedical}
                      placeholder="Add condition"
                      onChange={(event) => setCustomMedical(event.target.value)}
                    />
                    <button type="button" className="secondary-btn" onClick={addCustomMedical}>
                      Add
                    </button>
                  </div>
                </div>
              </div>
            </section>

            <section className="tools-form-section">
              <div className="tools-section-head">
                <h3>Goal setup</h3>
                <small>Target and food preference for personalization</small>
              </div>
              <div className="tools-grid-two tools-grid-tight">
                <label>
                  Goal (kcal/day)
                  <input
                    type="number"
                    min="1000"
                    max="5000"
                    value={goalInput}
                    onChange={(event) => setGoalInput(event.target.value)}
                  />
                </label>
                <label>
                  Liked foods
                  <input
                    value={likedFoodsInput}
                    placeholder="Example: dosa, idli, grilled chicken"
                    onChange={(event) => setLikedFoodsInput(event.target.value)}
                  />
                </label>
              </div>
            </section>

            <button
              type="button"
              className={`tools-advanced-toggle ${planAdvancedOpen ? 'is-open' : ''}`}
              onClick={() => setPlanAdvancedOpen((previous) => !previous)}
            >
              {planAdvancedOpen ? 'Hide advanced settings' : 'Show advanced settings'}
            </button>

            {planAdvancedOpen && (
              <section className="tools-form-section">
                <div className="tools-section-head">
                  <h3>Advanced preferences</h3>
                  <small>Region, BMI, currencies, and supplements</small>
                </div>

                <div className="tools-grid-two tools-grid-tight">
                  <label>
                    Region
                    <input
                      value={region}
                      placeholder="Region"
                      onChange={(event) => setRegion(event.target.value)}
                    />
                  </label>
                  <label>
                    Dietary preference
                    <select
                      value={dietaryPreference}
                      onChange={(event) => setDietaryPreference(event.target.value)}
                    >
                      {DIETARY_OPTIONS.map((item) => (
                        <option key={item} value={item}>
                          {item}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>

                <div className="bmi-box">
                  <div className="bmi-head">
                    <h3>BMI</h3>
                    <small>{bmi == null ? 'Add height/weight' : `${bmi} (${bmiCategory(bmi)})`}</small>
                  </div>
                  <div className="tools-grid-two tools-grid-tight">
                    <label>
                      Height (cm)
                      <input
                        type="number"
                        min="50"
                        max="260"
                        value={heightCm}
                        onChange={(event) => setHeightCm(event.target.value)}
                      />
                    </label>
                    <label>
                      Weight (kg)
                      <input
                        type="number"
                        min="20"
                        max="350"
                        value={weightKg}
                        onChange={(event) => setWeightKg(event.target.value)}
                      />
                    </label>
                  </div>
                </div>

                <div>
                  <label className="tools-label">Currencies</label>
                  <div className="chip-grid">
                    {(currencyOptions.length ? currencyOptions : ['INR', 'USD', 'EUR', 'GBP']).map((currency) => (
                      <button
                        key={currency}
                        type="button"
                        className={currencies.includes(currency) ? 'chip active' : 'chip'}
                        onClick={() => toggleCurrency(currency)}
                      >
                        {currency}
                      </button>
                    ))}
                  </div>
                </div>

                <label className="checkbox-row">
                  <input
                    type="checkbox"
                    checked={includeSupplements}
                    onChange={(event) => setIncludeSupplements(event.target.checked)}
                  />
                  Supplements
                </label>
              </section>
            )}

            <button type="submit" className="primary-btn" disabled={loading}>
              {loading ? 'Running...' : 'Generate Plan'}
            </button>
          </form>
        )}

        {activeToolsView !== 'plan' && (
        <div className="cheat-day-box">
          <div className="cheat-day-head">
            <h3>Cheat Day Recorder</h3>
            <small>
              {cheatDays.length} total · {cheatDaysThisMonth} this month
            </small>
          </div>

          <form className="cheat-day-form" onSubmit={handleSaveCheatDay}>
            <div className="tools-grid-two">
              <label>
                Date
                <input
                  type="date"
                  value={cheatDayDate}
                  onChange={(event) => setCheatDayDate(event.target.value)}
                />
              </label>
              <label>
                Indulgence
                <select
                  value={cheatDayLevel}
                  onChange={(event) => setCheatDayLevel(Number(event.target.value) || 3)}
                >
                  {Object.entries(CHEAT_DAY_LEVEL_LABELS).map(([value, label]) => (
                    <option key={`cheat-level-${value}`} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="tools-grid-two">
              <label>
                Title
                <input
                  value={cheatDayTitle}
                  placeholder="Cheat Day"
                  onChange={(event) => setCheatDayTitle(event.target.value)}
                />
              </label>
              <label>
                Extra calories (est.)
                <input
                  type="number"
                  min="0"
                  max="5000"
                  value={cheatDayExtraCalories}
                  placeholder="Optional"
                  onChange={(event) => setCheatDayExtraCalories(event.target.value)}
                />
              </label>
            </div>

            <label>
              Note
              <textarea
                value={cheatDayNote}
                placeholder="What did you eat? Any mood/occasion notes."
                onChange={(event) => setCheatDayNote(event.target.value)}
              />
            </label>

            <div className="cheat-day-actions">
              <button type="submit" className="primary-btn" disabled={cheatDayBusy}>
                {cheatDayBusy ? 'Saving...' : activeCheatDay ? 'Update Cheat Day' : 'Save Cheat Day'}
              </button>
              <button
                type="button"
                className="secondary-btn"
                disabled={cheatDayBusy || !activeCheatDay}
                onClick={() => handleDeleteCheatDay(cheatDayDate)}
              >
                Remove
              </button>
              <button type="button" className="ghost-btn" disabled={cheatDayBusy} onClick={jumpToTodayCheatDay}>
                Today
              </button>
            </div>
          </form>

          {cheatDayStatus && <div className="status-line">{cheatDayStatus}</div>}

          {!!cheatDays.length && (
            <div className="cheat-day-history">
              {cheatDays.slice(0, 8).map((entry) => {
                const dateKey = String(entry?.date || '');
                const level = Number(entry?.indulgenceLevel) || 3;
                const isSelected = cheatDayDate === dateKey;
                return (
                  <div key={`cheat-row-${dateKey}`} className={`cheat-day-row ${isSelected ? 'is-selected' : ''}`}>
                    <button type="button" className="cheat-day-row-main" onClick={() => setCheatDayDate(dateKey)}>
                      <strong>{dateKey}</strong>
                      <small>
                        {entry?.title || DEFAULT_CHEAT_TITLE}
                        {entry?.estimatedExtraCalories != null
                          ? ` · +${entry.estimatedExtraCalories} kcal`
                          : ''}
                        {` · ${CHEAT_DAY_LEVEL_LABELS[level] || 'Moderate'}`}
                      </small>
                    </button>
                    <button
                      type="button"
                      className="ghost-btn cheat-day-row-remove"
                      disabled={cheatDayBusy}
                      onClick={() => handleDeleteCheatDay(dateKey)}
                    >
                      Delete
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
        )}
      </section>

      {status && <div className="status-line">{status}</div>}

      {activeToolsView === 'results' && !result && (
        <section className="panel tools-results-panel">
          <div className="panel-title-row">
            <h2>Results</h2>
            <p>Run your plan to see recommendations.</p>
          </div>
        </section>
      )}

      {activeToolsView === 'results' && result && (
        <section className="panel tools-results-panel">
          <div className="panel-title-row">
            <h2>Results</h2>
            <p>{recommendationCount} matches</p>
          </div>

          <div className="tools-results-overview" aria-label="Results summary">
            <article className="tools-overview-card">
              <span>Natural foods</span>
              <strong>{naturalRecommendations.length}</strong>
            </article>
            <article className="tools-overview-card">
              <span>Supplements</span>
              <strong>{supplementRecommendations.length}</strong>
            </article>
            <article className="tools-overview-card">
              <span>Deficiency guides</span>
              <strong>{deficiencyInsightCount}</strong>
            </article>
            <article className="tools-overview-card">
              <span>Diet plan sections</span>
              <strong>{dietPlanSectionsCount}</strong>
            </article>
          </div>

          {deficiencyInsightCount > 0 && (
            <div className="deficiency-insights-panel">
              <h3>Deficiency Guide</h3>
              <div className="deficiency-insights-grid">
                {result.deficiencyInsights.map((insight, index) => (
                  <article className="deficiency-insight-card" key={`${insight.name}-${index}`}>
                    <div className="deficiency-insight-head">
                      <h4>{insight.name}</h4>
                      <span className="deficiency-insight-badge">{insight.category || 'Guide'}</span>
                    </div>
                    {insight.tip && <p className="deficiency-insight-tip">{insight.tip}</p>}
                    {firstItems(insight.symptoms, 4).length > 0 && (
                      <p className="deficiency-insight-line">
                        Symptoms: {firstItems(insight.symptoms, 4).join(' · ')}
                      </p>
                    )}
                    {firstItems(insight.naturalFoodPlan || insight.recommendedFoods, 8).length > 0 && (
                      <div className="deficiency-insight-group">
                        <p className="deficiency-insight-line">
                          Natural foods & ingredients (daily amount)
                        </p>
                        <ul className="deficiency-insight-list">
                          {firstItems(insight.naturalFoodPlan || insight.recommendedFoods, 8).map((foodLine) => (
                            <li key={`${insight.name}-${foodLine}`}>{foodLine}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                    {firstItems(insight.recommendedSupplements, 4).length > 0 && (
                      <div className="deficiency-insight-group">
                        <p className="deficiency-insight-line">Supplements (optional)</p>
                        <ul className="deficiency-insight-list">
                          {firstItems(insight.recommendedSupplements, 4).map((supplement) => (
                            <li key={`${insight.name}-${supplement}`}>{supplement}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                    {firstItems(insight.sources, 2).length > 0 && (
                      <div className="deficiency-source-row">
                        {firstItems(insight.sources, 2).map((url) => (
                          <a key={url} href={url} target="_blank" rel="noreferrer">
                            Source
                          </a>
                        ))}
                      </div>
                    )}
                  </article>
                ))}
              </div>
            </div>
          )}

          {naturalRecommendations.length > 0 && (
            <div className="recommendation-section">
              <h3>Foods & Ingredients</h3>
              <div className="tools-result-grid">
                {naturalRecommendations.map((item, index) => (
                  <article className="recommendation-card" key={`${item.name}-${index}`}>
                    <h3>{item.name}</h3>
                    <div className="recommendation-compact-row">
                      <small>
                        {formatAmount(item.caloriesPer100g)} cal · P {formatAmount(item.proteinPer100g)}g · C {formatAmount(item.carbsPer100g)}g · F {formatAmount(item.fatsPer100g)}g · Fi{' '}
                        {formatAmount(item.fiberPer100g)}g
                      </small>
                      <small>
                        {Object.entries(item.priceByCurrency || {}).length
                          ? Object.entries(item.priceByCurrency || {})
                              .map(([currency, value]) => `${currency} ${formatAmount(value, 2)}`)
                              .join(' · ')
                          : 'Price N/A'}
                      </small>
                    </div>
                  </article>
                ))}
              </div>
            </div>
          )}

          {supplementRecommendations.length > 0 && (
            <div className="recommendation-section">
              <h3>Supplements</h3>
              <div className="tools-result-grid">
                {supplementRecommendations.map((item, index) => (
                  <article className="recommendation-card recommendation-card-supplement" key={`${item.name}-${index}`}>
                    <h3>{item.name}</h3>
                    <div className="recommendation-compact-row">
                      <small>{item.reason || 'Supplement support'}</small>
                      <small>
                        {Object.entries(item.priceByCurrency || {}).length
                          ? Object.entries(item.priceByCurrency || {})
                              .map(([currency, value]) => `${currency} ${formatAmount(value, 2)}`)
                              .join(' · ')
                          : 'Price N/A'}
                      </small>
                    </div>
                  </article>
                ))}
              </div>
            </div>
          )}

          {Array.isArray(result.dietPlan) && result.dietPlan.length > 0 && (
            <div className="diet-plan-panel">
              <h3>Diet Plan</h3>
              <div className="diet-plan-grid">
                {result.dietPlan.map((section, index) => (
                  <article className="diet-plan-card" key={`${section.title}-${index}`}>
                    <h4>{section.title}</h4>
                    <ul>
                      {(section.items || []).map((item, itemIndex) => (
                        <li key={`${section.title}-${itemIndex}`}>{item}</li>
                      ))}
                    </ul>
                  </article>
                ))}
              </div>
            </div>
          )}

        </section>
      )}
    </div>
  );
}

export default ToolsPage;
