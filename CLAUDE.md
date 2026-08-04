# CLAUDE.md — Potok

Self-hosted рушій сценаріїв: тригери (webhook, cron) durable виконують
YAML-воркфлоу з дій (Telegram, HTTP, email, …) через Postgres-чергу задач.
Орієнтир — `README.md`; правила коду, тестів і SPI дій — `CONTRIBUTING.md`
(вони чинні, цей файл їх не дублює). Стан між сесіями — `docs/handoff.md`:
читати першим, оновлювати перед кінцем сесії.

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

```
origin = ssh://git@home-server.tailf9e8b0.ts.net:2222/yuric/Potok.git   ← джерело істини (Gitea)
github = git@github.com:AntonovYuriy/Potok.git                          ← ЛИШЕ архівне дзеркало
```

Той самий home-server, що й інші проєкти; SSH ходить спільним alias'ом з
авто-вибором LAN → Tailscale (`~/.ssh/config`, Host `acc-git`: LAN `192.168.2.45:2222`,
інакше `home-server.tailf9e8b0.ts.net:2222`). Пушити **тільки** в `origin`.
GitHub синхронізується автоматично (~22:30) — туди не пушити, PR там не
створювати, **`gh` CLI не використовувати взагалі**. `.github/workflows/ci.yml`
лишається у дзеркалі як артефакт — на нього не спиратися.

## MERGE — CC робить сам, автономно після зеленого suite

Флоу кінець-у-кінець:

```
branch → implement → ПОВНИЙ локальний suite (green) → commit → push origin (гілка, історія)
       → squash-merge у main + push origin main → webhook → деплой → підтвердити landing
```

```bash
git checkout main && git pull --ff-only origin main
git merge --squash <branch> && git commit -m "<type>: ..."
git push origin main                                   # → webhook → прод-деплой
git merge-base --is-ancestor <sha> origin/main && echo landed
```

`main` не під branch-protection — push проходить напряму, токен/`tea`/web-UI
не потрібні. Завжди **squash**; після merge гілку прибрати. Власник гальмує
конкретний merge лише явно («не мерж», «батчи») — тоді CC зупиняється на кроці
push гілки.

## CI — НЕМАЄ (постійний стан)

Gitea Actions не ввімкнено, runner не піднято. GitHub Actions — у дзеркалі,
не гейт. Отже **ГЕЙТ перед push = ПОВНИЙ ЛОКАЛЬНИЙ SUITE**:

```bash
docker compose up -d postgres    # (опційно; тести піднімають свій PG через Testcontainers)
./gradlew build                  # compile + увесь тест-сет — ЄДИНИЙ гейт
```

- `./gradlew build` = компіляція + `test` (59 тест-класів, 337 тестів), серед
  них інтеграційні на **реальному Postgres через Testcontainers** (потрібен
  запущений Docker/colima) та drift-тести `TemplatesIntegrationTest` /
  `HelpIntegrationDocsTest` (`examples/` згенеровані з `templates/`;
  `integration.md == connect.md`). «Зелений suite» означає саме це, не лише unit.
- Правив `templates/` → спершу `./gradlew renderExamples`, закомітити
  згенеровані `examples/`, інакше drift-тест червоний.
- **Окремих lint/typecheck кроків у проєкті немає** (жодного checkstyle/spotless),
  commitlint і scope-enum теж немає — не вигадувати їх і не посилатися на них.
- Не посилатися на CI, не чекати CI, не re-run CI. Проміжні таргетовані прогони
  (`./gradlew test --tests '...'`) — лише під час розробки; фінальний гейт повний.

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

**(B) SUCCESS + MERGE** — `./gradlew build` зелений → чиста гілка → `git push -u
origin <branch>` → squash-merge у `main` + push → підтвердити landing → доповісти:
«гілка X, локально зелено (N тестів), змінено: …, змержено в main (`<sha>`),
деплой пішов».

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
- ❌ Merge при червоному suite.
- ❌ Секрети (`.env`, ключі, токени, реальні chat_id) у git.
- ❌ Прямі робочі комміти в `main` повз гілку (виняток — тривіальна правка
  `docs/handoff.md` після merge).
- ❌ Видалення remote-гілки `main` або чужих гілок.
- ❌ Операції поза `~/Developer/Potok/`.

> Розгорнутий операційний runbook (покроково, формат звіту, single-writer,
> гігієна гілок) — `.claude/skills/git-workflow/SKILL.md` (локальний, у репо
> не трекається).
