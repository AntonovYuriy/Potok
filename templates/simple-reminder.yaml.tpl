# The simplest automation: a message on a schedule. Good first workflow.
name: {{param.name}}
trigger:
  cron: "{{param.cron}}"
steps:
  - name: remind
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "{{param.message}}"
