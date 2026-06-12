# A reminder that doesn't let go: first message, then a durable sleep, then a
# follow-up. The pause is a row in Postgres — restarts and deploys don't
# cancel it.
name: {{param.name}}
trigger:
  cron: "{{param.cron}}"
steps:
  - name: remind
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "{{param.message|dq}}"
  - name: pause
    wait: {{param.days}}d
  - name: follow_up
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "{{param.followup|dq}}"
