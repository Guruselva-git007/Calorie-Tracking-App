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

## Quality Upgrades (Latest)
- Faster and more stable one-click startup (`./run`) with process locking and stale-process recovery.
- Backend startup optimized by reducing heavy dataset scans during boot.
- Production-friendly frontend API routing: same-domain `/api` is now supported.
- Configurable CORS via `APP_CORS_ALLOWED_ORIGIN_PATTERNS` for safer deployment.
- CI pipeline added for backend + frontend build validation (`.github/workflows/ci.yml`).

## Project Paths
- `/Users/gs/college/Calorie Tracking App/backend`
- `/Users/gs/college/Calorie Tracking App/frontend`
- `/Users/gs/college/Calorie Tracking App/scripts`

## Easy Deployment (Recommended)
This repo is now deploy-ready with Docker.

Files added:
- `/Users/gs/college/Calorie Tracking App/docker-compose.deploy.yml`
- `/Users/gs/college/Calorie Tracking App/.env.deploy.example`
- `/Users/gs/college/Calorie Tracking App/.env.railway-backend`
- `/Users/gs/college/Calorie Tracking App/.env.railway-frontend`
- `/Users/gs/college/Calorie Tracking App/RAILWAY_DEPLOYMENT.md`
- `/Users/gs/college/Calorie Tracking App/scripts/deploy-docker.sh`
- `/Users/gs/college/Calorie Tracking App/scripts/deploy-docker-stop.sh`
- `/Users/gs/college/Calorie Tracking App/backend/Dockerfile`
- `/Users/gs/college/Calorie Tracking App/frontend/Dockerfile`
- `/Users/gs/college/Calorie Tracking App/frontend/nginx.template.conf`
- `/Users/gs/college/Calorie Tracking App/frontend/docker-entrypoint.sh`

Deploy steps:
1. `cd /Users/gs/college/Calorie\ Tracking\ App`
2. `cp .env.deploy.example .env.deploy`
3. Edit `.env.deploy` (set a strong `MYSQL_ROOT_PASSWORD`)
4. `./scripts/deploy-docker.sh`
5. Open:
   - Frontend: `http://localhost:3000`
   - Backend health: `http://localhost:8080/api/health`

Stop deployment:
`./scripts/deploy-docker-stop.sh`

Railway checklist:
- `/Users/gs/college/Calorie Tracking App/RAILWAY_DEPLOYMENT.md`
- `/Users/gs/college/Calorie Tracking App/RAILWAY_CLICK_CHECKLIST.md`

Public internet access:
- Temporary public demo URL (works while laptop is online):
  - `./scripts/start-public-tunnel.sh`
  - `./scripts/stop-public-tunnel.sh`
- Permanent Railway deployment (requires one-time `railway login`):
  - `./scripts/deploy-railway-public.sh`

## Database Config
Default backend connection:
- `DB_URL=jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
- `DB_USER=root`
- `DB_PASSWORD=<your-local-mysql-password>`

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

## Mobile APK (Android)
Build a debug APK (with LAN backend URL embedded automatically):

```bash
cd /Users/gs/college/Calorie\ Tracking\ App
./scripts/build-android-apk.sh
```

APK output:
- `/Users/gs/college/Calorie Tracking App/Calorie-Tracker-debug.apk`
- `/Users/gs/college/Calorie Tracking App/frontend/android/app/build/outputs/apk/debug/app-debug.apk`

## Continuous Auto-Maintenance (New)
The app now supports continuous automation:
- Backend auto-refreshes datasets when internet is reachable.
- Import jobs are scheduled with safe intervals and tracked in MySQL.
- Background `autopilot` watchdog keeps backend/frontend alive.

Default tuned profile (heavy automation):
- correction every `4h`
- Open Food Facts every `6h` (high-volume batch)
- world cuisines every `8h`
- sweets/desserts every `12h`
- image enrichment every `24h`

Manual control:

```bash
./scripts/start-autopilot.sh
./scripts/stop-autopilot.sh
./scripts/status.sh
```

Automation API:
- `GET /api/automation/status`
- `POST /api/automation/trigger?task=manual-next`
- `POST /api/automation/trigger?task=dataset-correction`
- `POST /api/automation/trigger?task=open-food-facts`
- `POST /api/automation/trigger?task=world-cuisines`
- `POST /api/automation/trigger?task=sweets-desserts`
- `POST /api/automation/trigger?task=images`

Optional overrides (example):
```bash
APP_AUTOMATION_TASK_OPEN_FOOD_FACTS_HOURS=12 \
APP_AUTOMATION_OPEN_FOOD_FACTS_PAGE_SIZE=160 \
./scripts/start-all.sh
```

## Chat Assistant
- Floating assistant in bottom-left for:
  - food suggestions
  - quick navigation tasks
  - heavy refresh trigger
  - help/support
  - direct feedback submission
- Feedback is stored in MySQL via support APIs.

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
- `GET /api/support/quick-help`
- `POST /api/support/feedback`
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
