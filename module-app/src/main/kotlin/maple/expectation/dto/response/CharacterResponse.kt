package maple.expectation.dto.response

import maple.expectation.domain.v2.GameCharacter

/**
 * Character response DTO (Issue #128)
 *
 * <p>Prevents direct Entity exposure and optimizes API response size (350KB → 4KB)
 *
 * <p>Internal fields (id, version, equipment blob) are not exposed.
 *
 * @param userIgn character nickname
 * @param ocid character unique ID
 * @param likeCount like count
 * @param worldName world name
 * @param characterClass class name
 * @param characterImage character image URL
 */
data class CharacterResponse(
    val userIgn: String,
    val ocid: String,
    val likeCount: Long?,
    val worldName: String?,
    val characterClass: String?,
    val characterImage: String?
) {
    companion object {
        /**
         * Convert Entity to DTO
         *
         * @param entity GameCharacter entity
         * @return CharacterResponse DTO
         */
        @JvmStatic
        fun from(entity: GameCharacter): CharacterResponse = CharacterResponse(
            userIgn = entity.userIgn,
            ocid = entity.ocid,
            likeCount = entity.likeCount,
            worldName = entity.worldName,
            characterClass = entity.characterClass,
            characterImage = entity.characterImage
        )
    }
}