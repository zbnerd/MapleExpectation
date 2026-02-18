# MapleExpectation Changelog

> **Format**: [Semantic Versioning](https://semver.org/)
>
> **Categories**: Added, Changed, Fixed, Deprecated, Removed, Security
>
> **Last Updated**: 2026-01-31

---

## [Unreleased]

### In Development
- Phase 7: Stateful removal (#283)
- Phase 7: Multi-module refactoring (#282)
- Phase 7: Pragmatic CQRS (#126)

---

## [1.0.0-SNAPSHOT] - 2026-01-31

### Added

#### Infrastructure & Core Components
- **LogicExecutor**: Zero try-catch execution framework with 6 patterns (execute, executeVoid, executeOrDefault, executeWithRecovery, executeWithFinally, executeWithTranslation)
- **TieredCache**: Two-tier cache architecture (L1 Caffeine 5min + L2 Redis 10min) with SingleFlightExecutor stampede prevention
- **Distributed Lock**: Redis-based locking with MySQL and local fallbacks, dedicated HikariCP pool (30 local, 150 prod)
- **CircuitBreaker**: Resilience4j integration with exception classification (CircuitBreakerIgnoreMarker, CircuitBreakerRecordMarker)
- **RateLimiting**: Bucket4j-based IP/User distributed rate limiting with externalized configuration

#### Features
- **Real-time Like Sync**: RTopic and RReliableTopic Pub/Sub for scale-out environments
- **WriteBackBuffer**: Async write-behind caching with batching and exponential backoff
- **Starforce Calculation**: V4 API lookup table with Noljang event support
- **Cube DP Engine**: Dynamic programming-based probability convolution for cube calculations
- **Redis HA**: Sentinel-based master-slave replication with failover support (3-node cluster)

#### Configuration & Properties
- **ExecutorProperties**: YAML-based thread pool configuration (equipment, preset executors)
- **BufferProperties**: Write-behind buffer batch size, TTL, retry logic externalization
- **CacheProperties**: Per-cache TTL and size configuration
- **RateLimitProperties**: Request limits per strategy (IP-based, user-based)
- **SecurityConfig**: Endpoint-based access control with role-based authorization

#### Security
- **JWT Authentication**: Token generation, validation, device fingerprinting
- **Admin Authorization**: Role-based access control for sensitive endpoints
- **Input Validation**: Bean Validation with @NotBlank, @Size, @Pattern, @Valid annotations
- **CORS Security**: Validated origin headers with configurable allowed origins
- **Secrets Management**: All sensitive values externalized to environment variables

#### Observability & Monitoring
- **Micrometer Metrics**: ExecutorServiceMetrics integration for thread pools
- **Prometheus Integration**: Metrics endpoint at /actuator/prometheus
- **Loki Logging**: Structured JSON logging with Loki4j appender
- **Grafana Dashboards**: JVM, cache, circuit breaker, and database metrics
- **Slow Query Logging**: MySQL slow query detection with 1-second threshold

#### Performance & Optimization
- **GZIP Compression**: 90% storage reduction for equipment JSON data (350KB → 35KB)
- **Streaming Parser**: Incremental JSON parsing to reduce memory footprint
- **Probabilistic Cache**: PER (Probabilistic Early Revalidation) pattern for cache stampede
- **Connection Pool Tuning**: Externalized pool sizes with environment-specific profiles
- **Async Pipeline**: CompletableFuture chains with orTimeout(10s) protection

### Changed

#### Architecture Decisions
- **@Cacheable Constraint**: Kept .join() at boundaries due to Spring caching limitations; mitigated with orTimeout(10s)
- **Transport Strategy**: RTopic default (at-most-once) with option to switch to RReliableTopic (at-least-once) post-Blue-Green
- **Lock Strategy Pattern**: Runtime selection between Redis, MySQL, and Guava lock implementations
- **Executor Separation**: Split expectation compute executor into equipment (I/O) and preset (CPU) for better resource isolation

#### Configuration Updates
- `application.yml`: Added executor, buffer, cache, rate limit, and security properties
- `application-prod.yml`: Production-optimized settings (thread pools 2x, lock pool 150)
- `build.gradle`: Updated Resilience4j BOM to 2.2.0, Redisson to 3.27.0

#### API & Response Format
- **StandardResponse**: Unified response wrapper for all endpoints
- **ErrorResponse**: Consistent error format with error codes and dynamic messages
- **ValidationError**: Detailed validation error reporting with field paths

### Fixed

#### Bug Fixes & Corrections
- **YAML Configuration**: Fixed application.yml structure (minor duplicate key issue; Spring config merge works correctly)
- **OrTimeout Protection**: Added 10-second timeout to all CompletableFuture.join() calls to prevent indefinite blocking
- **Cache Eviction Order**: Ensured L2 eviction before L1 to prevent stale cache backfill
- **Lock Pool Sizing**: Externalized from hardcoded 30 to configurable value (150 for production)

#### Stability Improvements
- **Graceful Degradation**: Redis failures don't block application functionality (like events, caching)
- **Connection Pool**: Dedicated LockHikariConfig prevents connection starvation in lock-heavy scenarios
- **Exception Handling**: All exceptions properly classified (ClientBase 4xx vs. ServerBase 5xx)

### Deprecated

- **PermutationUtil.java**: Marked for deletion post-DP engine completion (still present, low priority)
- **Legacy ExecutorProperties**: Hardcoded thread pool sizes deprecated in favor of YAML configuration

### Removed

- **Try-Catch Blocks**: Eliminated from service layer via LogicExecutor adoption (0 violations)
- **Spaghetti Code**: Flattened nested indentation to max 2 levels via method extraction
- **Hardcoded Timeouts**: All magic numbers externalized to properties

### Security

- **No Hardcoded Secrets**: JWT secret, API keys moved to environment variables
- **Input Validation**: All Controller DTOs have Bean Validation annotations
- **Rate Limiting**: Distributed rate limiting prevents DoS attacks
- **Authorization**: Admin APIs require ROLE_ADMIN; like/donation endpoints require authentication
- **Device Binding**: JWT includes device fingerprint to prevent token theft

---

## [0.9.0] - 2025-12-15

### Added
- Initial Spring Boot 3.5.4 setup with Java 21
- Redis and MySQL integration
- Nexon Open API client
- Basic equipment calculation logic
- V2/V3/V4 API endpoints

### Fixed
- Redis connection pooling
- MySQL transaction isolation

---

## Version Strategy

### Release Cycle
- **1.0.0**: Production-ready (all Phase 1-6 complete)
- **2.0.0**: Scale-out phase (Phase 7 complete: #283, #282, #126)
- **3.0.0**: Advanced features (Phase 8+: Virtual Threads, Event Sourcing)

### Semantic Versioning
- **MAJOR**: Breaking API changes, significant architectural shifts
- **MINOR**: New features, non-breaking enhancements
- **PATCH**: Bug fixes, configuration updates

### Branch Mapping
- `master` → Release versions (1.0.0, 1.1.0, 2.0.0)
- `develop` → Pre-release snapshots (1.0.0-SNAPSHOT, 1.1.0-RC1)
- `feature/*` → Feature branches (tagged with issue number)

---

## Related Documents

- **Roadmap**: docs/00_Start_Here/ROADMAP.md (Phase 1-7 planning)
- **Architecture**: docs/00_Start_Here/architecture.md (System design)
- **ADRs**: docs/01_Adr/ (Architectural Decision Records)
- **Completion Reports**: docs/04-report/ (PDCA results)

---

## Maintenance Policy

### End of Support
- **1.0.x**: Supported until 1.1.0 release
- **1.1.x**: Supported for 6 months post-release
- **2.0.x**: LTS (Long-term support) until 3.0.0

### Security Updates
- Critical vulnerabilities: Patched within 48 hours
- High vulnerabilities: Patched within 1 week
- Medium/Low: Included in next regular release

---

**Last Updated**: 2026-01-31
**Maintainers**: Architecture Council (Blue, Green, Yellow, Purple, Red)
**License**: Proprietary (MapleStory Equipment Calculator)
