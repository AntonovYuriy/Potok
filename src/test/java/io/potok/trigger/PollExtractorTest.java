package io.potok.trigger;

import io.potok.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PollExtractorTest {

    private static WorkflowDefinition.Extract jsonpath(String path) {
        return new WorkflowDefinition.Extract(path, null);
    }

    private static WorkflowDefinition.Extract css(String selector) {
        return new WorkflowDefinition.Extract(null, selector);
    }

    @Test
    void jsonpathExtractsScalar() {
        Map<String, Object> body = Map.of("product", Map.of("price", 99.5, "name", "thing"));
        assertThat(PollExtractor.extract(jsonpath("$.product.price"), body, null))
                .isEqualTo(99.5);
    }

    @Test
    void jsonpathExtractsFromArray() {
        Map<String, Object> body = Map.of("items", List.of(
                Map.of("price", 10), Map.of("price", 20)));
        assertThat(PollExtractor.extract(jsonpath("$.items[1].price"), body, null))
                .isEqualTo(20);
    }

    @Test
    void jsonpathMissingPathIsNull() {
        assertThat(PollExtractor.extract(jsonpath("$.nope.deeper"), Map.of("a", 1), null))
                .isNull();
    }

    @Test
    void cssExtractsFirstMatchText() {
        String html = "<html><body><div><span class='price'>149,99 zł</span>"
                + "<span class='price'>second</span></div></body></html>";
        assertThat(PollExtractor.extract(css("span.price"), html, html))
                .isEqualTo("149,99 zł");
    }

    @Test
    void cssMissingElementIsNull() {
        assertThat(PollExtractor.extract(css("span.absent"), "<p>x</p>", "<p>x</p>"))
                .isNull();
    }

    @Test
    void cssOverNonHtmlBodyIsNullSafe() {
        assertThat(PollExtractor.extract(css("span"), Map.of("json", true), null))
                .isNull();
    }
}
