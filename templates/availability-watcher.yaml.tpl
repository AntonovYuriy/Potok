# Watch a product page: extract ONE element (price/stock badge) with a css
# selector and ping Telegram when it changes. Page noise (ads, timestamps)
# never causes false alerts — only the extracted text is compared.
name: {{param.name}}
trigger:
  poll:
    interval: {{param.interval}}
    http:
      method: GET
      url: "{{param.url}}"
    extract:
      css: "{{param.selector}}"
    fire_when: "changed"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "👀 Наличие изменилось: {{ trigger.value }} — {{param.url}}"
