package io.potok.api;

import io.potok.preview.PreviewResult;
import io.potok.preview.PreviewService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dry run of a workflow definition: validates like create, executes once
 * synchronously in preview mode, persists nothing. Auth: any active token
 * (same filter as the rest of /api).
 */
@RestController
public class PreviewController {

    private final PreviewService previewService;

    public PreviewController(PreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping(value = "/api/preview",
            consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml", "text/yaml"})
    public PreviewResult preview(@RequestBody String yamlSource) {
        return previewService.preview(yamlSource);
    }
}
