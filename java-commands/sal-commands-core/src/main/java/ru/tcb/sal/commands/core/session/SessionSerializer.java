package ru.tcb.sal.commands.core.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Упаковывает/распаковывает сессию в .NET-совместимом формате:
 * JSON (PascalCase) → raw Deflate (без gzip wrapper) → Base64.
 *
 * <p>Локальный {@link ObjectMapper} с теми же настройками, что
 * у {@code RecordedMessageConverter} (PascalCase, JavaTime, ALWAYS-null) —
 * это гарантирует, что любая сессия, попадающая в {@code AdditionalData["Session"]},
 * читается .NET-стороной без рассинхрона.
 *
 * <p>При ошибке распаковки возвращает пустой {@link ObjectNode} и логирует
 * warn — зеркало поведения .NET {@code SessionSerializer.Deserialize},
 * который в случае corrupted session возвращал fake empty session.
 */
public class SessionSerializer {

    private static final Logger log = LoggerFactory.getLogger(SessionSerializer.class);

    private final ObjectMapper mapper;

    public SessionSerializer() {
        this.mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public String serialize(ObjectNode session) {
        try {
            byte[] json = mapper.writeValueAsBytes(session);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(bos)) {
                deflate.write(json);
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize session", e);
        }
    }

    public ObjectNode deserialize(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(base64);
            try (InflaterInputStream inflate = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                JsonNode node = mapper.readTree(inflate);
                if (node instanceof ObjectNode obj) {
                    return obj;
                }
                log.warn("Session JSON root is not an object; returning empty");
                return mapper.createObjectNode();
            }
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to deserialize session ('{}' bytes), returning empty: {}",
                base64.length(), e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /** Доступ к локальному mapper'у — для построения {@link ObjectNode}-сессий в тестах. */
    public ObjectMapper mapper() {
        return mapper;
    }
}
