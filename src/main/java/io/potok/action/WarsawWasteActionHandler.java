package io.potok.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches the Warsaw waste collection schedule (warszawa19115.pl) and reports
 * what is collected on the requested day (Europe/Warsaw). Doubles as the
 * reference implementation of a real-world custom action on the SPI.
 *
 * with: address_point_id (required — get yours from the address autocomplete
 * on warszawa19115.pl), when: "today"|"tomorrow" (default today),
 * base_url (optional, for tests).
 *
 * Output: {date, when, has_collection, collected: [labels],
 * summary — one human-ready Russian line ("Сегодня вывоз: Papier, Bio"),
 * upcoming: [{date, code, fraction}...]} — the endpoint only ever returns the
 * NEXT date per fraction, so "upcoming" is one entry per fraction.
 */
@Component
public class WarsawWasteActionHandler implements ActionHandler {

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final String DEFAULT_BASE_URL = "https://warszawa19115.pl";
    private static final String PORTLET =
            "portalCKMjunkschedules_WAR_portalCKMjunkschedulesportlet_INSTANCE_o5AIb2mimbRJ";
    /** "1900-01-01" means the city has not scheduled this fraction yet. */
    private static final String UNSCHEDULED = "1900-01-01";

    static final Map<String, String> FRACTION_LABELS = Map.of(
            "OP", "Papier",
            "OS", "Szkło",
            "ZM", "Zmieszane",
            "MT", "Metale i tworzywa sztuczne",
            "BK", "Bio",
            "BG", "Bio restauracyjne",
            "OZ", "Zielone",
            "WG", "Wielkogabarytowe");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public WarsawWasteActionHandler(ObjectMapper objectMapper) {
        this(objectMapper, Clock.system(WARSAW));
    }

    WarsawWasteActionHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "warsaw_waste";
    }

    @Override
    public StepResult execute(StepContext ctx) {
        String addressPointId;
        try {
            addressPointId = ctx.requireString("address_point_id");
        } catch (IllegalArgumentException e) {
            return StepResult.fail(e.getMessage());
        }
        String when = ctx.optionalString("when", "today");
        if (!when.equals("today") && !when.equals("tomorrow")) {
            return StepResult.fail("'when' must be \"today\" or \"tomorrow\", got '" + when + "'");
        }
        String baseUrl = ctx.optionalString("base_url", DEFAULT_BASE_URL);

        String url = baseUrl + "/harmonogramy-wywozu-odpadow"
                + "?p_p_id=" + PORTLET
                + "&p_p_lifecycle=2&p_p_state=normal&p_p_mode=view"
                + "&p_p_resource_id=ajaxResource&p_p_cacheability=cacheLevelPage"
                + "&_" + PORTLET + "_addressPointId="
                + URLEncoder.encode(addressPointId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*")
                .GET()
                .build();

        String body;
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return StepResult.fail("warszawa19115.pl returned status " + response.statusCode());
            }
            body = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepResult.fail("warsaw_waste request interrupted");
        } catch (Exception e) {
            return StepResult.fail("warsaw_waste request failed: " + io.potok.common.Errors.describe(e));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            // content-type is text/html even for good responses; non-JSON body = error page
            return StepResult.fail("warszawa19115.pl response is not JSON (first 150 chars): "
                    + body.substring(0, Math.min(150, body.length())));
        }

        return StepResult.ok(buildOutput(root, LocalDate.now(clock), when));
    }

    /** Pure transform, unit-testable: API JSON -> step output for the given "today" and when-mode. */
    Map<String, Object> buildOutput(JsonNode root, LocalDate today, String when) {
        LocalDate target = when.equals("tomorrow") ? today.plusDays(1) : today;
        List<Map<String, Object>> upcoming = new ArrayList<>();
        List<String> collected = new ArrayList<>();

        Iterable<JsonNode> blocks = root.isArray() ? root : List.of(root);
        for (JsonNode block : blocks) {
            // Z = residential ("zamieszkana") schedule, same field the official widget reads
            for (JsonNode item : block.path("harmonogramyZ")) {
                String date = item.path("data").asText("");
                if (date.isEmpty() || UNSCHEDULED.equals(date)) {
                    continue;
                }
                String code = item.path("frakcja").path("id_frakcja").asText("");
                String label = FRACTION_LABELS.getOrDefault(code,
                        item.path("frakcja").path("nazwa").asText(code));
                upcoming.add(Map.of("date", date, "code", code, "fraction", label));
                if (date.equals(target.toString())) {
                    collected.add(label);
                }
            }
        }
        upcoming.sort((a, b) -> ((String) a.get("date")).compareTo((String) b.get("date")));
        boolean hasCollection = !collected.isEmpty();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("date", target.toString());
        output.put("when", when);
        output.put("has_collection", hasCollection);
        output.put("collected", collected);
        output.put("summary", hasCollection
                ? (when.equals("tomorrow") ? "Завтра вывоз: " : "Сегодня вывоз: ") + String.join(", ", collected)
                : "");
        output.put("upcoming", upcoming);
        return output;
    }
}
