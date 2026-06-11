# GitHub publishes an Atom feed for every repo's releases — no API key
# needed. Each new release becomes one Telegram message.
name: {{param.name}}
trigger:
  rss:
    interval: {{param.interval}}
    url: "https://github.com/{{param.repo}}/releases.atom"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🚀 New release in {{param.repo}}: {{ trigger.title }} — {{ trigger.link }}"
