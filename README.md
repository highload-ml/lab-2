# Highload ML — ЛР2

Реализована область разработчика 1 из командного плана: bootstrap, project-service,
внутренний API, Eureka, Config Server, Gateway и окружение. Tracking реализован на WebFlux + R2DBC (B1–B2), см. [tracking-service/README.md](tracking-service/README.md).
Registry реализован на WebFlux + JPA (B3), см. [registry-service/README.md](registry-service/README.md).
Feign-клиенты, таймауты и Circuit Breaker реализованы (B4). Демонстрация выполняется через
общий Swagger; Python-библиотека в текущий объём работы не входит.
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

Если порты заняты, измените `GATEWAY_PORT`, `EUREKA_PORT`, `CONFIG_PORT` в `.env`.
Например, `GATEWAY_PORT=18080` и `CONFIG_PORT=18888`; тогда Swagger будет доступен
на http://localhost:18080/swagger-ui.html. Адреса между контейнерами менять не нужно.

Регистрация/обновление кеша Eureka может занять несколько секунд после успешных healthcheck.
`python3 scripts/smoke.py` ждёт discovery, проверяет конфигурацию, Swagger, создаёт пользователя,
проект и эксперимент через Gateway, проверяет soft delete и закрытые внутренние URL.
Скрипт оставляет созданные тестовые данные с уникальными именами.

## Демонстрация через Swagger

Откройте `/swagger-ui.html` на порту Gateway. В списке спецификаций выбирайте
**Projects**, **Tracking**, **Registry**; запросы выполняются кнопкой **Try it out → Execute**.
UUID из ответов подставляйте вместо обозначений ниже. При повторном показе меняйте
nickname и имя проекта, чтобы не получить конфликт уникальности.

1. **Projects:** `POST /api/v1/users`:
   ```json
   {"nickname":"demo_engineer","password":"demo-password-123","role":"ML_ENGINEER"}
   ```
   Сохраните `id` как `USER_ID`. Создайте проект через `POST /api/v1/projects`:
   ```json
   {"name":"demo-lab2","ownerId":"USER_ID"}
   ```
   Сохраните `PROJECT_ID`. Через `POST /api/v1/projects/{projectId}/experiments`
   создайте эксперимент с телом `{"name":"baseline"}` и сохраните `EXPERIMENT_ID`.
2. **Tracking:** `POST /api/v1/experiments/{experimentId}/runs`:
   ```json
   {"authorId":"USER_ID","name":"demo-training"}
   ```
   Сохраните `RUN_ID`. Вызовите `POST /api/v1/runs/{runId}/start` без тела.
   Добавьте метрики через `POST /api/v1/runs/{runId}/metrics/batch`:
   ```json
   {"metrics":[{"name":"loss","value":0.2,"step":0},{"name":"loss","value":0.1,"step":1}]}
   ```
   Через `POST /api/v1/runs/{runId}/artifacts` зарегистрируйте модель:
   ```json
   {"name":"model","type":"MODEL","path":"local/model.bin","sizeBytes":42}
   ```
   Сохраните `ARTIFACT_ID`. Здесь сохраняются метаданные; загрузка файла не требуется.
   Завершите запуск: `POST /api/v1/runs/{runId}/complete` без тела.
3. **Registry:** `POST /api/v1/projects/{projectId}/model-versions`:
   ```json
   {"userId":"USER_ID","artifactId":"ARTIFACT_ID"}
   ```
   Сохраните `VERSION_ID`. Последовательно вызовите `/{versionId}/stage` и
   `/{versionId}/promote` относительно этого же префикса, оба с телом
   `{"userId":"USER_ID"}`. `GET /production` вернёт опубликованную версию.
   Повторите шаги 2–3 с новым запуском: номер версии увеличится,
   а предыдущая версия перейдёт в `ARCHIVED`.

Дополнительные проверки в Swagger:

- `GET /api/v1/runs/{runId}/metrics?page=0&size=1` возвращает одну запись
  и заголовок `X-Total-Count: 2`.
- Создайте второй запуск эксперимента. `GET /api/v1/experiments/{experimentId}/runs?size=1`
  вернёт `X-Has-Next: true` и `X-Next-Cursor`; передайте курсор в `after` для следующей страницы.
- Повторная регистрация того же артефакта в Registry даёт 409;
  добавление метрики в завершённый запуск — 422; некорректное тело — 400.
- Для отказа зависимости выполните `docker compose stop project-service` и несколько раз
  создайте запуск в Tracking: ответ 503. После открытия circuit запросы быстро отклоняются.
  Выполните `docker compose start project-service`, дождитесь healthcheck и обновления Eureka;
  после паузы circuit в 10 секунд успешные пробные вызовы восстановят работу.

Eureka показывает зарегистрированные сервисы, а `/project-service/default` на порту Config
Server — централизованную конфигурацию. Детали транзакций и карта файлов приведены
в README каждого бизнес-сервиса ниже. Скрипты `scripts/*smoke.py` оставлены для автоматических
проверок и CI; для демонстрации они не нужны.

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
