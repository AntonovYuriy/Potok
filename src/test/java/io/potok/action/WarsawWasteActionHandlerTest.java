package io.potok.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WarsawWasteActionHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WarsawWasteActionHandler handler =
            new WarsawWasteActionHandler(mapper, Clock.systemUTC());

    // real response shape from warszawa19115.pl (captured 2026-06-10)
    private static final String API_JSON = """
            [{"adres":"ŚWIETLICKA 29","dzielnicy":"Rembertów","harmonogramy":[],
              "harmonogramyN":[],
              "harmonogramyZ":[
                {"data":"2026-06-11","frakcja":{"id_frakcja":"OP","nazwa":"opakowania z papieru i tektury"}},
                {"data":"2026-06-11","frakcja":{"id_frakcja":"BK","nazwa":"bio"}},
                {"data":"2026-06-18","frakcja":{"id_frakcja":"OS","nazwa":"opakowania ze szkła"}},
                {"data":"1900-01-01","frakcja":{"id_frakcja":"WG","nazwa":"wielkogabarytowe"}},
                {"data":"2026-06-22","frakcja":{"id_frakcja":"XX","nazwa":"frakcja nieznana"}}
              ]}]
            """;

    private JsonNode json() throws Exception {
        return mapper.readTree(API_JSON);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsTomorrowFractionsToPolishLabels() throws Exception {
        Map<String, Object> out = handler.buildOutput(json(), LocalDate.parse("2026-06-10"));

        assertThat(out.get("tomorrow_date")).isEqualTo("2026-06-11");
        assertThat((List<String>) out.get("tomorrow")).containsExactly("Papier", "Bio");
        assertThat(out.get("tomorrow_count")).isEqualTo(2);
        assertThat(out.get("summary")).isEqualTo("Papier, Bio");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyTomorrowWhenNothingScheduled() throws Exception {
        Map<String, Object> out = handler.buildOutput(json(), LocalDate.parse("2026-06-12"));

        assertThat(out.get("tomorrow_count")).isEqualTo(0);
        assertThat((List<String>) out.get("tomorrow")).isEmpty();
        assertThat(out.get("summary")).isEqualTo("");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsUnscheduledPlaceholderAndKeepsUnknownCodes() throws Exception {
        Map<String, Object> out = handler.buildOutput(json(), LocalDate.parse("2026-06-10"));
        List<Map<String, Object>> upcoming = (List<Map<String, Object>>) out.get("upcoming");

        // 1900-01-01 dropped; unknown code falls back to the API's own name
        assertThat(upcoming).hasSize(4);
        assertThat(upcoming).extracting(u -> u.get("code")).doesNotContain("WG");
        assertThat(upcoming).extracting(u -> u.get("fraction")).contains("frakcja nieznana");
        // sorted by date
        assertThat(upcoming).extracting(u -> (String) u.get("date")).isSorted();
    }

    @Test
    void unknownFractionLabelFallsBackThenToCode() throws Exception {
        JsonNode node = mapper.readTree("""
                [{"harmonogramyZ":[{"data":"2026-06-11","frakcja":{"id_frakcja":"YY"}}]}]
                """);
        Map<String, Object> out = handler.buildOutput(node, LocalDate.parse("2026-06-10"));
        assertThat(out.get("summary")).isEqualTo("YY");
    }

    @Test
    void missingAddressPointIdFailsGracefully() {
        StepResult result = handler.execute(new StepContext(
                UUID.randomUUID(), "wf", "schedule", Map.of(), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("address_point_id");
    }
}
