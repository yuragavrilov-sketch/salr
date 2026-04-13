package ru.tcb.sal.commands.core.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tcb.sal.commands.core.bus.CorrelationStore;

import java.util.concurrent.CompletableFuture;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.CommandCompletedEvent;
import ru.tcb.sal.commands.core.wire.CommandFailedEvent;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

public class ResultListener {

    private static final Logger log = LoggerFactory.getLogger(ResultListener.class);

    private final RecordedMessageConverter converter;
    private final CorrelationStore store;

    public ResultListener(RecordedMessageConverter converter, CorrelationStore store) {
        this.converter = converter;
        this.store = store;
    }

    @SuppressWarnings("unchecked")
    public void onMessage(RecordedMessageConverter.AmqpWireMessage amqpMessage) {
        RecordedMessage rm = converter.fromAmqp(amqpMessage);

        CorrelationStore.Pending pending = store.remove(rm.correlationId);
        if (pending == null) {
            log.debug("[RESULT] No pending for correlationId={}", rm.correlationId);
            return;
        }

        String exchange = rm.exchangeName;
        boolean isCompleted = exchange != null && exchange.contains("CommandCompletedEvent");
        boolean isFailed = exchange != null && exchange.contains("CommandFailedEvent");

        if (isCompleted) {
            try {
                CommandCompletedEvent event = converter.readAsCompleted(rm);
                RecordedMessage fakeRm = new RecordedMessage();
                fakeRm.payload = event.result;
                Object typedResult = converter.readPayloadAs(fakeRm, pending.expectedResultType());
                @SuppressWarnings({"unchecked", "rawtypes"})
                CompletableFuture<Object> future = (CompletableFuture) pending.future();
                future.complete(typedResult);
                log.info("[RESULT] Completed: correlationId={}", rm.correlationId);
            } catch (Exception e) {
                pending.future().completeExceptionally(e);
                log.error("[RESULT] Parse error: correlationId={}", rm.correlationId, e);
            }
        } else if (isFailed) {
            try {
                CommandFailedEvent event = converter.readAsFailed(rm);
                String msg = event.exceptionData != null ? event.exceptionData.message : "Unknown error";
                String code = event.exceptionData != null ? event.exceptionData.code : null;
                pending.future().completeExceptionally(
                    new RuntimeException("[" + code + "] " + msg));
                log.warn("[RESULT] Failed: correlationId={} code={}", rm.correlationId, code);
            } catch (Exception e) {
                pending.future().completeExceptionally(e);
            }
        } else {
            pending.future().completeExceptionally(
                new RuntimeException("Unknown result exchange: " + exchange));
        }
    }
}
