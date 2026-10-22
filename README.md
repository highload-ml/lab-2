# Highload ML — ЛР2

Реализована область разработчика 1 из командного плана: bootstrap, project-service,
внутренний API, Eureka, Config Server, Gateway и окружение. Tracking реализован на WebFlux + R2DBC (B1–B2), см. [tracking-service/README.md](tracking-service/README.md).
Registry реализован на WebFlux + JPA (B3), см. [registry-service/README.md](registry-service/README.md).
Circuit Breaker и адаптация Python-клиента остаются B4–B5.
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
./gradlew check jacocoTestReport       # также PostgreSQL/Testcontainers и >=70% покрытия бизнес-сервисов
```

Отчёты: `<module>/build/reports/tests/{test,integrationTest}/`,
`<module>/build/reports/jacoco/test/html/index.html`.
PostgreSQL-тесты помечены `integration`, запускаются отдельной задачей integrationTest.
CI выполняет check, строит Compose и запускает smoke-сценарии project, tracking и registry.

## Запуск JVM отдельно

```sh
./gradlew :discovery-server:bootRun
./gradlew :config-server:bootRun
DB_PASSWORD=... ./gradlew :project-service:bootRun
DB_PASSWORD=... ./gradlew :tracking-service:bootRun
DB_PASSWORD=... ./gradlew :registry-service:bootRun
./gradlew :api-gateway:bootRun
```

Команды выполняются в отдельных терминалах. Project-service ожидает подготовленную БД project
на localhost:5432 с пользователем project. `CONFIG_SERVER_URL`, `EUREKA_URL`, `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT` переопределяют окружение.
Config Server при bootRun ищет `../config/` относительно своего модуля;
при запуске jar из корня задайте `CONFIG_REPO=file:./config/`.

## Разбор бизнес-сервисов

- [Tracking: R2DBC, транзакции и API](tracking-service/README.md).
- [Registry: карта файлов, Reactor + JPA и API](registry-service/README.md).

Оба сервиса получают Config и регистрируются в Eureka. Tracking использует R2DBC
для запросов и JDBC для Liquibase; registry — JPA/JDBC и Liquibase.
Исходники ЛР1 сохранены в `legacy-lab1/` для сравнения.

Для проверки исходного монолита: `./gradlew -PincludeLegacy :legacy-lab1:bootJar`.
Не запускайте его одновременно с Gateway на стандартном порту 8080.
