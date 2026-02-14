package maple.expectation.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.domain.model.like.CharacterLike;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
    name = "character_like",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_target_liker",
            columnNames = {"target_ocid", "liker_account_id"}),
    indexes = {
      @Index(name = "idx_target_ocid", columnList = "target_ocid"),
      @Index(name = "idx_liker_account_id", columnList = "liker_account_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterLikeJpaEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "target_ocid", nullable = false, length = 64)
  private String targetOcid;

  @Column(name = "liker_account_id", nullable = false, length = 64)
  private String likerAccountId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public CharacterLikeJpaEntity(String targetOcid, String likerAccountId) {
    this.targetOcid = targetOcid;
    this.likerAccountId = likerAccountId;
    this.createdAt = LocalDateTime.now();
  }

  public CharacterLike toDomain() {
    // Create CharacterLike with data using static factory
    CharacterLike like =
        CharacterLike.restore(this.id, this.targetOcid, this.likerAccountId, this.createdAt);
    return like;
  }

  public static CharacterLikeJpaEntity fromDomain(CharacterLike domain) {
    if (domain == null) {
      throw new IllegalArgumentException("Domain cannot be null");
    }
    CharacterLikeJpaEntity entity;
    if (domain.id() == null) {
      entity = new CharacterLikeJpaEntity(domain.targetOcid(), domain.likerAccountId());
    } else {
      entity = new CharacterLikeJpaEntity();
      entity.id = domain.id();
      entity.targetOcid = domain.targetOcid();
      entity.likerAccountId = domain.likerAccountId();
      entity.createdAt = domain.createdAt();
    }
    return entity;
  }
}
