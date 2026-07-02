package com.warsim.frontline.network.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.warsim.frontline.api.redis.ControlMessageResult;
import org.junit.jupiter.api.Test;

class ControlDeliveryPolicyTest {
    private final ControlDeliveryPolicy policy = new ControlDeliveryPolicy(3);

    @Test void acknowledgesSuccessfulMessage() {
        assertEquals(ControlDeliveryPolicy.Action.ACKNOWLEDGE,
            policy.decide(ControlMessageResult.ACKNOWLEDGED, 1));
    }

    @Test void successfulDeduplicationAcknowledgesDuplicate() {
        assertEquals(ControlDeliveryPolicy.Action.ACKNOWLEDGE,
            policy.decide(ControlMessageResult.DUPLICATE, 1));
    }

    @Test void failedMessageRetriesBeforeLimit() {
        assertEquals(ControlDeliveryPolicy.Action.RETRY,
            policy.decide(ControlMessageResult.RETRY, 1));
    }

    @Test void retryCountStopsAtLimit() {
        assertEquals(ControlDeliveryPolicy.Action.DEAD_LETTER,
            policy.decide(ControlMessageResult.RETRY, 3));
    }

    @Test void explicitDeadLetterIsPreserved() {
        assertEquals(ControlDeliveryPolicy.Action.DEAD_LETTER,
            policy.decide(ControlMessageResult.DEAD_LETTER, 1));
    }

    @Test void invalidMessageIsAcknowledgedToAvoidPoisonLoop() {
        assertEquals(ControlDeliveryPolicy.Action.ACKNOWLEDGE,
            policy.decide(ControlMessageResult.INVALID, 1));
    }
}
