package ru.tcb.sal.commands.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSerializerTest {

    private SessionSerializer serializer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        serializer = new SessionSerializer();
        mapper = serializer.mapper();
    }

    @Test
    void roundTrip_preservesFields() {
        ObjectNode session = mapper.createObjectNode();
        session.put("SessionId", "sess-42");
        session.put("OperationId", 12345L);
        session.put("AuthId", 99L);
        session.put("CustomField", "custom-value");

        String encoded = serializer.serialize(session);
        ObjectNode decoded = serializer.deserialize(encoded);

        assertThat(decoded.get("SessionId").asText()).isEqualTo("sess-42");
        assertThat(decoded.get("OperationId").asLong()).isEqualTo(12345L);
        assertThat(decoded.get("AuthId").asLong()).isEqualTo(99L);
        assertThat(decoded.get("CustomField").asText()).isEqualTo("custom-value");
    }

    @Test
    void serialize_producesBase64() {
        ObjectNode session = mapper.createObjectNode();
        session.put("SessionId", "x");

        String encoded = serializer.serialize(session);

        // Base64 алфавит: A-Z, a-z, 0-9, +, /, =
        assertThat(encoded).matches("^[A-Za-z0-9+/=]+$");
    }

    @Test
    void deserialize_invalidBase64_returnsEmptyObject() {
        ObjectNode decoded = serializer.deserialize("not-a-valid-base64-$$$");
        assertThat(decoded).isNotNull();
        assertThat(decoded.isEmpty()).isTrue();
    }

    @Test
    void deserialize_corruptedDeflate_returnsEmptyObject() {
        // Валидный Base64, но не Deflate-поток
        String fakeBase64 = java.util.Base64.getEncoder().encodeToString("not deflate".getBytes());

        ObjectNode decoded = serializer.deserialize(fakeBase64);
        assertThat(decoded).isNotNull();
        assertThat(decoded.isEmpty()).isTrue();
    }

    @Test
    void serialize_emptyObject_roundTrips() {
        ObjectNode empty = mapper.createObjectNode();
        String encoded = serializer.serialize(empty);
        ObjectNode decoded = serializer.deserialize(encoded);
        assertThat(decoded.isEmpty()).isTrue();
    }
}
