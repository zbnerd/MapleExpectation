-- 닉네임(user_ign)은 고유해야 하므로 Unique Index를 겁니다. - 안하면 비관적 락할때 테이블 전체 Lock걸어서 개느려요.
CREATE UNIQUE INDEX idx_user_ign ON game_character (user_ign);