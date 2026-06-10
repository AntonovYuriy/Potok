package io.potok.trigger;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import io.potok.definition.WorkflowDefinition;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * Pulls the value a poller cares about out of the response:
 * jsonpath over the (JSON) body, or a css selector's first-match text over HTML.
 * Missing path/element → null (a real signal: "the thing disappeared").
 */
public final class PollExtractor {

    private static final Configuration JSONPATH = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS) // missing path -> null, not an exception
            .build();

    private PollExtractor() {
    }

    /** @param body parsed JSON (Map/List/scalar) or raw String as produced by the http action */
    public static Object extract(WorkflowDefinition.Extract extract, Object body, String rawBody) {
        if (extract == null) {
            return null;
        }
        if (extract.jsonpath() != null) {
            if (body == null) {
                return null;
            }
            return JsonPath.using(JSONPATH).parse(body).read(extract.jsonpath());
        }
        String html = rawBody != null ? rawBody : (body instanceof String s ? s : null);
        if (html == null) {
            return null;
        }
        Element element = Jsoup.parse(html).selectFirst(extract.css());
        return element == null ? null : element.text();
    }
}
