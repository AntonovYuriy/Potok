# One Telegram message per new feed item (deduped by guid/link, survives
# restarts). The first poll only records existing items — no flood.
name: {{param.name}}
trigger:
  rss:
    interval: {{param.interval}}
    url: "{{param.feed_url}}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "📰 {{ trigger.title }}\n{{ trigger.link }}"
