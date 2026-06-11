# GitHub push notifications with signature verification. Set the SAME secret
# in: (1) the env var named below on the Potok server, (2) the GitHub webhook
# settings (repo -> Settings -> Webhooks, payload URL https://your-host/hooks/{{param.path}},
# content type application/json). Unsigned or tampered deliveries get 401.
name: {{param.name}}
trigger:
  webhook:
    path: "{{param.path}}"
    hmac_secret_env: "{{param.secret_env}}"
steps:
  - name: notify
    action: telegram
    if: "{{ exists(trigger.head_commit) }}"
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "⬆️ {{ trigger.pusher.name }} -> {{ trigger.repository.full_name }}: {{ trigger.head_commit.message }}"
