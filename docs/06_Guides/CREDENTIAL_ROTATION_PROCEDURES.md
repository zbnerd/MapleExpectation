# Credential Rotation Procedures

> **상위 문서:** [Security Hardening](../02_Technical_Guides/security-hardening.md) | [On-call Checklist](ON_CALL_CHECKLIST.md) | [Security Checklist](../02_Technical_Guides/security-checklist.md)
>
> **Last Updated:** 2026-02-11
> **Applicable Versions:** Java 21, Spring Boot 3.5.4, Docker Compose, GitHub Actions
> **Documentation Version:** 1.0
> **Compliance:** NIST SP 800-53, SOC 2, PCI DSS

이 문서는 MapleExpectation 프로젝트의 자격 증명(Credential) 교체 절차를 정의합니다.

## Documentation Integrity Statement

This guide is based on **security best practices** and production incident history:
- Credential leak incidents: P0 #238 (2025-12) - JWT secret exposure (Evidence: [P0 Report](../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md))
- Security audit findings: 25+ vulnerabilities addressed (Evidence: [Security Audit](../04_Reports/security-audit-report-2026-02-08.md))
- Rotation testing: Validated in staging environment (Evidence: [Security Testing](../02_Technical_Guides/security-testing.md))

## Terminology

| 용어 | 정의 |
|------|------|
| **Credential Rotation** | 기존 자격 증명을 새 값으로 교체하고 이전 값을 무효화하는 절차 |
| **Zero-Downtime Rotation** | 서비스 중단 없이 자격 증명을 교체하는 기법 |
| **Dual-Active Period** |新旧 자격 증명이 모두 유효한 기간 (Grace Period) |
| **Grace Period** | 이전 자격 증명이 여전히 유효한 기간 |
| **Secret** | 암호화 키, 비밀번호, API 키 등 민감한 값 |

---

## Table of Contents

1. [Credential Inventory](#1-credential-inventory)
2. [Rotation Schedule](#2-rotation-schedule)
3. [JWT Secret Rotation](#3-jwt-secret-rotation)
4. [Database Password Rotation](#4-database-password-rotation)
5. [Redis Password Rotation](#5-redis-password-rotation)
6. [Nexon API Key Rotation](#6-nexon-api-key-rotation)
7. [GitHub Secrets Rotation](#7-github-secrets-rotation)
8. [Emergency Rotation](#8-emergency-rotation)
9. [Verification Procedures](#9-verification-procedures)
10. [Audit & Compliance](#10-audit--compliance)

---

## 1. Credential Inventory

### 1.1 Managed Credentials

| 자산 | 환경 변수 | 위치 | 주기 | 중요도 |
|------|-----------|------|------|--------|
| **JWT Secret** | `JWT_SECRET` | `.env`, GitHub Secrets | 90일 | Critical |
| **Database Password** | `DB_PASSWORD` | `.env`, docker-compose.yml | 180일 | Critical |
| **Redis Password** | `REDIS_PASSWORD` | `.env`, docker-compose.yml | 180일 | High |
| **Nexon API Key** | `NEXON_API_KEY` | `.env`, GitHub Secrets | 365일 | High |
| **Fingerprint Secret** | `FINGERPRINT_SECRET` | `.env`, GitHub Secrets | 90일 | High |
| **Jasypt Encryptor Password** | `JASYPT_ENCRYPTOR_PASSWORD` | GitHub Secrets | 180일 | Medium |

### 1.2 Credential Storage Locations

```
Local Development:
  .env                          # Never commit to git
  .env.example                  # Template only

Docker:
  docker-compose.yml            # Local/staging only
  /run/secrets/*                # Production (Docker Secrets)

CI/CD:
  GitHub Repository Secrets     # CI/CD pipelines
  GitHub Environment Secrets    # Environment-specific

Production (Future):
  AWS Secrets Manager           # Planned for Phase 7
  HashiCorp Vault              # Planned for Phase 8
```

---

## 2. Rotation Schedule

### 2.1 Standard Rotation Timeline

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    QUARTERLY CREDENTIAL ROTATION CALENDAR                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Q1 (Jan-Mar)                    Q2 (Apr-Jun)                    Q3 (Jul-Sep)│
│  ┌─────────────────────────────┐ ┌─────────────────────────────┐           │
│  │ Feb: JWT Secret Rotation    │ │ May: JWT Secret Rotation    │           │
│  │ Feb: Fingerprint Rotation   │ │ May: Fingerprint Rotation   │           │
│  └─────────────────────────────┘ └─────────────────────────────┘           │
│                                                                             │
│  Q2 (Apr-Jun)                    Q3 (Jul-Sep)                    Q4 (Oct-Dec)│
│  ┌─────────────────────────────┐ ┌─────────────────────────────┐           │
│  │ Jun: DB/Redis Password      │ │ Sep: DB/Redis Password      │           │
│  │ Jun: Jasypt Password        │ │ Sep: Jasypt Password        │           │
│  └─────────────────────────────┘ └─────────────────────────────┘           │
│                                                                             │
│  Annual (Every January):                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ Nexon API Key Rotation                                              │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Rotation Planning Checklist

- [ ] **2 weeks prior**: Schedule maintenance window (if downtime required)
- [ ] **1 week prior**: Generate new credential values
- [ ] **3 days prior**: Create rollback plan
- [ ] **1 day prior**: Notify stakeholders (Slack #announcements)
- [ ] **Day of rotation**: Execute procedure during low-traffic hours
- [ ] **Post rotation**: Verify and monitor

---

## 3. JWT Secret Rotation

> **Criticality:** P0 - JWT secret compromise allows authentication bypass
> **Downtime:** Zero-downtime with dual-active period
> **Rollback:** 5 minutes

### 3.1 Overview

JWT Secret rotation uses **dual-validation strategy** during grace period:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         JWT SECRET ROTATION TIMELINE                         │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  T-7 days                    T=0 (Deploy)                T+7 days             │
│  ┌───────────────────────────┐  ┌─────────────────────────┐  ┌─────────────┐│
│  │ OLD SECRET ONLY           │  │ OLD + NEW SECRET        │  │ NEW ONLY    ││
│  │ (Current State)           │  │ (Dual-Active Period)    │  │ (Final)     ││
│  └───────────────────────────┘  └─────────────────────────┘  └─────────────┘│
│                                                                              │
│  Generate:    T-7 days           Deploy: T=0              Remove: T+7 days  │
│  - New secret                    - Add to Secrets         - Remove old     │
│  - Document                      - Restart services       - Verify         │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Pre-Rotation Checklist

```bash
# 1. Check current JWT secret length
echo $JWT_SECRET | wc -c
# Expected: >= 32 characters

# 2. Verify JWT secret is not default
if echo "$JWT_SECRET" | grep -q "dev-secret"; then
    echo "[ERROR] Default dev-secret detected!"
    exit 1
fi

# 3. Backup current secret (encrypted)
echo "$JWT_SECRET" | openssl enc -aes-256-cbc -salt -out jwt_secret_backup_$(date +%Y%m%d).enc
```

### 3.3 Generate New JWT Secret

```bash
# Generate 64-character cryptographically secure secret
NEW_JWT_SECRET=$(openssl rand -base64 48 | tr -d '/+=' | head -c 64)

# Verify length
if [ ${#NEW_JWT_SECRET} -lt 32 ]; then
    echo "[ERROR] Generated secret too short!"
    exit 1
fi

# Document (do NOT commit to git)
echo "NEW_JWT_SECRET=$NEW_JWT_SECRET" >> /tmp/rotation_$(date +%Y%m%d).log
```

### 3.4 Dual-Active Deployment (Zero-Downtime)

**Phase 1: Deploy with Dual Secret Validation (T=0)**

```java
// Step 1: Update JwtTokenProvider to support dual secrets
@Value("${auth.jwt.secret}")
private String primarySecret;

@Value("${auth.jwt.secret.old:#{null}}")
private String secondarySecret;  // Null after grace period

public Jws<Claims> parseToken(String token) {
    try {
        // Try primary secret first
        return Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(primarySecret.getBytes()))
            .build()
            .parseSignedClaims(token);
    } catch (JwtException e) {
        // Fallback to secondary secret during grace period
        if (secondarySecret != null) {
            return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secondarySecret.getBytes()))
                .build()
                .parseSignedClaims(token);
        }
        throw e;
    }
}
```

```bash
# Step 2: Update environment variables
# For Local/Development
echo "JWT_SECRET=$NEW_JWT_SECRET" >> .env
echo "JWT_SECRET_OLD=$OLD_JWT_SECRET" >> .env

# For GitHub Secrets
gh secret set JWT_SECRET -b"$NEW_JWT_SECRET"
gh secret set JWT_SECRET_OLD -b"$OLD_JWT_SECRET"

# For Docker (update docker-compose.yml)
# JWT_SECRET: ${NEW_JWT_SECRET}
# JWT_SECRET_OLD: ${OLD_JWT_SECRET}

# Step 3: Restart services
docker-compose down && docker-compose up -d

# Step 4: Verify dual validation is working
# Both old and new tokens should be accepted
```

**Phase 2: Monitor Grace Period (T+0 to T+7)**

```bash
# Daily verification
for day in {1..7}; do
    echo "Day $day: Verifying dual-secret validation"
    # Test with old token
    curl -H "Authorization: Bearer $OLD_TOKEN" http://localhost:8080/api/v2/characters/test
    # Test with new token
    curl -H "Authorization: Bearer $NEW_TOKEN" http://localhost:8080/api/v2/characters/test
    sleep 86400  # Wait 24 hours
done
```

**Phase 3: Remove Old Secret (T+7 days)**

```bash
# 1. Remove secondary secret configuration
sed -i '/JWT_SECRET_OLD/d' .env
gh secret remove JWT_SECRET_OLD

# 2. Update code to remove dual validation (revert to single secret)

# 3. Deploy and restart
docker-compose down && docker-compose up -d

# 4. Verify only new tokens work
curl -H "Authorization: Bearer $OLD_TOKEN" http://localhost:8080/api/v2/characters/test
# Expected: 401 Unauthorized
curl -H "Authorization: Bearer $NEW_TOKEN" http://localhost:8080/api/v2/characters/test
# Expected: 200 OK
```

### 3.5 Rollback Procedure

If issues occur during rotation:

```bash
# 1. Immediately revert to old secret
echo "JWT_SECRET=$OLD_JWT_SECRET" > .env
docker-compose restart app

# 2. Verify service recovery
curl -f http://localhost:8080/actuator/health

# 3. Investigate logs
docker-compose logs app | tail -100

# 4. Schedule retry with engineering manager approval
```

---

## 4. Database Password Rotation

> **Criticality:** P0 - Database access required for all operations
> **Downtime:** ~30 seconds (connection pool restart)
> **Rollback:** 2 minutes

### 4.1 Pre-Rotation Checklist

```bash
# 1. Verify current database connectivity
mysql -h localhost -u maple_user -p"$DB_PASSWORD" -e "SELECT 1"

# 2. Check active connections
mysql -h localhost -u maple_user -p"$DB_PASSWORD" -e "SHOW PROCESSLIST"

# 3. Note the current password hash
mysql -h localhost -u root -e "SELECT user, host FROM mysql.user WHERE user='maple_user'"
```

### 4.2 Rotation Procedure

**Step 1: Generate New Password**

```bash
# Generate 32-character secure password
NEW_DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)

# Document securely
echo "NEW_DB_PASSWORD=$NEW_DB_PASSWORD" >> /tmp/rotation_$(date +%Y%m%d).log
```

**Step 2: Update Database Password**

```sql
-- Connect to MySQL as root
mysql -u root -p

-- Change password for application user
ALTER USER 'maple_user'@'%' IDENTIFIED BY 'new_secure_password_here';
FLUSH PRIVILEGES;

-- Verify new password works
SELECT 1;
exit
```

**Step 3: Update Application Configuration**

```bash
# For Local Development
sed -i "s/DB_PASSWORD=.*/DB_PASSWORD=$NEW_DB_PASSWORD/" .env

# For GitHub Secrets
gh secret set DB_PASSWORD -b"$NEW_DB_PASSWORD"

# For Docker Compose
# Update docker-compose.yml:
# environment:
#   - DB_PASSWORD=${NEW_DB_PASSWORD}

# For Docker Secrets (Production)
echo "$NEW_DB_PASSWORD" | docker secret create db_password_new -
docker service update maple-expectation --secret-add source=db_password_new,target=db_password
```

**Step 4: Restart Services**

```bash
# Graceful restart (waits for active connections to drain)
docker-compose up -d --force-recreate

# Verify health
curl -f http://localhost:8080/actuator/health

# Check database connectivity
curl -f http://localhost:8080/actuator/db/health
```

### 4.3 Rollback Procedure

```sql
-- If issues occur, revert immediately
mysql -u root -p

ALTER USER 'maple_user'@'%' IDENTIFIED BY 'old_password_here';
FLUSH PRIVILEGES;
```

```bash
# Revert application configuration
sed -i "s/DB_PASSWORD=.*/DB_PASSWORD=$OLD_DB_PASSWORD/" .env
docker-compose restart app
```

---

## 5. Redis Password Rotation

> **Criticality:** P1 - Redis used for caching and distributed locks
> **Downtime:** ~10 seconds
> **Rollback:** 1 minute

### 5.1 Pre-Rotation Checklist

```bash
# 1. Verify current Redis connectivity
redis-cli -a "$REDIS_PASSWORD" ping

# 2. Check active connections
redis-cli -a "$REDIS_PASSWORD" client list

# 3. Verify data persistence
redis-cli -a "$REDIS_PASSWORD" info persistence
```

### 5.2 Rotation Procedure

**Step 1: Generate New Password**

```bash
# Generate 32-character secure password
NEW_REDIS_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
```

**Step 2: Update Redis Configuration**

```bash
# For Local Docker Compose
# Edit docker-compose.yml
redis:
  command: redis-server --requirepass $NEW_REDIS_PASSWORD

# For Standalone Redis
redis-cli -a "$REDIS_PASSWORD" config set requirepass "$NEW_REDIS_PASSWORD"

# For Production (Redis config file)
# Edit redis.conf: requirepass new_password_here
```

**Step 3: Update Application Configuration**

```bash
# .env file
sed -i "s/REDIS_PASSWORD=.*/REDIS_PASSWORD=$NEW_REDIS_PASSWORD/" .env

# GitHub Secrets
gh secret set REDIS_PASSWORD -b"$NEW_REDIS_PASSWORD"

# docker-compose.yml
# environment:
#   - REDIS_PASSWORD=${NEW_REDIS_PASSWORD}
```

**Step 4: Restart Services**

```bash
# Restart Redis
docker-compose restart redis

# Wait for Redis to be ready
sleep 5

# Restart application
docker-compose restart app

# Verify connectivity
redis-cli -a "$NEW_REDIS_PASSWORD" ping
```

---

## 6. Nexon API Key Rotation

> **Criticality:** P1 - External API access for game data
> **Downtime:** Zero (can overlap old and new keys)
> **Rollback:** Immediate

### 6.1 Overview

Nexon API Key rotation requires coordination with Nexon developer portal.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         NEXON API KEY ROTATION TIMELINE                      │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Step 1: Request new key from Nexon Developer Portal                         │
│  Step 2: Add new key to environment (dual-key support)                       │
│  Step 3: Verify new key works                                                │
│  Step 4: Deprecate old key at Nexon portal                                   │
│  Step 5: Remove old key from environment                                     │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Rotation Procedure

**Step 1: Request New API Key**

1. Log in to [Nexon Open API Developer Portal](https://openapi.nexon.com)
2. Navigate to API Keys section
3. Request new API key
4. Document the new key securely (never commit to git)

**Step 2: Configure Dual-Key Support**

```java
// Update NexonApiClient configuration to support multiple keys
@Value("${nexon.api.key}")
private String primaryApiKey;

@Value("${nexon.api.key.old:#{null}}")
private String secondaryApiKey;

private String getApiKey() {
    // Try primary key first
    String result = callWithKey(primaryApiKey);
    if (result != null) return result;

    // Fallback to secondary key during rotation
    if (secondaryApiKey != null) {
        return callWithKey(secondaryApiKey);
    }

    throw new ApiKeyException("All API keys failed");
}
```

**Step 3: Deploy New Key**

```bash
# Add new key as primary, old as secondary
echo "NEXON_API_KEY=$NEW_NEXON_API_KEY" >> .env
echo "NEXON_API_KEY_OLD=$OLD_NEXON_API_KEY" >> .env

# GitHub Secrets
gh secret set NEXON_API_KEY -b"$NEW_NEXON_API_KEY"
gh secret set NEXON_API_KEY_OLD -b"$OLD_NEXON_API_KEY"

# Restart application
docker-compose restart app
```

**Step 4: Verify and Deprecate**

```bash
# Test new key
curl -H "x-nxopen-api-key: $NEW_NEXON_API_KEY" \
  https://openapi.nexon.com/maplestory/v1/character/test

# Once verified, deprecate old key at Nexon portal
# This prevents old key from being used for new requests
```

**Step 5: Complete Rotation (After 7 days)**

```bash
# Remove old key
sed -i '/NEXON_API_KEY_OLD/d' .env
gh secret remove NEXON_API_KEY_OLD

# Revert code to single-key configuration
# Deploy and restart
docker-compose restart app
```

---

## 7. GitHub Secrets Rotation

> **Criticality:** P1 - CI/CD pipeline security
> **Downtime:** None (can be done outside pipeline execution)
> **Rollback:** Immediate

### 7.1 Managed GitHub Secrets

| Secret Name | Usage | Rotation Frequency |
|-------------|-------|-------------------|
| `CI_JWT_SECRET` | CI tests JWT | 90 days |
| `CI_NEXON_API_KEY` | CI tests API calls | 365 days |
| `DOCKER_HUB_TOKEN` | Container registry push | 180 days |
| `SLACK_WEBHOOK_URL` | Notifications | 365 days |
| `JASYPT_ENCRYPTOR_PASSWORD` | Config encryption | 180 days |

### 7.2 Rotation Procedure

```bash
# 1. Install GitHub CLI (if not installed)
# https://cli.github.com/

# 2. Authenticate
gh auth login

# 3. List current secrets
gh secret list

# 4. Update individual secret
gh secret set CI_JWT_SECRET -b"new_secret_value_here"

# 5. Verify secret was updated
gh secret list

# 6. Test in CI workflow
gh workflow run test.yml
```

### 7.3 GitHub Environment-Specific Secrets

For secrets specific to environments (production, staging):

```bash
# Set environment-specific secret
gh secret set JWT_SECRET --env production -b"prod_secret_here"
gh secret set JWT_SECRET --env staging -b"staging_secret_here"

# List environment secrets
gh secret list --env production
gh secret list --env staging
```

---

## 8. Emergency Rotation

> **Trigger:** Confirmed credential leak, unauthorized access, or security incident
> **Time to Complete:** 15 minutes
> **Approval:** Engineering Manager or CTO

### 8.1 Emergency Triggers

Rotate credentials immediately if:
- [ ] Credential found in logs
- [ ] Credential found in git history
- [ ] Unauthorized API activity detected
- [ ] Employee with access leaves company
- [ ] Third-party breach notification

### 8.2 Emergency Rotation Procedure

```bash
#!/bin/bash
# emergency_rotation.sh - Execute only on confirmed security incident

# 1. STOP - Verify emergency trigger
read -p "EMERGENCY ROTATION - Reason required: " REASON
if [ -z "$REASON" ]; then
    echo "[ERROR] Reason required for emergency rotation"
    exit 1
fi

# 2. Alert
echo "[EMERGENCY] Credential rotation initiated: $REASON" | \
  slackpost -c "#incidents" -t "SECURITY ALERT"

# 3. Generate new credentials
NEW_JWT_SECRET=$(openssl rand -base64 48 | tr -d '/+=' | head -c 64)
NEW_DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
NEW_REDIS_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)

# 4. Update all locations
echo "JWT_SECRET=$NEW_JWT_SECRET" > .env
echo "DB_PASSWORD=$NEW_DB_PASSWORD" >> .env
echo "REDIS_PASSWORD=$NEW_REDIS_PASSWORD" >> .env

gh secret set JWT_SECRET -b"$NEW_JWT_SECRET"
gh secret set DB_PASSWORD -b"$NEW_DB_PASSWORD"
gh secret set REDIS_PASSWORD -b"$NEW_REDIS_PASSWORD"

# 5. Force restart all services
docker-compose down
docker-compose up -d

# 6. Verify
sleep 10
curl -f http://localhost:8080/actuator/health

# 7. Log incident
echo "$(date): EMERGENCY ROTATION - $REASON" >> /var/log/security-incidents.log
```

### 8.3 Post-Emergency Actions

1. **Root Cause Analysis**: How was credential exposed?
2. **Scan Repositories**: Check git history for exposed secrets
   ```bash
   git log --all --full-history --source -- "**/env*" "**/*.secret"
   ```
3. **Rotate All Credentials**: Not just the exposed one
4. **Security Audit**: Full security review
5. **Incident Report**: Document in `docs/05_Reports/`

---

## 9. Verification Procedures

### 9.1 Pre-Deployment Verification

```bash
#!/bin/bash
# verify_credentials.sh - Verify all credentials before rotation

echo "[1/5] Checking environment variables..."
required_vars=("JWT_SECRET" "DB_PASSWORD" "REDIS_PASSWORD" "NEXON_API_KEY")
for var in "${required_vars[@]}"; do
    if [ -z "${!var}" ]; then
        echo "[FAIL] $var not set"
        exit 1
    fi
    echo "[PASS] $var set"
done

echo "[2/5] Checking JWT secret length..."
if [ ${#JWT_SECRET} -lt 32 ]; then
    echo "[FAIL] JWT_SECRET too short: ${#JWT_SECRET} chars"
    exit 1
fi
echo "[PASS] JWT_SECRET length: ${#JWT_SECRET} chars"

echo "[3/5] Checking for default secrets..."
if echo "$JWT_SECRET" | grep -qi "dev-secret\|test-secret\|example"; then
    echo "[FAIL] Default secret detected!"
    exit 1
fi
echo "[PASS] No default secrets"

echo "[4/5] Checking database connectivity..."
if ! mysql -h localhost -u maple_user -p"$DB_PASSWORD" -e "SELECT 1" 2>/dev/null; then
    echo "[FAIL] Database connection failed"
    exit 1
fi
echo "[PASS] Database connectivity OK"

echo "[5/5] Checking Redis connectivity..."
if ! redis-cli -a "$REDIS_PASSWORD" ping 2>/dev/null | grep -q "PONG"; then
    echo "[FAIL] Redis connection failed"
    exit 1
fi
echo "[PASS] Redis connectivity OK"

echo ""
echo "All verification checks passed!"
```

### 9.2 Post-Rotation Health Check

```bash
#!/bin/bash
# health_check.sh - Verify system health after rotation

echo "Starting post-rotation health check..."

# 1. Application health
echo "[1/4] Checking application health..."
health=$(curl -s http://localhost:8080/actuator/health)
if echo "$health" | grep -q '"status":"UP"'; then
    echo "[PASS] Application is UP"
else
    echo "[FAIL] Application health check failed"
    exit 1
fi

# 2. JWT authentication
echo "[2/4] Testing JWT authentication..."
login_response=$(curl -s -X POST http://localhost:8080/api/v2/auth/login \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"test_api_key"}')
if echo "$login_response" | grep -q "accessToken"; then
    echo "[PASS] JWT authentication working"
else
    echo "[FAIL] JWT authentication failed"
    exit 1
fi

# 3. Database operations
echo "[3/4] Testing database operations..."
db_test=$(curl -s http://localhost:8080/api/v2/characters/test)
if [ $? -eq 0 ]; then
    echo "[PASS] Database operations working"
else
    echo "[FAIL] Database operations failed"
    exit 1
fi

# 4. Cache operations
echo "[4/4] Testing cache operations..."
# Add your cache-specific test here
echo "[PASS] Cache operations working"

echo ""
echo "All health checks passed! Rotation successful."
```

---

## 10. Audit & Compliance

### 10.1 Rotation Audit Log

All rotations must be logged in `docs/05_Reports/credential-rotation-audit.log`:

```
YYYY-MM-DD HH:MM:SS | CREDENTIAL | ACTION | PERFORMED_BY | NOTES
```

Example:
```
2026-02-11 10:30:00 | JWT_SECRET | ROTATE | engineer@company.com | Dual-active period started
2026-02-18 10:30:00 | JWT_SECRET | COMPLETE | engineer@company.com | Old secret removed
2026-06-01 14:00:00 | DB_PASSWORD | ROTATE | engineer@company.com | Emergency rotation
```

### 10.2 Rotation Evidence Checklist

After each rotation, collect evidence:

- [ ] **Pre-rotation screenshot**: Current credential configuration
- [ ] **Generation log**: New credential generation method
- [ ] **Deployment log**: Configuration changes made
- [ ] **Health check output**: Post-rotation verification
- [ ] **Monitoring data**: Grafana dashboard showing no errors
- [ ] **Incident report**: If emergency rotation

### 10.3 Compliance Requirements

| Standard | Requirement | MapleExpectation Implementation |
|----------|-------------|-------------------------------|
| **NIST SP 800-53** | SC-12: Cryptographic key rotation | 90-day JWT secret rotation |
| **NIST SP 800-53** | IA-5: Authenticator rotation | 180-day password rotation |
| **SOC 2** | Change management for credentials | Audit log for all rotations |
| **PCI DSS** | Quarterly key rotation | 90-day credential rotation |
| **GDPR** | Data protection by design | Secrets never in git, encrypted storage |

---

## Evidence Links

- **Security Audit:** `docs/05_Reports/security-audit-report-2026-02-08.md` (Evidence: [SEC-AUDIT-001])
- **Security Testing:** `docs/03_Technical_Guides/security-testing.md` (Evidence: [SEC-TEST-001])
- **On-call Checklist:** `docs/05_Guides/ON_CALL_CHECKLIST.md` (Evidence: [OPS-001])
- **JWT Provider:** `module-app/src/main/java/maple/expectation/global/security/jwt/JwtTokenProvider.java` (Evidence: [CODE-JWT-001])

## Technical Validity Check

This guide would be invalidated if:
- **Credentials in git:** Hardcoded secrets found in repository
- **Default secrets in production:** Dev secrets used in production
- **No rotation history:** Cannot prove credentials were rotated

### Verification Commands

```bash
# Check for hardcoded secrets in git
git log --all --full-history -S "secret" --source

# Check for credential leaks in logs
grep -r "JWT_SECRET\|DB_PASSWORD\|API_KEY" /var/log/maple-expectation/

# Verify current credential age
stat .env  # Check modification time
```

---

*This document follows the security best practices defined in NIST SP 800-53 and OWASP.*
*All credential rotations must be approved by Engineering Manager before execution.*

**Version History:**
- v1.0 (2026-02-11): Initial release
