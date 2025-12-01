package maple.expectation.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.external.dto.v1.EquipmentResponse;
import maple.expectation.external.dto.v1.ItemResponse;

import java.security.MessageDigest;
import java.util.*;


public class ChecksumUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String getEquipmentResponseChecksum(EquipmentResponse response) throws Exception {

        Map<String, String> responseMap = new TreeMap<>();

        List<Integer> itemEquipmentHashcodes = new ArrayList<>();

        for(ItemResponse item : response.getItemEquipment()) {
            itemEquipmentHashcodes.add(item.hashCode());
        }

        itemEquipmentHashcodes.sort(Integer::compareTo);

        responseMap.put("characterGender", response.getCharacterGender());
        responseMap.put("characterClass", response.getCharacterClass());
        responseMap.put("presetNo", String.valueOf(response.getPresetNo()));
        responseMap.put("itemEquipmentHashcode", String.valueOf(hash(itemEquipmentHashcodes)));

        String mapString = objectMapper.writeValueAsString(responseMap);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(mapString.getBytes("UTF-8"));

        return Base64.getEncoder().encodeToString(hash);
    }

    public static long hash(List<Integer> list) {
        return list.stream()
                .map(v -> v == null ? 0 : v)
                .sorted()
                .mapToLong(Integer::longValue)
                .reduce(1L, (a, b) -> 31L * a + b);
    }
}
