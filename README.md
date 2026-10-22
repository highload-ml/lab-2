# Highload ML — ЛР2

Реализована область разработчика 1 из [plan.md](plan.md): bootstrap, project-service,
внутренний API, Eureka, Config Server, Gateway и окружение. Tracking и registry пока
**bootstrap-приложения без бизнес-эндпоинтов**; их перенос и Python-клиент — B1–B5.
Исходный монолит сохранён в `legacy-lab1/` для этого переноса.

## Запуск

Нужны Docker с Compose v2. Для локальной сборки без контейнеров — JDK 21.
Скопируйте `.env.example` в `.env` и задайте четыре пароля. Затем одна команда:

```sh
docker compose up --build --wait
```

Создаются независимые базы project, tracking, registry. Init-скрипт работает только на пустом
томе; изменение паролей в `.env` не меняет существующие роли PostgreSQL автоматически.

- Gateway: http://localhost:8080
- Общий Swagger UI: http://localhost:8080/swagger-ui.html
- Eureka: http://localhost:8761
- Config: http://localhost:8888/project-service/default

Регистрация/обновление кеша Eureka может занять несколько секунд после успешных healthcheck.
`python3 scripts/smoke.py` ждёт discovery, проверяет конфигурацию, Swagger, создаёт пользователя,
проект и эксперимент через Gateway, проверяет soft delete и закрытые внутренние URL.
Скрипт оставляет созданные тестовые данные с уникальными именами.

## Сборка и тесты

```sh
./gradlew assemble                     # шесть исполняемых jar
./gradlew test                         # unit, HTTP и инфраструктурные тесты без Docker
./gradlew check jacocoTestReport       # также PostgreSQL/Testcontainers и >=70% покрытия project-service
```

Отчёты: `<module>/build/reports/tests/{test,integrationTest}/`,
`project-service/build/reports/jacoco/test/html/index.html`.
PostgreSQL-тесты помечены `integration`, запускаются отдельной задачей integrationTest.
CI выполняет check, строит Compose, запускает окружение и smoke-проверку.

## Запуск JVM отдельно

```sh
./gradlew :discovery-server:bootRun
./gradlew :config-server:bootRun
DB_PASSWORD=... ./gradlew :project-service:bootRun
./gradlew :api-gateway:bootRun
```

Команды выполняются в отдельных терминалах. Project-service ожидает подготовленную БД project
на localhost:5432 с пользователем project. `CONFIG_SERVER_URL`, `EUREKA_URL`, `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT` переопределяют окружение.
Config Server при bootRun ищет `../config/` относительно своего модуля;
при запуске jar из корня задайте `CONFIG_REPO=file:./config/`.

## Передача разработчику 2

[Контракты HTTP, ошибки и пагинация](docs/contracts.md),
[версии и архитектурные решения](docs/architecture.md),
[состояние реализации и проверки](docs/developer-1-status.md).

Бизнес-код для переноса: `legacy-lab1/src/main/java/ru/itmo/highload_ml/{tracking,registry}`,
миграции и тесты находятся рядом в legacy-lab1. Модули tracking-service и registry-service уже
получают Config и регистрируются в Eureka. В config/ подготовлены R2DBC/JDBC-настройки, которые
начнут использоваться после подключения соответствующих зависимостей в B1/B3.

Для проверки исходного монолита: `./gradlew -PincludeLegacy :legacy-lab1:bootJar`.
Не запускайте его одновременно с Gateway на стандартном порту 8080.
