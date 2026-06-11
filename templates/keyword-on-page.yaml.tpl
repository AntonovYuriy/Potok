# Watches the text of a page and pings you ONCE when a word or phrase shows
# up. Edge-triggered: the message fires when the keyword appears, then stays
# quiet until it disappears and appears again.
name: {{param.name}}
trigger:
  poll:
    interval: {{param.interval}}
    http:
      method: GET
      url: "{{param.url}}"
    extract:
      css: "body"
    fire_when: "{{ contains(poll.value, '{{param.keyword}}') }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🔎 \"{{param.keyword}}\" just appeared on {{param.url}}"
