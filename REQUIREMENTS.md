# Calorie Tracking App - Implemented Requirements

## Functional Coverage

### User Management
- Default user seeded automatically (`id=1`)
- Update daily calorie goal from dashboard

### Food Management
- Global ingredient database seeded on startup
- Supports categories including:
  - fruits
  - vegetables
  - juices
  - snacks
  - rice
  - meats
  - seafood
  - legumes/dairy/grains/spices/sauces
- Search/filter ingredient library by name/category/cuisine
- Create new ingredients directly from UI and auto-save to MySQL
- Mark custom ingredient facts as confirmed before save

### Dish Management
- Preloaded world cuisine dishes with component ingredients
- Calculate calories for default dish serving
- Customize dish ingredient grams (add/remove/update) before logging
- Create custom dishes with ingredient-level fact confirmation
- Auto-save new dishes and any new custom ingredients to MySQL

### Calorie Entry Management
- Log ingredient entries in grams
- Log dish entries in servings
- Save custom-dish entries with custom ingredient payload
- Fetch entries by today/date/all
- Delete entries

### Calculator
- Separate ingredient calculator endpoint + UI
- Returns total calories and per-ingredient breakdown

### Dashboard
- Daily consumed/goal/remaining stats
- Progress bar
- Date filter
- Entry list with delete action

## Technical Coverage

### Backend
- Spring Boot REST API
- MySQL datasource
- JPA/Hibernate persistence
- Layered structure (entity/repository/service/controller)
- CORS for frontend
- Startup seed service for global dataset
- OpenFoodFacts import endpoint
- TheMealDB + OpenFoodFacts world-cuisine import endpoint
- Async import job system with polling endpoints:
  - `POST /api/import/open-food-facts/async`
  - `POST /api/import/world-cuisines/async`
  - `GET /api/import/jobs/{jobId}`
  - `GET /api/import/jobs`
- Import concurrency guard to prevent overlapping lock-heavy imports

### Frontend
- React SPA
- Multi-column responsive layout
- Ingredient and dish logging flows
- Custom dish ingredient editor
- Ingredient-only calculator panel
- Global food library view

### Deployment/Access
- Backend listens on `0.0.0.0:8080`
- Frontend listens on `0.0.0.0:3000`
- Works on localhost and local network (LAN)
- Scripts provided:
  - `scripts/start-all.sh`
  - `scripts/stop-all.sh`
  - `scripts/status.sh`
