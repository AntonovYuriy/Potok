# One Telegram message on a fixed day every month — rent, insurance,
# subscriptions. Days 1-28 work in every month.
name: {{param.name}}
trigger:
  cron: "0 {{param.hour}} {{param.day}} * *"
steps:
  - name: remind
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "{{param.message}}"
