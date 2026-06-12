# Use cases

Fifteen real automations, each a single YAML file from [examples/](../examples).
The easy way: dashboard → Help → Examples → pick a card → fill the short form
(Preview ▶ shows what would happen right now before you commit). The curl way:
`curl -H 'Content-Type: text/plain' --data-binary @examples/<file> host/api/workflows`.
Placeholders like `${TELEGRAM_CHAT_ID}` are environment variables on the server.

---

## 1. Remind me on a schedule

A message arrives in your Telegram at the time you choose. Water the plants,
stretch, take the medicine — the simplest automation there is, and a good
first one.

```yaml
name: simple-reminder
trigger:
  cron: "0 9 * * *"
steps:
  - name: remind
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "Don't forget: drink some water 💧"
```

> Don't forget: drink some water 💧

## 2. Remind me — and don't let it go

One reminder is easy to dismiss. This one comes back: a message now, a durable
pause, a follow-up. The pause is a `run_at` timestamp in Postgres — restarts
and deploys can't cancel it.

```yaml
name: follow-up-reminder
trigger:
  cron: "0 9 * * *"
steps:
  - name: remind
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "💳 Pay the insurance"
  - name: pause
    wait: 3d
  - name: follow_up
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "⏰ Still pending? Insurance!"
```

> 💳 Pay the insurance → *(3 days later)* → ⏰ Still pending? Insurance!

## 3. Watch a number from any API

Exchange rates, prices, temperatures, follower counts — if it's a number in a
public API, Potok can watch it. Edge-triggered: exactly one message when the
number crosses your line, silence while nothing changes. The comparison
(`<`, `>`, `==`, `!=`) and threshold are form fields.

```yaml
name: json-threshold
trigger:
  poll:
    interval: 1h
    http: { method: GET, url: "https://api.nbp.pl/api/exchangerates/rates/a/eur/?format=json" }
    extract: { jsonpath: "$.rates[0].mid" }
    fire_when: "{{ poll.value > 4.30 }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "📈 The value is now {{ trigger.value }} (> 4.30) — …"
```

> 📈 The value is now 4.3120 (> 4.30) — https://api.nbp.pl/…

## 4. Ask me before doing it (human-in-the-loop)

A service wants to act — deploy, publish, order — but you want the final
word. The webhook becomes a Telegram question with one-time approve/deny
links; only your "yes" triggers the action, and silence past the timeout
counts as "no". The decision is the step's output, so the DAG just branches
on it.

```yaml
name: confirm-before-act
trigger:
  webhook:
    path: "confirm"
steps:
  - name: ask
    action: approval
    with:
      text: "Deploy v2.3 to prod?"
      timeout: 6h
  - name: act
    if: "{{ steps.ask.approved == true }}"
    action: http
    with: { method: POST, url: "https://ci.example.com/deploy", body: "approved" }
  - name: cancelled
    needs: [ask]
    if: "{{ steps.ask.approved == false }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🚫 Not done — denied or timed out"
```

> Deploy v2.3 to prod?
> ✅ Approve: https://… ❌ Deny: https://… ⏳ Expires in 6h

## 5. Get told when a page mentions something

Waiting for a name in the results list, a city added to a tour, your street in
a roadworks notice? Potok reads the page text and messages you once when the
words show up.

```yaml
name: keyword-on-page
trigger:
  poll:
    interval: 30m
    http: { method: GET, url: "https://band.example.com/tour" }
    extract: { css: "body" }
    fire_when: "{{ contains(poll.value, 'Warsaw') }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🔎 \"Warsaw\" just appeared on https://band.example.com/tour"
```

> 🔎 "Warsaw" just appeared on https://band.example.com/tour

## 6. Know when a price drops

Pick the price element on any shop page and name your limit. `number: true`
turns price tags like "249,99 zł" or "$1,299.00" into numbers, so the
comparison just works.

```yaml
name: price-drop
trigger:
  poll:
    interval: 30m
    http: { method: GET, url: "https://shop.example.com/product/123" }
    extract: { css: "span.price", number: true }
    fire_when: "{{ poll.value < 200 }}"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "💸 Price dropped to {{ trigger.value }} (under 200): …"
```

> 💸 Price dropped to 189.99 (under 200): https://shop.example.com/product/123

## 7. Never miss a recurring payment

Rent, insurance, the subscription you keep forgetting about. One message on
the same day every month — days 1–28 work in February too.

```yaml
name: monthly-payment-reminder
trigger:
  cron: "0 9 25 * *"
steps:
  - name: remind
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "💳 Rent is due today — transfer before 18:00"
```

> 💳 Rent is due today — transfer before 18:00

## 8. Get told when your site is down

Find out from a Telegram message, not from your users. `fail_on_status: false`
records the response instead of failing the step, so the alert condition can
react to it.

```yaml
name: uptime-monitor
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
      text: "🚨 https://your-site.example is down — returned {{ steps.probe.status }}"
```

> 🚨 https://your-site.example is down — returned 503

## 9. Know when a project ships a new release

GitHub publishes an Atom feed for every repo's releases — no API key needed.
Each release becomes one message with the version and link.

```yaml
name: release-watcher
trigger:
  rss:
    interval: 30m
    url: "https://github.com/spring-projects/spring-boot/releases.atom"
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"
      text: "🚀 New release in spring-projects/spring-boot: {{ trigger.title }} — {{ trigger.link }}"
```

> 🚀 New release in spring-projects/spring-boot: v3.5.14 — https://github.com/…

## 10. Follow a feed in Telegram

Any news feed, blog or podcast with RSS lands in your chat — one message per
new item. Items are deduplicated by guid/link in the database, so restarts
never re-send and the first poll never floods.

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

## 11. Know the moment a page changes

Sold-out product, closed registration, "no slots available" — point a css
selector at the one spot that says so. The page can be full of timestamps and
ads; only the extracted text is compared, so you get exactly one ping when it
actually changes.

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
      text: "👀 The page changed: {{ trigger.value }} — https://shop.example.com/product/123"
```

> 👀 The page changed: In stock — https://shop.example.com/product/123

## 12. Buy euros when they're cheap

The Polish central bank publishes the official mid rate daily, as free JSON.
Edge-triggered: one alert when the rate dips under your threshold, no repeats
while it stays low.

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
      text: "💶 EUR is at {{ trigger.value }} — below your 4.20 threshold, good time to exchange"
```

> 💶 EUR is at 4.1850 — below your 4.20 threshold, good time to exchange

## 13. Renew certificates before they expire

An expired certificate takes your site down with a scary browser warning — and
it always happens on a weekend. The `ssl_check` handler reads the served
certificate (even already-expired or self-signed ones) and warns two weeks
ahead. ~100 lines of Java, and the template for writing your own action.

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
      text: "🔒 Certificate for {{ steps.check.host }} expires in {{ steps.check.days_left }} days ({{ steps.check.not_after }})"
```

> 🔒 Certificate for example.com expires in 13 days (2026-06-25T12:00:00Z)

## 14. See repo pushes in Telegram (signed webhook)

Every push becomes a chat message: who pushed and what. The webhook is
HMAC-verified (GitHub's X-Hub-Signature-256 over the raw body), so nobody can
spoof a delivery. Same secret in the server env and the GitHub webhook config.

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

## 15. Remember to put the bins out (Warsaw)

The schedule is a PDF on a city website and every fraction has its own day.
This asks the official warszawa19115.pl API every morning and messages you
only when something is collected today — all fractions merged into one line,
silence on other days. The original use case Potok was built for; it runs in
production. Also the reference example of a custom action: a city-specific
API wrapped in one Spring bean. (`when: tomorrow` gives an evening heads-up;
crons fire in server time — compose sets `TZ=Europe/Warsaw`.)

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
