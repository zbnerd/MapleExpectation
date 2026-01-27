-- ========================================================================
-- MapleExpectation Database Index Strategy
-- Generated: 2026-01-28
-- Purpose: Repository 쿼리 패턴 분석 기반 인덱스 최적화
-- ========================================================================

-- ========================================================================
-- 1. game_character 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - findByUserIgn(userIgn)
--   - findByUserIgnWithEquipment(userIgn)
--   - incrementLikeCount(userIgn, count)
--
-- user_ign: UNIQUE 제약조건 -> 인덱스 자동 생성
-- ocid: UNIQUE 제약조건 -> 인덱스 자동 생성

-- 이미 Entity에서 UNIQUE로 정의됨 (JPA가 DDL 자동 생성)
-- 수동 생성 필요 시:
-- CREATE UNIQUE INDEX idx_game_character_user_ign ON game_character (user_ign);
-- CREATE UNIQUE INDEX idx_game_character_ocid ON game_character (ocid);


-- ========================================================================
-- 2. character_equipment 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - findByOcidAndUpdatedAtAfter(ocid, threshold)
--
-- ocid: PK -> 인덱스 자동 생성
-- updated_at: 범위 검색 최적화 필요

CREATE INDEX IF NOT EXISTS idx_character_equipment_updated_at
    ON character_equipment (updated_at);


-- ========================================================================
-- 3. character_like 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - existsByTargetOcidAndLikerFingerprint(targetOcid, likerFingerprint)
--   - findByTargetOcidAndLikerFingerprint(targetOcid, likerFingerprint)
--   - countByTargetOcid(targetOcid)
--   - countByLikerFingerprint(likerFingerprint)
--
-- Entity에 이미 정의됨:
--   - uk_target_liker: UNIQUE(target_ocid, liker_fingerprint)
--   - idx_target_ocid: INDEX(target_ocid)

-- liker_fingerprint 단일 인덱스 추가 (countByLikerFingerprint 최적화)
CREATE INDEX IF NOT EXISTS idx_character_like_liker_fingerprint
    ON character_like (liker_fingerprint);


-- ========================================================================
-- 4. member 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - decreasePoint(uuid, amount)
--   - increasePointByUuid(uuid, amount)
--   - findByUuid(uuid)
--
-- Entity에 이미 정의됨:
--   - idx_uuid: UNIQUE(uuid)

-- 이미 Entity에서 UNIQUE INDEX로 정의됨 (JPA가 DDL 자동 생성)
-- 수동 생성 필요 시:
-- CREATE UNIQUE INDEX idx_member_uuid ON member (uuid);


-- ========================================================================
-- 5. donation_history 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - existsByRequestId(requestId)
--
-- Entity에 이미 정의됨:
--   - uk_request_id: UNIQUE(request_id)

-- 이미 Entity에서 UNIQUE 제약조건으로 정의됨


-- ========================================================================
-- 6. donation_outbox 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - findByRequestId(requestId)
--   - findPendingWithLock(statuses, now) -> status, next_retry_at 사용
--   - resetStalledProcessing(staleTime) -> status, locked_at 사용
--   - findStalledProcessing(staleTime) -> status, locked_at 사용
--   - countByStatusIn(statuses)
--   - existsByRequestId(requestId)
--
-- Entity에 이미 정의됨:
--   - requestId: UNIQUE 제약조건
--   - idx_pending_poll: INDEX(status, next_retry_at, id)
--   - idx_locked: INDEX(locked_by, locked_at)

-- 이미 Entity에서 모든 인덱스가 정의됨


-- ========================================================================
-- 7. donation_dlq 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - findAllByOrderByMovedAtDesc()
--   - findByRequestId(requestId)
--   - findFirstPage() -> id(PK) 사용
--   - findByCursorGreaterThan(cursor) -> id(PK) 사용
--
-- Entity에 이미 정의됨:
--   - idx_dlq_moved_at: INDEX(moved_at)

-- request_id 단일 인덱스 추가 (findByRequestId 최적화)
CREATE INDEX IF NOT EXISTS idx_donation_dlq_request_id
    ON donation_dlq (request_id);


-- ========================================================================
-- 8. equipment_expectation_summary 테이블
-- ========================================================================
-- 쿼리 패턴:
--   - findByGameCharacterIdAndPresetNo(gameCharacterId, presetNo)
--   - findAllByGameCharacterId(gameCharacterId)
--   - existsByGameCharacterId(gameCharacterId)
--   - deleteAllByGameCharacterId(gameCharacterId)
--   - findAllByUserIgn(userIgn) -> JOIN game_character 사용
--   - findByUserIgnAndPresetNo(userIgn, presetNo) -> JOIN game_character 사용
--   - upsertExpectationSummary(...) -> UNIQUE KEY 사용
--
-- Entity에 이미 정의됨:
--   - idx_game_character_preset: INDEX(game_character_id, preset_no)
--   - uk_character_preset: UNIQUE(game_character_id, preset_no)

-- 이미 Entity에서 복합 인덱스와 UNIQUE 제약조건이 정의됨
-- 복합 인덱스의 첫 번째 컬럼이 game_character_id이므로 단일 조회에도 활용 가능


-- ========================================================================
-- 인덱스 요약
-- ========================================================================
--
-- [신규 추가 인덱스 - 3개]
-- 1. idx_character_equipment_updated_at (character_equipment.updated_at)
--    - 용도: TTL 기반 유효 데이터 조회 최적화
--    - 쿼리: findByOcidAndUpdatedAtAfter()
--
-- 2. idx_character_like_liker_fingerprint (character_like.liker_fingerprint)
--    - 용도: 특정 사용자가 누른 좋아요 수 조회 최적화
--    - 쿼리: countByLikerFingerprint()
--
-- 3. idx_donation_dlq_request_id (donation_dlq.request_id)
--    - 용도: DLQ에서 request_id로 조회 시 최적화
--    - 쿼리: findByRequestId()
--
-- [기존 Entity 정의 인덱스 - 8개]
-- - game_character: user_ign(UK), ocid(UK)
-- - character_like: (target_ocid, liker_fingerprint)(UK), target_ocid
-- - member: uuid(UK)
-- - donation_history: request_id(UK)
-- - donation_outbox: request_id(UK), (status, next_retry_at, id), (locked_by, locked_at)
-- - donation_dlq: moved_at
-- - equipment_expectation_summary: (game_character_id, preset_no)(UK, IDX)
