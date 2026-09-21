# CLAUDE.md — Potok

Self-hosted рушій сценаріїв: тригери (webhook, cron) durable виконують
YAML-воркфлоу з дій (Telegram, HTTP, email, …) через Postgres-чергу задач.
Орієнтир — `README.md`; правила коду, тестів і SPI дій — `CONTRIBUTING.md`
(вони чинні, цей файл їх не дублює). Стан між сесіями — `docs/state.md`:
читати першим, оновлювати перед кінцем сесії. `docs/handoff.md` — заморожений
журнал попередніх віх; нового туди не пишемо, історія живе в `git log`.

## Стек

Java 21 · Spring Boot 3.5 · Gradle (Kotlin DSL) · PostgreSQL 16 (черга задач,
`FOR UPDATE SKIP LOCKED`) · Flyway (`src/main/resources/db/migration`) ·
JUnit 5 + Testcontainers · дашборд на vanilla ES modules (без build-кроку).

Локально: `docker compose up -d postgres` → `./gradlew test` → `./gradlew bootRun`
(застосунок на :8080, дашборд на `/`).

## Розкладка (package-by-feature)

`io.potok.*` — `api` (REST + webhook ingress), `definition` (YAML-парсер,
template resolver), `trigger` (webhook + cron), `execution` (черга, воркери,
SPI-диспетчер), `action` (хендлери дій). Тести дзеркалять розкладку під
`src/test/java/io/potok/`.

## Конфігурація

Лише env-змінні, секретів у репо немає; `.env.example` — канонічний список.
**Backward compatibility (з M6.1):** кожна нова YAML/API/env-властивість
йде з логічним дефолтом — наявні воркфлоу мусять працювати без змін після
апгрейду; кожна фіча-віха містить backward-compat тест.

---

# GIT / DEPLOY (Gitea self-hosted — origin, НЕ GitHub)

**АВТОРИТЕТНО.** Замінює попередній `gh`/GitHub-Actions/PR-флоу цілком.

Адреси не переписуємо сюди — `git remote -v`. `origin` — Gitea на home-server,
джерело істини; `github` — дзеркало. SSH ходить спільним alias'ом з авто-вибором
LAN → Tailscale (`~/.ssh/config`, Host `acc-git`). Пушити **тільки** в `origin`,
PR на GitHub не створювати, **`gh` CLI не використовувати взагалі**.

**Дзеркало для Potok — миттєве, не нічне.** Проєкт стоїть у `INSTANT_MIRROR`
(`mirror/mirror-sync.sh` у `server-infra`): push у Gitea `main` одразу
з'являється в публічному репозиторії на GitHub. Усе, що змержено, вважається
опублікованим тієї ж хвилини — разом з історією.

**`.github/workflows/ci.yml` — живий, а не артефакт.** На дзеркалі він
збирає, ганяє тести і **публікує образ `ghcr.io/antonovyuriy/potok`** на кожен
push у `main`. Гейтом цього репозиторію він не є (гейт — нижче), але він
реально виконується. Не спиратися на нього і не чекати його.

## MERGE — через PR і зелений гейт

Флоу кінець-у-кінець:

```
branch → implement → make verify (локально, зелено) → commit → push origin (гілка)
       → PR у Gitea → зелений гейт → squash-merge → main → webhook → деплой
```

`main` **під branch-protection**: прямий push відбивається pre-receive хуком,
обов'язковий статус — `gate / gate (pull_request)`, мерж тільки **squash**,
гілка видаляється після мержу, апрув власника не потрібен. Мерж ставиться
через «merge when checks succeed» і відбувається сам, коли гейт позеленіє.
Власник гальмує конкретний merge лише явно («не мерж», «батчи») — тоді CC
зупиняється на кроці push гілки.

## ГЕЙТ — Gitea Actions, runner на маці

Склад гейта не переказуємо: читай `.gitea/workflows/gate.yml` і `Makefile`.
Workflow ідентичний шаблону `server-infra/templates/gate.yml` і в цьому
репозиторії не правиться — якщо здається, що він «не підходить», правиться
`Makefile`.

Локально те саме дає `make verify` (підіймає compose, чекає health, smoke).

- `make test` ганяє `cleanTest test` навмисно: інакше Gradle віддає
  `:test UP-TO-DATE` і гейт зеленіє, нічого не виконавши. Кількість тестів
  тут не дублюємо — вона у звіті прогону.
- Тести піднімають **реальний Postgres через Testcontainers** — потрібен
  запущений Docker/colima. Пропущені тести гейт рахує і вважає **червоним**.
- Правив `templates/` → спершу `./gradlew renderExamples`, закомітити
  згенеровані `examples/`, інакше drift-тест червоний.
- **`lint` і `test-integration` — заглушки `n/a`.** У проєкті немає ані
  spotless/checkstyle, ані розмітки `@Tag` на тестах; вигадувати їх і
  посилатися на них не можна. Обидва — окремі задачі, причини в
  `docs/state.md`.
- Проміжні таргетовані прогони (`./gradlew test --tests '...'`) — лише під
  час розробки; фінальний гейт повний.

## DEPLOY — webhook на merge у `main`

- Push у **будь-яку** гілку крім `main` — безпечний, **НЕ** деплоїть.
- Push/merge у `main` → Gitea webhook (HMAC-SHA256 + фільтр `ref == refs/heads/main`)
  → `~/apps/Potok/deploy.sh` (обгортка над `~/apps/_infra/deploy/deploy-template.sh`):
  `git pull` → `docker compose build` нового образу (стара версія обслуговує запити
  під час збірки) → `up -d` → health-check `http://127.0.0.1:8080/actuator/health`
  (до 240 с, крок 5 с) → рядок у `/var/log/deploys.log`.
- `DEPLOY_MODE=compose` з явним `docker-compose.override.yml` (серверні порти
  прив'язані до `127.0.0.1` в обхід публікації повз ufw). Цей override —
  локальний файл сервера, у git не тримається (`.gitignore`).
- Провал збірки або health-check → `ROLLBACK_ON_FAIL=true`: відкат робочої копії
  на попередній комміт з пересборкою, прод лишається на старій версії.
  `FAIL` у `/var/log/deploys.log` → виправляти **наступним комітом**, не
  force-push'ем.
- CC деплой не тригерить руками і доступу до нього не має — тільки знає механізм
  і читає результат.

## Інваріант двох результатів

Кожна задача завершується **рівно одним** зі станів — третього («закомічено, але
не запушено», «частково») немає:

**(A) STOP + REPORT** — зупинитись і доповісти: що / де (файл:рядок або команда) /
що потрібно. Тригери: потрібне рішення власника (бізнес-логіка, схема БД, UX,
неоднозначний scope); **red suite**, який не чиниться в межах задачі;
merge-конфлікт; будь-яка помилка push/hook. Після STOP далі не йти.

**(B) SUCCESS + MERGE** — `make verify` зелений → чиста гілка → `git push -u
origin <branch>` → PR → зелений гейт → squash-merge → підтвердити landing →
доповісти: «гілка X, гейт зелений, змінено: …, змержено в main (`<sha>`),
деплой пішов».

Перед (B) обов'язково, у тому самому PR:

- `docs/state.md` оновлено поточним станом (не історією);
- `.claude/skills/troubleshooting.md` доповнено, якщо щось падало і було
  розібрано.

## Гілки та комміти

- `feat/<milestone>-<short-name>` (напр. `feat/m14-...`), `fix/<kebab-name>`,
  `chore/<kebab-name>`, `docs/<kebab-name>` — від свіжого `main`.
- Conventional Commits: `feat:`, `fix:`, `refactor:`, `chore:`, `docs:`, `test:`.
  Один concern на комміт, imperative subject ≤72 символів, lowercase.
  **Scope необов'язковий** (enum'а немає — commitlint не налаштований).
- Комміт-меседжі та описи merge'ів: без co-author / «generated with» футерів.

## Заборонено

- ❌ `gh` CLI, push або PR на GitHub (дзеркало).
- ❌ `git push --force` / `--force-with-lease` у `main`; переписування
  опублікованої історії (`rebase -i`, `reset --hard`, `commit --amend` після push).
- ❌ Merge при червоному гейті.
- ❌ Секрети (`.env`, ключі, токени, реальні chat_id) у git. Дзеркало
  миттєве — те, що потрапило в комміт, уже публічне.
- ❌ Прямі комміти і push у `main` повз PR — branch-protection їх відбиває,
  винятків більше немає.
- ❌ Видалення remote-гілки `main` або чужих гілок.
- ❌ Операції поза `~/Developer/Potok/`.

> Розгорнутий операційний runbook (покроково, формат звіту, single-writer,
> гігієна гілок) — `.claude/skills/git-workflow/SKILL.md` (локальний, у репо
> не трекається). Виняток — `.claude/skills/troubleshooting.md`: він частина
> контракту стандарту і трекається, решта `.claude/` лишається локальною.
