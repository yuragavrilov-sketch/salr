package ru.tcb.sal.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    private final CommandSenderService sender;
    private final ResultListenerService listener;
    private final String adapterName;

    public CommandController(
            CommandSenderService sender,
            ResultListenerService listener,
            @Value("${demo.adapter-name}") String adapterName) {
        this.sender = sender;
        this.listener = listener;
        this.adapterName = adapterName;
    }

    public record SendRequest(
        String commandType,
        String assemblyQualified,
        String payloadJson
    ) {}

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendRequest req) {
        log.info("[API] POST /api/send: commandType='{}' payloadSize={}",
            req.commandType(),
            req.payloadJson() != null ? req.payloadJson().length() : 0);

        try {
            String correlationId = sender.sendCommand(
                req.commandType(), req.assemblyQualified(), req.payloadJson());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "sent");
            response.put("correlationId", correlationId);
            response.put("exchange", "CommandExchange");
            response.put("routingKey", req.commandType());

            log.info("[API] Command sent: correlationId={}", correlationId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[API] Send failed: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/result/{correlationId}")
    public ResponseEntity<Object> getResult(@PathVariable String correlationId) {
        ResultListenerService.CapturedResult result = listener.getResult(correlationId);
        if (result == null) {
            log.trace("[API] GET /api/result/{} -> waiting", correlationId.substring(0, 8));
            return ResponseEntity.ok(Map.of(
                "status", "waiting",
                "correlationId", correlationId));
        }
        log.info("[API] GET /api/result/{} -> received from '{}'",
            correlationId.substring(0, 8), result.exchange());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "received");
        response.put("correlationId", result.correlationId());
        response.put("exchange", result.exchange());
        response.put("contentType", result.contentType());
        response.put("receivedAt", result.receivedAt().toString());
        response.put("body", result.bodyJson());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/results")
    public ResponseEntity<Object> allResults() {
        log.debug("[API] GET /api/results -> count={}", listener.resultCount());
        return ResponseEntity.ok(Map.of(
            "count", listener.resultCount(),
            "results", listener.getAllResults()));
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok(Map.of("adapterName", adapterName));
    }
}
