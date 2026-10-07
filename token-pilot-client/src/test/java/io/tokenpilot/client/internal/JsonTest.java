package io.tokenpilot.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writtenStringsParseBackUnchanged() {
        String tricky = "요약 \"q\" \\ / \n\r\t\b\f \u0001 \u2028 😀";
        StringBuilder out = new StringBuilder();
        Json.writeString(out, tricky);

        assertThat(Json.parse(out.toString())).isEqualTo(tricky);
    }

    @Test
    void parsesTheServerResponseShape() {
        Object parsed = Json.parse("""
            {"success":true,"data":{"items":[{"index":0,"status":"CREATED","usageEventId":null,
             "retryable":false,"cost":1.5e-3}],"note":"\\u00e9\\ud83d\\ude00"}}
            """);

        Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) parsed).get("data");
        Map<?, ?> item = (Map<?, ?>) ((List<?>) data.get("items")).get(0);
        assertThat(item.get("status")).isEqualTo("CREATED");
        assertThat(item.get("retryable")).isEqualTo(false);
        assertThat(item.containsKey("usageEventId")).isTrue();
        assertThat((BigDecimal) item.get("cost")).isEqualByComparingTo("0.0015");
        assertThat(data.get("note")).isEqualTo("é😀");
    }

    @Test
    void malformedInputIsRejected() {
        assertThatThrownBy(() -> Json.parse("{\"a\":1,}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("<html>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("{\"a\":\"unterminated}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("{} x")).isInstanceOf(IllegalArgumentException.class);
    }
}
