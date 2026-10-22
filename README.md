# Highload ML — ЛР2

Реализована область разработчика 1 из командного плана: bootstrap, project-service,
внутренний API, Eureka, Config Server, Gateway и окружение. Tracking реализован на WebFlux + R2DBC (B1–B2), см. [tracking-service/README.md](tracking-service/README.md).
Registry реализован на WebFlux + JPA (B3), см. [registry-service/README.md](registry-service/README.md).
Feign-клиенты, таймауты и Circuit Breaker реализованы (B4). Адаптация Python-клиента остаётся B5.
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

## B4: отказы зависимостей

Защита включена в существующих Feign-клиентах через Spring Cloud CircuitBreaker + Resilience4j.
Новых production-классов нет: короткий bean в `TrackingServiceApplication` и
`RegistryServiceApplication` задаёт понятные имена circuit. Остальная обработка находится
в существующих `FeignProjectAccess` (tracking) и `ApiExceptionHandler` (registry).

В каждом приложении свой circuit `project-service`; в registry также есть независимый
`tracking-service`. Все методы одного клиента используют общий circuit своей зависимости.
Настройки находятся в одном месте — `config/application.yml`, выдаваемом Config Server.

| Настройка | Значение | Смысл |
|---|---|---|
| Feign connectTimeout | 2000 мс | Максимальное ожидание установления соединения |
| Feign readTimeout | 5000 мс | Ожидание чтения ответа; это не общий deadline всего HTTP-запроса |
| slidingWindowType / slidingWindowSize | COUNT_BASED / 10 | Статистика последних 10 учитываемых вызовов |
| minimumNumberOfCalls | 5 | До 5 учитываемых вызовов circuit не открывается |
| failureRateThreshold | 50% | Circuit открывается, если доля ошибок достигла 50% |
| waitDurationInOpenState | 10 секунд | После ожидания новый вызов может стать пробным |
| permittedNumberOfCallsInHalfOpenState | 2 | Два пробных вызова для проверки восстановления |
| ignoreExceptions | Feign NotFound, UnprocessableEntity | 404/422 исключены из статистики: это ожидаемые ответы API |

Например, первые 5 неудачных вызовов откроют circuit. В OPEN запросы к зависимости
не отправляются. Через 10 секунд следующий вызов переводит circuit в HALF_OPEN;
два успешных пробных вызова закрывают его. Считаются именно Feign-вызовы, а не внешние
HTTP-запросы: создание run вызывает project-service дважды (эксперимент и членство).

Стандартные настройки Resilience4j, которые не переопределялись: автоматического фонового
перехода OPEN → HALF_OPEN нет, отдельный health indicator не регистрируется,
slow-call порог — 60 секунд и 100% медленных вызовов. При обычных ответах таймаут чтения
Feign срабатывает раньше; медленная потоковая передача не равна общему deadline.

TimeLimiter и дополнительный executor Spring Cloud отключены: Feign уже работает на
`boundedElastic`, а сетевое ожидание ограничено его таймаутами. Bulkhead также отключён.
Так нет второго пула и `ExecutionException`, скрывающего исходный тип ошибки от circuit.
Повторные попытки приложением не добавлены: используется стандартный Feign `Retryer.NEVER_RETRY`.

Недоступность, сетевой таймаут и открытый circuit возвращают безопасный Problem Details
со статусом 503. Ответы 404/422 сохраняют статус. Положительных fallback-ответов нет:
ошибка проверки пользователя или источника прерывает операцию до транзакции БД.
Circuit защищает только удалённый Feign-вызов, поэтому ошибки собственной БД не входят
в статистику зависимости. Роли и JWT остаются областью ЛР3.

Таймауты можно переопределить переменными окружения сервиса `FEIGN_CONNECT_TIMEOUT_MS`,
`FEIGN_READ_TIMEOUT_MS`; circuit — `CB_WINDOW_SIZE`, `CB_MIN_CALLS`, `CB_FAILURE_RATE`,
`CB_OPEN_DURATION`, `CB_HALF_OPEN_CALLS`. Для Docker переменные передаются через environment.

```sh
./gradlew :tracking-service:check :registry-service:check
```

Новые проверки добавлены в существующие `ProjectClientIntegrationTest` и
`RegistryClientIntegrationTest`: реальные Feign-прокси, таймаут, обрыв соединения,
CLOSED → OPEN → HALF_OPEN → CLOSED, независимость зависимостей, игнорирование 404/422
и отсутствие записи в PostgreSQL при отказе. Для детерминированной проверки HALF_OPEN
тесты переводят circuit в это состояние явно, не ожидая 10 секунд.
В тесты копируется тот же production YAML; короткие сетевые таймауты переопределены
только для тестового HTTP-сервера. Запуск Config Server для этих тестов не требуется.
