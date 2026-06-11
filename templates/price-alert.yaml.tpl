# EUR/PLN drops below your threshold -> Telegram. NBP (Polish central bank)
# publishes daily mid rates as free JSON. Edge-triggered: one alert when the
# rate crosses the threshold, silence while it stays below.
name: {{param.name}}
trigger:
  poll:
    interval: {{param.interval}}
    http:
      method: GET
      url: "{{param.url}}"
    extract:
      jsonpath: "{{param.jsonpath}}"
    fire_when: "{{ poll.value < {{param.threshold}} }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "💶 EUR is at {{ trigger.value }} — below your {{param.threshold}} threshold, good time to exchange"
