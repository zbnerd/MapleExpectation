package maple.expectation.dto.response;

import maple.expectation.domain.v2.GameCharacter;

/**
 * 캐릭터 응답 DTO (Issue #128)
 *
 * <p>Entity 직접 노출 방지 및 API 응답 크기 최적화 (350KB → 4KB)</p>
 * <p>내부 필드(id, version, equipment blob)를 노출하지 않습니다.</p>
 *
 * @param userIgn 캐릭터 닉네임
 * @param ocid 캐릭터 고유 ID
 * @param likeCount 좋아요 수
 */
public record CharacterResponse(
        String userIgn,
        String ocid,
        Long likeCount
) {
    /**
     * Entity → DTO 변환 팩토리 메서드
     *
     * @param entity GameCharacter 엔티티
     * @return CharacterResponse DTO
     */
    public static CharacterResponse from(GameCharacter entity) {
        return new CharacterResponse(
                entity.getUserIgn(),
                entity.getOcid(),
                entity.getLikeCount()
        );
    }
}
