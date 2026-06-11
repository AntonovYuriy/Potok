package io.potok.preview;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * What a dry run "right now" would do. Steps come in execution order; mode is
 * executed (real read-only call), simulated (side effect described, not
 * performed), skipped (condition/dependency/time limit) or failed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreviewResult(TriggerPreview trigger, List<StepPreview> steps) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TriggerPreview(
            String kind,
            String note,
            @JsonProperty("human_summary") String humanSummary,
            String detail) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepPreview(
            String name,
            String kind,
            String mode,
            @JsonProperty("human_summary") String humanSummary,
            String detail,
            @JsonProperty("rendered_output") Map<String, Object> renderedOutput) {

        public static StepPreview executed(String name, String kind, String summary,
                                           String detail, Map<String, Object> output) {
            return new StepPreview(name, kind, "executed", summary, detail, output);
        }

        public static StepPreview simulated(String name, String kind, String summary,
                                            String detail, Map<String, Object> output) {
            return new StepPreview(name, kind, "simulated", summary, detail, output);
        }

        public static StepPreview skipped(String name, String kind, String summary, String detail) {
            return new StepPreview(name, kind, "skipped", summary, detail, null);
        }

        public static StepPreview failed(String name, String kind, String summary, String detail) {
            return new StepPreview(name, kind, "failed", summary, detail, null);
        }
    }
}
