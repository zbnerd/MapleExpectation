# ADR-036: V5 CQRS Architecture with MongoDB

**Status**: Accepted (Implementation Phase)
**Date**: 2026-02-14
**Author**: MapleExpectation Architecture Team
**Supersedes**: [ADR-014](ADR-014-multi-module-cross-cutting-concerns.md), [ADR-013](ADR-013-redis-stream-design.md), [ADR-003](ADR-003-tiered-cache.md)

## Summary

Implement CQRS (Command Query Responsibility Segregation) pattern to decouple read and write operations, using MongoDB for read-optimized views and maintaining MySQL as the authoritative data source. This addresses the current V4 system's blocking calculation pipeline limitation and enables horizontal scaling for read-heavy workloads.

## Context

The current V4 system faces critical scalability challenges:

1. **Blocking Calculation Pipeline**: Equipment expectation calculations take 500ms - 30s due to complex probability calculations
2. **High Read-to-Write Ratio**: 90%+ of requests are read operations, mostly for character valuation data
3. **Read Scaling Limitations**: Current MySQL read path is inefficient for high-volume reads
4. **MongoDB Availability**: Document store available for read-optimized schemas

The system requires horizontal scaling to support 1,000+ concurrent users while maintaining low latency for read operations.

## Decision

Implement CQRS pattern with the following architecture:

### 1. Command Side (Write Path)
- **Database**: MySQL (existing write-optimized schema)
- **Events**: Redis Stream for event sourcing
- **Processing**: Existing V4 calculation pipeline with Redis Stream events
- **Validation**: Domain logic enforced at write side

### 2. Query Side (Read Path)
- **Database**: MongoDB with `CharacterValuationView` collection
- **Schema**: Optimized for read queries with indexed fields
- **Access**: Spring Data MongoDB with reactive repository pattern
- **Performance**: Sub-10ms read latency via document-level indexes

### 3. Sync Layer
- **Processor**: Async worker consuming Redis Stream events
- **Transformation**: MySQL → MongoDB document transformation
- **Backpressure**: Queue-based calculation to prevent system overload
- **Monitoring**: Sync lag metrics with TTL-based invalidation

### 4. Cache Integration
- **L1**: Local Caffeine cache for frequent queries
- **L2**: Redis cache for recent queries
- **Strategy**: Cache-aside pattern with MongoDB as primary read store
- **Fallback**: Calculation service for stale cache misses

## Consequences

### Positive
- **Read Performance**: 500ms → 1-10ms latency (MongoDB indexed lookup)
- **Horizontal Scaling**: Add MongoDB replica nodes without write-side impact
- **Write Isolation**: MySQL remains source of truth, calculations isolated
- **Backpressure Control**: Queue-based processing prevents system overload
- **Query Flexibility**: MongoDB aggregation for complex valuation queries
- **Cost Efficiency**: Read scaling on commodity MongoDB infrastructure

### Negative
- **Eventual Consistency**: Read views may be stale (TTL-based invalidation)
- **Operational Complexity**: Additional MongoDB sync worker required
- **Storage Overhead**: MongoDB doubles storage for read models
- **Sync Lag**: Potential delays between write and read view updates
- **Learning Curve**: Team familiarity with MongoDB patterns needed

### Mitigation
- **TTL Index**: Automatic view expiration (24 hours) for stale data cleanup
- **Cache-Aside**: Fallback to calculation service for stale cache misses
- **Monitoring**: Sync lag metrics with alerting ( > 1 minute lag)
- **Circuit Breaker**: Fallback to V4 calculation pipeline if sync fails
- **Batch Processing**: Batch MongoDB writes to improve throughput

## Implementation Plan

### Phase 1: Infrastructure Setup
1. MongoDB cluster configuration
2. Redis Stream event schema definition
3. Sync worker foundation

### Phase 2: Query Side Implementation
1. `CharacterValuationView` document schema
2. Spring Data MongoDB repositories
3. Query endpoints integration
4. MongoDB indexes optimization

### Phase 3: Sync Layer Development
1. Redis Stream consumer implementation
2. MySQL → MongoDB transformation logic
3. Backpressure handling mechanisms
4. Monitoring and alerting

### Phase 4: Migration & Testing
1. Canary deployment strategy
2. Performance benchmarking
3. Chaos testing validation
4. Full migration to V5

## Design Decisions

### Schema Design
```java
@Document(collection = "character_valuation_views")
public class CharacterValuationView {
    @Id
    private String characterId;

    @Indexed
    private String ocid;

    @Indexed
    private LocalDateTime lastUpdated;

    private CharacterValuationData valuation;

    private EquipmentView[] equipment;

    @Indexed(expireAfterSeconds = 86400) // 24 hours TTL
    private LocalDateTime expiresAt;
}
```

### Sync Worker Architecture
```java
@Component
@RequiredArgsConstructor
public class MongoSyncWorker {
    private final RedisStreamTemplate redisTemplate;
    private final MongoTemplate mongoTemplate;

    @Scheduled(fixedDelay = 100)
    public void processStream() {
        List<CharacterEvent> events = redisTemplate.poll("character-events", 100);
        events.forEach(this::syncToMongo);
    }

    private void syncToMongo(CharacterEvent event) {
        CharacterValuationView view = transformToView(event);
        mongoTemplate.save(view);
    }
}
```

### Query Service
```java
@Service
@RequiredArgsConstructor
public class CharacterValuationQueryService {
    private final MongoCharacterValuationRepository repository;
    private final CaffeineCache cache;

    @Cacheable(value = "valuation", key = "#characterId")
    public Mono<ValuationResult> getValuation(String characterId) {
        return repository.findById(characterId)
            .map(this::toResult)
            .switchIfEmpty(calculateFallback(characterId));
    }
}
```

## Alternatives Considered

### 1. MySQL-Only Approach
**Rejected**:
- Vertical scaling limitation
- Poor query performance for complex aggregations
- Cannot achieve sub-10ms read latency

### 2. Kafka-Based Architecture
**Rejected**:
- Higher operational overhead
- Additional infrastructure requirements
- Over-engineering for current needs
- Redis Stream already in use with proven reliability

### 3. Redis-Only Read Store
**Rejected**:
- Limited query capabilities (no aggregation support)
- Data volatility concerns
- Scaling limitations at high volume
- Storage cost inefficiency

### 4. Full Event Sourcing
**Rejected**:
- Complete rewrite required
- High risk and complexity
- Over-engineering for current requirements
- V4 already functional for writes

## Monitoring & Observability

### Key Metrics
- **Sync Lag**: Time between write and read view update
- **Query Latency**: MongoDB query performance (target: < 10ms)
- **Cache Hit Rate**: MongoDB view cache effectiveness
- **Stream Processing**: Queue depth and processing rate
- **Error Rate**: Sync worker failure rate

### Alerting Rules
- Sync lag > 1 minute
- MongoDB query latency > 50ms (P95)
- Stream queue depth > 10,000 messages
- Sync worker error rate > 1%

## Security Considerations

### Data Protection
- MongoDB connection encryption (TLS 1.3)
- Role-based access control (RBAC)
- Audit logging for read operations
- Input validation for all queries

### Access Control
```java
@Configuration
@EnableMongoRepositories
public class MongoSecurityConfig {
    @Bean
    public MongoUserDetailsManager userDetailsManager() {
        return new MongoUserDetailsManager(
            User.withUsername("read-user")
                .password(enc.encode("secure-password"))
                .roles("QUERY_READ")
                .build()
        );
    }
}
```

## References

- [ADR-014: Multi-module architecture](ADR-014-multi-module-cross-cutting-concerns.md)
- [ADR-013: Redis Stream design](ADR-013-redis-stream-design.md)
- [ADR-003: Tiered cache strategy](ADR-003-tiered-cache.md)
- Spring Data MongoDB 3.5.4 Best Practices
- Redisson 3.27.0 Stream API Documentation
- MongoDB CQRS Pattern Guide
- AWS MongoDB Best Practices

## Related Work

### Ongoing Tasks
- #283: MongoDB cluster setup and configuration
- #282: Redis Stream event schema design
- #126: Sync worker implementation

### Future Considerations
- [Future ADR]: Eventual consistency strategy for financial calculations
- [Future ADR: Fallback mechanisms for sync failures
- [Future ADR: MongoDB vs Redis performance analysis for specific queries

---

*This document follows the project's ADR template format, including status, context, decision, consequences, and implementation details. It addresses the specific challenges of the current V4 system while providing a clear path to V5 CQRS implementation.*