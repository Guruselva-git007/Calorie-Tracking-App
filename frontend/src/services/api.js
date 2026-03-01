import axios from 'axios';

const AUTH_TOKEN_KEY = 'calorie_auth_token';
const API_BASE_KEY = 'calorie_api_base';
const SEARCH_CACHE_TTL_MS = 120_000;
const SEARCH_CACHE_MAX = 320;
const SEARCH_REQUEST_TIMEOUT_MS = 7000;
const searchResponseCache = new Map();
const inflightSearchRequests = new Map();

const formatHostForUrl = (hostname) => {
  if (!hostname) {
    return 'localhost';
  }
  if (hostname.includes(':') && !hostname.startsWith('[')) {
    return `[${hostname}]`;
  }
  return hostname;
};

const normalizeApiBase = (value) => {
  const raw = String(value || '').trim();
  if (!raw || !/^https?:\/\//i.test(raw)) {
    return '';
  }

  const trimmed = raw.replace(/\/+$/, '');
  if (/\/api(?:\/.*)?$/i.test(trimmed)) {
    return trimmed;
  }
  return `${trimmed}/api`;
};

const addApiCandidate = (collection, value) => {
  const normalized = normalizeApiBase(value);
  if (!normalized || collection.includes(normalized)) {
    return;
  }
  collection.push(normalized);
};

const resolveApiBaseCandidates = () => {
  const candidates = [];

  addApiCandidate(candidates, process.env.REACT_APP_API_BASE);

  if (typeof window === 'undefined') {
    addApiCandidate(candidates, 'http://localhost:8080/api');
    addApiCandidate(candidates, 'http://127.0.0.1:8080/api');
    return candidates;
  }

  const protocol = window.location.protocol || 'http:';
  const hostname = window.location.hostname || 'localhost';
  const hostForUrl = formatHostForUrl(hostname);

  // Same-origin reverse proxy support (recommended for production deployments).
  addApiCandidate(candidates, `${protocol}//${hostForUrl}/api`);
  addApiCandidate(candidates, `${protocol}//${hostForUrl}:8080/api`);
  addApiCandidate(candidates, `http://${hostForUrl}:8080/api`);
  addApiCandidate(candidates, 'http://localhost:8080/api');
  addApiCandidate(candidates, 'http://127.0.0.1:8080/api');

  return candidates;
};

const readStoredApiBase = () => {
  if (typeof window === 'undefined') {
    return '';
  }
  return normalizeApiBase(window.localStorage.getItem(API_BASE_KEY) || '');
};

const API_BASE_CANDIDATES = resolveApiBaseCandidates();
const storedApiBase = readStoredApiBase();
if (storedApiBase) {
  const existingIndex = API_BASE_CANDIDATES.indexOf(storedApiBase);
  if (existingIndex >= 0) {
    API_BASE_CANDIDATES.splice(existingIndex, 1);
  }
  API_BASE_CANDIDATES.unshift(storedApiBase);
}

let activeApiBase = API_BASE_CANDIDATES[0] || 'http://localhost:8080/api';
let client;

const setActiveApiBase = (value) => {
  const normalized = normalizeApiBase(value);
  if (!normalized || normalized === activeApiBase) {
    return;
  }
  activeApiBase = normalized;
  if (client) {
    client.defaults.baseURL = normalized;
  }
  clearSearchClientCache();
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(API_BASE_KEY, normalized);
  }
};

let authToken = '';
if (typeof window !== 'undefined') {
  authToken = window.localStorage.getItem(AUTH_TOKEN_KEY) || '';
}

export const setAuthToken = (token) => {
  authToken = token ? String(token).trim() : '';
  if (typeof window === 'undefined') {
    return;
  }
  if (authToken) {
    window.localStorage.setItem(AUTH_TOKEN_KEY, authToken);
  } else {
    window.localStorage.removeItem(AUTH_TOKEN_KEY);
  }
};

export const getAuthToken = () => {
  if (authToken) {
    return authToken;
  }
  if (typeof window === 'undefined') {
    return '';
  }
  const stored = window.localStorage.getItem(AUTH_TOKEN_KEY) || '';
  authToken = stored;
  return stored;
};

export const getApiBase = () => activeApiBase;

export const describeApiError = (error, fallbackMessage) => {
  const serverMessage = error?.response?.data?.message;
  if (serverMessage) {
    return serverMessage;
  }

  if (error?.code === 'ECONNABORTED') {
    return `Backend timed out at ${activeApiBase}. Try again.`;
  }

  if (!error?.response) {
    return `Backend is unreachable at ${activeApiBase}. Start backend and retry (MySQL or H2 fallback).`;
  }

  return fallbackMessage || 'Request failed.';
};

client = axios.create({
  baseURL: activeApiBase,
  timeout: 12000
});

const normalizeParamValue = (value) => {
  if (value === null || value === undefined) {
    return '';
  }
  if (Array.isArray(value)) {
    return value.map((item) => normalizeParamValue(item)).join(',');
  }
  return String(value).trim();
};

const buildParamsCacheKey = (path, params = {}) => {
  const stablePairs = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== '')
    .map(([key, value]) => [key, normalizeParamValue(value)])
    .sort((left, right) => left[0].localeCompare(right[0]));

  const query = stablePairs.map(([key, value]) => `${key}=${value}`).join('&');
  return `${path}?${query}`;
};

const readSearchCache = (cacheKey) => {
  const entry = searchResponseCache.get(cacheKey);
  if (!entry) {
    return null;
  }
  if (Date.now() - entry.cachedAt > SEARCH_CACHE_TTL_MS) {
    searchResponseCache.delete(cacheKey);
    return null;
  }
  return entry.data;
};

const writeSearchCache = (cacheKey, data) => {
  searchResponseCache.set(cacheKey, { data, cachedAt: Date.now() });
  if (searchResponseCache.size <= SEARCH_CACHE_MAX) {
    return;
  }

  const oldestKey = searchResponseCache.keys().next().value;
  if (oldestKey) {
    searchResponseCache.delete(oldestKey);
  }
};

function clearSearchClientCache() {
  searchResponseCache.clear();
  inflightSearchRequests.clear();
}

const getCachedSearch = (path, params = {}) => {
  const cacheKey = buildParamsCacheKey(path, params);
  const cached = readSearchCache(cacheKey);
  if (cached) {
    return Promise.resolve({ data: cached, cached: true });
  }

  const inFlight = inflightSearchRequests.get(cacheKey);
  if (inFlight) {
    return inFlight;
  }

  const request = client
    .get(path, { params, timeout: SEARCH_REQUEST_TIMEOUT_MS })
    .then((response) => {
      writeSearchCache(cacheKey, response?.data || []);
      return response;
    })
    .finally(() => {
      inflightSearchRequests.delete(cacheKey);
    });

  inflightSearchRequests.set(cacheKey, request);
  return request;
};

const withSearchCacheInvalidation = (requestPromise) =>
  requestPromise.then((response) => {
    clearSearchClientCache();
    return response;
  });

client.interceptors.request.use((config) => {
  const token = getAuthToken();
  if (token) {
    config.headers = config.headers || {};
    if (!config.headers['X-Auth-Token']) {
      config.headers['X-Auth-Token'] = token;
    }
  }
  return config;
});

client.interceptors.response.use(
  (response) => {
    const responseBase = normalizeApiBase(response?.config?.baseURL || '');
    if (responseBase) {
      setActiveApiBase(responseBase);
    }
    return response;
  },
  async (error) => {
    const config = error?.config;
    if (!config || config.__apiFallbackTried || error?.response) {
      throw error;
    }

    const currentBase = normalizeApiBase(config.baseURL || client.defaults.baseURL || activeApiBase);
    const fallbackBases = API_BASE_CANDIDATES.filter((candidate) => candidate && candidate !== currentBase);

    let lastError = error;
    for (const fallbackBase of fallbackBases) {
      try {
        const retryResponse = await client.request({
          ...config,
          baseURL: fallbackBase,
          __apiFallbackTried: true
        });
        setActiveApiBase(fallbackBase);
        return retryResponse;
      } catch (retryError) {
        lastError = retryError;
      }
    }

    throw lastError;
  }
);

export const authAPI = {
  requestCode: (payload) => client.post('/auth/request-code', payload),
  verifyCode: (payload) => client.post('/auth/verify-code', payload),
  guest: (payload = {}) => client.post('/auth/guest', payload),
  ping: (timeoutMs = 2400) =>
    client.get('/health', {
      timeout: timeoutMs
    }),
  me: () => client.get('/auth/me'),
  logout: () => client.post('/auth/logout')
};

export const userAPI = {
  get: (id) => client.get(`/users/${id}`),
  updateGoal: (id, goal) => client.put(`/users/${id}/goal`, { goal }),
  updateProfile: (id, payload) => client.put(`/users/${id}/profile`, payload)
};

export const ingredientAPI = {
  list: (params = {}) => client.get('/ingredients', { params }),
  search: (search, params = {}) =>
    getCachedSearch('/ingredients', { limit: 10, ...params, search }),
  get: (id) => client.get(`/ingredients/${id}`),
  createCustom: (payload) => withSearchCacheInvalidation(client.post('/ingredients/custom', payload))
};

export const dishAPI = {
  list: (params = {}) => client.get('/dishes', { params }),
  search: (search, params = {}) =>
    getCachedSearch('/dishes', { limit: 8, ...params, search }),
  suggest: (search, params = {}) =>
    getCachedSearch('/dishes/suggest', { limit: 8, ...params, search }),
  get: (id) => client.get(`/dishes/${id}`),
  calculate: (id, payload) => client.post(`/dishes/${id}/calculate`, payload),
  createCustom: (payload) => withSearchCacheInvalidation(client.post('/dishes/custom', payload))
};

export const entryAPI = {
  getAll: (userId) => client.get('/entries', { params: { userId } }),
  getToday: (userId) => client.get('/entries/today', { params: { userId } }),
  getByDate: (date, userId) => client.get(`/entries/date/${date}`, { params: { userId } }),
  getSummary: (userId, date) =>
    client.get('/entries/summary', {
      params: date ? { userId, date } : { userId }
    }),
  createIngredient: (payload) => client.post('/entries/ingredient', payload),
  createDish: (payload) => client.post('/entries/dish', payload),
  delete: (id) => client.delete(`/entries/${id}`),
  create: (legacyPayload) => client.post('/entries', legacyPayload)
};

export const calculatorAPI = {
  calculateIngredients: (items) => client.post('/calculator/ingredients', { items })
};

export const importAPI = {
  importOpenFoodFacts: (countries, pages = 2, pageSize = 120) =>
    client.post('/import/open-food-facts', null, {
      params: {
        countries,
        pages,
        pageSize
      }
    }),
  importOpenFoodFactsAsync: (countries, pages = 2, pageSize = 120) =>
    client.post('/import/open-food-facts/async', null, {
      params: {
        countries,
        pages,
        pageSize
      }
    }),
  importWorldCuisines: ({
    cuisines,
    maxPerCuisine = 40,
    includeOpenFoodFacts = true,
    countries = 'india,china,japan,italy,greece,morocco,united-states',
    pages = 3,
    pageSize = 150
  }) =>
    client.post('/import/world-cuisines', null, {
      params: {
        cuisines,
        maxPerCuisine,
        includeOpenFoodFacts,
        countries,
        pages,
        pageSize
      }
    }),
  importWorldCuisinesAsync: ({
    cuisines,
    maxPerCuisine = 40,
    includeOpenFoodFacts = true,
    countries = 'india,china,japan,italy,greece,morocco,united-states',
    pages = 3,
    pageSize = 150
  }) =>
    client.post('/import/world-cuisines/async', null, {
      params: {
        cuisines,
        maxPerCuisine,
        includeOpenFoodFacts,
        countries,
        pages,
        pageSize
      }
    }),
  importGlobalDatasets: ({
    cuisines,
    maxPerCuisine = 80,
    includeMealDbAreas = true,
    maxPerArea = 18,
    includeOpenFoodFacts = true,
    countries = 'india,china,japan,thailand,vietnam,indonesia,philippines,saudi-arabia,turkey,egypt,morocco,nigeria,south-africa,united-states,canada,mexico,brazil,argentina,chile,peru,italy,spain,france,germany,united-kingdom,portugal,poland,russia,ukraine,greece,australia',
    pages = 3,
    pageSize = 180,
    includeDummyJson = true,
    dummyJsonPageSize = 50,
    dummyJsonMaxRecipes = 300
  }) =>
    client.post('/import/global-datasets', null, {
      params: {
        cuisines,
        maxPerCuisine,
        includeMealDbAreas,
        maxPerArea,
        includeOpenFoodFacts,
        countries,
        pages,
        pageSize,
        includeDummyJson,
        dummyJsonPageSize,
        dummyJsonMaxRecipes
      }
    }),
  importGlobalDatasetsAsync: ({
    cuisines,
    maxPerCuisine = 80,
    includeMealDbAreas = true,
    maxPerArea = 18,
    includeOpenFoodFacts = true,
    countries = 'india,china,japan,thailand,vietnam,indonesia,philippines,saudi-arabia,turkey,egypt,morocco,nigeria,south-africa,united-states,canada,mexico,brazil,argentina,chile,peru,italy,spain,france,germany,united-kingdom,portugal,poland,russia,ukraine,greece,australia',
    pages = 3,
    pageSize = 180,
    includeDummyJson = true,
    dummyJsonPageSize = 50,
    dummyJsonMaxRecipes = 300
  }) =>
    client.post('/import/global-datasets/async', null, {
      params: {
        cuisines,
        maxPerCuisine,
        includeMealDbAreas,
        maxPerArea,
        includeOpenFoodFacts,
        countries,
        pages,
        pageSize,
        includeDummyJson,
        dummyJsonPageSize,
        dummyJsonMaxRecipes
      }
    }),
  importImages: ({
    includeIngredients = true,
    includeDishes = true,
    ingredientLimit = 1200,
    dishLimit = 1200,
    overwriteExisting = true
  } = {}) =>
    client.post('/import/images', null, {
      params: {
        includeIngredients,
        includeDishes,
        ingredientLimit,
        dishLimit,
        overwriteExisting
      }
    }),
  importImagesAsync: ({
    includeIngredients = true,
    includeDishes = true,
    ingredientLimit = 1200,
    dishLimit = 1200,
    overwriteExisting = true
  } = {}) =>
    client.post('/import/images/async', null, {
      params: {
        includeIngredients,
        includeDishes,
        ingredientLimit,
        dishLimit,
        overwriteExisting
      }
    }),
  importSweetsDesserts: ({
    countries = 'india,china,japan,south-korea,thailand,vietnam,indonesia,philippines,italy,france,germany,spain,greece,turkey,united-kingdom,united-states,mexico,brazil,argentina,south-africa,nigeria,australia',
    pages = 1,
    pageSize = 120,
    maxPerQuery = 14,
    maxMealDbDesserts = 140,
    includeCuratedFallback = true
  } = {}) =>
    client.post('/import/sweets-desserts', null, {
      params: {
        countries,
        pages,
        pageSize,
        maxPerQuery,
        maxMealDbDesserts,
        includeCuratedFallback
      }
    }),
  importSweetsDessertsAsync: ({
    countries = 'india,china,japan,south-korea,thailand,vietnam,indonesia,philippines,italy,france,germany,spain,greece,turkey,united-kingdom,united-states,mexico,brazil,argentina,south-africa,nigeria,australia',
    pages = 1,
    pageSize = 120,
    maxPerQuery = 14,
    maxMealDbDesserts = 140,
    includeCuratedFallback = true
  } = {}) =>
    client.post('/import/sweets-desserts/async', null, {
      params: {
        countries,
        pages,
        pageSize,
        maxPerQuery,
        maxMealDbDesserts,
        includeCuratedFallback
      }
    }),
  correctDatasets: ({
    promoteFactChecked = true
  } = {}) =>
    client.post('/import/correct-datasets', null, {
      params: {
        promoteFactChecked
      }
    }),
  correctDatasetsAsync: ({
    promoteFactChecked = true
  } = {}) =>
    client.post('/import/correct-datasets/async', null, {
      params: {
        promoteFactChecked
      }
    }),
  getImportJob: (jobId) => client.get(`/import/jobs/${jobId}`),
  listImportJobs: (limit = 20) =>
    client.get('/import/jobs', {
      params: { limit }
    })
};

export const automationAPI = {
  status: () => client.get('/automation/status'),
  trigger: (task = 'manual-next') =>
    client.post('/automation/trigger', null, {
      params: { task }
    })
};

export const supportAPI = {
  quickHelp: () => client.get('/support/quick-help'),
  submitFeedback: (payload) => client.post('/support/feedback', payload)
};

export const toolsAPI = {
  getCurrencies: () => client.get('/tools/currencies'),
  getDeficiencies: () => client.get('/tools/deficiencies'),
  lookupBarcode: (code) =>
    client.get('/tools/barcode-lookup', {
      params: { code }
    }),
  resolveVoiceFood: (query, limit = 8) =>
    client.get('/tools/voice-resolve', {
      params: { query, limit }
    }),
  recognizeImage: (formData, params = {}) =>
    client.post('/tools/image-recognition', formData, {
      params,
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    }),
  getRecommendations: (payload) => client.post('/tools/recommendations', payload),
  getCheatDays: (userId, limit = 32) =>
    client.get('/tools/cheat-days', {
      params: { userId, limit }
    }),
  saveCheatDay: (payload) => client.post('/tools/cheat-days', payload),
  deleteCheatDay: (userId, date) =>
    client.delete('/tools/cheat-days', {
      params: { userId, date }
    })
};

export const statsAPI = {
  get: () => client.get('/stats')
};

export const perfAPI = {
  getSummary: (limit = 16) => client.get('/perf/summary', { params: { limit } })
};

// Backward compatibility aliases.
export const foodAPI = {
  getAll: () => ingredientAPI.list(),
  search: (name) => ingredientAPI.list({ search: name }),
  create: () => Promise.reject(new Error('Use admin import endpoints for adding large datasets.'))
};

export default client;
