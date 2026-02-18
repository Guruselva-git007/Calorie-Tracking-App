# Calorie Tracking App

Production-ready full-stack calorie tracker with:
- Spring Boot + MySQL backend
- React frontend
- global dishes/ingredients + internet imports
- custom dish + ingredient creation with fact confirmation
- ingredient-level calorie calculator
- localhost + LAN access

## Stack
- Backend: Java 17, Spring Boot 3, Spring Data JPA
- Database: MySQL
- Frontend: React 18 + Axios

## Project Paths
- `/Users/gs/college/Calorie Tracking App/backend`
- `/Users/gs/college/Calorie Tracking App/frontend`
- `/Users/gs/college/Calorie Tracking App/scripts`

## Database Config
Default backend connection:
- `DB_URL=jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
- `DB_USER=root`
- `DB_PASSWORD=guruselvaselvam1085sql&&&`

Override anytime with environment variables.

## Run Everything

Start backend + frontend together:

```bash
cd /Users/gs/college/Calorie\ Tracking\ App
./scripts/start-all.sh
```

Check status:

```bash
./scripts/status.sh
```

Stop both:

```bash
./scripts/stop-all.sh
```

## Run Separately

Backend:

```bash
cd /Users/gs/college/Calorie\ Tracking\ App
./scripts/start-backend.sh
```

Frontend:

```bash
cd /Users/gs/college/Calorie\ Tracking\ App
./scripts/start-frontend.sh
```

## URLs
- Frontend local: [http://localhost:3000](http://localhost:3000)
- Backend local: [http://localhost:8080](http://localhost:8080)
- LAN pattern: `http://<your-lan-ip>:3000` and `http://<your-lan-ip>:8080`

## Main App Features
- Home, Tools, and Settings pages with responsive UI
- OTP login (email/phone), editable nickname, persistent session restore on reload
- One-click Guest Mode (no OTP) so new users can try the app instantly
- Round top-right day/night/auto theme control with automatic local-time switching
- Log intake by ingredient quantity (`g`, `kg`, `ml`, `l`) or dish servings
- Quantity presets (`50`, `100`, `250`, `500`, `750`, `1000`) and `+10g/-10g` controls
- Live nutrition + pricing preview (calories, protein, carbs, fats, fibre, estimated price)
- Search suggestions only after typing (backend-driven, no preloaded suggestion list)
- Customize dish ingredients directly from dish selection and before save
- Custom ingredient creation with mandatory fact confirmation
- Custom dish creation with per-line ingredient fact confirmation
- Auto-save new ingredients/dishes to MySQL
- Separate ingredient calorie calculator (`/api/calculator/ingredients`)
- Deficiency tools page for food/supplement recommendations, region availability, and currency pricing
- Tools page now supports separate nutrition-deficiency and medical-condition inputs + BMI helper
- Settings page for profile, dataset import, custom data management, help/support, funding, and credits
- Built-in performance monitor: API timing, repository timing, and summary view in Settings
- Dashboard (daily totals, goal, entries, date filter)
- Searchable food library with macros and pricing

## Dataset Coverage
Seed + imported data includes:
- fruits, vegetables, juices
- snacks, rice, grains, legumes
- oils, sauces, spices
- dairy, meat, seafood
- soups (veg/non-veg), frozen ice creams, cakes
- regional foods like vada pav and mixture
- cuisines: Indian, Chinese, Indo-Chinese, European, Mediterranean, African, Western, Eastern, Northern, Southern, and more from internet sources

## Internet Import (Async, Non-Blocking)
Large imports now run as background jobs to avoid UI/API timeouts.

Start OpenFoodFacts async import:

`POST /api/import/open-food-facts/async?countries=india,japan,brazil&pages=2&pageSize=120`

Start world-cuisine async import:

`POST /api/import/world-cuisines/async?cuisines=indian,chinese,european&maxPerCuisine=40&includeOpenFoodFacts=true`

Check job status:

`GET /api/import/jobs/{jobId}`

List recent jobs:

`GET /api/import/jobs?limit=20`

Synchronous endpoints are still available:
- `POST /api/import/open-food-facts`
- `POST /api/import/world-cuisines`

## API Highlights
- `GET /api/ingredients`
- `POST /api/ingredients/custom`
- `GET /api/dishes`
- `GET /api/dishes/suggest?search=...&limit=10`
- `POST /api/dishes/custom`
- `POST /api/dishes/{id}/calculate`
- `POST /api/calculator/ingredients`
- `POST /api/tools/recommendations`
- `GET /api/tools/currencies`
- `GET /api/stats`
- `GET /api/perf/summary?limit=20`
- `POST /api/auth/request-code`
- `POST /api/auth/verify-code`
- `POST /api/auth/guest`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `PUT /api/users/{id}/profile`
- `POST /api/entries/ingredient`
- `POST /api/entries/dish`
- `GET /api/entries/today`
- `GET /api/entries/summary`
- `PUT /api/users/{id}/goal`

Compatibility aliases:
- `GET /api/foods`
- `GET /api/foods/search?name=...`
- `POST /api/entries` (legacy payload)

## Performance Tuning
- API timing logs are enabled with thresholds:
  - `APP_PERF_API_INFO_MS` (default `120`)
  - `APP_PERF_API_WARN_MS` (default `400`)
- Repository timing logs are enabled with thresholds:
  - `APP_PERF_REPOSITORY_INFO_MS` (default `50`)
  - `APP_PERF_REPOSITORY_WARN_MS` (default `180`)
- Hibernate slow query timing threshold:
  - `APP_PERF_DB_SLOW_MS` (default `120`)
- Server response compression is enabled by default for JSON/text payloads.
