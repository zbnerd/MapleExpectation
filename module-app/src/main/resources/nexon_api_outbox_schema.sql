-- ============================================
-- Nexon API Outbox Schema
-- Transactional Outbox for External API Failures
-- ============================================

-- Outbox Table
CREATE TABLE IF NOT EXISTS nexon_api_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version BIGINT DEFAULT 0,
    request_id VARCHAR(100) NOT NULL UNIQUE,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')
           NOT NULL DEFAULT 'PENDING',
    locked_by VARCHAR(100) NULL,
    locked_at DATETIME NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    last_error VARCHAR(500) NULL,
    next_retry_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Polling optimization index for pending messages
    INDEX idx_pending_poll (status, next_retry_at, id),
    -- Index for lock management (skip locked optimization)
    INDEX idx_locked (locked_by, locked_at),
    -- Unique index for request tracking
    INDEX idx_request_id (request_id) UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Nexon API Transactional Outbox for External API Failures';

-- Optional: DLQ Table (Dead Letter Queue)
CREATE TABLE IF NOT EXISTS nexon_api_dlq (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    original_outbox_id BIGINT NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    failure_reason VARCHAR(500) NULL,
    moved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_dlq_moved_at (moved_at),
    INDEX idx_dlq_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Dead Letter Queue for Failed Nexon API Outbox Messages';