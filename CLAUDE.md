# CLAUDE.md — Potok

Java 21 / Spring Boot 3.5 рушій сценаріїв з PostgreSQL 16 і vanilla-ES-modules
дашбордом. Орієнтир — `README.md`; правила коду, тестів і SPI дій —
`CONTRIBUTING.md` (вони чинні, цей файл їх не дублює).

Локально: `docker compose up -d postgres` → `./gradlew test` → `./gradlew bootRun`
(застосунок на :8080, дашборд на `/`).

## Git flow (повна автоматизація, затверджено власником 03.08.2026)

- origin = Gitea: ssh://git@home-server.tailf9e8b0.ts.net:2222/yuric/Potok.git
  Пушити ТІЛЬКИ в origin. GitHub — архівне дзеркало (синк автоматично о
  22:30), туди не пушити ніколи, PR там не створювати.
- Флоу розробки: нова гілка від main → комміти → локальний suite
  (lint/typecheck/tests/build проекту) ЗЕЛЕНИЙ → push гілки → merge у main
  БЕЗ участі власника (git checkout main && git merge --squash або
  fast-forward → push origin main) → гілку можна видалити.
- Merge у main = автоматичний деплой на прод-сервер (webhook → deploy.sh:
  збірка поза продом, health-check, авто-відкат робочої копії при падінні
  збірки). Якщо деплой впав (FAIL у /var/log/deploys.log) — виправити
  наступним комітом; прод при цьому залишається на попередній версії.
- ЗАБОРОНЕНО: force-push у main; merge при червоних тестах; секрети
  (.env, ключі, токени) у git; прямі комміти в main повз гілку (виняток —
  тривіальні правки документації).
- Деплой цього репо: `DEPLOY_MODE=compose` з `docker-compose.override.yml`
  (серверні порти/ліміти). Health-check — `http://127.0.0.1:8080/actuator/health`,
  до 240 с. Провал health-check → авто-відкат на попередній комміт з пересборкою.
- Інтеграційні тести піднімають реальний Postgres через Testcontainers —
  «зелений suite» перед merge означає саме їх, не тільки unit.
