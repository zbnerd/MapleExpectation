package maple.expectation.infrastructure.mongodb;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterValuationRepository
    extends MongoRepository<CharacterValuationView, String> {

  Optional<CharacterValuationView> findByUserIgn(String userIgn);

  void deleteByUserIgn(String userIgn);
}
