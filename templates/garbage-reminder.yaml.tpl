# Every morning at 07:00 Warsaw time: check the real Warsaw waste collection
# schedule (warszawa19115.pl) and send one Telegram line when something is
# collected TODAY. Quiet days — no message.
#
# NOTE on time: the cron fires in the SERVER's timezone. The provided
# docker-compose sets TZ=Europe/Warsaw; if your host runs UTC, either set the
# TZ env var or shift the cron (07:00 Warsaw = 05:00/06:00 UTC by season).
#
# address_point_id: find yours via the address autocomplete on
# https://warszawa19115.pl/harmonogramy-wywozu-odpadow
# Requires TELEGRAM_BOT_TOKEN (app env) and TELEGRAM_CHAT_ID (substituted below).
name: {{param.name}}
trigger:
  cron: "{{param.cron}}"
steps:
  - name: schedule
    action: warsaw_waste
    with:
      address_point_id: "{{param.address_point_id}}"
      when: {{param.when}}

  - name: notify
    if: "{{ steps.schedule.has_collection == true }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🗑️ {{ steps.schedule.summary }}"
