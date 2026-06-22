# One trigger, two channels. When a number crosses your line, Potok pings BOTH
# Telegram and email. The two steps are independent roots (needs: []), so they
# run in parallel and one channel failing never blocks the other.
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
  - name: telegram_alert
    action: telegram
    needs: []
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "📈 The value is now {{ trigger.value }} ({{param.comparison}} {{param.threshold}})"
  - name: email_alert
    action: email
    needs: []
    with:
      to: "{{param.to}}"
      subject: "{{param.subject}}"
      body: "The value is now {{ trigger.value }} ({{param.comparison}} {{param.threshold}}) — {{param.url}}"
