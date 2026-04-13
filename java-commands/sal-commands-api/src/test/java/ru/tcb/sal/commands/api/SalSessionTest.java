package ru.tcb.sal.commands.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SalSessionTest {

    @Test
    void empty_hasDefaults() {
        SalSession s = SalSession.empty();
        assertThat(s.getSessionId()).isEmpty();
        assertThat(s.getOperationId()).isEqualTo(0L);
        assertThat(s.getAuthId()).isNull();
        assertThat(s.getHierarchyId()).isNull();
    }

    @Test
    void addOrUpdate_tracked() {
        SalSession s = SalSession.empty();
        s.addOrUpdate("SessionId", "sess-42");
        s.addOrUpdate("CustomKey", 123);

        assertThat(s.getSessionId()).isEqualTo("sess-42");
        assertThat(s.getSafeValue("CustomKey", 0)).isEqualTo(123);
        assertThat(s.isChanged()).isTrue();
    }

    @Test
    void remove_tracked() {
        SalSession s = SalSession.empty();
        s.addOrUpdate("Key", "val");
        s.getChangedData(); // clear
        s.remove("Key");
        assertThat(s.isChanged()).isTrue();

        ObjectNode delta = s.getChangedData();
        assertThat(delta.has("removeValues")).isTrue();
    }

    @Test
    void getChangedData_clearsTracking() {
        SalSession s = SalSession.empty();
        s.addOrUpdate("A", 1);
        assertThat(s.isChanged()).isTrue();
        s.getChangedData();
        assertThat(s.isChanged()).isFalse();
    }

    @Test
    void snapshot_isDeepCopy() {
        SalSession s = SalSession.empty();
        s.addOrUpdate("Key", "val");
        ObjectNode snap = s.snapshot();
        s.addOrUpdate("Key", "changed");
        assertThat(snap.get("Key").asText()).isEqualTo("val");
    }

    @Test
    void tryGetValue_missingKey() {
        SalSession s = SalSession.empty();
        assertThat(s.tryGetValue("missing", String.class)).isEmpty();
    }
}
