package maple.expectation.service.v2;

/**
 * 좋아요 처리 인터페이스
 */
public interface LikeProcessor {
    /**
     * 좋아요 추가
     * @return 버퍼의 현재 delta 값 (increment 후)
     */
    Long processLike(String userIgn);

    /**
     * 좋아요 취소
     * @return 버퍼의 현재 delta 값 (decrement 후)
     */
    Long processUnlike(String userIgn);
}