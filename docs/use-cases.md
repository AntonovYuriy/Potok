# Use cases

Seven real automations, each a single YAML file from [examples/](../examples).
Create any of them via the dashboard (Help → Examples → Import) or
`curl -H 'Content-Type: text/plain' --data-binary @examples/<file> host/api/workflows`.
Placeholders like `${TELEGRAM_CHAT_ID}` are environment variables on the server.

---

## 1. Waste collection reminder (Warsaw)

You sort your waste, but the schedule is a PDF on a city website and every
fraction has its own day. This workflow asks the official warszawa19115.pl
API every morning and messages you only when something is collected today —
silence on the other days. All fractions for the day arrive merged into one
line. This is the original use case Potok was built for, and it runs in
production. (`when: tomorrow` gives an evening heads-up instead; crons fire
in the server timezone — compose sets `TZ=Europe/Warsaw`.)

```yaml
name: garbage-reminder
trigger:
  cron: "0 7 * * *"
steps:
  - name: schedule
    action: warsaw_waste
    with:
      address_point_id: "27086987"   # from the address autocomplete on warszawa19115.pl
      when: today                    # or "tomorrow" for an evening reminder

  - name: notify
    if: "{{ steps.schedule.has_collection == true }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🗑️ {{ steps.schedule.summary }}"
```

> 🗑️ Сегодня вывоз: Papier, Bio

## 2. Availability / price-tag watcher

A product is sold out and the shop has no "notify me" button. Point a css
selector at the availability badge: the page can be full of timestamps and
ads — only the extracted text is compared, so you get exactly one ping when
it actually changes.

```yaml
name: availability-watcher
trigger:
  poll:
    interval: 10m
    http: { method: GET, url: "https://shop.example.com/product/123" }
    extract: { css: "span.availability" }
    fire_when: "changed"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "👀 Наличие изменилось: {{ trigger.value }} — https://shop.example.com/product/123"
```

> 👀 Наличие изменилось: In stock — https://shop.example.com/product/123

## 3. Exchange-rate alert (NBP)

You want to buy euros when they dip. The Polish central bank publishes daily
mid rates as free JSON; this polls hourly and fires once when the rate crosses
your threshold (edge-triggered — no repeat alerts while it stays low).

```yaml
name: price-alert
trigger:
  poll:
    interval: 1h
    http: { method: GET, url: "https://api.nbp.pl/api/exchangerates/rates/a/eur/?format=json" }
    extract: { jsonpath: "$.rates[0].mid" }
    fire_when: "{{ poll.value < 4.20 }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "💶 EUR/PLN {{ trigger.value }} — ниже порога 4.20, пора менять"
```

> 💶 EUR/PLN 4.1850 — ниже порога 4.20, пора менять

## 4. Website uptime monitor

The cheapest pager: probe your site every 5 minutes, alert on a bad status.
`fail_on_status: false` records the response instead of failing the step, so
the alert condition can react to it.

```yaml
name: healthcheck
trigger:
  cron: "*/5 * * * *"
steps:
  - name: probe
    action: http
    with:
      method: GET
      url: "https://your-site.example"
      fail_on_status: false

  - name: alert
    if: "{{ steps.probe.status != 200 }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "ALERT: healthcheck returned {{ steps.probe.status }}"
```

> ALERT: healthcheck returned 503

## 5. Morning RSS digest

Follow a feed without a reader: each NEW item becomes one Telegram message.
Items are deduplicated by guid/link in the database, so restarts never
re-send and the first poll never floods.

```yaml
name: rss-digest
trigger:
  rss: { interval: 15m, url: "https://hnrss.org/frontpage" }
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "📰 {{ trigger.title }}\n{{ trigger.link }}"
```

> 📰 Show HN: Potok — workflow engine without a broker
> https://news.ycombinator.com/item?id=…

## 6. GitHub push notification (signed webhook)

Pushes land in Telegram — and because the webhook is HMAC-verified
(GitHub's X-Hub-Signature-256 over the raw body), nobody can spoof a delivery
to your endpoint. Same secret in the server env and the GitHub webhook config.

```yaml
name: github-notify
trigger:
  webhook:
    path: "gh"
    hmac_secret_env: "GITHUB_HOOK_SECRET"
steps:
  - name: notify
    action: telegram
    if: "{{ exists(trigger.head_commit) }}"
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "⬆️ {{ trigger.pusher.name }} -> {{ trigger.repository.full_name }}: {{ trigger.head_commit.message }}"
```

> ⬆️ yura -> AntonovYuriy/Potok: fix: validate conditions at create time

## 7. SSL certificate expiry check

Expired certificates take sites down silently. A daily `ssl_check` reads the
served certificate — including already-expired or self-signed ones — and the
condition warns two weeks ahead. The handler is ~100 lines and doubles as the
template for writing your own action: one Spring bean, discovered automatically.

```yaml
name: ssl-expiry
trigger:
  cron: "0 9 * * *"
steps:
  - name: check
    action: ssl_check
    with:
      host: "example.com"

  - name: warn
    action: telegram
    needs: [check]
    if: "{{ steps.check.days_left < 14 }}"
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🔒 Сертификат {{ steps.check.host }} истекает через {{ steps.check.days_left }} дн. ({{ steps.check.not_after }})"
```

> 🔒 Сертификат example.com истекает через 13 дн. (2026-06-25T12:00:00Z)
