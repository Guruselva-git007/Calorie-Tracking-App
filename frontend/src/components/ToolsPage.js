import React, { useEffect, useMemo, useState } from 'react';
import { toolsAPI } from '../services/api';
import { formatAmount } from '../utils/food';
import './ToolsPage.css';

const DEFICIENCY_OPTIONS = [
  'Iron Deficiency',
  'Vitamin D Deficiency',
  'Vitamin B12 Deficiency',
  'Protein Deficiency',
  'Fiber Deficiency',
  'Vitamin C Deficiency',
  'Calcium Deficiency',
  'Magnesium Deficiency',
  'Zinc Deficiency',
  'Omega-3 Deficiency'
];

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

const parseCommaList = (value) =>
  String(value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);

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
  const [heightCm, setHeightCm] = useState(userProfile?.heightCm || '');
  const [weightKg, setWeightKg] = useState(userProfile?.weightKg || '');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState('');
  const [result, setResult] = useState(null);

  useEffect(() => {
    let active = true;

    const loadCurrencies = async () => {
      try {
        const response = await toolsAPI.getCurrencies();
        if (!active) {
          return;
        }

        const nextOptions = orderCurrencies(Object.keys(response.data || {}));
        setCurrencyOptions(nextOptions);
        if (!currencies.length && nextOptions.length) {
          setCurrencies(nextOptions.slice(0, 4));
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

    const deficiencyFromProfile = parseCommaList(userProfile.nutritionDeficiency);
    if (deficiencyFromProfile.length) {
      setDeficiencies((previous) => Array.from(new Set([...previous, ...deficiencyFromProfile])));
    }

    const conditionFromProfile = parseCommaList(userProfile.medicalIllness);
    if (conditionFromProfile.length) {
      setMedicalConditions((previous) => Array.from(new Set([...previous, ...conditionFromProfile])));
    }
  }, [userProfile]);

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
      setStatus('Select deficiency or medical.');
      return;
    }

    if (!currencies.length) {
      setStatus('Select currency.');
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
        currencies
      });
      setResult(response.data);
    } catch (error) {
      setResult(null);
      setStatus(error?.response?.data?.message || 'Unable to generate results.');
    } finally {
      setLoading(false);
    }
  };

  const recommendationCount = useMemo(() => (result?.recommendations || []).length, [result]);

  return (
    <div className="page-stack tools-page">
      <section className="panel tools-config-panel">
        <div className="panel-title-row">
          <h2>Tools</h2>
        </div>

        <form className="tools-form" onSubmit={handleRun}>
          <div className="tools-columns">
            <div>
              <label className="tools-label">Deficiency</label>
              <div className="chip-grid">
                {DEFICIENCY_OPTIONS.map((item) => (
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

            <div>
              <label className="tools-label">Medical</label>
              <div className="chip-grid">
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

          <div className="tools-grid-two">
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
            <div className="tools-grid-two">
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

          <button type="submit" className="primary-btn" disabled={loading}>
            {loading ? 'Running...' : 'Run'}
          </button>
        </form>
      </section>

      {status && <div className="status-line">{status}</div>}

      {result && (
        <section className="panel tools-results-panel">
          <div className="panel-title-row">
            <h2>Results</h2>
            <p>{recommendationCount} matches</p>
          </div>

          <div className="tools-result-grid">
            {(result.recommendations || []).map((item, index) => (
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

        </section>
      )}
    </div>
  );
}

export default ToolsPage;
