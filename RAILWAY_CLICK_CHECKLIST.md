# Railway Click-by-Click Checklist (Copy/Paste)

Use this checklist in order. It is written for your repo:
- https://github.com/Guruselva-git007/Calorie-Tracking-App

## 0) Preflight (local)

From terminal:

```bash
cd "/Users/gs/college/Calorie Tracking App"
./run
curl -sS http://127.0.0.1:8080/api/health
```

Expected JSON includes:
- `"status":"UP"`
- `"database":"UP"`
- `"dbMode":"mysql"`

## 1) Push latest code to GitHub

```bash
cd "/Users/gs/college/Calorie Tracking App"
git add .
git commit -m "chore: railway deployment pack and quality improvements"
git push origin main
```

If your branch is not `main`, push your current branch and select that branch in Railway.

## 2) Create Railway project

1. Open Railway dashboard.
2. Click `New Project`.
3. Click `Deploy from GitHub repo`.
4. Choose `Guruselva-git007/Calorie-Tracking-App`.

## 3) Add services (exact names)

Inside the Railway project:

1. Add service: `mysql` (MySQL template).
2. Add service: `backend` (from repo).
3. Add service: `frontend` (from repo).

Set root directory for each repo service:
- `backend` service -> `backend`
- `frontend` service -> `frontend`

## 4) Backend variables (paste into `backend` service)

Railway -> `backend` -> `Variables` -> `Raw Editor` -> paste all:

```env
DB_URL=jdbc:mysql://${{mysql.MYSQLHOST}}:${{mysql.MYSQLPORT}}/${{mysql.MYSQLDATABASE}}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
DB_USER=${{mysql.MYSQLUSER}}
DB_PASSWORD=${{mysql.MYSQLPASSWORD}}
DB_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver
DB_DIALECT=org.hibernate.dialect.MySQLDialect
APP_RUNTIME_DB_MODE=mysql
SERVER_ADDRESS=0.0.0.0
APP_SEED_BACKFILL_ON_STARTUP=false
APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://${{frontend.RAILWAY_PUBLIC_DOMAIN}},https://app.yourdomain.com
APP_AUTOMATION_ENABLED=true
APP_AUTOMATION_POLL_MS=300000
DB_POOL_MAX_SIZE=12
```

## 5) Frontend variables (paste into `frontend` service)

Railway -> `frontend` -> `Variables` -> `Raw Editor` -> paste all:

```env
REACT_APP_API_BASE=
BACKEND_BASE_URL=http://${{backend.RAILWAY_PRIVATE_DOMAIN}}:8080
PORT=80
```

## 6) Generate domains

1. Open `frontend` service -> `Settings` -> `Domains`.
2. Click `Generate Domain`.
3. Copy this URL; this is your public app URL.
4. (Optional) Add custom domain (for scholarship demo use your own domain if available).

Optional backend public domain (only if needed for direct API tests):
1. Open `backend` service -> `Settings` -> `Domains`.
2. Click `Generate Domain`.

## 7) Redeploy

1. In `backend`, click `Deployments` -> `Redeploy` latest.
2. In `frontend`, click `Deployments` -> `Redeploy` latest.

## 8) Smoke test (must pass)

1. Open frontend public domain.
2. Login page should load.
3. Guest login should work.
4. Quick logger search should return results.

If backend has a public domain, test:
- `https://<backend-domain>/api/health`

Expected:
- `status = UP`
- `database = UP`
- `dbMode = mysql`

## 9) If something fails

1. `backend` logs show DB connect error:
- Recheck `DB_URL`, `DB_USER`, `DB_PASSWORD` variable references.
- Confirm service names are exactly `mysql`, `backend`, `frontend` or update `${{serviceName.VAR}}` accordingly.

2. Frontend opens but API fails:
- Verify frontend variable `BACKEND_BASE_URL=http://${{backend.RAILWAY_PRIVATE_DOMAIN}}:8080`
- Redeploy frontend.

3. CORS error in browser console:
- Update backend `APP_CORS_ALLOWED_ORIGIN_PATTERNS` with exact frontend domain.
- Redeploy backend.

## 10) Final investor/demo URL

Use only frontend URL in demos:
- `https://<frontend-public-domain>`

This URL is enough for users and investors to test the app end-to-end.
