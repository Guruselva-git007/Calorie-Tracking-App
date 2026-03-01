import React, { useEffect, useMemo, useRef, useState } from 'react';
import { calculatorAPI, ingredientAPI } from '../services/api';
import {
  buildFoodPlaceholderDataUrl,
  UNIT_OPTIONS,
  formatUnitPresetLabel,
  formatAmount,
  formatInr,
  getFoodImageSrc,
  getQuantityInputStep,
  getUnitPresets,
  getUnitStepForTenGrams,
  getUnitStepLabel,
  lineNutritionFromIngredient,
  normalizeText,
  parseNumber,
  toGrams
} from '../utils/food';
import './IngredientCalculator.css';

const SEARCH_DEBOUNCE_MS = 110;

const createRow = () => ({
  key: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
  ingredientId: null,
  ingredient: null,
  query: '',
  quantity: 100,
  unit: 'g'
});

const saveCacheEntry = (cache, key, value, maxSize = 100) => {
  cache.set(key, value);
  if (cache.size > maxSize) {
    const firstKey = cache.keys().next().value;
    cache.delete(firstKey);
  }
};

const IngredientThumb = ({ ingredient }) => {
  const fallback = buildFoodPlaceholderDataUrl(ingredient?.name || 'Food', ingredient?.category || 'ingredient');
  const src = getFoodImageSrc(ingredient, { bucket: ingredient?.category || 'ingredient' });

  return (
    <img
      className="calc-thumb"
      src={src}
      alt={`${ingredient?.name || 'Food'} image`}
      loading="lazy"
      data-fallback={fallback}
      onError={(event) => {
        event.currentTarget.onerror = null;
        event.currentTarget.src = event.currentTarget.dataset.fallback || fallback;
      }}
    />
  );
};

function IngredientCalculator() {
  const [rows, setRows] = useState([createRow()]);
  const [suggestionsByRow, setSuggestionsByRow] = useState({});
  const [loadingByRow, setLoadingByRow] = useState({});
  const [result, setResult] = useState(null);
  const [status, setStatus] = useState('');

  const timersRef = useRef({});
  const requestIdsRef = useRef({});
  const searchCacheRef = useRef(new Map());

  useEffect(
    () => () => {
      Object.values(timersRef.current).forEach((timer) => window.clearTimeout(timer));
    },
    []
  );

  const updateRow = (rowKey, patch) => {
    setRows((previous) =>
      previous.map((row) => (row.key === rowKey ? { ...row, ...patch } : row))
    );
  };

  const updateQuery = (rowKey, query) => {
    updateRow(rowKey, { query, ingredientId: null, ingredient: null });

    const normalized = normalizeText(query);
    if (timersRef.current[rowKey]) {
      window.clearTimeout(timersRef.current[rowKey]);
    }

    if (normalized.length < 2) {
      setSuggestionsByRow((previous) => ({ ...previous, [rowKey]: [] }));
      setLoadingByRow((previous) => ({ ...previous, [rowKey]: false }));
      return;
    }

    const cached = searchCacheRef.current.get(normalized);
    if (cached) {
      setSuggestionsByRow((previous) => ({ ...previous, [rowKey]: cached }));
      setLoadingByRow((previous) => ({ ...previous, [rowKey]: false }));
      return;
    }

    timersRef.current[rowKey] = window.setTimeout(async () => {
      const requestId = (requestIdsRef.current[rowKey] || 0) + 1;
      requestIdsRef.current[rowKey] = requestId;
      setLoadingByRow((previous) => ({ ...previous, [rowKey]: true }));

      try {
        const response = await ingredientAPI.search(query, { limit: 8 });
        if (requestIdsRef.current[rowKey] !== requestId) {
          return;
        }

        const items = (response.data || []).slice(0, 8);
        saveCacheEntry(searchCacheRef.current, normalized, items);

        setSuggestionsByRow((previous) => ({
          ...previous,
          [rowKey]: items
        }));
      } catch (error) {
        if (requestIdsRef.current[rowKey] === requestId) {
          setSuggestionsByRow((previous) => ({ ...previous, [rowKey]: [] }));
        }
      } finally {
        if (requestIdsRef.current[rowKey] === requestId) {
          setLoadingByRow((previous) => ({ ...previous, [rowKey]: false }));
        }
      }
    }, SEARCH_DEBOUNCE_MS);
  };

  const selectIngredient = (rowKey, ingredient) => {
    updateRow(rowKey, {
      ingredientId: ingredient.id,
      ingredient,
      query: ingredient.name
    });

    setSuggestionsByRow((previous) => ({ ...previous, [rowKey]: [] }));
  };

  const addRow = () => {
    setRows((previous) => [...previous, createRow()]);
  };

  const removeRow = (rowKey) => {
    setRows((previous) => {
      if (previous.length <= 1) {
        return [createRow()];
      }
      return previous.filter((row) => row.key !== rowKey);
    });

    setSuggestionsByRow((previous) => {
      const next = { ...previous };
      delete next[rowKey];
      return next;
    });

    setLoadingByRow((previous) => {
      const next = { ...previous };
      delete next[rowKey];
      return next;
    });

    if (timersRef.current[rowKey]) {
      window.clearTimeout(timersRef.current[rowKey]);
      delete timersRef.current[rowKey];
    }
  };

  const applyPreset = (rowKey, value) => {
    updateRow(rowKey, { quantity: value });
  };

  const stepRow = (rowKey, direction) => {
    setRows((previous) =>
      previous.map((row) => {
        if (row.key !== rowKey) {
          return row;
        }

        const step = getUnitStepForTenGrams(row.unit);
        const next = parseNumber(row.quantity, 0) + direction * step;
        return {
          ...row,
          quantity: Math.max(0, Number(next.toFixed(3)))
        };
      })
    );
  };

  const fallbackCalculation = (usableRows) => {
    const breakdown = usableRows.map((row) => {
      const line = lineNutritionFromIngredient(row.ingredient, row.quantity, row.unit);
      return {
        ingredientId: row.ingredientId,
        ingredientName: row.ingredient?.name || row.query,
        grams: line.grams,
        calories: line.calories,
        protein: line.protein,
        carbs: line.carbs,
        fats: line.fats,
        fiber: line.fiber,
        estimatedPriceUsd: line.priceUsd
      };
    });

    const totals = breakdown.reduce(
      (sum, line) => ({
        calories: sum.calories + line.calories,
        protein: sum.protein + line.protein,
        carbs: sum.carbs + line.carbs,
        fats: sum.fats + line.fats,
        fiber: sum.fiber + line.fiber,
        priceUsd: sum.priceUsd + line.estimatedPriceUsd
      }),
      { calories: 0, protein: 0, carbs: 0, fats: 0, fiber: 0, priceUsd: 0 }
    );

    return {
      totalCalories: totals.calories,
      totalProtein: totals.protein,
      totalCarbs: totals.carbs,
      totalFats: totals.fats,
      totalFiber: totals.fiber,
      estimatedTotalPriceUsd: totals.priceUsd,
      breakdown
    };
  };

  const handleCalculate = async () => {
    setStatus('');

    const usableRows = rows.filter((row) => row.ingredientId && parseNumber(row.quantity) > 0);
    if (!usableRows.length) {
      setStatus('Select at least one ingredient.');
      return;
    }

    const payload = usableRows.map((row) => ({
      ingredientId: row.ingredientId,
      grams: toGrams(row.quantity, row.unit, row.ingredient)
    }));

    try {
      const response = await calculatorAPI.calculateIngredients(payload);
      setResult(response.data);
    } catch (error) {
      setStatus('Using local calculation.');
      setResult(fallbackCalculation(usableRows));
    }
  };

  const totals = useMemo(
    () => ({
      calories: parseNumber(result?.totalCalories),
      protein: parseNumber(result?.totalProtein),
      carbs: parseNumber(result?.totalCarbs),
      fats: parseNumber(result?.totalFats),
      fiber: parseNumber(result?.totalFiber),
      priceUsd: parseNumber(result?.estimatedTotalPriceUsd)
    }),
    [result]
  );

  return (
    <div className="panel calculator-panel">
      <div className="panel-title-row">
        <h2>Ingredient Calculator</h2>
      </div>

      {rows.map((row) => {
        const suggestions = suggestionsByRow[row.key] || [];
        const isExact = normalizeText(row.ingredient?.name) === normalizeText(row.query);
        const showSuggestions = normalizeText(row.query).length >= 2 && !isExact;

        return (
          <div className="calculator-row" key={row.key}>
            <div className="calc-search-cell">
              <input
                type="text"
                value={row.query}
                placeholder="Search ingredient"
                onChange={(event) => updateQuery(row.key, event.target.value)}
              />

              {showSuggestions && (
                <div className="calc-suggestions">
                  {suggestions.map((item) => (
                    <button
                      type="button"
                      key={item.id}
                      className="calc-suggestion-item"
                      onMouseDown={(event) => {
                        event.preventDefault();
                        selectIngredient(row.key, item);
                      }}
                    >
                      <div className="calc-suggestion-main">
                        <IngredientThumb ingredient={item} />
                        <div>
                          <span>{item.name}</span>
                          <small>
                            {item.category} · {Math.round(parseNumber(item.caloriesPer100g))} cal/100g
                          </small>
                        </div>
                      </div>
                    </button>
                  ))}

                  {!suggestions.length && !loadingByRow[row.key] && (
                    <div className="calc-suggestion-empty">No matches.</div>
                  )}
                  {loadingByRow[row.key] && <div className="calc-suggestion-empty">Searching...</div>}
                </div>
              )}
            </div>

            <div className="calc-quantity-row">
              <button type="button" onClick={() => stepRow(row.key, -1)}>
                {getUnitStepLabel(row.unit, -1)}
              </button>
              <input
                type="number"
                min="0"
                step={getQuantityInputStep(row.unit)}
                value={row.quantity}
                onChange={(event) => updateRow(row.key, { quantity: event.target.value })}
              />
              <select value={row.unit} onChange={(event) => updateRow(row.key, { unit: event.target.value })}>
                {UNIT_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
              <button type="button" onClick={() => stepRow(row.key, 1)}>
                {getUnitStepLabel(row.unit, 1)}
              </button>
            </div>

            <div className="calc-preset-row">
              {getUnitPresets(row.unit).map((value) => (
                <button key={`${row.key}-${value}`} type="button" onClick={() => applyPreset(row.key, value)}>
                  {formatUnitPresetLabel(value, row.unit)}
                </button>
              ))}
            </div>

            <button type="button" className="calc-remove-btn" onClick={() => removeRow(row.key)}>
              Remove
            </button>
          </div>
        );
      })}

      <div className="calculator-actions">
        <button type="button" onClick={addRow} className="secondary-btn">
          Add
        </button>
        <button type="button" onClick={handleCalculate} className="primary-btn">
          Calculate
        </button>
      </div>

      {result && (
        <div className="calc-result">
          <div className="calc-total-grid">
            <div>
              <small>Calories</small>
              <strong>{formatAmount(totals.calories)} cal</strong>
            </div>
            <div>
              <small>Protein</small>
              <strong>{formatAmount(totals.protein)} g</strong>
            </div>
            <div>
              <small>Carbs</small>
              <strong>{formatAmount(totals.carbs)} g</strong>
            </div>
            <div>
              <small>Fats</small>
              <strong>{formatAmount(totals.fats)} g</strong>
            </div>
            <div>
              <small>Fibre</small>
              <strong>{formatAmount(totals.fiber)} g</strong>
            </div>
            <div>
              <small>Price</small>
              <strong>{formatInr(totals.priceUsd)}</strong>
            </div>
          </div>

          <div className="breakdown-list">
            {(result.breakdown || []).map((item, index) => (
              <div className="breakdown-item" key={`${item.ingredientId}-${index}`}>
                <div>
                  <strong>{item.ingredientName}</strong>
                  <small>{formatAmount(item.grams)} g</small>
                </div>
                <div>
                  <span>{formatAmount(item.calories)} cal</span>
                  <small>
                    P {formatAmount(item.protein)} / C {formatAmount(item.carbs)} / F {formatAmount(item.fats)} / Fi {formatAmount(item.fiber)}
                  </small>
                </div>
                <div>
                  <span>{formatInr(item.estimatedPriceUsd)}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {status && <div className="status-line">{status}</div>}
    </div>
  );
}

export default IngredientCalculator;
