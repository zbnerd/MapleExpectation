package maple.expectation.service.v5.event;

import maple.expectation.dto.v4.EquipmentExpectationResponseV4;

/** Interface for MongoSyncEventPublisher to allow stub implementation */
public interface MongoSyncEventPublisherInterface {

  void publishCalculationCompleted(String taskId, EquipmentExpectationResponseV4 response);
}
