import React, { useEffect, useMemo, useRef, useState } from 'react';
import { dishAPI, entryAPI, ingredientAPI } from '../services/api';
import {
  buildFoodPlaceholderDataUrl,
  UNIT_OPTIONS,
  WEIGHT_PRESETS,
  formatAmount,
  formatInr,
  getFoodImageSrc,
  getUnitStepForTenGrams,
  lineNutritionFromIngredient,
  normalizeText,
  parseNumber,
  toGrams
} from '../utils/food';
import './AddEntry.css';

const saveCacheEntry = (cache, key, value, maxSize = 80) => {
  cache.set(key, value);
  if (cache.size > maxSize) {
    const firstKey = cache.keys().next().value;
    cache.delete(firstKey);
  }
};

const createIngredientFromDishComponent = (component) => {
  const grams = parseNumber(component.grams, 100);
  const safeGrams = grams <= 0 ? 100 : grams;
  const pricePer100g = (parseNumber(component.estimatedPriceUsd) * 100) / safeGrams;

  return {
    id: component.ingredientId,
    name: component.ingredientName,
    imageUrl: component.ingredientImageUrl,
    caloriesPer100g: parseNumber(component.caloriesPer100g),
    proteinPer100g: (parseNumber(component.protein) * 100) / safeGrams,
    carbsPer100g: (parseNumber(component.carbs) * 100) / safeGrams,
    fatsPer100g: (parseNumber(component.fats) * 100) / safeGrams,
    fiberPer100g: (parseNumber(component.fiber) * 100) / safeGrams,
    averagePriceUsd: pricePer100g,
    averagePriceUnit: 'g',
    category: 'DISH_COMPONENT',
    cuisine: 'Dish'
  };
};

const FoodThumb = ({ item, label, bucket = 'food', className = 'food-thumb' }) => {
  const name = label || item?.name || 'Food';
  const fallbackSrc = buildFoodPlaceholderDataUrl(name, bucket);
  const src = getFoodImageSrc(item, { label: name, bucket });

  return (
    <img
      className={className}
      src={src}
      alt={`${name} image`}
      loading="lazy"
      data-fallback={fallbackSrc}
      onError={(event) => {
        event.currentTarget.onerror = null;
        event.currentTarget.src = event.currentTarget.dataset.fallback || fallbackSrc;
      }}
    />
  );
};

const toCustomRowFromComponent = (component) => ({
  key: `${component.ingredientId}-${Math.random().toString(16).slice(2)}`,
  ingredientId: component.ingredientId,
  ingredientName: component.ingredientName,
  ingredient: createIngredientFromDishComponent(component),
  quantity: parseNumber(component.grams, 100),
  unit: 'g'
});

const metricsFromResponse = (response) => ({
  calories: parseNumber(response?.totalCalories),
  protein: parseNumber(response?.totalProtein),
  carbs: parseNumber(response?.totalCarbs),
  fats: parseNumber(response?.totalFats),
  fiber: parseNumber(response?.totalFiber),
  priceUsd: parseNumber(response?.estimatedTotalPriceUsd)
});

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

const includesAny = (value, tokens) => tokens.some((token) => value.includes(token));

const calorieLoadScore = (calories) => {
  if (calories <= 0) {
    return 0;
  }
  if (calories <= 180) {
    return 84;
  }
  if (calories <= 360) {
    return 96;
  }
  if (calories <= 560) {
    return 88;
  }
  if (calories <= 760) {
    return 72;
  }
  if (calories <= 980) {
    return 54;
  }
  if (calories <= 1250) {
    return 34;
  }
  return 18;
};

const deriveNutritionTier = (metrics, context, mode) => {
  const calories = Math.max(0, parseNumber(metrics?.calories));
  if (calories <= 0.1) {
    return null;
  }

  const protein = Math.max(0, parseNumber(metrics?.protein));
  const carbs = Math.max(0, parseNumber(metrics?.carbs));
  const fats = Math.max(0, parseNumber(metrics?.fats));
  const fiber = Math.max(0, parseNumber(metrics?.fiber));
  const priceUsd = Math.max(0, parseNumber(metrics?.priceUsd));

  const kcalUnit = Math.max(calories / 100, 1);
  const proteinPer100kcal = protein / kcalUnit;
  const fiberPer100kcal = fiber / kcalUnit;
  const fatsPer100kcal = fats / kcalUnit;
  const carbsPer100kcal = carbs / kcalUnit;

  const calorieScore = calorieLoadScore(calories);
  const proteinScore = clamp((proteinPer100kcal / 7.5) * 100 + (protein >= 25 ? 5 : 0), 10, 100);
  const fiberScore = clamp((fiberPer100kcal / 2.4) * 100 + (fiber >= 10 ? 6 : 0), 8, 100);

  const contextText = normalizeText(`${context?.name || ''} ${context?.cuisine || ''} ${context?.category || ''}`);
  const riskFatWords = ['fried', 'cake', 'ice cream', 'chips', 'mixture', 'vada', 'pav', 'butter', 'ghee', 'cream', 'pastry'];
  const cleanerFatWords = ['olive', 'nuts', 'fish', 'salmon', 'seed', 'avocado'];

  let fatQualityScore = 88 - Math.abs(fatsPer100kcal - 2.2) * 17;
  if (fatsPer100kcal > 4.5) {
    fatQualityScore -= (fatsPer100kcal - 4.5) * 11;
  }
  if (includesAny(contextText, riskFatWords)) {
    fatQualityScore -= 22;
  }
  if (includesAny(contextText, cleanerFatWords)) {
    fatQualityScore += 10;
  }
  fatQualityScore = clamp(fatQualityScore, 8, 100);

  const fiberToCarbRatio = fiber / Math.max(carbs, 1);
  let cleanCarbScore = 70 + fiberToCarbRatio * 95 - Math.max(0, carbsPer100kcal - 11) * 5 - Math.max(0, carbs - 90) * 0.45;
  cleanCarbScore = clamp(cleanCarbScore, 10, 100);

  let absorptionScore = 82;
  absorptionScore -= Math.max(0, fats - 28) * 1.2;
  absorptionScore -= Math.max(0, fiber - 16) * 1.1;
  absorptionScore -= Math.max(0, carbs - 95) * 0.4;
  absorptionScore -= Math.max(0, calories - 900) * 0.06;
  if (protein >= 15 && protein <= 50) {
    absorptionScore += 7;
  }
  if (calories <= 550 && fiber <= 14) {
    absorptionScore += 4;
  }
  absorptionScore = clamp(absorptionScore, 12, 100);

  let tasteScore = mode === 'dish' ? 82 : 74;
  if (includesAny(contextText, ['biryani', 'curry', 'grill', 'roast', 'soup', 'masala', 'mediterranean', 'shawarma', 'salad'])) {
    tasteScore += 6;
  }
  if (includesAny(contextText, ['plain', 'boiled'])) {
    tasteScore -= 4;
  }
  tasteScore = clamp(tasteScore, 45, 95);

  let pricingScore = 32;
  if (priceUsd <= 0.75) {
    pricingScore = 94;
  } else if (priceUsd <= 2) {
    pricingScore = 88;
  } else if (priceUsd <= 4) {
    pricingScore = 79;
  } else if (priceUsd <= 7) {
    pricingScore = 68;
  } else if (priceUsd <= 12) {
    pricingScore = 56;
  } else if (priceUsd <= 18) {
    pricingScore = 44;
  }
  const proteinPerDollar = protein / Math.max(priceUsd, 0.25);
  if (proteinPerDollar >= 6) {
    pricingScore += 12;
  } else if (proteinPerDollar >= 3.5) {
    pricingScore += 7;
  } else if (proteinPerDollar >= 2) {
    pricingScore += 4;
  }
  pricingScore = clamp(pricingScore, 10, 100);

  const score = clamp(
    calorieScore * 0.18
      + proteinScore * 0.22
      + fiberScore * 0.16
      + fatQualityScore * 0.12
      + cleanCarbScore * 0.1
      + absorptionScore * 0.08
      + tasteScore * 0.07
      + pricingScore * 0.07,
    0,
    100
  );

  let tier = 'D';
  if (score >= 88) {
    tier = 'S';
  } else if (score >= 76) {
    tier = 'A';
  } else if (score >= 64) {
    tier = 'B';
  } else if (score >= 50) {
    tier = 'C';
  }

  const positives = [];
  if (proteinScore >= 78) {
    positives.push('high protein density');
  }
  if (fiberScore >= 72) {
    positives.push('strong fibre support');
  }
  if (cleanCarbScore >= 70) {
    positives.push('clean carb profile');
  }
  if (pricingScore >= 72) {
    positives.push('good price efficiency');
  }

  const cautions = [];
  if (calorieScore <= 48) {
    cautions.push('high calorie load');
  }
  if (fiberScore <= 40) {
    cautions.push('low fibre balance');
  }
  if (fatQualityScore <= 42) {
    cautions.push('heavier fat profile');
  }

  const summary = positives.length
    ? `${positives.slice(0, 2).join(' • ')}${cautions.length ? ` | Watch: ${cautions[0]}` : ''}`
    : cautions.length
      ? `Watch: ${cautions.slice(0, 2).join(' • ')}`
      : 'Balanced macros for current serving';

  return {
    tier,
    score: Math.round(score),
    summary
  };
};

const INSTANT_PRESET_STORAGE_KEY = 'quick_logger_custom_instant_presets_v1';
const INSTANT_CATEGORY_OPTIONS = ['BEVERAGE', 'JUICE', 'SNACK', 'GRAIN', 'DAIRY', 'MEAT', 'SEAFOOD', 'RICE', 'OIL', 'OTHER'];
const INSTANT_SCOPE_OPTIONS = [
  { id: 'all', label: 'All' },
  { id: 'instant', label: 'Instant' },
  { id: 'dish', label: 'Dishes' },
  { id: 'ingredient', label: 'Ingredients' }
];
const INSTANT_SORT_OPTIONS = [
  { id: 'match', label: 'Best Match' },
  { id: 'protein', label: 'High Protein' },
  { id: 'calories', label: 'Low Calories' },
  { id: 'price', label: 'Low Price' }
];

const CUSTOM_PRESET_DEFAULTS = {
  name: '',
  aliases: '',
  quantity: 200,
  unit: 'ml',
  category: 'BEVERAGE',
  cuisine: 'Global',
  caloriesPer100g: 45,
  proteinPer100g: 1,
  carbsPer100g: 8,
  fatsPer100g: 1,
  fiberPer100g: 0,
  averagePriceUsd: 3
};

const BUILT_IN_INSTANT_PRESETS = [
  {
    id: 'black-coffee-200',
    label: 'Black Coffee',
    query: 'black coffee',
    aliases: ['americano'],
    quantity: 200,
    unit: 'ml',
    hint: '200ml cup',
    fallback: {
      name: 'Black Coffee',
      category: 'BEVERAGE',
      cuisine: 'Global',
      caloriesPer100g: 2,
      proteinPer100g: 0.3,
      carbsPer100g: 0,
      fatsPer100g: 0,
      fiberPer100g: 0,
      averagePriceUsd: 3.2,
      averagePriceUnit: 'l'
    }
  },
  {
    id: 'milk-coffee-200',
    label: 'Milk Coffee',
    query: 'milk coffee',
    aliases: ['coffee with milk'],
    quantity: 200,
    unit: 'ml',
    hint: '200ml cup',
    fallback: {
      name: 'Milk Coffee',
      category: 'BEVERAGE',
      cuisine: 'Global',
      caloriesPer100g: 46,
      proteinPer100g: 2.2,
      carbsPer100g: 6.3,
      fatsPer100g: 1.4,
      fiberPer100g: 0,
      averagePriceUsd: 4.2,
      averagePriceUnit: 'l'
    }
  },
  {
    id: 'tea-milk-200',
    label: 'Tea (with milk)',
    query: 'milk tea',
    aliases: ['chai', 'tea with milk'],
    quantity: 200,
    unit: 'ml',
    hint: '200ml cup',
    fallback: {
      name: 'Tea With Milk',
      category: 'BEVERAGE',
      cuisine: 'Indian',
      caloriesPer100g: 38,
      proteinPer100g: 1.5,
      carbsPer100g: 6.1,
      fatsPer100g: 1.0,
      fiberPer100g: 0,
      averagePriceUsd: 3,
      averagePriceUnit: 'l'
    }
  },
  {
    id: 'orange-juice-250',
    label: 'Orange Juice',
    query: 'orange juice',
    aliases: ['fresh orange juice'],
    quantity: 250,
    unit: 'ml',
    hint: '250ml glass',
    fallback: {
      name: 'Orange Juice',
      category: 'JUICE',
      cuisine: 'Global',
      caloriesPer100g: 45,
      proteinPer100g: 0.7,
      carbsPer100g: 10.4,
      fatsPer100g: 0.2,
      fiberPer100g: 0.3,
      averagePriceUsd: 2.8,
      averagePriceUnit: 'l'
    }
  },
  {
    id: 'instant-noodles-70',
    label: 'Instant Noodles',
    query: 'instant noodles',
    aliases: ['ramen'],
    quantity: 70,
    unit: 'g',
    hint: '1 mini pack',
    fallback: {
      name: 'Instant Noodles',
      category: 'SNACK',
      cuisine: 'Global',
      caloriesPer100g: 471,
      proteinPer100g: 8.8,
      carbsPer100g: 62,
      fatsPer100g: 20.5,
      fiberPer100g: 3,
      averagePriceUsd: 7.5,
      averagePriceUnit: 'kg'
    }
  },
  {
    id: 'veg-soup-250',
    label: 'Veg Soup',
    query: 'vegetable soup',
    aliases: ['veg clear soup'],
    quantity: 250,
    unit: 'ml',
    hint: '250ml bowl',
    fallback: {
      name: 'Vegetable Soup',
      category: 'SNACK',
      cuisine: 'Global',
      caloriesPer100g: 35,
      proteinPer100g: 1.6,
      carbsPer100g: 5.5,
      fatsPer100g: 0.9,
      fiberPer100g: 1.2,
      averagePriceUsd: 4,
      averagePriceUnit: 'l'
    }
  },
  {
    id: 'chicken-soup-250',
    label: 'Chicken Soup',
    query: 'chicken soup',
    aliases: ['non veg soup'],
    quantity: 250,
    unit: 'ml',
    hint: '250ml bowl',
    fallback: {
      name: 'Chicken Soup',
      category: 'MEAT',
      cuisine: 'Global',
      caloriesPer100g: 52,
      proteinPer100g: 5.8,
      carbsPer100g: 2.6,
      fatsPer100g: 2.2,
      fiberPer100g: 0.4,
      averagePriceUsd: 5.4,
      averagePriceUnit: 'l'
    }
  },
  {
    id: 'vada-pav-1',
    label: 'Vada Pav',
    query: 'vada pav',
    aliases: ['wada pav'],
    quantity: 150,
    unit: 'g',
    hint: '1 serving',
    fallback: {
      name: 'Vada Pav',
      category: 'SNACK',
      cuisine: 'Indian',
      caloriesPer100g: 276,
      proteinPer100g: 7.4,
      carbsPer100g: 38.5,
      fatsPer100g: 10.2,
      fiberPer100g: 3.2,
      averagePriceUsd: 9,
      averagePriceUnit: 'kg'
    }
  },
  {
    id: 'mixture-snack-50',
    label: 'Mixture Snack',
    query: 'mixture',
    aliases: ['namkeen mixture'],
    quantity: 50,
    unit: 'g',
    hint: '50g serving',
    fallback: {
      name: 'Mixture Snack',
      category: 'SNACK',
      cuisine: 'Indian',
      caloriesPer100g: 545,
      proteinPer100g: 11,
      carbsPer100g: 42,
      fatsPer100g: 36,
      fiberPer100g: 6.5,
      averagePriceUsd: 12,
      averagePriceUnit: 'kg'
    }
  },
  {
    id: 'ice-cream-100',
    label: 'Vanilla Ice Cream',
    query: 'vanilla ice cream',
    aliases: ['icecream'],
    quantity: 100,
    unit: 'g',
    hint: '100g scoop',
    fallback: {
      name: 'Vanilla Ice Cream',
      category: 'DAIRY',
      cuisine: 'Global',
      caloriesPer100g: 207,
      proteinPer100g: 3.5,
      carbsPer100g: 24,
      fatsPer100g: 11,
      fiberPer100g: 0.7,
      averagePriceUsd: 7.8,
      averagePriceUnit: 'kg'
    }
  },
  {
    id: 'cake-choco-100',
    label: 'Chocolate Cake',
    query: 'chocolate cake',
    aliases: ['cake'],
    quantity: 100,
    unit: 'g',
    hint: '100g slice',
    fallback: {
      name: 'Chocolate Cake',
      category: 'SNACK',
      cuisine: 'Global',
      caloriesPer100g: 382,
      proteinPer100g: 4.7,
      carbsPer100g: 54,
      fatsPer100g: 16,
      fiberPer100g: 2.2,
      averagePriceUsd: 13,
      averagePriceUnit: 'kg'
    }
  }
];

const parseCsv = (value) =>
  String(value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);

const formatServingLabel = (quantity, unit) => {
  const value = parseNumber(quantity, 0);
  if (Math.abs(value - Math.round(value)) < 0.0001) {
    return `${Math.round(value)}${unit}`;
  }
  return `${value.toFixed(2)}${unit}`;
};

const toBasePriceUnit = (unit) => {
  const normalized = normalizeText(unit);
  if (normalized === 'ml' || normalized === 'l') {
    return 'l';
  }
  return 'kg';
};

const sanitizeCategory = (value) => {
  const candidate = String(value || '')
    .trim()
    .toUpperCase();
  return INSTANT_CATEGORY_OPTIONS.includes(candidate) ? candidate : 'OTHER';
};

const normalizeInstantPreset = (preset, index = 0) => {
  if (!preset || !String(preset.label || '').trim()) {
    return null;
  }

  const quantity = Math.max(1, parseNumber(preset.quantity, 100));
  const unit = UNIT_OPTIONS.includes(preset.unit) ? preset.unit : 'g';
  const fallback = preset.fallback || {};
  const averagePriceUnit = normalizeText(fallback.averagePriceUnit);

  return {
    id: String(preset.id || `custom-${Date.now()}-${index}`),
    custom: Boolean(preset.custom),
    label: String(preset.label).trim(),
    query: String(preset.query || preset.label).trim(),
    aliases: Array.isArray(preset.aliases) ? preset.aliases.map((item) => String(item || '').trim()).filter(Boolean) : [],
    quantity,
    unit,
    hint: String(preset.hint || `${formatServingLabel(quantity, unit)} serving`).trim(),
    backendIngredientId: parseNumber(preset.backendIngredientId) > 0 ? parseNumber(preset.backendIngredientId) : null,
    fallback: {
      name: String(fallback.name || preset.label).trim(),
      category: sanitizeCategory(fallback.category),
      cuisine: String(fallback.cuisine || 'Global').trim(),
      caloriesPer100g: Math.max(1, parseNumber(fallback.caloriesPer100g, 80)),
      proteinPer100g: Math.max(0, parseNumber(fallback.proteinPer100g, 1)),
      carbsPer100g: Math.max(0, parseNumber(fallback.carbsPer100g, 10)),
      fatsPer100g: Math.max(0, parseNumber(fallback.fatsPer100g, 1)),
      fiberPer100g: Math.max(0, parseNumber(fallback.fiberPer100g, 0)),
      averagePriceUsd: Math.max(0.05, parseNumber(fallback.averagePriceUsd, 3)),
      averagePriceUnit: averagePriceUnit === 'kg' || averagePriceUnit === 'l' ? averagePriceUnit : toBasePriceUnit(unit)
    }
  };
};

const createIngredientFromPreset = (preset) => {
  const normalized = normalizeInstantPreset(preset);
  if (!normalized) {
    return null;
  }

  return {
    id: normalized.backendIngredientId,
    name: normalized.fallback.name,
    category: normalized.fallback.category,
    cuisine: normalized.fallback.cuisine,
    caloriesPer100g: normalized.fallback.caloriesPer100g,
    proteinPer100g: normalized.fallback.proteinPer100g,
    carbsPer100g: normalized.fallback.carbsPer100g,
    fatsPer100g: normalized.fallback.fatsPer100g,
    fiberPer100g: normalized.fallback.fiberPer100g,
    averagePriceUsd: normalized.fallback.averagePriceUsd,
    averagePriceUnit: normalized.fallback.averagePriceUnit,
    aliases: normalized.aliases,
    regionalAvailability: ['Global']
  };
};

const readCustomInstantPresets = () => {
  if (typeof window === 'undefined') {
    return [];
  }

  try {
    const raw = window.localStorage.getItem(INSTANT_PRESET_STORAGE_KEY);
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .map((item, index) => normalizeInstantPreset(item, index))
      .filter(Boolean)
      .slice(0, 80);
  } catch (error) {
    return [];
  }
};

const writeCustomInstantPresets = (presets) => {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    window.localStorage.setItem(INSTANT_PRESET_STORAGE_KEY, JSON.stringify(presets.slice(0, 80)));
  } catch (error) {
    // Ignore local storage write errors.
  }
};

const presetSearchScore = (preset, normalizedQuery) => {
  const text = normalizeText(`${preset.label} ${preset.query} ${(preset.aliases || []).join(' ')}`);
  if (!text) {
    return 0;
  }

  const tokens = normalizedQuery.split(' ').filter(Boolean);
  let score = text.startsWith(normalizedQuery) ? 120 : text.includes(normalizedQuery) ? 80 : 0;

  tokens.forEach((token) => {
    if (token.length < 2) {
      return;
    }
    if (text.startsWith(token)) {
      score += 24;
    } else if (text.includes(token)) {
      score += 14;
    }
  });

  return score;
};

const levenshteinDistance = (left, right) => {
  const a = String(left || '');
  const b = String(right || '');
  const aLen = a.length;
  const bLen = b.length;

  if (!aLen) {
    return bLen;
  }
  if (!bLen) {
    return aLen;
  }

  const previous = Array.from({ length: bLen + 1 }, (_, index) => index);
  const current = new Array(bLen + 1);

  for (let i = 1; i <= aLen; i += 1) {
    current[0] = i;
    for (let j = 1; j <= bLen; j += 1) {
      const substitutionCost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + substitutionCost);
    }
    for (let j = 0; j <= bLen; j += 1) {
      previous[j] = current[j];
    }
  }

  return previous[bLen];
};

const smartTextMatchScore = (textValue, queryValue) => {
  const text = normalizeText(textValue);
  const query = normalizeText(queryValue);
  if (!text || !query) {
    return 0;
  }

  let score = 0;
  if (text === query) {
    score += 240;
  } else if (text.startsWith(query)) {
    score += 150;
  } else if (text.includes(query)) {
    score += 90;
  }

  const textTokens = text.split(' ').filter(Boolean);
  const queryTokens = query.split(' ').filter(Boolean);
  queryTokens.forEach((token) => {
    if (token.length < 2) {
      return;
    }

    if (textTokens.some((word) => word === token)) {
      score += 36;
      return;
    }

    if (textTokens.some((word) => word.startsWith(token))) {
      score += 28;
      return;
    }

    if (text.includes(token)) {
      score += 18;
      return;
    }

    if (token.length >= 4) {
      const nearDistance = textTokens.reduce((best, word) => {
        const deltaLength = Math.abs(word.length - token.length);
        if (deltaLength > 2) {
          return best;
        }
        const distance = levenshteinDistance(word, token);
        return Math.min(best, distance);
      }, 9);

      if (nearDistance <= 1) {
        score += 14;
      } else if (nearDistance === 2) {
        score += 8;
      }
    }
  });

  return score;
};

const metricValue = (value, fallback = Number.POSITIVE_INFINITY) => {
  const parsed = parseNumber(value, fallback);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const proteinDensity = (protein, calories) => metricValue(protein, 0) / Math.max(1, metricValue(calories, 0));

const sortBySmartMode = (items, sortMode, matchScore, caloriesValue, proteinValue, priceValue) =>
  [...items].sort((left, right) => {
    if (sortMode === 'protein') {
      const densityDiff = proteinDensity(proteinValue(right), caloriesValue(right)) - proteinDensity(proteinValue(left), caloriesValue(left));
      if (Math.abs(densityDiff) > 0.0001) {
        return densityDiff;
      }
    } else if (sortMode === 'calories') {
      const calorieDiff = metricValue(caloriesValue(left)) - metricValue(caloriesValue(right));
      if (Math.abs(calorieDiff) > 0.0001) {
        return calorieDiff;
      }
    } else if (sortMode === 'price') {
      const priceDiff = metricValue(priceValue(left)) - metricValue(priceValue(right));
      if (Math.abs(priceDiff) > 0.0001) {
        return priceDiff;
      }
    }

    const matchDiff = matchScore(right) - matchScore(left);
    if (matchDiff !== 0) {
      return matchDiff;
    }

    return normalizeText(String(left?.name || left?.label || '')).localeCompare(normalizeText(String(right?.name || right?.label || '')));
  });

const findIngredientMatchForName = (items, name) => {
  if (!items?.length) {
    return null;
  }

  const normalized = normalizeText(name);
  const exact = items.find((item) => {
    if (normalizeText(item?.name) === normalized) {
      return true;
    }
    return (item?.aliases || []).some((alias) => normalizeText(alias) === normalized);
  });

  if (exact) {
    return exact;
  }

  const startsWith = items.find((item) => normalizeText(item?.name).startsWith(normalized));
  return startsWith || items[0] || null;
};

const buildIngredientPayloadFromPreset = (preset) => {
  const normalized = normalizeInstantPreset(preset);
  const ingredient = createIngredientFromPreset(normalized);
  const aliases = Array.from(new Set([ingredient.name, ...normalized.aliases])).slice(0, 12);

  return {
    name: ingredient.name,
    category: sanitizeCategory(ingredient.category),
    cuisine: ingredient.cuisine || 'Global',
    caloriesPer100g: Math.max(1, parseNumber(ingredient.caloriesPer100g, 80)),
    servingNote: `${formatServingLabel(normalized.quantity, normalized.unit)} instant preset`,
    proteinPer100g: Math.max(0, parseNumber(ingredient.proteinPer100g)),
    carbsPer100g: Math.max(0, parseNumber(ingredient.carbsPer100g)),
    fatsPer100g: Math.max(0, parseNumber(ingredient.fatsPer100g)),
    fiberPer100g: Math.max(0, parseNumber(ingredient.fiberPer100g)),
    averagePriceUsd: Math.max(0.05, parseNumber(ingredient.averagePriceUsd, 3)),
    averagePriceUnit: toBasePriceUnit(normalized.unit),
    aliases,
    regionalAvailability: ['Global'],
    factConfirmed: true,
    source: 'USER_INSTANT_PRESET'
  };
};

function AddEntry({ userId, onEntryAdded }) {
  const [mode, setMode] = useState('instant');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');

  const [instantQuery, setInstantQuery] = useState('');
  const [instantPresetSuggestions, setInstantPresetSuggestions] = useState([]);
  const [instantIngredientSuggestions, setInstantIngredientSuggestions] = useState([]);
  const [instantDishSuggestions, setInstantDishSuggestions] = useState([]);
  const [instantLoading, setInstantLoading] = useState(false);
  const [instantSearchScope, setInstantSearchScope] = useState('all');
  const [instantSmartSort, setInstantSmartSort] = useState('match');
  const [selectedInstant, setSelectedInstant] = useState(null);
  const [instantQuantity, setInstantQuantity] = useState(200);
  const [instantUnit, setInstantUnit] = useState('ml');

  const [customInstantPresets, setCustomInstantPresets] = useState(() => readCustomInstantPresets());
  const [showPresetBuilder, setShowPresetBuilder] = useState(false);
  const [presetDraft, setPresetDraft] = useState(CUSTOM_PRESET_DEFAULTS);
  const [presetSaving, setPresetSaving] = useState(false);
  const [presetStatus, setPresetStatus] = useState('');

  const [ingredientQuery, setIngredientQuery] = useState('');
  const [ingredientSuggestions, setIngredientSuggestions] = useState([]);
  const [ingredientLoading, setIngredientLoading] = useState(false);
  const [selectedIngredient, setSelectedIngredient] = useState(null);
  const [quantity, setQuantity] = useState(100);
  const [unit, setUnit] = useState('g');
  const [ingredientMixRows, setIngredientMixRows] = useState([]);

  const [dishQuery, setDishQuery] = useState('');
  const [dishSuggestions, setDishSuggestions] = useState([]);
  const [dishLoading, setDishLoading] = useState(false);
  const [selectedDish, setSelectedDish] = useState(null);
  const [dishAmount, setDishAmount] = useState(250);
  const [dishUnit, setDishUnit] = useState('g');
  const [customizeDish, setCustomizeDish] = useState(false);
  const [customRows, setCustomRows] = useState([]);
  const [note, setNote] = useState('');

  const [customSearch, setCustomSearch] = useState('');
  const [customSuggestions, setCustomSuggestions] = useState([]);
  const [customLoading, setCustomLoading] = useState(false);
  const [customSelection, setCustomSelection] = useState(null);
  const [customQuantity, setCustomQuantity] = useState(100);
  const [customUnit, setCustomUnit] = useState('g');

  const [dishCalculation, setDishCalculation] = useState(null);

  const instantTimerRef = useRef(null);
  const instantQueryIdRef = useRef(0);
  const instantIngredientCacheRef = useRef(new Map());
  const instantDishCacheRef = useRef(new Map());

  const ingredientTimerRef = useRef(null);
  const ingredientQueryIdRef = useRef(0);
  const ingredientSearchCacheRef = useRef(new Map());
  const dishTimerRef = useRef(null);
  const dishQueryIdRef = useRef(0);
  const dishDetailQueryIdRef = useRef(0);
  const dishSearchCacheRef = useRef(new Map());
  const customTimerRef = useRef(null);
  const customQueryIdRef = useRef(0);
  const customSearchCacheRef = useRef(new Map());

  const allInstantPresets = useMemo(
    () => [...customInstantPresets, ...BUILT_IN_INSTANT_PRESETS].map((preset, index) => normalizeInstantPreset(preset, index)).filter(Boolean),
    [customInstantPresets]
  );

  useEffect(() => {
    writeCustomInstantPresets(customInstantPresets);
  }, [customInstantPresets]);

  useEffect(
    () => () => {
      if (instantTimerRef.current) {
        window.clearTimeout(instantTimerRef.current);
      }
      if (ingredientTimerRef.current) {
        window.clearTimeout(ingredientTimerRef.current);
      }
      if (dishTimerRef.current) {
        window.clearTimeout(dishTimerRef.current);
      }
      if (customTimerRef.current) {
        window.clearTimeout(customTimerRef.current);
      }
    },
    []
  );

  useEffect(() => {
    if (instantTimerRef.current) {
      window.clearTimeout(instantTimerRef.current);
    }

    const normalized = normalizeText(instantQuery);
    if (normalized.length < 2) {
      setInstantPresetSuggestions([]);
      setInstantIngredientSuggestions([]);
      setInstantDishSuggestions([]);
      setInstantLoading(false);
      return;
    }

    const presetMatches = allInstantPresets
      .map((preset) => ({
        preset,
        score: presetSearchScore(preset, normalized)
      }))
      .filter((item) => item.score > 0)
      .sort((a, b) => b.score - a.score)
      .map((item) => item.preset)
      .slice(0, 8);

    setInstantPresetSuggestions(presetMatches);

    const fetchIngredients = instantSearchScope === 'all' || instantSearchScope === 'ingredient';
    const fetchDishes = instantSearchScope === 'all' || instantSearchScope === 'dish';

    if (!fetchIngredients) {
      setInstantIngredientSuggestions([]);
    }
    if (!fetchDishes) {
      setInstantDishSuggestions([]);
    }

    if (!fetchIngredients && !fetchDishes) {
      setInstantLoading(false);
      return;
    }

    const cachedIngredients = fetchIngredients ? instantIngredientCacheRef.current.get(normalized) : [];
    const cachedDishes = fetchDishes ? instantDishCacheRef.current.get(normalized) : [];

    const ingredientsReady = !fetchIngredients || Boolean(cachedIngredients);
    const dishesReady = !fetchDishes || Boolean(cachedDishes);
    if (ingredientsReady && dishesReady) {
      setInstantIngredientSuggestions(cachedIngredients || []);
      setInstantDishSuggestions(cachedDishes || []);
      setInstantLoading(false);
      return;
    }

    instantTimerRef.current = window.setTimeout(async () => {
      const requestId = instantQueryIdRef.current + 1;
      instantQueryIdRef.current = requestId;
      setInstantLoading(true);

      try {
        const ingredientRequest = fetchIngredients
          ? cachedIngredients
            ? Promise.resolve({ data: cachedIngredients })
            : ingredientAPI.search(instantQuery, { limit: 12 })
          : Promise.resolve({ data: [] });
        const dishRequest = fetchDishes
          ? cachedDishes
            ? Promise.resolve({ data: cachedDishes })
            : dishAPI.search(instantQuery, { limit: 12 })
          : Promise.resolve({ data: [] });

        const [ingredientsResponse, dishesResponse] = await Promise.all([ingredientRequest, dishRequest]);

        if (requestId !== instantQueryIdRef.current) {
          return;
        }

        const ingredients = (ingredientsResponse.data || []).slice(0, 12);
        const dishes = (dishesResponse.data || []).slice(0, 12);

        if (fetchIngredients && !cachedIngredients) {
          saveCacheEntry(instantIngredientCacheRef.current, normalized, ingredients);
        }
        if (fetchDishes && !cachedDishes) {
          saveCacheEntry(instantDishCacheRef.current, normalized, dishes);
        }

        setInstantIngredientSuggestions(ingredients);
        setInstantDishSuggestions(dishes);
      } catch (error) {
        if (requestId === instantQueryIdRef.current) {
          setInstantIngredientSuggestions([]);
          setInstantDishSuggestions([]);
        }
      } finally {
        if (requestId === instantQueryIdRef.current) {
          setInstantLoading(false);
        }
      }
    }, 150);
  }, [instantQuery, allInstantPresets, instantSearchScope]);

  useEffect(() => {
    if (ingredientTimerRef.current) {
      window.clearTimeout(ingredientTimerRef.current);
    }

    const normalized = normalizeText(ingredientQuery);
    const isExact = normalizeText(selectedIngredient?.name) === normalized;

    if (normalized.length < 2 || isExact) {
      setIngredientSuggestions([]);
      setIngredientLoading(false);
      return;
    }

    const cached = ingredientSearchCacheRef.current.get(normalized);
    if (cached) {
      setIngredientSuggestions(cached);
      setIngredientLoading(false);
      return;
    }

    ingredientTimerRef.current = window.setTimeout(async () => {
      const requestId = ingredientQueryIdRef.current + 1;
      ingredientQueryIdRef.current = requestId;
      setIngredientLoading(true);

      try {
        const response = await ingredientAPI.search(ingredientQuery, { limit: 10 });
        if (requestId !== ingredientQueryIdRef.current) {
          return;
        }
        const items = (response.data || []).slice(0, 10);
        saveCacheEntry(ingredientSearchCacheRef.current, normalized, items);
        setIngredientSuggestions(items);
      } catch (error) {
        if (requestId === ingredientQueryIdRef.current) {
          setIngredientSuggestions([]);
        }
      } finally {
        if (requestId === ingredientQueryIdRef.current) {
          setIngredientLoading(false);
        }
      }
    }, 150);
  }, [ingredientQuery, selectedIngredient]);

  useEffect(() => {
    if (dishTimerRef.current) {
      window.clearTimeout(dishTimerRef.current);
    }

    const normalized = normalizeText(dishQuery);
    const isExact = normalizeText(selectedDish?.name) === normalized;

    if (normalized.length < 2 || isExact) {
      setDishSuggestions([]);
      setDishLoading(false);
      return;
    }

    const cached = dishSearchCacheRef.current.get(normalized);
    if (cached) {
      setDishSuggestions(cached);
      setDishLoading(false);
      return;
    }

    dishTimerRef.current = window.setTimeout(async () => {
      const requestId = dishQueryIdRef.current + 1;
      dishQueryIdRef.current = requestId;
      setDishLoading(true);

      try {
        const response = await dishAPI.suggest(dishQuery, { limit: 10 });
        if (requestId !== dishQueryIdRef.current) {
          return;
        }
        const items = (response.data || []).slice(0, 10);
        saveCacheEntry(dishSearchCacheRef.current, normalized, items);
        setDishSuggestions(items);
      } catch (error) {
        if (requestId === dishQueryIdRef.current) {
          setDishSuggestions([]);
        }
      } finally {
        if (requestId === dishQueryIdRef.current) {
          setDishLoading(false);
        }
      }
    }, 150);
  }, [dishQuery, selectedDish]);

  useEffect(() => {
    if (customTimerRef.current) {
      window.clearTimeout(customTimerRef.current);
    }

    const normalized = normalizeText(customSearch);
    const isExact = normalizeText(customSelection?.name) === normalized;

    if (normalized.length < 2 || isExact) {
      setCustomSuggestions([]);
      setCustomLoading(false);
      return;
    }

    const cached = customSearchCacheRef.current.get(normalized);
    if (cached) {
      setCustomSuggestions(cached);
      setCustomLoading(false);
      return;
    }

    customTimerRef.current = window.setTimeout(async () => {
      const requestId = customQueryIdRef.current + 1;
      customQueryIdRef.current = requestId;
      setCustomLoading(true);

      try {
        const response = await ingredientAPI.search(customSearch, { limit: 10 });
        if (requestId !== customQueryIdRef.current) {
          return;
        }
        const items = (response.data || []).slice(0, 10);
        saveCacheEntry(customSearchCacheRef.current, normalized, items);
        setCustomSuggestions(items);
      } catch (error) {
        if (requestId === customQueryIdRef.current) {
          setCustomSuggestions([]);
        }
      } finally {
        if (requestId === customQueryIdRef.current) {
          setCustomLoading(false);
        }
      }
    }, 150);
  }, [customSearch, customSelection]);

  useEffect(() => {
    if (!selectedDish || !customizeDish) {
      return;
    }

    if (customRows.length) {
      return;
    }

    const rows = (selectedDish.components || []).map(toCustomRowFromComponent);
    setCustomRows(rows);
  }, [selectedDish, customizeDish, customRows.length]);

  const normalizedInstantQuery = useMemo(() => normalizeText(instantQuery), [instantQuery]);

  const sortedInstantPresetSuggestions = useMemo(
    () =>
      sortBySmartMode(
        instantPresetSuggestions,
        instantSmartSort,
        (preset) => {
          const descriptor = `${preset?.label || ''} ${preset?.query || ''} ${(preset?.aliases || []).join(' ')} ${preset?.fallback?.cuisine || ''}`;
          return presetSearchScore(preset, normalizedInstantQuery) + smartTextMatchScore(descriptor, normalizedInstantQuery);
        },
        (preset) => preset?.fallback?.caloriesPer100g,
        (preset) => preset?.fallback?.proteinPer100g,
        (preset) => preset?.fallback?.averagePriceUsd
      ).slice(0, 8),
    [instantPresetSuggestions, instantSmartSort, normalizedInstantQuery]
  );

  const sortedInstantIngredientSuggestions = useMemo(
    () =>
      sortBySmartMode(
        instantIngredientSuggestions,
        instantSmartSort,
        (item) =>
          smartTextMatchScore(
            `${item?.name || ''} ${(item?.aliases || []).join(' ')} ${item?.category || ''} ${item?.cuisine || ''}`,
            normalizedInstantQuery
          ),
        (item) => item?.caloriesPer100g,
        (item) => item?.proteinPer100g,
        (item) => item?.averagePriceUsd
      ).slice(0, 8),
    [instantIngredientSuggestions, instantSmartSort, normalizedInstantQuery]
  );

  const sortedInstantDishSuggestions = useMemo(
    () =>
      sortBySmartMode(
        instantDishSuggestions,
        instantSmartSort,
        (item) => smartTextMatchScore(`${item?.name || ''} ${item?.cuisine || ''} ${item?.description || ''}`, normalizedInstantQuery),
        (item) => item?.caloriesPerServing,
        (item) => item?.proteinPerServing,
        (item) => item?.estimatedPriceUsdPerServing
      ).slice(0, 8),
    [instantDishSuggestions, instantSmartSort, normalizedInstantQuery]
  );

  const visibleInstantPresetSuggestions =
    instantSearchScope === 'all' || instantSearchScope === 'instant' ? sortedInstantPresetSuggestions : [];
  const visibleInstantDishSuggestions =
    instantSearchScope === 'all' || instantSearchScope === 'dish' ? sortedInstantDishSuggestions : [];
  const visibleInstantIngredientSuggestions =
    instantSearchScope === 'all' || instantSearchScope === 'ingredient' ? sortedInstantIngredientSuggestions : [];

  const ingredientMixLineItems = useMemo(
    () =>
      ingredientMixRows.map((row) => ({
        ...row,
        nutrition: lineNutritionFromIngredient(row.ingredient, row.quantity, row.unit)
      })),
    [ingredientMixRows]
  );

  const ingredientMixTotals = useMemo(
    () =>
      ingredientMixLineItems.reduce(
        (totals, row) => ({
          calories: totals.calories + row.nutrition.calories,
          protein: totals.protein + row.nutrition.protein,
          carbs: totals.carbs + row.nutrition.carbs,
          fats: totals.fats + row.nutrition.fats,
          fiber: totals.fiber + row.nutrition.fiber,
          priceUsd: totals.priceUsd + row.nutrition.priceUsd
        }),
        { calories: 0, protein: 0, carbs: 0, fats: 0, fiber: 0, priceUsd: 0 }
      ),
    [ingredientMixLineItems]
  );

  const ingredientPreview = useMemo(
    () =>
      ingredientMixLineItems.length
        ? ingredientMixTotals
        : lineNutritionFromIngredient(selectedIngredient, quantity, unit),
    [selectedIngredient, quantity, unit, ingredientMixLineItems.length, ingredientMixTotals]
  );

  const instantPreview = useMemo(
    () => lineNutritionFromIngredient(selectedInstant?.ingredient, instantQuantity, instantUnit),
    [selectedInstant, instantQuantity, instantUnit]
  );

  const dishPreview = useMemo(() => {
    if (!selectedDish) {
      return {
        calories: 0,
        protein: 0,
        carbs: 0,
        fats: 0,
        fiber: 0,
        priceUsd: 0
      };
    }

    if (!customizeDish) {
      return lineNutritionFromIngredient(
        {
          caloriesPer100g: selectedDish.caloriesPerServing || 0,
          proteinPer100g: selectedDish.proteinPerServing || 0,
          carbsPer100g: selectedDish.carbsPerServing || 0,
          fatsPer100g: selectedDish.fatsPerServing || 0,
          fiberPer100g: selectedDish.fiberPerServing || 0,
          averagePriceUsd: selectedDish.estimatedPriceUsdPerServing || 0,
          averagePriceUnit: 'serving'
        },
        dishAmount,
        dishUnit
      );
    }

    const base = customRows.reduce(
      (totals, row) => {
        const line = lineNutritionFromIngredient(row.ingredient, row.quantity, row.unit);
        return {
          calories: totals.calories + line.calories,
          protein: totals.protein + line.protein,
          carbs: totals.carbs + line.carbs,
          fats: totals.fats + line.fats,
          fiber: totals.fiber + line.fiber,
          priceUsd: totals.priceUsd + line.priceUsd
        };
      },
      { calories: 0, protein: 0, carbs: 0, fats: 0, fiber: 0, priceUsd: 0 }
    );

    return base;
  }, [selectedDish, dishAmount, dishUnit, customizeDish, customRows]);

  const displayedPreview = mode === 'dish' ? dishPreview : mode === 'ingredient' ? ingredientPreview : instantPreview;

  const instantSuggestionsVisible = useMemo(() => {
    const normalizedQuery = normalizeText(instantQuery);
    if (normalizedQuery.length < 2) {
      return false;
    }

    const selectedName = normalizeText(
      selectedInstant?.kind === 'preset'
        ? selectedInstant?.preset?.label
        : selectedInstant?.ingredient?.name
    );

    return normalizedQuery !== selectedName;
  }, [instantQuery, selectedInstant]);

  const ingredientSuggestionsVisible =
    normalizeText(ingredientQuery).length >= 2 &&
    normalizeText(selectedIngredient?.name) !== normalizeText(ingredientQuery);

  const dishSuggestionsVisible =
    normalizeText(dishQuery).length >= 2 &&
    normalizeText(selectedDish?.name) !== normalizeText(dishQuery);

  const customSuggestionsVisible =
    normalizeText(customSearch).length >= 2 &&
    normalizeText(customSelection?.name) !== normalizeText(customSearch);

  const instantSearchStatus = useMemo(() => {
    const normalized = normalizeText(instantQuery);
    if (normalized.length < 2) {
      return 'Type 2+ letters';
    }
    if (instantLoading) {
      return 'Searching...';
    }

    const total =
      visibleInstantPresetSuggestions.length + visibleInstantDishSuggestions.length + visibleInstantIngredientSuggestions.length;
    if (!total) {
      return 'No matches';
    }

    return `${total} matches`;
  }, [
    instantQuery,
    instantLoading,
    visibleInstantPresetSuggestions.length,
    visibleInstantDishSuggestions.length,
    visibleInstantIngredientSuggestions.length
  ]);

  const ingredientSearchStatus = useMemo(() => {
    const normalized = normalizeText(ingredientQuery);
    if (normalized.length < 2) {
      return 'Type 2+ letters';
    }
    if (ingredientLoading) {
      return 'Searching...';
    }
    if (!ingredientSuggestions.length) {
      return 'No matches';
    }
    return `${ingredientSuggestions.length} matches`;
  }, [ingredientQuery, ingredientLoading, ingredientSuggestions.length]);

  const dishSearchStatus = useMemo(() => {
    const normalized = normalizeText(dishQuery);
    if (normalized.length < 2) {
      return 'Type 2+ letters';
    }
    if (dishLoading) {
      return 'Searching...';
    }
    if (!dishSuggestions.length) {
      return 'No matches';
    }
    return `${dishSuggestions.length} matches`;
  }, [dishQuery, dishLoading, dishSuggestions.length]);

  const customSearchStatus = useMemo(() => {
    const normalized = normalizeText(customSearch);
    if (normalized.length < 2) {
      return 'Type 2+ letters';
    }
    if (customLoading) {
      return 'Searching...';
    }
    if (!customSuggestions.length) {
      return 'No matches';
    }
    return `${customSuggestions.length} matches`;
  }, [customSearch, customLoading, customSuggestions.length]);

  const shiftMainQuantity = (direction) => {
    const step = getUnitStepForTenGrams(unit);
    const next = parseNumber(quantity, 0) + direction * step;
    setQuantity(Math.max(0, Number(next.toFixed(3))));
  };

  const updateIngredientMixRow = (index, patch) => {
    setIngredientMixRows((previous) =>
      previous.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row))
    );
  };

  const shiftIngredientMixQuantity = (index, direction) => {
    setIngredientMixRows((previous) =>
      previous.map((row, rowIndex) => {
        if (rowIndex !== index) {
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

  const addIngredientToMix = () => {
    if (!selectedIngredient || parseNumber(quantity) <= 0) {
      return;
    }

    setIngredientMixRows((previous) => [
      ...previous,
      {
        key: `${selectedIngredient.id}-${Math.random().toString(16).slice(2)}`,
        ingredientId: selectedIngredient.id,
        ingredient: selectedIngredient,
        quantity: parseNumber(quantity, 120),
        unit
      }
    ]);
  };

  const removeIngredientFromMix = (index) => {
    setIngredientMixRows((previous) => previous.filter((_, rowIndex) => rowIndex !== index));
  };

  const shiftInstantQuantity = (direction) => {
    const step = getUnitStepForTenGrams(instantUnit);
    const next = parseNumber(instantQuantity, 0) + direction * step;
    setInstantQuantity(Math.max(0, Number(next.toFixed(3))));
  };

  const shiftCustomQuantity = (direction) => {
    const step = getUnitStepForTenGrams(customUnit);
    const next = parseNumber(customQuantity, 0) + direction * step;
    setCustomQuantity(Math.max(0, Number(next.toFixed(3))));
  };

  const shiftRowQuantity = (index, direction) => {
    setCustomRows((previous) =>
      previous.map((row, rowIndex) => {
        if (rowIndex !== index) {
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

  const updateCustomRow = (index, patch) => {
    setCustomRows((previous) =>
      previous.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row))
    );
  };

  const selectInstantPreset = (preset) => {
    const normalized = normalizeInstantPreset(preset);
    if (!normalized) {
      return;
    }

    setSelectedInstant({
      kind: 'preset',
      preset: normalized,
      ingredient: createIngredientFromPreset(normalized)
    });
    setInstantQuery(normalized.label);
    setInstantQuantity(normalized.quantity);
    setInstantUnit(normalized.unit);
    setInstantPresetSuggestions([]);
    setInstantIngredientSuggestions([]);
    setInstantDishSuggestions([]);
  };

  const selectInstantIngredient = (ingredient) => {
    const priceUnit = normalizeText(ingredient?.averagePriceUnit);
    const nextUnit = priceUnit === 'l' ? 'ml' : 'g';
    setSelectedInstant({
      kind: 'ingredient',
      ingredient
    });
    setInstantQuery(ingredient.name);
    setInstantQuantity(nextUnit === 'ml' ? 200 : 100);
    setInstantUnit(nextUnit);
    setInstantPresetSuggestions([]);
    setInstantIngredientSuggestions([]);
    setInstantDishSuggestions([]);
  };

  const selectIngredient = (ingredient) => {
    setSelectedIngredient(ingredient);
    setIngredientQuery(ingredient.name);
    setIngredientSuggestions([]);
  };

  const selectDish = async (dish, withCustomize = false) => {
    const requestId = dishDetailQueryIdRef.current + 1;
    dishDetailQueryIdRef.current = requestId;
    setDishLoading(true);
    setDishSuggestions([]);
    setDishAmount(250);
    setDishUnit('g');

    try {
      const response = await dishAPI.get(dish.id);
      if (requestId !== dishDetailQueryIdRef.current) {
        return;
      }

      const fullDish = response?.data || dish;
      setSelectedDish(fullDish);
      setDishQuery(fullDish.name);
      setDishCalculation(null);

      if (withCustomize) {
        setCustomizeDish(true);
        setCustomRows((fullDish.components || []).map(toCustomRowFromComponent));
      } else {
        setCustomizeDish(false);
        setCustomRows([]);
      }
    } catch (error) {
      if (requestId !== dishDetailQueryIdRef.current) {
        return;
      }
      setSelectedDish(dish);
      setDishQuery(dish.name);
      setDishCalculation(null);
      if (withCustomize) {
        setCustomizeDish(true);
        setCustomRows([]);
      } else {
        setCustomizeDish(false);
        setCustomRows([]);
      }
      setStatus('Loaded basic dish.');
    } finally {
      if (requestId === dishDetailQueryIdRef.current) {
        setDishLoading(false);
      }
    }
  };

  const jumpToDishModeFromQuickSearch = (dish) => {
    setMode('dish');
    setStatus('Dish selected.');
    selectDish(dish, false);
  };

  const selectCustomIngredient = (ingredient) => {
    setCustomSelection(ingredient);
    setCustomSearch(ingredient.name);
    setCustomSuggestions([]);
  };

  const addCustomIngredient = () => {
    if (!customSelection || parseNumber(customQuantity) <= 0) {
      return;
    }

    setCustomRows((previous) => [
      ...previous,
      {
        key: `${customSelection.id}-${Math.random().toString(16).slice(2)}`,
        ingredientId: customSelection.id,
        ingredientName: customSelection.name,
        ingredient: customSelection,
        quantity: parseNumber(customQuantity, 100),
        unit: customUnit
      }
    ]);

    setCustomSelection(null);
    setCustomSearch('');
    setCustomQuantity(100);
    setCustomUnit('g');
  };

  const removeCustomIngredient = (index) => {
    setCustomRows((previous) => previous.filter((_, rowIndex) => rowIndex !== index));
  };

  const runDishCalculation = async () => {
    if (!selectedDish || !customizeDish) {
      return;
    }

    const customIngredients = customRows
      .filter((row) => row.ingredientId && parseNumber(row.quantity) > 0)
      .map((row) => ({
        ingredientId: row.ingredientId,
        grams: toGrams(row.quantity, row.unit)
      }));

    try {
      const response = await dishAPI.calculate(selectedDish.id, {
        servings: 1,
        customIngredients
      });
      setDishCalculation(metricsFromResponse(response.data));
    } catch (error) {
      setDishCalculation(null);
    }
  };

  useEffect(() => {
    if (mode !== 'dish' || !selectedDish || !customizeDish) {
      return;
    }

    const timer = window.setTimeout(() => {
      runDishCalculation();
    }, 360);

    return () => window.clearTimeout(timer);
  }, [mode, selectedDish, dishAmount, dishUnit, customizeDish, customRows]);

  const findExistingIngredientByName = async (name) => {
    if (!normalizeText(name)) {
      return null;
    }

    const response = await ingredientAPI.search(name, { limit: 12 });
    return findIngredientMatchForName(response.data || [], name);
  };

  const linkCustomPresetToIngredient = (presetId, ingredientId) => {
    if (!presetId || !ingredientId) {
      return;
    }

    setCustomInstantPresets((previous) =>
      previous.map((preset) =>
        preset.id === presetId
          ? {
              ...preset,
              backendIngredientId: ingredientId
            }
          : preset
      )
    );
  };

  const syncPresetToBackendIngredient = async (preset) => {
    const normalized = normalizeInstantPreset(preset);
    if (!normalized) {
      return null;
    }

    if (normalized.backendIngredientId) {
      try {
        const response = await ingredientAPI.get(normalized.backendIngredientId);
        if (response?.data?.id) {
          return response.data;
        }
      } catch (error) {
        // Continue to fallback lookup/create.
      }
    }

    const existing = await findExistingIngredientByName(normalized.fallback.name);
    if (existing?.id) {
      return existing;
    }

    const payload = buildIngredientPayloadFromPreset(normalized);
    try {
      const created = await ingredientAPI.createCustom(payload);
      if (created?.data?.id) {
        return created.data;
      }
    } catch (error) {
      if (error?.response?.status === 409) {
        const conflictMatch = await findExistingIngredientByName(payload.name);
        if (conflictMatch?.id) {
          return conflictMatch;
        }
      }
      throw error;
    }

    return null;
  };

  const ensureIngredientForInstantSelection = async (selection) => {
    if (!selection) {
      return null;
    }

    if (selection.kind === 'ingredient') {
      return selection.ingredient?.id ? selection.ingredient : null;
    }

    const preset = normalizeInstantPreset(selection.preset);
    if (!preset) {
      return null;
    }

    const synced = await syncPresetToBackendIngredient(preset);
    if (synced?.id) {
      if (preset.custom) {
        linkCustomPresetToIngredient(preset.id, synced.id);
      }
      setSelectedInstant((previous) => {
        if (!previous || previous.kind !== 'preset' || previous.preset?.id !== preset.id) {
          return previous;
        }
        return {
          ...previous,
          preset: {
            ...previous.preset,
            backendIngredientId: synced.id
          },
          ingredient: {
            ...previous.ingredient,
            ...synced,
            id: synced.id
          }
        };
      });
      return synced;
    }

    return selection.ingredient?.id ? selection.ingredient : null;
  };

  const onPresetDraftChange = (field, value) => {
    setPresetDraft((previous) => ({
      ...previous,
      [field]: value
    }));
  };

  const resetPresetDraft = () => {
    setPresetDraft(CUSTOM_PRESET_DEFAULTS);
  };

  const removeCustomPreset = (presetId) => {
    setCustomInstantPresets((previous) => previous.filter((preset) => preset.id !== presetId));
    setSelectedInstant((previous) => {
      if (!previous || previous.kind !== 'preset' || previous.preset?.id !== presetId) {
        return previous;
      }
      return null;
    });
  };

  const createCustomPreset = async () => {
    const name = String(presetDraft.name || '').trim();
    const quantityValue = Math.max(0, parseNumber(presetDraft.quantity, 0));
    const caloriesValue = Math.max(0, parseNumber(presetDraft.caloriesPer100g, 0));

    if (!name || quantityValue <= 0 || caloriesValue <= 0) {
      setPresetStatus('Enter preset name, serving quantity, and calories before saving.');
      return;
    }

    const preset = normalizeInstantPreset({
      id: `custom-${Date.now().toString(36)}-${Math.random().toString(16).slice(2, 7)}`,
      custom: true,
      label: name,
      query: name,
      aliases: parseCsv(presetDraft.aliases),
      quantity: quantityValue,
      unit: UNIT_OPTIONS.includes(presetDraft.unit) ? presetDraft.unit : 'g',
      hint: `${formatServingLabel(quantityValue, UNIT_OPTIONS.includes(presetDraft.unit) ? presetDraft.unit : 'g')} custom`,
      fallback: {
        name,
        category: sanitizeCategory(presetDraft.category),
        cuisine: String(presetDraft.cuisine || 'Global').trim() || 'Global',
        caloriesPer100g: caloriesValue,
        proteinPer100g: Math.max(0, parseNumber(presetDraft.proteinPer100g)),
        carbsPer100g: Math.max(0, parseNumber(presetDraft.carbsPer100g)),
        fatsPer100g: Math.max(0, parseNumber(presetDraft.fatsPer100g)),
        fiberPer100g: Math.max(0, parseNumber(presetDraft.fiberPer100g)),
        averagePriceUsd: Math.max(0.05, parseNumber(presetDraft.averagePriceUsd, 3)),
        averagePriceUnit: toBasePriceUnit(presetDraft.unit)
      }
    });

    if (!preset) {
      setPresetStatus('Unable to create preset.');
      return;
    }

    setPresetSaving(true);
    setPresetStatus('Saving preset...');
    setCustomInstantPresets((previous) => [preset, ...previous].slice(0, 80));
    selectInstantPreset(preset);

    try {
      const synced = await syncPresetToBackendIngredient(preset);
      if (synced?.id) {
        linkCustomPresetToIngredient(preset.id, synced.id);
        setSelectedInstant((previous) => {
          if (!previous || previous.kind !== 'preset' || previous.preset?.id !== preset.id) {
            return previous;
          }
          return {
            ...previous,
            preset: {
              ...previous.preset,
              backendIngredientId: synced.id
            },
            ingredient: {
              ...previous.ingredient,
              ...synced,
              id: synced.id
            }
          };
        });
        setPresetStatus('Preset saved.');
      } else {
        setPresetStatus('Preset saved locally.');
      }
    } catch (error) {
      setPresetStatus('Preset saved locally.');
    } finally {
      setPresetSaving(false);
      resetPresetDraft();
      setShowPresetBuilder(false);
    }
  };

  const finalPreview = mode === 'dish' && customizeDish && dishCalculation ? dishCalculation : displayedPreview;

  const activeContext =
    mode === 'dish'
      ? selectedDish
      : mode === 'ingredient'
        ? ingredientMixLineItems.length
          ? { name: 'Ingredient Mix', cuisine: 'Custom', category: 'MIX' }
          : selectedIngredient
        : selectedInstant?.ingredient;
  const tierMode = mode === 'dish' ? 'dish' : 'ingredient';
  const nutritionTier = useMemo(
    () => deriveNutritionTier(finalPreview, activeContext, tierMode),
    [finalPreview, activeContext, tierMode]
  );

  const handleCreateEntry = async (event) => {
    event.preventDefault();
    setStatus('');

    try {
      setBusy(true);

      if (!userId) {
        setStatus('Please login to add entries.');
        return;
      }

      if (mode === 'instant') {
        if (!selectedInstant || parseNumber(instantQuantity) <= 0) {
          setStatus('Select an instant food.');
          return;
        }

        const syncedIngredient = await ensureIngredientForInstantSelection(selectedInstant);
        if (!syncedIngredient?.id) {
          setStatus('Unable to save. Retry.');
          return;
        }

        await entryAPI.createIngredient({
          userId,
          ingredientId: syncedIngredient.id,
          quantity: parseNumber(instantQuantity),
          unit: instantUnit
        });

        setStatus('Instant entry added.');
      } else if (mode === 'ingredient') {
        const rowsToSave = ingredientMixRows.filter((row) => row.ingredientId && parseNumber(row.quantity) > 0);

        if (rowsToSave.length) {
          for (const row of rowsToSave) {
            await entryAPI.createIngredient({
              userId,
              ingredientId: row.ingredientId,
              quantity: parseNumber(row.quantity),
              unit: row.unit
            });
          }
          setIngredientMixRows([]);
          setStatus(`Added ${rowsToSave.length} ingredient entries.`);
        } else {
          if (!selectedIngredient || parseNumber(quantity) <= 0) {
            setStatus('Select an ingredient.');
            return;
          }

          await entryAPI.createIngredient({
            userId,
            ingredientId: selectedIngredient.id,
            quantity: parseNumber(quantity),
            unit
          });

          setStatus('Ingredient entry added.');
        }
      } else {
        if (!selectedDish || parseNumber(dishAmount) <= 0) {
          setStatus('Select a dish.');
          return;
        }

        const customIngredients = customizeDish
          ? customRows
              .filter((row) => row.ingredientId && parseNumber(row.quantity) > 0)
              .map((row) => ({
                ingredientId: row.ingredientId,
                grams: toGrams(row.quantity, row.unit)
              }))
          : [];

        await entryAPI.createDish({
          userId,
          dishId: selectedDish.id,
          servings: 1,
          customIngredients,
          note: note.trim() || null
        });

        setStatus('Dish entry added.');
      }

      onEntryAdded?.();
    } catch (error) {
      setStatus(error?.response?.data?.message || 'Failed to add entry.');
    } finally {
      setBusy(false);
    }
  };

  const submitLabel =
    busy
      ? 'Saving...'
      : mode === 'instant'
        ? 'Add Instant Entry'
        : mode === 'dish'
          ? 'Add Dish Entry'
          : ingredientMixRows.length
            ? `Add ${ingredientMixRows.length} Ingredient Entries`
            : 'Add Entry';

  return (
    <div className="panel add-entry-panel">
      <div className="panel-title-row">
        <h2>Quick Logger</h2>
      </div>

      <div className="mode-switch mode-switch-pill">
        <button
          type="button"
          className={mode === 'instant' ? 'active' : ''}
          onClick={() => setMode('instant')}
        >
          Instant Foods
        </button>
        <button
          type="button"
          className={mode === 'dish' ? 'active' : ''}
          onClick={() => setMode('dish')}
        >
          Dishes
        </button>
        <button
          type="button"
          className={mode === 'ingredient' ? 'active' : ''}
          onClick={() => setMode('ingredient')}
        >
          Ingredients
        </button>
      </div>

      <form onSubmit={handleCreateEntry} className="entry-form">
        {mode === 'instant' ? (
          <>
            <label>
              Search
              <div className="search-field quick-search-field">
                <div className="quick-search-input-shell">
                  <input
                    type="text"
                    value={instantQuery}
                    placeholder="Search"
                    onChange={(event) => {
                      setInstantQuery(event.target.value);
                      setSelectedInstant(null);
                    }}
                  />
                  <span className={`quick-search-status ${instantLoading ? 'is-loading' : ''}`}>
                    {instantSearchStatus}
                  </span>
                </div>
                <div className="smart-search-controls">
                  <div className="smart-search-scope">
                    {INSTANT_SCOPE_OPTIONS.map((option) => (
                      <button
                        key={`scope-${option.id}`}
                        type="button"
                        className={instantSearchScope === option.id ? 'is-active' : ''}
                        onClick={() => setInstantSearchScope(option.id)}
                      >
                        {option.label}
                      </button>
                    ))}
                  </div>
                  <label className="smart-search-sort">
                    Sort
                    <select value={instantSmartSort} onChange={(event) => setInstantSmartSort(event.target.value)}>
                      {INSTANT_SORT_OPTIONS.map((option) => (
                        <option key={`sort-${option.id}`} value={option.id}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>
                {instantSuggestionsVisible && (
                  <div className="search-suggestions search-suggestions-layered">
                    {(instantSearchScope === 'all' || instantSearchScope === 'instant') && (
                      <section className="suggestion-group">
                        <h4>Instant Foods</h4>
                        {visibleInstantPresetSuggestions.map((preset) => (
                          <button
                            type="button"
                            key={preset.id}
                            className="suggestion-item instant-suggestion-item"
                            onMouseDown={(event) => {
                              event.preventDefault();
                              selectInstantPreset(preset);
                            }}
                          >
                            <div className="suggestion-main-row">
                              <FoodThumb
                                item={{ name: preset.label, imageUrl: preset?.fallback?.imageUrl }}
                                label={preset.label}
                                bucket="instant"
                              />
                              <div>
                                <div className="suggestion-top-row">
                                  <span>{preset.label}</span>
                                  <small>{preset.hint}</small>
                                </div>
                                <div className="suggestion-meta-row">
                                  <span className="suggestion-kind-pill instant">Instant</span>
                                  <small>
                                    {Math.round(parseNumber(preset.fallback.caloriesPer100g))} cal/100g · P {formatAmount(preset.fallback.proteinPer100g)}g · C{' '}
                                    {formatAmount(preset.fallback.carbsPer100g)}g
                                  </small>
                                </div>
                                <small>
                                  {preset.custom ? 'Custom preset' : 'Built-in'} · {preset.fallback.cuisine}
                                </small>
                              </div>
                            </div>
                          </button>
                        ))}
                        {!visibleInstantPresetSuggestions.length && <div className="suggestion-empty suggestion-empty-compact">No matches.</div>}
                      </section>
                    )}

                    {(instantSearchScope === 'all' || instantSearchScope === 'dish') && (
                      <section className="suggestion-group">
                        <h4>Dishes</h4>
                        {visibleInstantDishSuggestions.map((item) => (
                          <div key={`instant-dish-${item.id}`} className="dish-suggestion-card dish-suggestion-card-compact">
                            <div className="dish-suggestion-main">
                              <FoodThumb item={item} bucket="dish" className="food-thumb food-thumb-lg" />
                              <div>
                                <div className="suggestion-top-row">
                                  <span>{item.name}</span>
                                  <small>{item.cuisine}</small>
                                </div>
                                <div className="suggestion-meta-row">
                                  <span className="suggestion-kind-pill dish">Dish</span>
                                  <small>
                                    {formatAmount(item.caloriesPerServing)} cal · P {formatAmount(item.proteinPerServing)}g · C {formatAmount(item.carbsPerServing)}g · F{' '}
                                    {formatAmount(item.fatsPerServing)}g
                                  </small>
                                </div>
                              </div>
                            </div>
                            <div className="dish-suggestion-actions">
                              <button
                                type="button"
                                onMouseDown={(event) => {
                                  event.preventDefault();
                                  jumpToDishModeFromQuickSearch(item);
                                }}
                              >
                                Open Dish
                              </button>
                            </div>
                          </div>
                        ))}
                        {!visibleInstantDishSuggestions.length && !instantLoading && (
                          <div className="suggestion-empty suggestion-empty-compact">No matches.</div>
                        )}
                      </section>
                    )}

                    {(instantSearchScope === 'all' || instantSearchScope === 'ingredient') && (
                      <section className="suggestion-group">
                        <h4>Ingredients</h4>
                        {visibleInstantIngredientSuggestions.map((item) => (
                          <button
                            type="button"
                            key={`instant-ingredient-${item.id}`}
                            className="suggestion-item"
                            onMouseDown={(event) => {
                              event.preventDefault();
                              selectInstantIngredient(item);
                            }}
                          >
                            <div className="suggestion-main-row">
                              <FoodThumb item={item} bucket="ingredient" />
                              <div>
                                <div className="suggestion-top-row">
                                  <span>{item.name}</span>
                                  <small>{Math.round(parseNumber(item.caloriesPer100g))} cal/100g</small>
                                </div>
                                <div className="suggestion-meta-row">
                                  <span className="suggestion-kind-pill ingredient">Ingredient</span>
                                  <small>
                                    P {formatAmount(item.proteinPer100g)}g · C {formatAmount(item.carbsPer100g)}g · F {formatAmount(item.fatsPer100g)}g · Fi{' '}
                                    {formatAmount(item.fiberPer100g)}g
                                  </small>
                                </div>
                                <small>
                                  {item.category} · {item.cuisine}
                                </small>
                              </div>
                            </div>
                          </button>
                        ))}
                        {!visibleInstantIngredientSuggestions.length && !instantLoading && (
                          <div className="suggestion-empty suggestion-empty-compact">No matches.</div>
                        )}
                      </section>
                    )}
                  </div>
                )}
              </div>
            </label>

            <div className="quantity-cluster">
              <span className="input-caption">Weight / volume</span>
              <div className="quantity-row">
                <button type="button" className="qty-step-btn" onClick={() => shiftInstantQuantity(-1)}>
                  -10g
                </button>
                <input
                  type="number"
                  min="0"
                  step={instantUnit === 'kg' || instantUnit === 'l' ? '0.01' : '1'}
                  value={instantQuantity}
                  onChange={(event) => setInstantQuantity(event.target.value)}
                />
                <select value={instantUnit} onChange={(event) => setInstantUnit(event.target.value)}>
                  {UNIT_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
                <button type="button" className="qty-step-btn" onClick={() => shiftInstantQuantity(1)}>
                  +10g
                </button>
              </div>
              <div className="preset-row">
                {WEIGHT_PRESETS.map((value) => (
                  <button key={`instant-${value}`} type="button" onClick={() => setInstantQuantity(value)}>
                    {value}
                  </button>
                ))}
              </div>
            </div>

            {selectedInstant && (
              <div className="selected-line with-thumb">
                <FoodThumb
                  item={selectedInstant.ingredient || { name: 'Food' }}
                  label={selectedInstant.ingredient?.name || 'Food'}
                  bucket={selectedInstant.kind === 'preset' ? 'instant' : 'ingredient'}
                  className="food-thumb food-thumb-inline"
                />
                <span>
                  Selected: <strong>{selectedInstant.ingredient?.name}</strong> · {selectedInstant.kind === 'preset' ? 'Instant preset' : 'Ingredient'} ·{' '}
                  {selectedInstant.ingredient?.category || 'Global'}
                </span>
              </div>
            )}

            <div className="instant-preset-builder">
              <div className="instant-builder-head">
                <div>
                  <h3>Custom Preset</h3>
                </div>
                <button
                  type="button"
                  className="secondary-btn"
                  onClick={() => {
                    setShowPresetBuilder((previous) => !previous);
                    setPresetStatus('');
                  }}
                >
                  {showPresetBuilder ? 'Close Creator' : 'Create Preset'}
                </button>
              </div>

              {showPresetBuilder && (
                <div className="instant-builder-body">
                  <div className="instant-builder-grid">
                    <label>
                      Preset name
                      <input
                        type="text"
                        value={presetDraft.name}
                        placeholder="Example: Coconut water"
                        onChange={(event) => onPresetDraftChange('name', event.target.value)}
                      />
                    </label>
                    <label>
                      Aliases (comma separated)
                      <input
                        type="text"
                        value={presetDraft.aliases}
                        placeholder="Example: tender coconut, nariyal pani"
                        onChange={(event) => onPresetDraftChange('aliases', event.target.value)}
                      />
                    </label>
                    <label>
                      Serving quantity
                      <input
                        type="number"
                        min="1"
                        step="1"
                        value={presetDraft.quantity}
                        onChange={(event) => onPresetDraftChange('quantity', event.target.value)}
                      />
                    </label>
                    <label>
                      Unit
                      <select value={presetDraft.unit} onChange={(event) => onPresetDraftChange('unit', event.target.value)}>
                        {UNIT_OPTIONS.map((option) => (
                          <option key={`preset-unit-${option}`} value={option}>
                            {option}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Category
                      <select value={presetDraft.category} onChange={(event) => onPresetDraftChange('category', event.target.value)}>
                        {INSTANT_CATEGORY_OPTIONS.map((option) => (
                          <option key={`preset-category-${option}`} value={option}>
                            {option}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Cuisine
                      <input
                        type="text"
                        value={presetDraft.cuisine}
                        placeholder="Global"
                        onChange={(event) => onPresetDraftChange('cuisine', event.target.value)}
                      />
                    </label>
                    <label>
                      Calories /100g
                      <input
                        type="number"
                        min="1"
                        step="0.1"
                        value={presetDraft.caloriesPer100g}
                        onChange={(event) => onPresetDraftChange('caloriesPer100g', event.target.value)}
                      />
                    </label>
                    <label>
                      Protein /100g
                      <input
                        type="number"
                        min="0"
                        step="0.1"
                        value={presetDraft.proteinPer100g}
                        onChange={(event) => onPresetDraftChange('proteinPer100g', event.target.value)}
                      />
                    </label>
                    <label>
                      Carbs /100g
                      <input
                        type="number"
                        min="0"
                        step="0.1"
                        value={presetDraft.carbsPer100g}
                        onChange={(event) => onPresetDraftChange('carbsPer100g', event.target.value)}
                      />
                    </label>
                    <label>
                      Fats /100g
                      <input
                        type="number"
                        min="0"
                        step="0.1"
                        value={presetDraft.fatsPer100g}
                        onChange={(event) => onPresetDraftChange('fatsPer100g', event.target.value)}
                      />
                    </label>
                    <label>
                      Fibre /100g
                      <input
                        type="number"
                        min="0"
                        step="0.1"
                        value={presetDraft.fiberPer100g}
                        onChange={(event) => onPresetDraftChange('fiberPer100g', event.target.value)}
                      />
                    </label>
                    <label>
                      Base Price (USD per kg/l)
                      <input
                        type="number"
                        min="0.01"
                        step="0.01"
                        value={presetDraft.averagePriceUsd}
                        onChange={(event) => onPresetDraftChange('averagePriceUsd', event.target.value)}
                      />
                    </label>
                  </div>
                  <div className="instant-builder-actions">
                    <button type="button" className="primary-btn" disabled={presetSaving} onClick={createCustomPreset}>
                      {presetSaving ? 'Saving Preset...' : 'Save Preset'}
                    </button>
                  </div>
                </div>
              )}

              {customInstantPresets.length > 0 && (
                <div className="instant-custom-preset-list">
                  {customInstantPresets.slice(0, 8).map((preset) => (
                    <div className="instant-custom-preset-item" key={`custom-preset-${preset.id}`}>
                      <div>
                        <strong>{preset.label}</strong>
                        <small>
                          {formatServingLabel(preset.quantity, preset.unit)} · {preset.fallback.cuisine}
                        </small>
                      </div>
                      <div className="instant-custom-preset-actions">
                        <button type="button" onClick={() => selectInstantPreset(preset)}>
                          Use
                        </button>
                        <button type="button" onClick={() => removeCustomPreset(preset.id)}>
                          Delete
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {presetStatus && <div className="status-line">{presetStatus}</div>}
          </>
        ) : mode === 'ingredient' ? (
          <>
            <label>
              Search ingredient
              <div className="search-field quick-search-field">
                <div className="quick-search-input-shell">
                  <input
                    type="text"
                    value={ingredientQuery}
                    placeholder="Search ingredient"
                    onChange={(event) => {
                      setIngredientQuery(event.target.value);
                      setSelectedIngredient(null);
                    }}
                  />
                  <span className={`quick-search-status ${ingredientLoading ? 'is-loading' : ''}`}>
                    {ingredientSearchStatus}
                  </span>
                </div>
                {ingredientSuggestionsVisible && (
                  <div className="search-suggestions">
                    {ingredientSuggestions.map((item) => (
                      <button
                        type="button"
                        key={item.id}
                        className="suggestion-item"
                        onMouseDown={(event) => {
                          event.preventDefault();
                          selectIngredient(item);
                        }}
                      >
                        <div className="suggestion-main-row">
                          <FoodThumb item={item} bucket="ingredient" />
                          <div>
                            <div className="suggestion-top-row">
                              <span>{item.name}</span>
                              <small>{Math.round(parseNumber(item.caloriesPer100g))} cal/100g</small>
                            </div>
                            <div className="suggestion-meta-row">
                              <span className="suggestion-kind-pill ingredient">Ingredient</span>
                              <small>
                                P {formatAmount(item.proteinPer100g)}g · C {formatAmount(item.carbsPer100g)}g · F {formatAmount(item.fatsPer100g)}g · Fi{' '}
                                {formatAmount(item.fiberPer100g)}g
                              </small>
                            </div>
                            <small>
                              {item.category} · {item.cuisine}
                            </small>
                            {!!(item.aliases || []).length && (
                              <div className="suggestion-aliases">AKA: {(item.aliases || []).slice(0, 3).join(', ')}</div>
                            )}
                          </div>
                        </div>
                      </button>
                    ))}
                    {!ingredientSuggestions.length && !ingredientLoading && (
                      <div className="suggestion-empty">No matches.</div>
                    )}
                    {ingredientLoading && <div className="suggestion-empty">Searching...</div>}
                  </div>
                )}
              </div>
            </label>

            <div className="quantity-cluster">
              <span className="input-caption">Weight / volume</span>
              <div className="quantity-row">
                <button type="button" className="qty-step-btn" onClick={() => shiftMainQuantity(-1)}>
                  -10g
                </button>
                <input
                  type="number"
                  min="0"
                  step={unit === 'kg' || unit === 'l' ? '0.01' : '1'}
                  value={quantity}
                  onChange={(event) => setQuantity(event.target.value)}
                />
                <select value={unit} onChange={(event) => setUnit(event.target.value)}>
                  {UNIT_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
                <button type="button" className="qty-step-btn" onClick={() => shiftMainQuantity(1)}>
                  +10g
                </button>
              </div>
              <div className="preset-row">
                {WEIGHT_PRESETS.map((value) => (
                  <button key={value} type="button" onClick={() => setQuantity(value)}>
                    {value}
                  </button>
                ))}
              </div>
            </div>

            {selectedIngredient && (
              <div className="selected-line with-thumb">
                <FoodThumb item={selectedIngredient} bucket="ingredient" className="food-thumb food-thumb-inline" />
                <span>
                  Selected: <strong>{selectedIngredient.name}</strong> · {selectedIngredient.category} ·{' '}
                  {(selectedIngredient.regionalAvailability || []).slice(0, 2).join(' / ') || 'Global'}
                </span>
              </div>
            )}

            <div className="ingredient-mix-actions">
              <button
                type="button"
                className="secondary-btn"
                disabled={!selectedIngredient || parseNumber(quantity) <= 0}
                onClick={addIngredientToMix}
              >
                Add To Ingredient Mix
              </button>
              {!!ingredientMixRows.length && (
                <button type="button" className="secondary-btn" onClick={() => setIngredientMixRows([])}>
                  Clear Mix
                </button>
              )}
            </div>

            <div className="ingredient-mix-panel">
              <div className="ingredient-mix-head">
                <h4>Ingredient Mix</h4>
                <small>{ingredientMixRows.length ? `${ingredientMixRows.length} items` : 'Add ingredients to compare totals'}</small>
              </div>
              {ingredientMixLineItems.length ? (
                <>
                  {ingredientMixLineItems.map((row, index) => (
                    <div className="ingredient-mix-row" key={row.key}>
                      <div className="ingredient-mix-meta">
                        <strong>{row.ingredient?.name}</strong>
                        <small>
                          {formatAmount(row.nutrition.calories)} cal · P {formatAmount(row.nutrition.protein)}g · C {formatAmount(row.nutrition.carbs)}g · F{' '}
                          {formatAmount(row.nutrition.fats)}g
                        </small>
                      </div>
                      <div className="ingredient-mix-controls">
                        <button type="button" className="qty-step-btn" onClick={() => shiftIngredientMixQuantity(index, -1)}>
                          -10g
                        </button>
                        <input
                          type="number"
                          min="0"
                          step={row.unit === 'kg' || row.unit === 'l' ? '0.01' : '1'}
                          value={row.quantity}
                          onChange={(event) => updateIngredientMixRow(index, { quantity: event.target.value })}
                        />
                        <select value={row.unit} onChange={(event) => updateIngredientMixRow(index, { unit: event.target.value })}>
                          {UNIT_OPTIONS.map((option) => (
                            <option key={`${row.key}-${option}`} value={option}>
                              {option}
                            </option>
                          ))}
                        </select>
                        <button type="button" className="qty-step-btn" onClick={() => shiftIngredientMixQuantity(index, 1)}>
                          +10g
                        </button>
                        <button type="button" className="ingredient-mix-remove" onClick={() => removeIngredientFromMix(index)}>
                          Remove
                        </button>
                      </div>
                    </div>
                  ))}
                  <div className="ingredient-mix-total">
                    Total: <strong>{formatAmount(ingredientMixTotals.calories)} cal</strong> · P {formatAmount(ingredientMixTotals.protein)}g · C{' '}
                    {formatAmount(ingredientMixTotals.carbs)}g · F {formatAmount(ingredientMixTotals.fats)}g · Fi {formatAmount(ingredientMixTotals.fiber)}g
                  </div>
                </>
              ) : (
                <div className="ingredient-mix-empty">Search, choose ingredient, set quantity, then tap "Add To Ingredient Mix".</div>
              )}
            </div>
          </>
        ) : (
          <>
            <label>
              Search dish
              <div className="search-field quick-search-field">
                <div className="quick-search-input-shell">
                  <input
                    type="text"
                    value={dishQuery}
                    placeholder="Search dish"
                    onChange={(event) => {
                      setDishQuery(event.target.value);
                      setSelectedDish(null);
                      setCustomizeDish(false);
                      setCustomRows([]);
                    }}
                  />
                  <span className={`quick-search-status ${dishLoading ? 'is-loading' : ''}`}>{dishSearchStatus}</span>
                </div>
                {dishSuggestionsVisible && (
                  <div className="search-suggestions">
                    {dishSuggestions.map((item) => (
                      <div key={item.id} className="dish-suggestion-card">
                        <div className="dish-suggestion-main">
                          <FoodThumb item={item} bucket="dish" className="food-thumb food-thumb-lg" />
                          <div>
                            <div className="suggestion-top-row">
                              <span>{item.name}</span>
                              <small>{item.cuisine}</small>
                            </div>
                            <div className="suggestion-meta-row">
                              <span className="suggestion-kind-pill dish">Dish</span>
                              <small>
                                P {formatAmount(item.proteinPerServing)}g · C {formatAmount(item.carbsPerServing)}g · F {formatAmount(item.fatsPerServing)}g · Fi{' '}
                                {formatAmount(item.fiberPerServing)}g
                              </small>
                            </div>
                            <small>
                              {item.description || 'Tap to load details'}
                            </small>
                          </div>
                        </div>
                        <div className="dish-suggestion-actions">
                          <button
                            type="button"
                            onMouseDown={(event) => {
                              event.preventDefault();
                              selectDish(item, false);
                            }}
                          >
                            Select
                          </button>
                          <button
                            type="button"
                            onMouseDown={(event) => {
                              event.preventDefault();
                              selectDish(item, true);
                            }}
                          >
                            Select + Customize
                          </button>
                        </div>
                      </div>
                    ))}
                    {!dishSuggestions.length && !dishLoading && (
                      <div className="suggestion-empty">No matches.</div>
                    )}
                    {dishLoading && <div className="suggestion-empty">Searching...</div>}
                  </div>
                )}
              </div>
            </label>

            <div className="quantity-cluster">
              <span className="input-caption">Amount</span>
              <div className="quantity-row">
                <button type="button" className="qty-step-btn" onClick={() => {
                  const step = getUnitStepForTenGrams(dishUnit);
                  setDishAmount(Math.max(0, parseNumber(dishAmount, 0) - step));
                }}>
                  -10g
                </button>
                <input
                  type="number"
                  min="0"
                  step={dishUnit === 'kg' || dishUnit === 'l' ? '0.01' : '1'}
                  value={dishAmount}
                  onChange={(event) => setDishAmount(event.target.value)}
                />
                <select value={dishUnit} onChange={(event) => setDishUnit(event.target.value)}>
                  {UNIT_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
                <button type="button" className="qty-step-btn" onClick={() => {
                  const step = getUnitStepForTenGrams(dishUnit);
                  setDishAmount(Math.max(0, parseNumber(dishAmount, 0) + step));
                }}>
                  +10g
                </button>
              </div>
              <div className="preset-row">
                {WEIGHT_PRESETS.map((value) => (
                  <button key={`dish-${value}`} type="button" onClick={() => setDishAmount(value)}>
                    {value}
                  </button>
                ))}
              </div>
            </div>

            <label className="checkbox-row">
              <input
                type="checkbox"
                checked={customizeDish}
                onChange={(event) => {
                  const checked = event.target.checked;
                  setDishCalculation(null);
                  setCustomizeDish(checked);
                  if (checked && selectedDish) {
                    setCustomRows((selectedDish.components || []).map(toCustomRowFromComponent));
                  }
                  if (!checked) {
                    setCustomRows([]);
                  }
                }}
              />
              Customize ingredients
            </label>

            {selectedDish && (
              <div className="selected-line with-thumb">
                <FoodThumb item={selectedDish} bucket="dish" className="food-thumb food-thumb-inline" />
                <span>
                  Selected: <strong>{selectedDish.name}</strong> · {selectedDish.cuisine}
                </span>
              </div>
            )}

            {customizeDish && (
              <div className="custom-editor">
                <div className="custom-grid-header custom-grid-header-wide">
                  <span>Ingredient</span>
                  <span>Quantity</span>
                  <span>Unit</span>
                  <span>Quick</span>
                  <span>Action</span>
                </div>

                {customRows.map((row, index) => (
                  <div className="custom-grid-row custom-grid-row-wide" key={row.key}>
                    <span className="custom-name">{row.ingredientName}</span>
                    <input
                      type="number"
                      min="0"
                      step={row.unit === 'kg' || row.unit === 'l' ? '0.01' : '1'}
                      value={row.quantity}
                      onChange={(event) => updateCustomRow(index, { quantity: event.target.value })}
                    />
                    <select
                      value={row.unit}
                      onChange={(event) => updateCustomRow(index, { unit: event.target.value })}
                    >
                      {UNIT_OPTIONS.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                    <div className="mini-actions">
                      <button type="button" onClick={() => shiftRowQuantity(index, -1)}>
                        -10g
                      </button>
                      <button type="button" onClick={() => shiftRowQuantity(index, 1)}>
                        +10g
                      </button>
                    </div>
                    <button type="button" onClick={() => removeCustomIngredient(index)}>
                      Remove
                    </button>
                  </div>
                ))}

                <div className="custom-add-wrap">
                  <div className="search-field quick-search-field">
                    <div className="quick-search-input-shell">
                      <input
                        type="text"
                        value={customSearch}
                        placeholder="Search ingredient"
                        onChange={(event) => {
                          setCustomSearch(event.target.value);
                          setCustomSelection(null);
                        }}
                      />
                      <span className={`quick-search-status ${customLoading ? 'is-loading' : ''}`}>{customSearchStatus}</span>
                    </div>
                    {customSuggestionsVisible && (
                      <div className="search-suggestions">
                        {customSuggestions.map((item) => (
                          <button
                            type="button"
                            key={item.id}
                            className="suggestion-item"
                            onMouseDown={(event) => {
                              event.preventDefault();
                              selectCustomIngredient(item);
                            }}
                          >
                            <div className="suggestion-main-row">
                              <FoodThumb item={item} bucket="ingredient" />
                              <div>
                                <div className="suggestion-top-row">
                                  <span>{item.name}</span>
                                  <small>{Math.round(parseNumber(item.caloriesPer100g))} cal/100g</small>
                                </div>
                                <div className="suggestion-meta-row">
                                  <span className="suggestion-kind-pill ingredient">Ingredient</span>
                                  <small>
                                    P {formatAmount(item.proteinPer100g)}g · C {formatAmount(item.carbsPer100g)}g · F {formatAmount(item.fatsPer100g)}g · Fi{' '}
                                    {formatAmount(item.fiberPer100g)}g
                                  </small>
                                </div>
                                <small>
                                  {item.category} · {item.cuisine}
                                </small>
                              </div>
                            </div>
                          </button>
                        ))}
                        {!customSuggestions.length && !customLoading && (
                          <div className="suggestion-empty">No matches.</div>
                        )}
                        {customLoading && <div className="suggestion-empty">Searching...</div>}
                      </div>
                    )}
                  </div>

                  <div className="quantity-row compact-row">
                    <button type="button" className="qty-step-btn" onClick={() => shiftCustomQuantity(-1)}>
                      -10g
                    </button>
                    <input
                      type="number"
                      min="0"
                      step={customUnit === 'kg' || customUnit === 'l' ? '0.01' : '1'}
                      value={customQuantity}
                      onChange={(event) => setCustomQuantity(event.target.value)}
                    />
                    <select value={customUnit} onChange={(event) => setCustomUnit(event.target.value)}>
                      {UNIT_OPTIONS.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                    <button type="button" className="qty-step-btn" onClick={() => shiftCustomQuantity(1)}>
                      +10g
                    </button>
                  </div>

                  <div className="preset-row">
                    {WEIGHT_PRESETS.map((value) => (
                      <button key={`custom-${value}`} type="button" onClick={() => setCustomQuantity(value)}>
                        {value}
                      </button>
                    ))}
                  </div>

                  <button type="button" className="secondary-btn" onClick={addCustomIngredient}>
                    Add Ingredient
                  </button>
                </div>
              </div>
            )}

            <label>
              Note
              <input
                type="text"
                value={note}
                placeholder="Optional"
                onChange={(event) => setNote(event.target.value)}
              />
            </label>
          </>
        )}

        <div className="preview-box preview-box-pop">
          <div className="preview-header-row">
            <span>Estimated calories</span>
            <div className="preview-header-right">
              <strong>{formatAmount(finalPreview.calories)} cal</strong>
              {nutritionTier && (
                <div className={`nutrition-tier-stamp tier-${nutritionTier.tier.toLowerCase()}`}>
                  <span>{nutritionTier.tier}</span>
                  <small>Tier</small>
                </div>
              )}
            </div>
          </div>
          <div className="macro-preview-grid">
            <div>
              <small>Protein</small>
              <strong>{formatAmount(finalPreview.protein)} g</strong>
            </div>
            <div>
              <small>Carbs</small>
              <strong>{formatAmount(finalPreview.carbs)} g</strong>
            </div>
            <div>
              <small>Fats</small>
              <strong>{formatAmount(finalPreview.fats)} g</strong>
            </div>
            <div>
              <small>Fibre</small>
              <strong>{formatAmount(finalPreview.fiber)} g</strong>
            </div>
            <div className="price-highlight">
              <small>Estimated Price</small>
              <strong>{formatInr(finalPreview.priceUsd)}</strong>
            </div>
          </div>
          {nutritionTier && (
            <div className="tier-summary-line">
              Nutrition Tier Score: <strong>{nutritionTier.score}/100</strong> · {nutritionTier.summary}
            </div>
          )}
        </div>

        <button type="submit" disabled={busy} className="primary-btn add-entry-submit">
          {submitLabel}
        </button>
      </form>

      {status && <div className="status-line">{status}</div>}
    </div>
  );
}

export default AddEntry;
