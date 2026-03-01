export const UNIT_OPTIONS = ['g', 'kg', 'ml', 'l', 'serving', 'count'];
export const WEIGHT_PRESETS = [50, 100, 250, 500, 750, 1000];
export const USD_TO_INR_RATE = 83;

const formatHostForUrl = (hostname) => {
  if (!hostname) {
    return 'localhost';
  }
  if (hostname.includes(':') && !hostname.startsWith('[')) {
    return `[${hostname}]`;
  }
  return hostname;
};

const resolveBackendOrigin = () => {
  if (typeof window === 'undefined') {
    return 'http://127.0.0.1:8080';
  }

  const protocol = window.location.protocol || 'http:';
  const hostname = window.location.hostname || 'localhost';
  const hostForUrl = formatHostForUrl(hostname);

  if (hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1') {
    return 'http://127.0.0.1:8080';
  }
  return `${protocol}//${hostForUrl}:8080`;
};

const resolveBackendMediaUrl = (path) => {
  const sanitized = String(path || '').trim();
  if (!sanitized) {
    return '';
  }
  const normalizedPath = sanitized.startsWith('/') ? sanitized : `/${sanitized}`;
  return `${resolveBackendOrigin()}${normalizedPath}`;
};

export const normalizeText = (value) => String(value || '').trim().toLowerCase();

export const parseNumber = (value, fallback = 0) => {
  const next = Number(value);
  return Number.isFinite(next) ? next : fallback;
};

const SERVING_GRAMS_FALLBACK = 100;
const SERVING_GRAMS_REGEX = /([0-9]+(?:\.[0-9]+)?)\s*(kg|g|grams?|ml|l|liters?|litres?)\b/i;

const unitToGramsMultiplier = (unit) => {
  const normalized = normalizeText(unit);
  if (normalized === 'kg' || normalized === 'l' || normalized === 'liter' || normalized === 'liters' || normalized === 'litre' || normalized === 'litres') {
    return 1000;
  }
  return 1;
};

export const extractServingSizeInGrams = (ingredient, fallback = SERVING_GRAMS_FALLBACK) => {
  const note = String(ingredient?.servingNote || '').trim();
  if (!note) {
    return fallback;
  }

  const match = note.match(SERVING_GRAMS_REGEX);
  if (!match) {
    return fallback;
  }

  const value = parseNumber(match[1], fallback);
  if (!Number.isFinite(value) || value <= 0) {
    return fallback;
  }

  return value * unitToGramsMultiplier(match[2]);
};

export const toGrams = (quantity, unit, ingredient = null) => {
  const amount = Math.max(0, parseNumber(quantity, 0));
  const normalizedUnit = normalizeText(unit);

  if (normalizedUnit === 'serving' || normalizedUnit === 'servings' || normalizedUnit === 'count' || normalizedUnit === 'counts') {
    const servingSize = extractServingSizeInGrams(ingredient);
    return amount * servingSize;
  }
  if (normalizedUnit === 'kg' || normalizedUnit === 'l') {
    return amount * 1000;
  }
  return amount;
};

export const getUnitStepForTenGrams = (unit) => {
  const normalizedUnit = normalizeText(unit);
  if (normalizedUnit === 'serving' || normalizedUnit === 'servings' || normalizedUnit === 'count' || normalizedUnit === 'counts') {
    return 1;
  }
  if (normalizedUnit === 'kg' || normalizedUnit === 'l') {
    return 0.01;
  }
  return 10;
};

const formatStepValue = (value) => (Math.abs(value - Math.round(value)) < 0.0001 ? `${Math.round(value)}` : value.toFixed(2));

const singularUnitLabel = (unit) => {
  const normalized = normalizeText(unit);
  if (normalized === 'servings') {
    return 'serving';
  }
  if (normalized === 'counts') {
    return 'count';
  }
  return normalized || 'g';
};

export const getQuantityInputStep = (unit) => {
  const normalized = normalizeText(unit);
  if (normalized === 'kg' || normalized === 'l') {
    return '0.01';
  }
  return '1';
};

export const getUnitStepLabel = (unit, direction = 1) => {
  const sign = direction < 0 ? '-' : '+';
  const step = getUnitStepForTenGrams(unit);
  const baseUnit = singularUnitLabel(unit);
  if (baseUnit === 'serving' || baseUnit === 'count') {
    return `${sign}${formatStepValue(step)} ${baseUnit}`;
  }
  return `${sign}${formatStepValue(step)}${baseUnit}`;
};

export const getUnitPresets = (unit) => {
  const baseUnit = singularUnitLabel(unit);
  if (baseUnit === 'serving' || baseUnit === 'count') {
    return [1, 2, 3, 4, 5];
  }
  return WEIGHT_PRESETS;
};

export const formatUnitPresetLabel = (value, unit) => {
  const amount = parseNumber(value, 0);
  const rounded = Math.abs(amount - Math.round(amount)) < 0.0001 ? Math.round(amount) : Number(amount.toFixed(2));
  const baseUnit = singularUnitLabel(unit);

  if (baseUnit === 'serving') {
    return `${rounded} ${rounded === 1 ? 'serving' : 'servings'}`;
  }
  if (baseUnit === 'count') {
    return `${rounded} ${rounded === 1 ? 'count' : 'counts'}`;
  }
  return `${rounded}`;
};

export const lineNutritionFromIngredient = (ingredient, quantity, unit) => {
  if (!ingredient) {
    return {
      grams: 0,
      calories: 0,
      protein: 0,
      carbs: 0,
      fats: 0,
      fiber: 0,
      priceUsd: 0
    };
  }

  const grams = toGrams(quantity, unit, ingredient);
  const ratio = grams / 100;

  const calories = parseNumber(ingredient.caloriesPer100g) * ratio;
  const protein = parseNumber(ingredient.proteinPer100g) * ratio;
  const carbs = parseNumber(ingredient.carbsPer100g) * ratio;
  const fats = parseNumber(ingredient.fatsPer100g) * ratio;
  const fiber = parseNumber(ingredient.fiberPer100g) * ratio;

  const priceUnit = normalizeText(ingredient.averagePriceUnit);
  const priceBase = parseNumber(ingredient.averagePriceUsd);
  const priceRatio = priceUnit === 'kg' || priceUnit === 'l' ? grams / 1000 : ratio;

  return {
    grams,
    calories,
    protein,
    carbs,
    fats,
    fiber,
    priceUsd: priceBase * priceRatio
  };
};

export const formatAmount = (value, digits = 1) => parseNumber(value).toFixed(digits);

const INR_FORMATTER = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 2,
  minimumFractionDigits: 2
});

export const convertUsdToInr = (usdAmount) => parseNumber(usdAmount) * USD_TO_INR_RATE;

export const formatInr = (usdAmount, digits = 2) => {
  const amount = convertUsdToInr(usdAmount);
  if (digits === 2) {
    return INR_FORMATTER.format(amount);
  }

  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: digits,
    minimumFractionDigits: digits
  }).format(amount);
};

const FOOD_PLACEHOLDER_PALETTE = [
  ['#0EA5E9', '#0369A1'],
  ['#10B981', '#047857'],
  ['#F59E0B', '#B45309'],
  ['#EF4444', '#B91C1C'],
  ['#8B5CF6', '#6D28D9'],
  ['#14B8A6', '#0F766E'],
  ['#F97316', '#C2410C'],
  ['#3B82F6', '#1D4ED8']
];

const hashText = (value) => {
  const text = String(value || '');
  let hash = 0;
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 31 + text.charCodeAt(index)) | 0;
  }
  return Math.abs(hash);
};

const toLabel = (value) =>
  String(value || 'Food')
    .replace(/[^a-zA-Z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 32) || 'Food';

const toInitials = (value) => {
  const label = toLabel(value);
  const words = label.split(' ').filter(Boolean);
  if (!words.length) {
    return 'FD';
  }
  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase();
  }
  return `${words[0][0]}${words[1][0]}`.toUpperCase();
};

export const buildFoodPlaceholderDataUrl = (label, bucket = 'food') => {
  const safeLabel = toLabel(label);
  const initials = toInitials(safeLabel);
  const [startColor, endColor] = FOOD_PLACEHOLDER_PALETTE[hashText(`${bucket}-${safeLabel}`) % FOOD_PLACEHOLDER_PALETTE.length];

  const svg = `
<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 120 120'>
  <defs>
    <linearGradient id='g' x1='0%' y1='0%' x2='100%' y2='100%'>
      <stop offset='0%' stop-color='${startColor}'/>
      <stop offset='100%' stop-color='${endColor}'/>
    </linearGradient>
  </defs>
  <rect width='120' height='120' fill='url(#g)' rx='20' ry='20'/>
  <text x='60' y='68' text-anchor='middle' fill='white' font-family='system-ui,Segoe UI,sans-serif' font-size='34' font-weight='700'>
    ${initials}
  </text>
</svg>`.trim();

  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
};

export const getFoodImageSrc = (item, options = {}) => {
  const rawUrl = typeof item === 'string' ? item : item?.imageUrl;
  const trimmedUrl = String(rawUrl || '').trim();

  if (trimmedUrl.startsWith('//')) {
    return `https:${trimmedUrl}`;
  }
  if (/^https?:\/\//i.test(trimmedUrl) || /^data:image\//i.test(trimmedUrl)) {
    return trimmedUrl;
  }
  if (trimmedUrl.startsWith('/')) {
    return resolveBackendMediaUrl(trimmedUrl);
  }
  if (trimmedUrl.toLowerCase().startsWith('api/')) {
    return resolveBackendMediaUrl(`/${trimmedUrl}`);
  }

  const label = options.label || (typeof item === 'object' ? item?.name : '') || 'Food';
  const bucket =
    options.bucket || (typeof item === 'object' ? item?.category || item?.type || item?.cuisine : '') || 'food';
  return buildFoodPlaceholderDataUrl(label, bucket);
};
