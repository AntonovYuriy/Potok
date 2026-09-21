#!/usr/bin/env bash
#
# Автодеплой Potok (Spring Boot + PostgreSQL под docker compose).
#
# Тонкая обёртка: логика в ~/apps/_infra/deploy/deploy-template.sh,
# здесь только настройки проекта.
#
# Порядок в режиме compose: git pull -> build нового образа (старый контейнер
# продолжает обслуживать запросы) -> up -d только после успешной сборки ->
# health-check -> при провале откат на предыдущий коммит с пересборкой.
#
# Запуск вручную:  ~/apps/Potok/deploy.sh
# Пересобрать без новых коммитов:  FORCE=true ~/apps/Potok/deploy.sh

set -Eeuo pipefail

export APP_NAME="Potok"
export APP_DIR="/home/yuric/apps/Potok"
export GIT_REMOTE="origin"
export GIT_BRANCH="main"

export DEPLOY_MODE="compose"
export COMPOSE_FILE="${APP_DIR}/docker-compose.yml"
# Серверные правки (привязка портов к 127.0.0.1). Передаётся явно: как только
# у compose есть -f, сам он override уже не подхватывает — без этой строки
# деплой опубликовал бы postgres на 0.0.0.0 в обход ufw.
export COMPOSE_OVERRIDE="${APP_DIR}/docker-compose.override.yml"
# Пусто = оба сервиса (app + postgres).
export COMPOSE_SERVICES=""

# Spring Boot поднимается не мгновенно: старт JVM + flyway-миграции.
# 200 от actuator/health = состояние UP (при DOWN Spring отдаёт 503).
export HEALTH_URL="http://127.0.0.1:8080/actuator/health"
export HEALTH_TIMEOUT="240"
export HEALTH_INTERVAL="5"

export ROLLBACK_ON_FAIL="true"

exec /home/yuric/apps/_infra/deploy/deploy-template.sh
