package ru.tcb.sal.demo;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ResultListenerService {

    public record CapturedResult(
        String correlationId,
        String exchange,
        String routingKey,
        String contentType,
        String bodyJson,
        Instant receivedAt
    ) {}

    private final RecordedMessageConverter converter;
    private final ConcurrentHashMap<String, CapturedResult> results = new ConcurrentHashMap<>();

    public ResultListenerService(RecordedMessageConverter converter) {
        this.converter = converter;
    }

    @RabbitListener(queues = "#{demoResultQueue.name}")
    public void onMessage(Message message) {
        try {
            String exchange = message.getMessageProperties().getReceivedExchange();
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            String contentType = message.getMessageProperties().getContentType();
            String correlationId = message.getMessageProperties().getCorrelationId();
            String bodyJson = new String(message.getBody(), StandardCharsets.UTF_8);

            CapturedResult result = new CapturedResult(
                correlationId,
                exchange,
                routingKey,
                contentType,
                bodyJson,
                Instant.now()
            );

            if (correlationId != null) {
                results.put(correlationId, result);
            }

            System.out.println("[RESULT] " + exchange + " :: " + correlationId
                + " (" + message.getBody().length + " bytes)");
        } catch (Exception e) {
            System.err.println("[RESULT ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    public CapturedResult getResult(String correlationId) {
        return results.get(correlationId);
    }

    public Map<String, CapturedResult> getAllResults() {
        return Map.copyOf(results);
    }

    public int resultCount() {
        return results.size();
    }
}
