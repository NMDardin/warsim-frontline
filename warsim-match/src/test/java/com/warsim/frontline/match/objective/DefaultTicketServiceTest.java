package com.warsim.frontline.match.objective;

import static org.junit.jupiter.api.Assertions.*;

import com.warsim.frontline.api.roster.TeamSide;
import com.warsim.frontline.api.ticket.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultTicketServiceTest {
    private static final UUID MATCH = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Test void initialAttackersTickets() {
        assertEquals(300, service().snapshot().attackers().current());
    }

    @Test void defendersDisabled() {
        assertFalse(service().snapshot().defenders().enabled());
    }

    @Test void addTickets() {
        TicketOperationResult result = service().apply(operation(TicketOperationType.ADD, 10));
        assertTrue(result.successful());
        assertEquals(310, result.snapshot().attackers().current());
    }

    @Test void takeTickets() {
        TicketOperationResult result = service().apply(operation(TicketOperationType.TAKE, 10));
        assertEquals(290, result.snapshot().attackers().current());
    }

    @Test void setTickets() {
        TicketOperationResult result = service().apply(operation(TicketOperationType.SET, 125));
        assertEquals(125, result.snapshot().attackers().current());
    }

    @Test void clampsAddAtMaximum() {
        TicketOperationResult result = service().apply(operation(TicketOperationType.ADD, 1000));
        assertEquals(500, result.snapshot().attackers().current());
    }

    @Test void clampsTakeAtZero() {
        TicketOperationResult result = service().apply(operation(TicketOperationType.TAKE, 1000));
        assertEquals(0, result.snapshot().attackers().current());
    }

    @Test void rejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class,
            () -> new TicketOperation(UUID.randomUUID(), TeamSide.ATTACKERS,
                TicketOperationType.ADD, -1, TicketChangeReason.ADMINISTRATOR, NOW));
    }

    @Test void disabledSideRejectsOperation() {
        TicketOperation operation = new TicketOperation(UUID.randomUUID(), TeamSide.DEFENDERS,
            TicketOperationType.ADD, 1, TicketChangeReason.ADMINISTRATOR, NOW);
        assertFalse(service().apply(operation).successful());
    }

    @Test void duplicateOperationDoesNotApplyTwice() {
        DefaultTicketService service = service();
        TicketOperation operation = operation(TicketOperationType.ADD, 10);
        assertTrue(service.apply(operation).successful());
        TicketOperationResult duplicate = service.apply(operation);
        assertTrue(duplicate.duplicate());
        assertEquals(310, duplicate.snapshot().attackers().current());
    }

    @Test void changeEventPublishedForZeroAmount() {
        DefaultTicketService service = service();
        AtomicInteger events = new AtomicInteger();
        service.subscribe(event -> events.incrementAndGet());
        service.apply(operation(TicketOperationType.ADD, 0));
        assertEquals(1, events.get());
    }

    @Test void depletionEventPublishedOnce() {
        DefaultTicketService service = service();
        AtomicInteger depleted = new AtomicInteger();
        service.subscribe(event -> {
            if (event instanceof TicketsDepletedEvent) depleted.incrementAndGet();
        });
        service.apply(operation(TicketOperationType.SET, 0));
        service.apply(operation(TicketOperationType.SET, 0));
        assertEquals(1, depleted.get());
    }

    @Test void metricsTrackAddedTickets() {
        DefaultTicketService service = service();
        service.apply(operation(TicketOperationType.ADD, 10));
        assertEquals(10, service.metrics().ticketsAdded());
    }

    @Test void metricsTrackRemovedTickets() {
        DefaultTicketService service = service();
        service.apply(operation(TicketOperationType.TAKE, 10));
        assertEquals(10, service.metrics().ticketsRemoved());
    }

    @Test void closeRejectsOperations() {
        DefaultTicketService service = service();
        service.close();
        assertFalse(service.apply(operation(TicketOperationType.ADD, 1)).successful());
    }

    @Test void closeIsIdempotent() {
        DefaultTicketService service = service();
        service.close();
        assertDoesNotThrow(service::close);
    }

    @Test void setAboveMaximumClamps() {
        assertEquals(500,
            service().apply(operation(TicketOperationType.SET, Integer.MAX_VALUE))
                .snapshot().attackers().current());
    }

    @Test void objectiveRewardMetricOnlyCountsAppliedDelta() {
        DefaultTicketService service = service();
        service.apply(new TicketOperation(UUID.randomUUID(), TeamSide.ATTACKERS,
            TicketOperationType.ADD, 1000, TicketChangeReason.OBJECTIVE_CAPTURE_REWARD, NOW));
        assertEquals(200, service.metrics().objectiveRewards());
    }

    @Test void raisingTicketsAllowsFutureDepletionEvent() {
        DefaultTicketService service = service();
        AtomicInteger depleted = new AtomicInteger();
        service.subscribe(event -> {
            if (event instanceof TicketsDepletedEvent) depleted.incrementAndGet();
        });
        service.apply(operation(TicketOperationType.SET, 0));
        service.apply(operation(TicketOperationType.SET, 5));
        service.apply(operation(TicketOperationType.SET, 0));
        assertEquals(2, depleted.get());
    }

    @Test void changeContainsAppliedDelta() {
        TicketOperationResult result = service().apply(operation(TicketOperationType.TAKE, 12));
        assertEquals(-12, result.change().appliedDelta());
    }

    @Test void revisionIncrementsForEveryAcceptedOperation() {
        DefaultTicketService service = service();
        service.apply(operation(TicketOperationType.ADD, 0));
        service.apply(operation(TicketOperationType.TAKE, 0));
        assertEquals(2, service.snapshot().revision());
    }

    @Test void listenerFailureDoesNotBlockNextListener() {
        DefaultTicketService service = new DefaultTicketService(
            MATCH, TicketConfiguration.defaults(true), NOW, ignored -> {});
        AtomicInteger called = new AtomicInteger();
        service.subscribe(event -> { throw new RuntimeException("boom"); });
        service.subscribe(event -> called.incrementAndGet());
        service.apply(operation(TicketOperationType.ADD, 1));
        assertEquals(1, called.get());
    }

    @Test void disabledConfigurationStartsWithZeroPools() {
        DefaultTicketService service = new DefaultTicketService(
            MATCH, TicketConfiguration.disabled(), NOW, ignored -> {});
        assertEquals(0, service.snapshot().attackers().maximum());
        assertFalse(service.apply(operation(TicketOperationType.ADD, 1)).successful());
    }

    private static DefaultTicketService service() {
        return new DefaultTicketService(MATCH, TicketConfiguration.defaults(true), NOW, ignored -> {});
    }

    private static TicketOperation operation(TicketOperationType type, int amount) {
        return new TicketOperation(UUID.randomUUID(), TeamSide.ATTACKERS, type, amount,
            TicketChangeReason.ADMINISTRATOR, NOW);
    }
}
