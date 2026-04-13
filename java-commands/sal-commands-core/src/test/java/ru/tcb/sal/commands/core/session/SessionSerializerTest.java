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

    @Test
    void deserialize_realDotNetSession_parsesFields() {
        // Real session from production .NET SAL (captured via wire-probe, msg-002)
        // This is from Additional-Data.Session in a CommandCompletedEvent
        String realSession = "bZTbsqpGEIYfIG/BvmQpDAyndYeKgqggKoqpXAwwHJTzUUilKo8edO9U5SJzNfX3V" +
            "/13d03P37/9SaziukjQcEApJr6JAg2oiIkvIsWVF/vEN6BZlhW+iHqoG5xOhCk7U/g2szBKZpo5KYCeMxD" +
            "OOXEOJOYTW+dVjyof++/bRPDMnGXnLD9x3ASccBWjJB5xdR6Kt+tyMk+WqMZT8FLjaoW72MPaZE/IpvZL1" +
            "Ir/8bJwmjdYK2Tfr3Bd/yQkaQ4Ycc7Sb684CxNs4bLFdXOakDjPiO+mavEX4eVZEPsoa4jv34kzK9Hsz26" +
            "1FfHHF2EUuELNByeoaShUByjkeXmbNRR+NbjKUEJ5Ffbj5tPTJ/WnZoblaQBo+geAIvzBsFNYbptI+4xzO" +
            "owE+C9Cjaf8lRcNb/1t/h/LDyoAKIg0S0/6NMvqgwFGmKr8IpRX87HyXCmQIHRnDIf4GfR8ZoY8gGYuL/" +
            "GA5ZGLufeYWIgB5t1ghn3MzSDPejOJ8fDMFVgPBhxgXISmbJG93rr8vl/vhMOKTs1mv3GeCltquH9GOz3R3" +
            "PReOM/FkdHC0Bn5hvTEYpODzDvkW6VjRKq+BiJJBV3vnTa2tWVoKT2QZqTLsVQ1530RmoHy9JgydKtoj1R" +
            "7bdn03rL8pFcuduLx5FNd+mvzbuVKa3Tna1ZFCyenektahhBjJtGfr9Ful8O+XQSZcQ4X5sFnu50eXbCWS" +
            "Jr+4HSTbfhEJ/tgMP2VujXXpaVAaSFrMAj1xxYKrHGFZFoLi5tXpq1+gkJWyratL8WOMxAyU6ErupBtS1d" +
            "cUYzcr29RZML7cUEtBtkdBy63qBeOclmEEjhz/gXYfrpxZDUIXbjohd5cUUU7kFlk8vflOYisYl/AvRpSg" +
            "VrtRoG7PcHLw+EL2jxDbQrJPo5qV/a701pjrJ2yBao1xkrVuLtyoxztk6u57uhuT1dSKuHw0Fy5NX0zGcY" +
            "Nt4iY1f4mcuG1dq7kq7AErX5Y8AESew8NUKdYMbbUZhvaVpItH/XZF5yKvQq7OFA3ba3oTuzA1GmGYtlaF" +
            "W3HBzrpnAvX3cO7LbTMcZPTqZ7fS7i5Jfax0KU+7o9GSz9355cV4/GsOVihu4J/sjowAakJxtMRb3K2HRw" +
            "ru6wjQ2Hw4rIUL3o2Nq5/w2BV2RGPF9s817NI14VluirXKuCbVZSJ5HElaAikpCNoldGfRoahD8mx21dwc1" +
            "GDsSZ5OR+4pkiubbZLmLg6K+zWR+fuZJxW6WFH16xrItgPDzs2yOltrhTcmq/6fn26TulHy+Aw5Jq6rW7V" +
            "FtW+hoqTumtx6LQH77C7kdzmdsUHLeIWKbbByzk0w3ivTVkaDN0VnkJRFqXYnx+5E+ZPjV1c0QurEHemu" +
            "Jk27VyhrEbev0vM8DzgwbTC1/XerHLv/Uu85V8/xF//AA==";

        ObjectNode decoded = serializer.deserialize(realSession);

        // Real .NET session should have fields — at minimum it should parse without error
        assertThat(decoded).isNotNull();
        // The session content depends on the actual .NET application,
        // but it should not be empty (real sessions have data)
        assertThat(decoded.isEmpty()).isFalse();
    }
}
