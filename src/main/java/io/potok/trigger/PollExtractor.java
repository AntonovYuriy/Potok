package io.potok.trigger;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import io.potok.definition.WorkflowDefinition;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the value a poller cares about out of the response:
 * jsonpath over the (JSON) body, or a css selector's first-match text over HTML.
 * Missing path/element → null (a real signal: "the thing disappeared").
 * With {@code number: true} the result is coerced to a number — price tags
 * like "249,99 zł" or "$1,299.00" become 249.99 / 1299.00 so conditions can
 * compare them; text without digits → null.
 */
public final class PollExtractor {

    private static final Configuration JSONPATH = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS) // missing path -> null, not an exception
            .build();

    private static final Pattern NUMBER_TOKEN = Pattern.compile("-?[\\d\\s\\u00A0.,]*\\d");

    private PollExtractor() {
    }

    /** @param body parsed JSON (Map/List/scalar) or raw String as produced by the http action */
    public static Object extract(WorkflowDefinition.Extract extract, Object body, String rawBody) {
        if (extract == null) {
            return null;
        }
        Object value;
        if (extract.jsonpath() != null) {
            value = body == null ? null : JsonPath.using(JSONPATH).parse(body).read(extract.jsonpath());
        } else {
            String html = rawBody != null ? rawBody : (body instanceof String s ? s : null);
            if (html == null) {
                return null;
            }
            Element element = Jsoup.parse(html).selectFirst(extract.css());
            value = element == null ? null : element.text();
        }
        return extract.asNumber() ? parseNumber(value) : value;
    }

    /**
     * First number in the text, currency/locale noise stripped: spaces and
     * NBSP removed; a comma is a decimal separator unless a dot also appears
     * (then commas are thousands separators). Numbers pass through unchanged.
     */
    static BigDecimal parseNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        Matcher matcher = NUMBER_TOKEN.matcher(String.valueOf(value));
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group().replaceAll("[\\s\\u00A0]", "");
        if (token.contains(",") && token.contains(".")) {
            token = token.replace(",", "");          // 1,299.00 -> 1299.00
        } else if (token.contains(",")) {
            token = token.replace(',', '.');          // 249,99 -> 249.99
        }
        try {
            return new BigDecimal(token);
        } catch (NumberFormatException e) {
            return null; // e.g. "1.2.3" — not a number after all
        }
    }
}
