# Watch ONE number in any JSON API and get an EMAIL when it crosses your line.
# Edge-triggered — one email when the condition becomes true, silence while it stays true.
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
    action: email
    with:
      to: "{{param.to}}"
      subject: "{{param.subject}}"
      body: "The value is now {{ trigger.value }} ({{param.comparison}} {{param.threshold}}) — {{param.url}}"
