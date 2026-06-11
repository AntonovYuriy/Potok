# Watches a price on a shop page (css selector) and alerts when it drops
# below your limit. "number: true" turns price tags like "249,99 zł" into
# numbers, so the comparison just works. Edge-triggered: one message per drop.
name: {{param.name}}
trigger:
  poll:
    interval: {{param.interval}}
    http:
      method: GET
      url: "{{param.url}}"
    extract:
      css: "{{param.selector}}"
      number: true
    fire_when: "{{ poll.value < {{param.threshold}} }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "💸 Price dropped to {{ trigger.value }} (under {{param.threshold}}): {{param.url}}"
