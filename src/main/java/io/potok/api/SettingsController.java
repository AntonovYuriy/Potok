package io.potok.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-wide settings. Today only telegram_auto_approve (bool) — when ON,
 * any Telegram chat that messages the bot lands as APPROVED and starts
 * receiving broadcasts immediately. Default OFF so the operator approves
 * each new recipient in the dashboard.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final String TELEGRAM_AUTO_APPROVE = "telegram_auto_approve";

    private final SettingsRepository settings;

    public SettingsController(SettingsRepository settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsResponse get() {
        return new SettingsResponse(settings.getBoolean(TELEGRAM_AUTO_APPROVE, false));
    }

    @PatchMapping
    public ResponseEntity<SettingsResponse> patch(@RequestBody SettingsPatch body) {
        if (body != null && body.telegramAutoApprove() != null) {
            settings.setBoolean(TELEGRAM_AUTO_APPROVE, body.telegramAutoApprove());
        }
        return ResponseEntity.ok(get());
    }

    public record SettingsResponse(@JsonProperty("telegram_auto_approve") boolean telegramAutoApprove) {
    }

    public record SettingsPatch(@JsonProperty("telegram_auto_approve") Boolean telegramAutoApprove) {
    }
}
