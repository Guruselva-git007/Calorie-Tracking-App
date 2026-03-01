# Railway Deployment Plan (Exact Map)

This project is ready for a 3-service Railway deployment:
1. `mysql` (Railway MySQL service)
2. `backend` (Spring Boot)
3. `frontend` (React + nginx reverse proxy)

Frontend traffic flow:
- Browser -> `frontend` service (public domain/custom domain)
- `frontend` service proxies `/api/*` -> `backend` service over Railway private network
- `backend` service -> `mysql` service

## 1) Create Services in Railway

In Railway UI:
1. Create a new project from your GitHub repo.
2. Add service: **MySQL** (from Railway template), name it `mysql`.
3. Add service: **backend** from this repo.
4. Add service: **frontend** from this repo.

Recommended service settings:
- `backend` Root Directory: `backend`
- `frontend` Root Directory: `frontend`

Build method:
- Both services use their Dockerfiles (`backend/Dockerfile`, `frontend/Dockerfile`).

## 2) Exact Environment Variables

Use these two files from repo root for copy/paste:
- `.env.railway-backend`
- `.env.railway-frontend`

Important: if your Railway service names differ from `backend`, `frontend`, `mysql`, update `${{serviceName.VAR}}` references accordingly.

### Backend variables (set on `backend` service)

Required:
- `DB_URL=jdbc:mysql://${{mysql.MYSQLHOST}}:${{mysql.MYSQLPORT}}/${{mysql.MYSQLDATABASE}}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
- `DB_USER=${{mysql.MYSQLUSER}}`
- `DB_PASSWORD=${{mysql.MYSQLPASSWORD}}`
- `DB_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver`
- `DB_DIALECT=org.hibernate.dialect.MySQLDialect`
- `APP_RUNTIME_DB_MODE=mysql`
- `SERVER_ADDRESS=0.0.0.0`

Recommended:
- `APP_SEED_BACKFILL_ON_STARTUP=false`
- `APP_AUTOMATION_ENABLED=true`
- `DB_POOL_MAX_SIZE=12`
- `APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://${{frontend.RAILWAY_PUBLIC_DOMAIN}},https://app.yourdomain.com`

### Frontend variables (set on `frontend` service)

Required:
- `REACT_APP_API_BASE=`
- `BACKEND_BASE_URL=http://${{backend.RAILWAY_PRIVATE_DOMAIN}}:8080`

Optional:
- `PORT=80`

## 3) Domains Checklist

1. Generate public domain for `frontend` service.
2. (Optional) Generate public domain for `backend` service (useful for direct API/mobile testing).
3. Add custom domain to `frontend` service (for users).
4. If using direct backend domain from browsers/mobile, include that origin in backend CORS.
5. Update backend variable:
   - `APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://<frontend-public-domain>,https://<custom-domain>`

## 4) Verify Deployment

From your laptop/browser:
1. Open frontend domain and test login/guest flow.
2. Open backend health endpoint:
   - `https://<backend-public-domain>/api/health`
3. In app, test:
   - quick logger search
   - food detail open
   - tool recommendation
   - voice resolve endpoint usage

Expected health JSON fields:
- `status: "UP"`
- `database: "UP"`
- `dbMode: "mysql"`

## 5) Production Notes

- Keep backend private where possible; expose public backend only when required.
- Keep secrets only in Railway variables (not in git).
- CI build checks are in `.github/workflows/ci.yml`.
