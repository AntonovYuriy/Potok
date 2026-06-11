# The universal workhorse: watch ONE number in any JSON API and get a message
# when it crosses your line. Edge-triggered — one alert when the condition
# becomes true, silence while it stays true.
name: {{param.name}}
trigger:
  poll:
    interval: {{param.interval}}
    http:
      method: GET
      url: "{{param.url}}"
    extract:
      jsonpath: "{{param.jsonpath}}"
    fire_when: "{{ poll.value {{param.comparison}} {{param.threshold}} }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "📈 The value is now {{ trigger.value }} ({{param.comparison}} {{param.threshold}}) — {{param.url}}"
