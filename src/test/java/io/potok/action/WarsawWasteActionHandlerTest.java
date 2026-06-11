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
            new WarsawWasteActionHandler(mapper, Clock.systemUTC(), new io.potok.common.UrlGuard(true));

    // real response shape from warszawa19115.pl
    private static final String API_JSON = """
            [{"adres":"ŚWIETLICKA 29","dzielnicy":"Rembertów","harmonogramy":[],
              "harmonogramyN":[],
              "harmonogramyZ":[
                {"data":"2026-06-11","frakcja":{"id_frakcja":"OP","nazwa":"opakowania z papieru i tektury"}},
                {"data":"2026-06-11","frakcja":{"id_frakcja":"BK","nazwa":"bio"}},
                {"data":"2026-06-12","frakcja":{"id_frakcja":"ZM","nazwa":"odpady zmieszane"}},
                {"data":"2026-06-18","frakcja":{"id_frakcja":"OS","nazwa":"opakowania ze szkła"}},
                {"data":"1900-01-01","frakcja":{"id_frakcja":"WG","nazwa":"wielkogabarytowe"}}
              ]}]
            """;

    private JsonNode json() throws Exception {
        return mapper.readTree(API_JSON);
    }

    @Test
    @SuppressWarnings("unchecked")
    void todayMergesAllFractionsIntoOneRussianLine() throws Exception {
        Map<String, Object> out = handler.buildOutput(json(), LocalDate.parse("2026-06-11"), "today");

        assertThat(out.get("has_collection")).isEqualTo(true);
        assertThat(out.get("date")).isEqualTo("2026-06-11");
        assertThat(out.get("when")).isEqualTo("today");
        assertThat((List<String>) out.get("collected")).containsExactly("Papier", "Bio");
        assertThat(out.get("summary")).isEqualTo("Сегодня вывоз: Papier, Bio");
    }

    @Test
    void tomorrowPrefixAndDate() throws Exception {
        Map<String, Object> out = handler.buildOutput(json(), LocalDate.parse("2026-06-11"), "tomorrow");

        assertThat(out.get("date")).isEqualTo("2026-06-12");
        assertThat(out.get("has_collection")).isEqualTo(true);
        assertThat(out.get("summary")).isEqualTo("Завтра вывоз: Zmieszane");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noCollectionThatDate() throws Exception {
        Map<String, Object> out = handler.buildOutput(json(), LocalDate.parse("2026-06-14"), "today");

        assertThat(out.get("has_collection")).isEqualTo(false);
        assertThat((List<String>) out.get("collected")).isEmpty();
        assertThat(out.get("summary")).isEqualTo("");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mappingFallsBackToApiNameThenCode() throws Exception {
        JsonNode node = mapper.readTree("""
                [{"harmonogramyZ":[
                  {"data":"2026-06-11","frakcja":{"id_frakcja":"XX","nazwa":"frakcja nieznana"}},
                  {"data":"2026-06-11","frakcja":{"id_frakcja":"YY"}}
                ]}]
                """);
        Map<String, Object> out = handler.buildOutput(node, LocalDate.parse("2026-06-11"), "today");
        assertThat(out.get("summary")).isEqualTo("Сегодня вывоз: frakcja nieznana, YY");
    }

    @Test
    void skipsUnscheduledPlaceholderInUpcoming() throws Exception {
        Map<String, Object> out = handler.buildOutput(json(), LocalDate.parse("2026-06-11"), "today");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upcoming = (List<Map<String, Object>>) out.get("upcoming");
        assertThat(upcoming).extracting(u -> u.get("code")).doesNotContain("WG");
        assertThat(upcoming).extracting(u -> (String) u.get("date")).isSorted();
    }

    @Test
    void rejectsUnknownWhen() {
        StepResult result = handler.execute(new StepContext(
                UUID.randomUUID(), "wf", "schedule",
                Map.of("address_point_id", "1", "when", "yesterday"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'when'").contains("yesterday");
    }

    @Test
    void missingAddressPointIdFailsGracefully() {
        StepResult result = handler.execute(new StepContext(
                UUID.randomUUID(), "wf", "schedule", Map.of(), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("address_point_id");
    }
}
