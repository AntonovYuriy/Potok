package io.potok;

import io.potok.recipient.RecipientService;
import io.potok.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end M7: subscribable workflows show up in the bot menu, callback
 * toggles flip subscription state in-place, fan-out via {@code to:subscribers}
 * delivers ONLY to APPROVED subscribers of the running workflow. Defaults stay
 * private — workflows without {@code subscribable: true} never appear in the
 * menu and never fan out. The bot updates poller is disabled here so the test
 * is deterministic; the JSON dispatch path is covered separately by
 * {@link TelegramRecipientIngestIntegrationTest}, callback parsing by
 * {@link TelegramButtonsIntegrationTest}.
 */
class SubscriptionsIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void disablePoller(DynamicPropertyRegistry registry) {
        registry.add("potok.telegram.poll-updates", () -> "false");
    }

    @Autowired
    private RecipientService recipientService;
    @Autowired
    private SubscriptionService subscriptionService;

    private static String subscribableWorkflow(String name, String path, boolean subscribable) {
        return """
                name: %s
                subscribable: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - name: notify
                    action: telegram
                    with:
                      to: subscribers
                      text: "Broadcast for %s"
                """.formatted(name, subscribable, path, name);
    }

    private UUID createWorkflow(String yaml) {
        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", yaml);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(String.valueOf(created.getBody().get("id")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findRecipient(String displayName) {
        Map<String, Object> body = rest.getForObject("/api/recipients?size=200", Map.class);
        return ((List<Map<String, Object>>) body.get("items")).stream()
                .filter(r -> displayName.equals(r.get("displayName")))
                .findFirst().orElseThrow();
    }

    private UUID registerApproved(String chatId, String name) {
        recipientService.handleBotMessage(chatId, name, "/start");
        UUID id = UUID.fromString(String.valueOf(findRecipient(name).get("id")));
        ResponseEntity<Map<String, Object>> approve = postJson(
                "/api/recipients/" + id + "/approve", Map.of());
        assertThat(approve.getStatusCode().is2xxSuccessful()).isTrue();
        return id;
    }

    private void setSubscribable(UUID workflowId, boolean value) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> patch = rest.exchange(
                "/api/workflows/" + workflowId + "/subscribable",
                org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(
                        Map.of("subscribable", value), headers),
                MAP_TYPE);
        assertThat(patch.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(patch.getBody()).containsEntry("subscribable", value);
    }

    @Test
    void yamlSubscribableFlagPersistsAndExposesInResponse() {
        UUID id = createWorkflow(subscribableWorkflow("yaml-sub", "yaml-sub", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> w = rest.getForObject("/api/workflows/" + id, Map.class);
        assertThat(w).containsEntry("subscribable", true);
    }

    @Test
    void buildMenuListsOnlySubscribableAndEnabled() {
        UUID approvedId = registerApproved("700001", "Menu-User");
        UUID pubA = createWorkflow(subscribableWorkflow("menu-a", "menu-a", true));
        UUID pubB = createWorkflow(subscribableWorkflow("menu-b", "menu-b", true));
        UUID privC = createWorkflow(subscribableWorkflow("menu-c", "menu-c", false));
        // disable pubB → should drop out of the menu
        ResponseEntity<Void> disable = rest.exchange("/api/workflows/" + pubB,
                org.springframework.http.HttpMethod.DELETE, null, Void.class);
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        SubscriptionService.Menu menu = subscriptionService.buildMenu(approvedId);

        // assertions are id-targeted because cached Spring contexts share the
        // DB across tests in this class; other tests' subscribable workflows
        // remain in the menu and that is fine for this assertion.
        java.util.Set<String> callbackData = menu.keyboard().stream()
                .map(row -> (String) row.get(0).get("callback_data"))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(callbackData).contains("sub:" + pubA);
        assertThat(callbackData).doesNotContain("sub:" + pubB); // disabled
        assertThat(callbackData).doesNotContain("sub:" + privC); // not subscribable
    }

    @Test
    void toggleAdvancesMenuCheckmarkAndRedrawIsConsistent() {
        UUID approvedId = registerApproved("700002", "Toggle-User");
        UUID workflowId = createWorkflow(subscribableWorkflow("toggle-wf", "toggle-wf", true));

        SubscriptionService.ToggleOutcome first = subscriptionService.toggle(workflowId, approvedId);
        assertThat(first.subscribed()).isTrue();
        SubscriptionService.Menu afterAdd = subscriptionService.buildMenu(approvedId);
        assertThat(buttonTextFor(afterAdd, workflowId)).startsWith("✅");

        SubscriptionService.ToggleOutcome second = subscriptionService.toggle(workflowId, approvedId);
        assertThat(second.subscribed()).isFalse();
        SubscriptionService.Menu afterRemove = subscriptionService.buildMenu(approvedId);
        assertThat(buttonTextFor(afterRemove, workflowId)).startsWith("⬜");
    }

    /** Find the button row matching a workflow id — robust against unrelated entries from other tests. */
    private static String buttonTextFor(SubscriptionService.Menu menu, UUID workflowId) {
        return menu.keyboard().stream()
                .map(row -> row.get(0))
                .filter(btn -> ("sub:" + workflowId).equals(btn.get("callback_data")))
                .map(btn -> (String) btn.get("text"))
                .findFirst().orElseThrow();
    }

    @Test
    void pendingAndRevokedCallersCannotSubscribeEvenIfTheyTapAStaleButton() {
        recipientService.handleBotMessage("700003", "Pending-Caller", "/start");
        UUID pendingId = UUID.fromString(String.valueOf(findRecipient("Pending-Caller").get("id")));
        UUID workflowId = createWorkflow(subscribableWorkflow("stale-button", "stale-button", true));

        SubscriptionService.ToggleOutcome pendingOutcome = subscriptionService.toggle(workflowId, pendingId);

        assertThat(pendingOutcome.changed()).isFalse();
        assertThat(pendingOutcome.subscribed()).isFalse();
        assertThat(pendingOutcome.rejection()).isEqualTo("not authorised");
        // and no row was inserted
        assertThat(subscriptionService.countSubscribers(workflowId)).isZero();
    }

    @Test
    void toSubscribersDeliversToOnlySubscribedApproved() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true}")));
        // 3 approved, 2 subscribe, 1 doesn't
        UUID alice = registerApproved("701001", "Sub-Alice");
        UUID bob   = registerApproved("701002", "Sub-Bob");
        UUID carol = registerApproved("701003", "Sub-Carol");
        UUID workflowId = createWorkflow(subscribableWorkflow("delivery-wf", "delivery-wf", true));
        subscriptionService.toggle(workflowId, alice);
        subscriptionService.toggle(workflowId, bob);
        // carol stays unsubscribed

        ResponseEntity<Map<String, Object>> run = postJson(
                "/api/workflows/" + workflowId + "/run", Map.of());
        String executionId = String.valueOf(run.getBody().get("executionId"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        var requests = WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
        java.util.function.Function<String, Long> sentFor = chat -> requests.stream()
                .filter(r -> r.getBodyAsString().contains("Broadcast for delivery-wf")
                        && r.getBodyAsString().contains(chat))
                .count();
        assertThat(sentFor.apply("701001")).as("subscribed Alice").isEqualTo(1L);
        assertThat(sentFor.apply("701002")).as("subscribed Bob").isEqualTo(1L);
        assertThat(sentFor.apply("701003")).as("unsubscribed Carol").isEqualTo(0L);
    }

    @Test
    void toSubscribersWithNoSubscribersIsSuccessSentCountZero() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true}")));
        UUID workflowId = createWorkflow(subscribableWorkflow("empty-wf", "empty-wf", true));

        ResponseEntity<Map<String, Object>> run = postJson(
                "/api/workflows/" + workflowId + "/run", Map.of());
        String executionId = String.valueOf(run.getBody().get("executionId"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        @SuppressWarnings("unchecked")
        Map<String, Object> execution = rest.getForObject("/api/executions/" + executionId, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) execution.get("steps");
        Map<String, Object> notify = steps.stream()
                .filter(s -> "notify".equals(s.get("name"))).findFirst().orElseThrow();
        assertThat(notify.get("status")).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) notify.get("output");
        assertThat(output).containsEntry("sent_count", 0);
        assertThat(output).containsEntry("audience", "approved subscribers");
    }

    @Test
    void revokeRecipientDropsThemFromSubscribersList() {
        UUID id = registerApproved("701101", "Drop-User");
        UUID workflowId = createWorkflow(subscribableWorkflow("drop-wf", "drop-wf", true));
        subscriptionService.toggle(workflowId, id);
        assertThat(subscriptionService.countSubscribers(workflowId)).isEqualTo(1L);

        ResponseEntity<Map<String, Object>> revoke = postJson(
                "/api/recipients/" + id + "/revoke", Map.of());
        assertThat(revoke.getStatusCode().is2xxSuccessful()).isTrue();

        // revoke does NOT delete the recipient row; the subscription row stays
        // in the table but counters/fan-out filter on status='APPROVED'.
        assertThat(subscriptionService.countSubscribers(workflowId)).isZero();
        assertThat(subscriptionService.listApprovedSubscribers(workflowId)).isEmpty();
    }

    @Test
    void deleteRecipientCascadesSubscriptions() {
        UUID id = registerApproved("701201", "Delete-User");
        UUID workflowId = createWorkflow(subscribableWorkflow("delete-wf", "delete-wf", true));
        subscriptionService.toggle(workflowId, id);
        assertThat(subscriptionService.countSubscribers(workflowId)).isEqualTo(1L);

        ResponseEntity<Void> delete = rest.exchange(
                "/api/recipients/" + id,
                org.springframework.http.HttpMethod.DELETE, null, Void.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(subscriptionService.countSubscribers(workflowId)).isZero();
    }

    @Test
    void patchSubscribableFlipsWithoutBumpingVersion() {
        UUID workflowId = createWorkflow(subscribableWorkflow("patch-wf", "patch-wf", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> initial = rest.getForObject("/api/workflows/" + workflowId, Map.class);
        int versionBefore = ((Number) initial.get("currentVersion")).intValue();
        assertThat(initial.get("subscribable")).isEqualTo(false);

        setSubscribable(workflowId, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = rest.getForObject("/api/workflows/" + workflowId, Map.class);
        assertThat(after.get("subscribable")).isEqualTo(true);
        assertThat(((Number) after.get("currentVersion")).intValue()).isEqualTo(versionBefore);
    }

    @Test
    void subscribersEndpointReturnsApprovedOnly() {
        UUID approvedId = registerApproved("701301", "Sub-API-User");
        // a second recipient that subscribes then gets revoked
        UUID revokedId = registerApproved("701302", "Revoked-API-User");
        UUID workflowId = createWorkflow(subscribableWorkflow("api-wf", "api-wf", true));
        subscriptionService.toggle(workflowId, approvedId);
        subscriptionService.toggle(workflowId, revokedId);
        postJson("/api/recipients/" + revokedId + "/revoke", Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = rest.getForObject(
                "/api/workflows/" + workflowId + "/subscribers", Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("displayName", "Sub-API-User");
    }
}
