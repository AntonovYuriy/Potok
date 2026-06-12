# Human-in-the-loop: an external service calls the webhook, YOU get the
# question in Telegram with one-time approve/deny links, and only a "yes"
# triggers the action. Silence past the timeout counts as "no".
name: {{param.name}}
trigger:
  webhook:
    path: "{{param.path}}"
steps:
  - name: ask
    action: approval
    with:
      text: "{{param.question|dq}}"
      timeout: {{param.timeout}}
  - name: act
    if: "{{ steps.ask.approved == true }}"
    action: http
    with:
      method: POST
      url: "{{param.action_url}}"
      body: "approved"
  - name: confirm
    needs: [act]
    if: "{{ steps.ask.approved == true }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "✅ Done — action triggered after your approval"
  - name: cancelled
    needs: [ask]
    if: "{{ steps.ask.approved == false }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🚫 Not done — denied or timed out"
