package maple.expectation.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PermutationUtil {

    /**
     * 입력된 리스트의 모든 순열(Permutation)을 생성하여 반환합니다.
     * 중복된 조합은 Set을 통해 자동으로 제거됩니다.
     * 예: [A, A, B] -> [[A, A, B], [A, B, A], [B, A, A]]
     */
    public static Set<List<String>> generateUniquePermutations(List<String> input) {
        Set<List<String>> result = new HashSet<>();
        permute(input, 0, result);
        return result;
    }

    private static void permute(List<String> arr, int k, Set<List<String>> result) {
        if (k == arr.size()) {
            result.add(new ArrayList<>(arr));
        } else {
            for (int i = k; i < arr.size(); i++) {
                java.util.Collections.swap(arr, i, k);
                permute(arr, k + 1, result);
                java.util.Collections.swap(arr, i, k);
            }
        }
    }
}