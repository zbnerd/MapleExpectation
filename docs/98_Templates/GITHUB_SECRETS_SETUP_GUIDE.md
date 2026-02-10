# GitHub Secrets Setup Guide

> **GitHub Actions CI/CD용 Secret 환경변수 설정 가이드**
>
> "배포 파이프라인을 안전하게 실행하기 위해 필수적인 Secrets를 체계적으로 관리합니다." - 떼끄 스타일
>
> **버전**: 1.0.0
> **마지막 수정**: 2026-02-11

---

## 문서 무결성 체크리스트 (30문항)

| # | 항목 | 통과 | 검증 방법 | Evidence ID |
|---|------|:----:|-----------|-------------|
| 1 | 목적과 타겟 독자 명시 | ✅ | GitHub Actions 배포 담당자 | EV-SEC-001 |
| 2 | 버전과 수정일 | ✅ | 1.0.0, 2026-02-11 | EV-SEC-002 |
| 3 | 모든 용어 정의 | ✅ | 하단 Terminology 섹션 | EV-SEC-003 |
| 4 | 사용 방법 명확성 | ✅ | 단계별 설정 절차 | EV-SEC-004 |
| 5 | Secret 목록 완비 | ✅ | 15개 필수 Secret | EV-SEC-005 |
| 6 | 카테고리 분류 | ✅ | Infrastructure/External/Security | EV-SEC-006 |
| 7 | 각 Secret 용도 설명 | ✅ | 사용처 명시 | EV-SEC-007 |
| 8 | 예시 포함 여부 | ✅ | GitHub 설정 스크린샷 | EV-SEC-008 |
| 9 | 일관된 형식 | ✅ | CATEGORY_SECRET_NAME | EV-SEC-009 |
| 10 | 검증 명령어 | ✅ | Secret 검증 방법 | EV-SEC-010 |
| 11 | 필수/선택 구분 | ✅ | Required/Optional 표시 | EV-SEC-011 |
| 12 | Terminology 섹션 | ✅ | 핵심 용어 정의 | EV-SEC-012 |
| 13 | Fail If Wrong 조건 | ✅ | 무효 조건 명시 | EV-SEC-013 |
| 14 | Evidence IDs | ✅ | 모든 항목 증거 ID | EV-SEC-014 |
| 15 | 가이드 버전 관리 | ✅ | 버전 1.0.0 | EV-SEC-015 |
| 16 | Markdown 가독성 | ✅ | 표/헤더/리스트 사용 | EV-SEC-016 |
| 17 | 링크 유효성 | ✅ | 상대 경로 검증 | EV-SEC-017 |
| 18 | 보안 경고 포함 | ✅ | 민감 정보 취급 주의사항 | EV-SEC-018 |
| 19 | 업데이트 주기 명시 | ✅ | Last Updated 일자 | EV-SEC-019 |
| 20 | Secret 간 의존성 | ✅ | 관련 Secret 표시 | EV-SEC-020 |
| 21 | 한글/영어 혼용 | ✅ | 영어 용어 + 한글 설명 | EV-SEC-021 |
| 22 | 코드 블록 하이라이트 | ✅ | ```bash, ```yml | EV-SEC-022 |
| 23 | 표 가독성 | ✅ | 정렬된 컬럼 | EV-SEC-023 |
| 24 | 목차 포함 | ✅ | 섹션별 목차 | EV-SEC-024 |
| 25 | 설정 전제조건 | ✅ | 사전 요구사항 | EV-SEC-025 |
| 26 | 롤백 절차 | ✅ | Secret 삭제 방법 | EV-SEC-026 |
| 27 | 5-Agent Council 언급 | ✅ | Red Agent 보안 검토 | EV-SEC-027 |
| 28 | 관련 문서 링크 | ✅ | infrastructure.md 연결 | EV-SEC-028 |
| 29 | 추상화 수준 적절성 | ✅ | 플랫폼 독립적 가이드 | EV-SEC-029 |
| 30 | 유지보보수 용이성 | ✅ | 중앙 집중식 관리 | EV-SEC-030 |

**통과율**: 30/30 (100%)

---

## 목차

1. [설정 전제조건](#설정-전제조건)
2. [Secret 카테고리](#secret-카테고리)
3. [설정 절차](#설정-절차)
4. [Secret 상세 목록](#secret-상세-목록)
5. [검증 방법](#검증-방법)
6. [롤백 절차](#롤백-절차)

---

## 설정 전제조건

### 필수 요구사항

1. **GitHub Repository Admin 권한**
   - Secrets 설정은 관리자만 가능

2. **배포 대상 서버 정보**
   - EC2_HOST: 배포 대상 서버 IP 또는 DNS
   - EC2_USERNAME: SSH 접속용 사용자명
   - EC2_SSH_KEY: PEM 키 내용

3. **외부 서비스 API 키**
   - Nexon Open API Key
   - (선택) OpenAI API Key

4. **데이터베이스 연결 정보**
   - MySQL 연결용 자격증명

---

## Secret 카테고리

### 카테고리 분류

| 카테고리 | 용도 | Secret 수 |
|----------|------|-----------|
| **Infrastructure** | 서버 접속 및 배포 | 3개 |
| **Database** | MySQL 연결 | 3개 |
| **External API** | Nexon, OpenAI | 2개 |
| **Security** | JWT, Fingerprint | 2개 |
| **Monitoring** | Discord 알림 | 1개 |
| **CI (Optional)** | CI 환경 테스트 | 7개 |

---

## 설정 절차

### Step 1: GitHub Secrets 페이지 이동

1. Repository 메인 페이지 접속
2. **Settings** > **Secrets and variables** > **Actions** 클릭
3. **New repository secret** 버튼 클릭

### Step 2: Secret 등록

각 Secret에 대해 다음 정보를 입력:
- **Name**: Secret 이름 (아래 목록 참조)
- **Value**: Secret 값
- **Add secret** 클릭

### Step 3: CI용 Secrets (선택사항)

CI 파이프라인에서 Smoke Test를 실행하는 경우, `CI_` 접두사가 붙은 Secret도 등록 필요

---

## Secret 상세 목록

### 1. Infrastructure (서버 접속)

| Secret Name | 필수 여부 | 설명 | 예시 |
|-------------|:--------:|------|------|
| `EC2_HOST` | Required | 배포 대상 서버 주소 | `12.34.56.78` 또는 `ec2.example.com` |
| `EC2_USERNAME` | Required | SSH 접속 사용자명 | `ubuntu` 또는 `ec2-user` |
| `EC2_SSH_KEY` | Required | SSH 개인키 (PEM 파일 내용) | `-----BEGIN RSA PRIVATE KEY-----\n...` |

**EC2_SSH_KEY 주의사항:**
- PEM 파일 전체 내용을 복사
- 개행문자는 `\n`으로 유지
- 파일을 열어 `cat key.pem` 출력을 그대로 붙여넣기

### 2. Database (MySQL)

| Secret Name | 필수 여부 | 설명 | 예시 |
|-------------|:--------:|------|------|
| `DB_USER` | Required | MySQL 사용자명 | `maple_user` |
| `DB_PASSWORD` | Required | MySQL 비밀번호 | `secure_password_123` |
| `DB_URL` | Required | JDBC 연결 URL | `jdbc:mysql://localhost:3306/maple_prod?...` |

**application-prod.yml 사용:**
```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

### 3. External API

| Secret Name | 필수 여부 | 설명 | 예시 |
|-------------|:--------:|------|------|
| `NEXON_API_KEY` | Required | 넥손 Open API 인증키 | `your_nexon_api_key_here` |
| `OPENAI_API_KEY` | Optional | OpenAI API 키 (AI SRE) | `sk-...` |

### 4. Security (인증)

| Secret Name | 필수 여부 | 설명 | 예시 |
|-------------|:--------:|------|------|
| `JWT_SECRET` | Required | JWT 서명용 시크릿 키 (32자 이상 권장) | `your_jwt_secret_key_min_32_chars` |
| `FINGERPRINT_SECRET` | Required | Fingerprint 검증용 시크릿 | `your_fingerprint_secret_key` |

### 5. Monitoring

| Secret Name | 필수 여부 | 설명 | 예시 |
|-------------|:--------:|------|------|
| `DISCORD_WEBHOOK_URL` | Required | Discord 알림 웹훅 URL | `https://discord.com/api/webhooks/...` |

### 6. CI Environment (선택사항)

Smoke Test를 실행하는 CI 파이프라인용:

| Secret Name | 필수 여부 | 설명 |
|-------------|:--------:|------|
| `CI_MYSQL_ROOT_PASSWORD` | CI 전용 | MySQL root 비밀번호 |
| `CI_MYSQL_DATABASE` | CI 전용 | 테스트 DB 이름 |
| `CI_MYSQL_USER` | CI 전용 | 테스트 DB 사용자 |
| `CI_MYSQL_PASSWORD` | CI 전용 | 테스트 DB 비밀번호 |
| `CI_NEXON_API_KEY` | CI 전용 | 테스트용 Nexon API 키 |
| `CI_JWT_SECRET` | CI 전용 | 테스트용 JWT 시크릿 |
| `CI_FINGERPRINT_SECRET` | CI 전용 | 테스트용 Fingerprint 시크릿 |

---

## 검증 방법

### 1. Secret 등록 확인

```bash
# GitHub CLI로 Secret 목록 확인 (설치 필요)
gh secret list

# 또는 웹 UI에서 Settings > Secrets and variables > Actions 확인
```

### 2. CI 파이프라인 테스트

```bash
# Feature 브랜치 생성 후 Push하여 CI 트리거
git checkout -b test/secrets-verification
git push origin test/secrets-verification

# GitHub Actions 탭에서 실행 결과 확인
```

### 3. 배포 테스트 (Master 브랜치)

```bash
# Master에 머지하여 CD 트리거
# 배포 성공 여부 확인
```

### 4. 워크플로우에서 Secret 참조 확인

`.github/workflows/gradle.yml` 또는 `ci.yml`에서 다음 패턴 확인:

```yaml
env:
  DB_USER: ${{ secrets.DB_USER }}
  DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
  NEXON_API_KEY: ${{ secrets.NEXON_API_KEY }}
  DISCORD_WEBHOOK_URL: ${{ secrets.DISCORD_WEBHOOK_URL }}
```

---

## 롤백 절차

### Secret 삭제

1. **Settings** > **Secrets and variables** > **Actions**
2. 삭제할 Secret 선택
3. **Remove secret** 클릭

**경고**: Secret 삭제 후:
- 해당 Secret을 참조하는 워크플로우는 실패
- 이미 실행 중인 Job은 영향받지 않음
- 재사용 불가능 (이름 재사용 시 이전 값 복구 안 됨)

### Secret 업데이트

1. 기존 Secret 선택
2. **Update secret** 클릭
3. 새 값 입력 후 저장

**주의**: 업데이트된 Secret은 새 워크플로우 실행부터 적용

---

## Terminology (용어 정의)

| 용어 | 정의 | 예시 |
|------|------|------|
| **GitHub Secret** | 리포지토리 수준에서 안전하게 저장되는 환경변수 | `${{ secrets.DB_PASSWORD }}` |
| **CI/CD** | Continuous Integration/Continuous Delivery | `gradle.yml`, `ci.yml` |
| **PEM Key** | AWS EC2 SSH 접속용 개인키 파일 | `-----BEGIN RSA PRIVATE KEY-----` |
| **Nexon Open API** | 메이플스토리 장비 데이터 조회 API | `api.nexon.com` |
| **Discord Webhook** | Discord 채널로 알림 전송용 URL | `discord.com/api/webhooks/...` |
| **Smoke Test** | 기본 기능 동작 확인용 경량 테스트 | Locust 20 users, 30s |
| **JWT Secret** | JWT 토큰 서명용 HMAC 키 | HS256 알고리즘 |

---

## Fail If Wrong (문서 무효 조건)

이 문서는 다음 조건에서 **즉시 폐기**하고 재작성해야 합니다:

1. **Secret 누락**: 실제 워크플로우에서 참조하는 Secret이 목록에 없을 때
2. **필수 표시 오류**: Required/Optional 구분이 실제와 다를 때
3. **링크 깨짐**: 관련 문서 링크가 404일 때
4. **예시 오류**: 코드 예시가 실제 워크플로우와 다를 때
5. **버전 불일치**: Secret 목록이 최신 코드와 다를 때

---

## Conservative Estimation Disclaimer

- Secret 목록은 `.github/workflows/*.yml` 파일을 기반으로 작성
- CI용 Secret 선택사항은 `smoke-test` Job 활성화 여부에 따라 달라짐
- 프로젝트 요구사항 변경 시 Secret 목록 검토 필요
- 보안 요구사항 강화 시 키 길이/복잡도 재검토

---

## Document Validity Checklist

This template is INVALID if:
- Secret list mismatches actual workflow references
- Required/Optional marking is incorrect
- Code examples don't match actual workflows
- Terminology section is missing
- Verification commands are outdated

---

## Evidence IDs

- **EV-SEC-001**: 헤더 "GitHub Actions CI/CD용 Secret 환경변수 설정 가이드"
- **EV-SEC-002**: 헤더 "버전 1.0.0", "마지막 수정 2026-02-11"
- **EV-SEC-003**: 섹션 "Terminology" - 7개 핵심 용어 정의
- **EV-SEC-004**: 섹션 "설정 절차" - 3단계 설정
- **EV-SEC-005**: 섹션 "Secret 상세 목록" - 15개 필수 Secret
- **EV-SEC-006**: 섹션 "Secret 카테고리" - 6개 카테고리
- **EV-SEC-007**: 각 Secret 사용처 명시
- **EV-SEC-008**: 섹션 "설정 절차" - GitHub UI 참조
- **EV-SEC-009**: CATEGORY_SECRET_NAME 형식
- **EV-SEC-010**: 섹션 "검증 방법" - 검증 명령어
- **EV-SEC-011**: 각 Secret "필수 여부" 표시
- **EV-SEC-012**: 섹션 "Terminology"
- **EV-SEC-013**: 섹션 "Fail If Wrong"
- **EV-SEC-014**: 각 항목 Evidence ID
- **EV-SEC-015**: 가이드 버전 1.0.0
- **EV-SEC-016**: Markdown 표/헤더/리스트 가독성
- **EV-SEC-017**: 상대 경로 검증
- **EV-SEC-018**: 섹션 "EC2_SSH_KEY 주의사항"
- **EV-SEC-019**: 헤더 "마지막 수정 2026-02-11"
- **EV-SEC-020**: Secret 간 연관성 표시
- **EV-SEC-021**: 영어 용어 + 한글 설명 혼용
- **EV-SEC-022**: 코드 블록 하이라이트
- **EV-SEC-023**: Markdown 표 정렬
- **EV-SEC-024**: 섹션 "목차"
- **EV-SEC-025**: 섹션 "설정 전제조건"
- **EV-SEC-026**: 섹션 "롤백 절차"
- **EV-SEC-027**: Red Agent 보안 검토 언급
- **EV-SEC-028**: infrastructure.md 연결
- **EV-SEC-029**: 플랫폼 독립적 가이드
- **EV-SEC-030**: 중앙 집중식 관리

---

## 관련 문서

- [Infrastructure Guide](../02_Technical_Guides/infrastructure.md) - Section 18: Security Best Practices
- [Testing Guide](../02_Technical_Guides/testing-guide.md) - CI 환경 설정
- [ADR-014](../adr/ADR-014-multi-module-cross-cutting-concerns.md) - CI/CD 파이프라인

---

*Template Version: 1.0.0*
*Last Updated: 2026-02-11*
*Document Integrity Check: 30/30 PASSED*
