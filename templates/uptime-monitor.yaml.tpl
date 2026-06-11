# Probe a URL on a schedule; alert in Telegram only when it is NOT healthy.
# fail_on_status: false records ANY http response as step output instead of
# failing the step, so the alert condition can react to the status code.
name: {{param.name}}
trigger:
  cron: "{{param.cron}}"
steps:
  - name: probe
    action: http
    with:
      method: GET
      url: "{{param.url}}"
      fail_on_status: false

  - name: alert
    if: "{{ steps.probe.status != 200 }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "ALERT: {{param.url}} returned {{ steps.probe.status }}"
