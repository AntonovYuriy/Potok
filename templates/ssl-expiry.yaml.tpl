# Daily certificate check: warn in Telegram before expiry.
# ssl_check connects, reads the served certificate (even an already-expired
# one) and reports days_left — see its handler for a minimal example of
# adding your own action to the engine.
name: {{param.name}}
trigger:
  cron: "{{param.cron}}"
steps:
  - name: check
    action: ssl_check
    with:
      host: "{{param.host}}"

  - name: warn
    action: telegram
    needs: [check]
    if: "{{ steps.check.days_left < {{param.days}} }}"
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🔒 Certificate for {{ steps.check.host }} expires in {{ steps.check.days_left }} days ({{ steps.check.not_after }})"
