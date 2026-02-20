package maple.expectation.event

/**
 * V5 CQRS: Event published when expectation calculation completes
 *
 * <h3>Purpose</h3>
 *
 * <ul>
 *   <li>Carries calculation result from worker to MongoDB sync worker
 *   <li>Serialized to Redis Stream character-sync
 *   <li>Contains full V4 response payload for MongoDB upsert
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * ```
 * ExpectationCalculationWorker.calculate()
 *   → MongoSyncEventPublisher.publishCalculationCompleted()
 *   → Redis Stream (character-sync)
 *   → MongoDBSyncWorker.consume()
 *   → CharacterValuationView.upsert()
 * ```
 */
data class ExpectationCalculationCompletedEvent(
    var taskId: String? = null,
    var userIgn: String? = null,
    var messageId: String? = null, // Redis Stream message ID for idempotency
    var characterOcid: String? = null,
    var characterClass: String? = null,
    var characterLevel: Int? = null,
    var calculatedAt: String? = null,
    var totalExpectedCost: String? = null,
    var maxPresetNo: Int? = null,
    var payload: String? = null // Serialized EquipmentExpectationResponseV4
) {
    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var taskId: String? = null
        private var userIgn: String? = null
        private var messageId: String? = null
        private var characterOcid: String? = null
        private var characterClass: String? = null
        private var characterLevel: Int? = null
        private var calculatedAt: String? = null
        private var totalExpectedCost: String? = null
        private var maxPresetNo: Int? = null
        private var payload: String? = null

        fun taskId(taskId: String?) = apply { this.taskId = taskId }
        fun userIgn(userIgn: String?) = apply { this.userIgn = userIgn }
        fun messageId(messageId: String?) = apply { this.messageId = messageId }
        fun characterOcid(characterOcid: String?) = apply { this.characterOcid = characterOcid }
        fun characterClass(characterClass: String?) = apply { this.characterClass = characterClass }
        fun characterLevel(characterLevel: Int?) = apply { this.characterLevel = characterLevel }
        fun calculatedAt(calculatedAt: String?) = apply { this.calculatedAt = calculatedAt }
        fun totalExpectedCost(totalExpectedCost: String?) = apply { this.totalExpectedCost = totalExpectedCost }
        fun maxPresetNo(maxPresetNo: Int?) = apply { this.maxPresetNo = maxPresetNo }
        fun payload(payload: String?) = apply { this.payload = payload }

        fun build(): ExpectationCalculationCompletedEvent = ExpectationCalculationCompletedEvent(
            taskId = taskId,
            userIgn = userIgn,
            messageId = messageId,
            characterOcid = characterOcid,
            characterClass = characterClass,
            characterLevel = characterLevel,
            calculatedAt = calculatedAt,
            totalExpectedCost = totalExpectedCost,
            maxPresetNo = maxPresetNo,
            payload = payload
        )
    }
}

