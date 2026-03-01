import React, { useEffect, useMemo, useRef, useState } from 'react';
import { dishAPI, ingredientAPI } from '../services/api';
import { buildFoodPlaceholderDataUrl, formatAmount, formatInr, getFoodImageSrc, normalizeText, parseNumber } from '../utils/food';
import './FoodLibrary.css';

const SEARCH_DEBOUNCE_MS = 120;

const saveCacheEntry = (cache, key, value, maxSize = 120) => {
  cache.set(key, value);
  if (cache.size > maxSize) {
    const firstKey = cache.keys().next().value;
    cache.delete(firstKey);
  }
};

const scoreMatch = (value, normalizedQuery) => {
  const normalizedValue = normalizeText(value);
  if (!normalizedValue) {
    return 0;
  }
  if (normalizedValue === normalizedQuery) {
    return 320;
  }
  if (normalizedValue.startsWith(normalizedQuery)) {
    return 240;
  }
  if (normalizedValue.includes(` ${normalizedQuery}`)) {
    return 190;
  }
  if (normalizedValue.includes(normalizedQuery)) {
    return 130;
  }
  return 70;
};

const ingredientSuggestion = (item, normalizedQuery) => ({
  key: `ingredient-${item.id}`,
  id: item.id,
  type: 'ingredient',
  name: item.name,
  subtitle: `${item.category} · ${item.cuisine}`,
  calories: parseNumber(item.caloriesPer100g),
  protein: parseNumber(item.proteinPer100g),
  carbs: parseNumber(item.carbsPer100g),
  fats: parseNumber(item.fatsPer100g),
  fiber: parseNumber(item.fiberPer100g),
  price: parseNumber(item.averagePriceUsd),
  priceUnit: item.averagePriceUnit || 'kg',
  detailLabel: 'per 100g',
  note: Array.isArray(item.aliases) && item.aliases.length ? `AKA: ${item.aliases.join(', ')}` : '',
  imageUrl: item.imageUrl,
  score: scoreMatch(item.name, normalizedQuery) + 12
});

const dishSuggestion = (item, normalizedQuery) => ({
  key: `dish-${item.id}`,
  id: item.id,
  type: 'dish',
  name: item.name,
  subtitle: `${item.cuisine} · ${item.description || 'Custom dish'}`,
  calories: parseNumber(item.caloriesPerServing),
  protein: parseNumber(item.proteinPerServing),
  carbs: parseNumber(item.carbsPerServing),
  fats: parseNumber(item.fatsPerServing),
  fiber: parseNumber(item.fiberPerServing),
  price: parseNumber(item.estimatedPriceUsdPerServing),
  priceUnit: 'serving',
  detailLabel: 'per serving',
  note: '',
  imageUrl: item.imageUrl,
  score: scoreMatch(item.name, normalizedQuery)
});

const FoodThumb = ({ item, bucket = 'food', className = 'library-thumb' }) => {
  const fallback = buildFoodPlaceholderDataUrl(item?.name || 'Food', bucket);
  const src = getFoodImageSrc(item, { bucket });

  return (
    <img
      className={className}
      src={src}
      alt={`${item?.name || 'Food'} image`}
      loading="lazy"
      data-fallback={fallback}
      onError={(event) => {
        event.currentTarget.onerror = null;
        event.currentTarget.src = event.currentTarget.dataset.fallback || fallback;
      }}
    />
  );
};

function FoodLibrary() {
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [suggestions, setSuggestions] = useState([]);
  const [selected, setSelected] = useState(null);
  const [searchFocused, setSearchFocused] = useState(false);
  const blurTimerRef = useRef(null);
  const requestIdRef = useRef(0);
  const detailRequestIdRef = useRef(0);
  const searchCacheRef = useRef(new Map());

  useEffect(
    () => () => {
      if (blurTimerRef.current) {
        window.clearTimeout(blurTimerRef.current);
      }
    },
    []
  );

  useEffect(() => {
    const normalized = normalizeText(search);
    const queryValue = search.trim();
    if (normalized.length < 2) {
      setSuggestions([]);
      setLoading(false);
      return undefined;
    }

    const cached = searchCacheRef.current.get(normalized);
    if (cached) {
      setSuggestions(cached);
      setLoading(false);
      return undefined;
    }

    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;
    setLoading(true);

    const timer = window.setTimeout(async () => {
      try {
        const [ingredientRes, dishRes] = await Promise.all([
          ingredientAPI.search(queryValue, { limit: 8 }),
          dishAPI.suggest(queryValue, { limit: 8 })
        ]);

        if (requestId !== requestIdRef.current) {
          return;
        }

        const ingredientItems = (ingredientRes?.data || []).slice(0, 8).map((item) => ingredientSuggestion(item, normalized));
        const dishItems = (dishRes?.data || []).slice(0, 8).map((item) => dishSuggestion(item, normalized));

        const merged = [...ingredientItems, ...dishItems]
          .sort((a, b) => b.score - a.score || a.name.localeCompare(b.name))
          .slice(0, 12);

        saveCacheEntry(searchCacheRef.current, normalized, merged);
        setSuggestions(merged);
      } catch (error) {
        if (requestId === requestIdRef.current) {
          setSuggestions([]);
        }
      } finally {
        if (requestId === requestIdRef.current) {
          setLoading(false);
        }
      }
    }, SEARCH_DEBOUNCE_MS);

    return () => {
      window.clearTimeout(timer);
    };
  }, [search]);

  const hasQuery = normalizeText(search).length >= 2;
  const showSuggestions = hasQuery && searchFocused;

  const resultSummary = useMemo(() => {
    if (!hasQuery) {
      return 'Type 2+ letters';
    }
    if (loading) {
      return 'Searching...';
    }
    if (!suggestions.length) {
      return 'No matches';
    }
    return `${suggestions.length} matches`;
  }, [hasQuery, loading, suggestions.length]);

  const onSearchBlur = () => {
    blurTimerRef.current = window.setTimeout(() => setSearchFocused(false), 120);
  };

  const onSearchFocus = () => {
    if (blurTimerRef.current) {
      window.clearTimeout(blurTimerRef.current);
      blurTimerRef.current = null;
    }
    setSearchFocused(true);
  };

  const selectSuggestion = async (item) => {
    const requestId = detailRequestIdRef.current + 1;
    detailRequestIdRef.current = requestId;

    setSelected(item);
    setSearch(item.name);
    setSearchFocused(false);

    if (item.type !== 'dish') {
      setDetailLoading(false);
      return;
    }

    try {
      setDetailLoading(true);
      const response = await dishAPI.get(item.id);
      if (requestId !== detailRequestIdRef.current) {
        return;
      }

      const fullDish = response?.data;
      if (!fullDish) {
        return;
      }

      setSelected((previous) => {
        if (!previous || previous.type !== 'dish' || previous.id !== item.id) {
          return previous;
        }
        return {
          ...previous,
          subtitle: `${fullDish.cuisine} · ${fullDish.description || 'Custom dish'}`,
          calories: parseNumber(fullDish.caloriesPerServing),
          protein: parseNumber(fullDish.proteinPerServing),
          carbs: parseNumber(fullDish.carbsPerServing),
          fats: parseNumber(fullDish.fatsPerServing),
          fiber: parseNumber(fullDish.fiberPerServing),
          price: parseNumber(fullDish.estimatedPriceUsdPerServing),
          imageUrl: fullDish.imageUrl || previous.imageUrl
        };
      });
    } catch (error) {
      // Keep lightweight suggestion data if detail fetch fails.
    } finally {
      if (requestId === detailRequestIdRef.current) {
        setDetailLoading(false);
      }
    }
  };

  return (
    <div className="panel library-panel">
      <div className="panel-title-row">
        <h2>Smart Food Search</h2>
      </div>

      <div className="library-search-wrap">
        <div className="library-input-shell">
          <input
            type="text"
            placeholder="Search food"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setSelected(null);
            }}
            onFocus={onSearchFocus}
            onBlur={onSearchBlur}
          />
          <span className={`library-search-status ${loading ? 'is-loading' : ''}`}>{resultSummary}</span>
        </div>

        {showSuggestions && (
          <div className="library-suggestion-pop">
            {suggestions.map((item) => (
              <button key={item.key} type="button" className="library-suggestion-item" onMouseDown={() => selectSuggestion(item)}>
                <div className="library-suggestion-body">
                  <FoodThumb item={item} bucket={item.type === 'dish' ? 'dish' : 'ingredient'} />
                  <div>
                    <div className="library-suggestion-head">
                      <strong>{item.name}</strong>
                      <span className="library-calorie-chip">
                        {Math.round(item.calories)} cal {item.detailLabel}
                      </span>
                    </div>
                    <div className="library-suggestion-sub">{item.subtitle}</div>
                    <div className="library-suggestion-meta">
                      <span className={`library-kind-pill ${item.type === 'dish' ? 'dish' : 'ingredient'}`}>
                        {item.type === 'dish' ? 'Dish' : 'Ingredient'}
                      </span>
                      <small>
                        P {formatAmount(item.protein)}g · C {formatAmount(item.carbs)}g · F {formatAmount(item.fats)}g · Fi{' '}
                        {formatAmount(item.fiber)}g
                      </small>
                    </div>
                  </div>
                </div>
              </button>
            ))}
            {!loading && !suggestions.length && <div className="library-suggestion-empty">No matches.</div>}
          </div>
        )}
      </div>

      {selected ? (
        <div className="library-detail-card">
          <div className="library-detail-head">
            <div className="library-detail-title">
              <FoodThumb item={selected} bucket={selected.type === 'dish' ? 'dish' : 'ingredient'} className="library-thumb library-thumb-lg" />
              <div>
                <h3>{selected.name}</h3>
                <p>{selected.subtitle}</p>
              </div>
            </div>
            <span className={`library-kind-pill ${selected.type === 'dish' ? 'dish' : 'ingredient'}`}>
              {selected.type === 'dish' ? 'Dish' : 'Ingredient'}
            </span>
          </div>

          <div className="library-detail-grid">
            <div>
              <small>Calories</small>
              <strong>
                {Math.round(selected.calories)} <em>{selected.detailLabel}</em>
              </strong>
            </div>
            <div>
              <small>Protein</small>
              <strong>{formatAmount(selected.protein)}g</strong>
            </div>
            <div>
              <small>Carbs</small>
              <strong>{formatAmount(selected.carbs)}g</strong>
            </div>
            <div>
              <small>Fats</small>
              <strong>{formatAmount(selected.fats)}g</strong>
            </div>
            <div>
              <small>Fibre</small>
              <strong>{formatAmount(selected.fiber)}g</strong>
            </div>
            <div className="library-price-cell">
              <small>Avg Price</small>
              <strong>
                {formatInr(selected.price)} / {selected.priceUnit}
              </strong>
            </div>
          </div>

          {detailLoading && selected.type === 'dish' ? <div className="library-note">Loading dish nutrition...</div> : null}
          {selected.note ? <div className="library-note">{selected.note}</div> : null}
        </div>
      ) : (
        <div className="library-placeholder">Select a result.</div>
      )}
    </div>
  );
}

export default FoodLibrary;
