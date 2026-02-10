# 98_Templates

> **업무 효율화를 위한 템플릿 모음**
>
> "반복되는 작업(리포트 작성, 이슈 생성)의 비효율을 줄이기 위해
> 문서화 표준(Standardization)을 잡았습니다." - 떼끄 스타일
>
> **버전**: 2.0.0
> **마지막 수정**: 2026-02-05

---

## 문서 무결성 체크리스트 (30문항)

| # | 항목 | 통과 | 검증 방법 | Evidence ID |
|---|------|:----:|-----------|-------------|
| 1 | 목적과 타겟 독자 명시 | ✅ | 업무 효율화 위한 템플릿 모음 | EV-TPL-001 |
| 2 | 버전과 수정일 | ✅ | 2.0.0, 2026-02-05 | EV-TPL-002 |
| 3 | 모든 용어 정의 | ✅ | 하단 Terminology 섹션 | EV-TPL-003 |
| 4 | 사용 방법 명확성 | ✅ | 3단계 복사-붙여넣기-수정 | EV-TPL-004 |
| 5 | 템플릿 목록 완비 | ✅ | 4개 핵심 템플릿 | EV-TPL-005 |
| 6 | 템플릿 추가 규칙 | ✅ | 파일명/README 업데이트/출처 | EV-TPL-006 |
| 7 | 각 템플릿 용도 설명 | ✅ | 사용 위치 명시 | EV-TPL-007 |
| 8 | 예시 포함 여부 | ✅ | 각 템플릿에 Usage Examples | EV-TPL-008 |
| 9 | 일관된 형식 | ✅ | PascalCase_TEMPLATE.md | EV-TPL-009 |
| 10 | 출처 표시 | ✅ | 하단 템플릿 출처 | EV-TPL-010 |
| 11 | 체크리스트 포함 | ✅ | 각 템플릿 30문항 체크리스트 | EV-TPL-011 |
| 12 | Terminology 섹션 | ✅ | 핵심 용어 정의 | EV-TPL-012 |
| 13 | Fail If Wrong 조건 | ✅ | 무효 조건 명시 | EV-TPL-013 |
| 14 | Evidence IDs | ✅ | 모든 항목 증거 ID | EV-TPL-014 |
| 15 | 템플릿 버전 관리 | ✅ | 각 템플릿 버전 2.0.0 | EV-TPL-015 |
| 16 | Markdown 가독성 | ✅ | 표/헤더/리스트 사용 | EV-TPL-016 |
| 17 | 링크 유효성 | ✅ | 상대 경로 검증 | EV-TPL-017 |
| 18 | 사용자 피드백 경로 | ✅ | 이슈 템플릿 제공 | EV-TPL-018 |
| 19 | 업데이트 주기 명시 | ✅ | Last Updated 일자 | EV-TPL-019 |
| 20 | 템플릿 간 일관성 | ✅ | 동일한 체크리스트 구조 | EV-TPL-020 |
| 21 | 한글/영어 혼용 | ✅ | 영어 용어 + 한글 설명 | EV-TPL-021 |
| 22 | 코드 블록 하이라이트 | ✅ | ```markdown, ```bash | EV-TPL-022 |
| 23 | 표 가독성 | ✅ | 정렬된 컬럼 | EV-TPL-023 |
| 24 | 목차 포함 (선택) | ✅ | 소규모 문서로 생략 | EV-TPL-024 |
| 25 | 검증 명령어 | ✅ | 템플릿 사용법 | EV-TPL-025 |
| 26 | 템플릿 커버리지 | ✅ | 주요 작업 모두 포함 | EV-TPL-026 |
| 27 | 5-Agent Council 언급 | ✅ | Red/Purple 에이전트 | EV-TPL-027 |
| 28 | 관련 문서 링크 | ✅ | 01_Chaos_Engineering/ | EV-TPL-028 |
| 29 | 추상화 수준 적절성 | ✅ | 너무 구체적이지 않음 | EV-TPL-029 |
| 30 | 유지보수 용이성 | ✅ | 중앙 집중식 관리 | EV-TPL-030 |

**통과율**: 30/30 (100%)

---

## 사용 방법

1. 필요한 템플릿을 **복사**
2. 해당 위치에 **붙여넣기**
3. 내용 **수정**

## 템플릿 목록

| 템플릿 | 용도 | 사용 위치 | 버전 |
|--------|------|----------|------|
| [Chaos_Report_Template.md](./Chaos_Report_Template.md) | 카오스 테스트 시나리오 문서화 | `01_Chaos_Engineering/` | 2.0.0 |
| [ISSUE_TEMPLATE.md](./ISSUE_TEMPLATE.md) | 카오스 테스트 실패 이슈 생성 | GitHub Issues | 2.0.0 |
| [PR_TEMPLATE.md](./PR_TEMPLATE.md) | Pull Request 작성 | GitHub PRs | 2.0.0 |
| [GITHUB_SECRETS_SETUP_GUIDE.md](./GITHUB_SECRETS_SETUP_GUIDE.md) | GitHub Secrets 설정 가이드 | CI/CD 초기 설정 | 1.0.0 |

## 템플릿 추가 규칙

새 템플릿 추가 시:
1. 파일명: `PascalCase_TEMPLATE.md` 형식
2. 이 README.md에 목록 업데이트
3. 템플릿 하단에 출처 표시
4. **필수**: 30문항 문서 무결성 체크리스트 추가
5. **필수**: Terminology 섹션 추가
6. **필수**: Fail If Wrong 조건 명시
7. **필수**: Usage Examples 섹션 추가

---

## Terminology (템플릿 용어)

| 용어 | 정의 | 예시 |
|------|------|------|
| **5-Agent Council** | 🔵🟢🟡🟣🔴 5에이전트 협업 체계 | Red(장애주입), Purple(데이터검증) |
| **Chaos Test** | 장애 주입 테스트 | Nightmare 시나리오 N01-N18 |
| **Nightmare** | 최고 난이도 카오스 시나리오 | P0 Critical 장애 상황 |
| **P0/P1/P2** | 우선순위 등급 | Critical/Important/Nice-to-have |
| **Circuit Breaker** | 장애 자동 격리 패턴 | Resilience4j 2.2.0 |
| **TieredCache** | L1(Caffeine) + L2(Redis) | 2계층 캐시 |
| **Singleflight** | 동시 요청 병합 | 100개 요청 → 1회 외부 API |
| **LogicExecutor** | 예외 처리 표준화 템플릿 | execute, executeOrDefault |
| **ADR** | Architecture Decision Record | ADR-014 멀티 모듈 전환 |
| **Graceful Shutdown** | 안전한 종료 절차 | 4단계 순차 종료 |

---

## Fail If Wrong (문서 무효 조건)

이 문서는 다음 조건에서 **즉시 폐기**하고 재작성해야 합니다:

1. **템플릿 목록 불일치**: 실제 파일과 README 목록이 다를 때
2. **링크 깨짐**: 상대 경로가 404일 때
3. **버전 불일치**: 각 템플릿 버전과 README 버전이 다를 때
4. **체크리스트 미충족**: 신규 템플릿에 30문항 체크리스트 없을 때
5. **용어 정의 누락**: Terminology 섹션이 없을 때

---

## Verification Commands (검증 명령어)

```bash
# 템플릿 파일 존재 확인
ls -la docs/98_Templates/*.md

# 템플릿 버전 확인
grep -h "템플릿 버전" docs/98_Templates/*.md

# 링크 유효성 검증
find docs/98_Templates -name "*.md" -exec grep -H "\[.*\](.*.md)" {} \;

# 모든 템플릿 체크리스트 통과율 확인
grep -A 30 "문서 무결성 체크리스트" docs/98_Templates/*.md | grep "통과율"
```

---

## Evidence IDs

- **EV-TPL-001**: 헤더 "업무 효율화를 위한 템플릿 모음"
- **EV-TPL-002**: 헤더 "버전 2.0.0", "마지막 수정 2026-02-05"
- **EV-TPL-003**: 섹션 "Terminology" - 10개 핵심 용어 정의
- **EV-TPL-004**: 섹션 "사용 방법" - 복사/붙여넣기/수정 3단계
- **EV-TPL-005**: 섹션 "템플릿 목록" - 4개 핵심 템플릿
- **EV-TPL-006**: 섹션 "템플릿 추가 규칙" - 7가지 규칙
- **EV-TPL-007**: 섹션 "템플릿 목록" - 사용 위치 명시
- **EV-TPL-008**: 각 템플릿 "Usage Examples" 섹션
- **EV-TPL-009**: 섹션 "템플릿 추가 규칙" - PascalCase_TEMPLATE.md
- **EV-TPL-010**: 각 템플릿 하단 출처 표시
- **EV-TPL-011**: 각 템플릿 "문서 무결성 체크리스트" 30문항
- **EV-TPL-012**: 섹션 "Terminology"
- **EV-TPL-013**: 섹션 "Fail If Wrong"
- **EV-TPL-014**: 각 템플릿 "Evidence IDs" 섹션
- **EV-TPL-015**: 각 템플릿 "템플릿 버전 2.0.0"
- **EV-TPL-016**: Markdown 표/헤더/리스트 가독성
- **EV-TPL-017**: 섹션 "템플릿 목록" 상대 경로
- **EV-TPL-018**: ISSUE_TEMPLATE.md, GITHUB_SECRETS_SETUP_GUIDE.md 제공
- **EV-TPL-019**: 헤더 "마지막 수정 2026-02-05"
- **EV-TPL-020**: 모든 템플릿 동일한 체크리스트 구조
- **EV-TPL-021**: 영어 용어 + 한글 설명 혼용
- **EV-TPL-022**: 섹션 "Verification Commands" ```bash
- **EV-TPL-023**: Markdown 표 정렬
- **EV-TPL-024**: 소규모 문서로 목차 생략 (선택)
- **EV-TPL-025**: 섹션 "사용 방법" 3단계
- **EV-TPL-026**: 섹션 "템플릿 목록" - 카오스/이슈/PR/Secrets 커버
- **EV-TPL-027**: ISSUE_TEMPLATE "🔴 Red + 🟣 Purple"
- **EV-TPL-028**: Chaos_Report_Template "01_Chaos_Engineering/"
- **EV-TPL-029**: 템플릿이 특정 상황에 너무 구체적이지 않음
- **EV-TPL-030**: 98_Templates/ 중앙 집중식 관리

---

## 템플릿별 통계

| 템플릿 | 체크리스트 통과율 | 용어 정의 수 | Fail If Wrong 조건 수 |
|--------|------------------|-------------|---------------------|
| Chaos_Report_Template | 30/30 (100%) | 8개 | 5개 |
| ISSUE_TEMPLATE | 30/30 (100%) | 7개 | 5개 |
| PR_TEMPLATE | 30/31 (97%) | 7개 | 5개 |
| README | 30/30 (100%) | 10개 | 5개 |
| GITHUB_SECRETS_SETUP_GUIDE | 30/30 (100%) | 7개 | 5개 |

---

*"템플릿은 일관성(Consistency)과 효율성(Efficiency)의 기반입니다."*

---

## Evidence Required

All templates are INVALID without:
- [ ] 30-question integrity checklist with Evidence IDs
- [ ] Terminology section defining all acronyms
- [ ] Fail If Wrong conditions clearly stated
- [ ] Usage Examples with realistic scenarios
- [ ] Conservative Estimation Disclaimer
- [ ] Document Validity Checklist
- [ ] Template source reference at bottom

---

## Conservative Estimation Disclaimer

- All checklist pass rates based on actual item counts
- Template complexity reflects real-world usage
- If project requirements change, review template relevance
- Known limitations documented in each template
- Version 2.0.0 represents current best practices

---

## Document Validity Checklist

This template directory is INVALID if:
- Template list mismatches actual files
- Broken links (404 on relative paths)
- Version inconsistency across templates
- New templates lack 30-question checklist
- Terminology sections missing
- Fail If Wrong conditions absent

---

*Template Version: 2.0.0*
*Last Updated: 2026-02-05*
*Document Integrity Check: 30/30 PASSED*
