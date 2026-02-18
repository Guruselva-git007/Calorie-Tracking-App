import axios from 'axios';

const AUTH_TOKEN_KEY = 'calorie_auth_token';

const formatHostForUrl = (hostname) => {
  if (!hostname) {
    return 'localhost';
  }
  if (hostname.includes(':') && !hostname.startsWith('[')) {
    return `[${hostname}]`;
  }
  return hostname;
};

const resolveApiBase = () => {
  if (process.env.REACT_APP_API_BASE) {
    return process.env.REACT_APP_API_BASE;
  }

  if (typeof window === 'undefined') {
    return 'http://localhost:8080/api';
  }

  const protocol = window.location.protocol || 'http:';
  const hostname = window.location.hostname || 'localhost';
  if (hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1') {
    return 'http://127.0.0.1:8080/api';
  }
  return `${protocol}//${formatHostForUrl(hostname)}:8080/api`;
};

const API_BASE = resolveApiBase();

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

export const getApiBase = () => API_BASE;

export const describeApiError = (error, fallbackMessage) => {
  const serverMessage = error?.response?.data?.message;
  if (serverMessage) {
    return serverMessage;
  }

  if (error?.code === 'ECONNABORTED') {
    return `Backend timed out at ${API_BASE}. Try again.`;
  }

  if (!error?.response) {
    return `Backend is unreachable at ${API_BASE}. Start backend and MySQL, then retry.`;
  }

  return fallbackMessage || 'Request failed.';
};

const client = axios.create({
  baseURL: API_BASE,
  timeout: 20000
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

export const authAPI = {
  requestCode: (payload) => client.post('/auth/request-code', payload),
  verifyCode: (payload) => client.post('/auth/verify-code', payload),
  guest: (payload = {}) => client.post('/auth/guest', payload),
  ping: () => client.get('/health'),
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
    client.get('/ingredients', { params: { limit: 12, ...params, search } }),
  get: (id) => client.get(`/ingredients/${id}`),
  createCustom: (payload) => client.post('/ingredients/custom', payload)
};

export const dishAPI = {
  list: (params = {}) => client.get('/dishes', { params }),
  search: (search, params = {}) =>
    client.get('/dishes', { params: { limit: 10, ...params, search } }),
  suggest: (search, params = {}) =>
    client.get('/dishes/suggest', { params: { limit: 10, ...params, search } }),
  get: (id) => client.get(`/dishes/${id}`),
  calculate: (id, payload) => client.post(`/dishes/${id}/calculate`, payload),
  createCustom: (payload) => client.post('/dishes/custom', payload)
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
  getImportJob: (jobId) => client.get(`/import/jobs/${jobId}`),
  listImportJobs: (limit = 20) =>
    client.get('/import/jobs', {
      params: { limit }
    })
};

export const toolsAPI = {
  getCurrencies: () => client.get('/tools/currencies'),
  getRecommendations: (payload) => client.post('/tools/recommendations', payload)
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
