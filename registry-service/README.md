# Registry — B3: Reactor + JPA

Сервис хранит версии моделей в собственной PostgreSQL-базе `registry`.
Публичный API доступен через Gateway и общий Swagger. Версия создаётся из MODEL-артефакта
завершённого запуска того же проекта. Жизненный цикл: NEW → STAGING → PRODUCTION → ARCHIVED.
Старые версии сохраняются; произвольного UPDATE/DELETE истории нет.

## С чего начать чтение

Основной код находится в `src/main/java/ru/itmo/highload_ml/registry/`.

| Файл | За что отвечает |
|---|---|
| `api/ModelVersionController.java` | URL, статусы, заголовки и единственная граница Reactor |
| `service/RegistryService.java` | Проверки проекта, участника и исходного артефакта через Feign |
| `service/ModelVersionService.java` | JPA-операции, правила состояний и транзакции |
| `model/ModelVersion.java` | Entity с Lombok и допустимые переходы состояний |
| `repository/ModelVersionRepository.java` | JPA-запросы и блокировка версии |
| `repository/RegistryCounterRepository.java` | Атомарная нумерация и блокировка проекта через JDBC |
| `client/ProjectClient.java`, `client/TrackingClient.java` | Два Feign-интерфейса и небольшие DTO внешних ответов |
| `api/dto/` | Три DTO публичного API: регистрация, действие, ответ |
| `api/RegistryPagination.java` | Размер страницы максимум 50 и сортировка по убыванию номера |
| `api/ApiExceptionHandler.java` | Problem Details без внутренних подробностей |
| `src/main/resources/db/changelog/` | Liquibase-схема; Hibernate только проверяет её |

Поток одного запроса:

```text
HTTP → Controller.blocking() → RegistryService (Feign-проверки)
                            → ModelVersionService (@Transactional) → PostgreSQL
                            → commit → DTO → HTTP-ответ
```

## Как здесь совмещены Reactor и JPA

`blocking(...)` — три строки в контроллере:

```java
return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
```

Вся блокирующая работа, включая Feign и вызов JPA-сервиса через Spring-прокси,
выполняется на worker-потоке. `@Transactional` находится на обычных методах
отдельного bean `ModelVersionService`, возвращающих DTO/Page, поэтому транзакция
действует во время SQL и завершается до выдачи результата из Mono.
Аннотации на методе, который только создаёт Mono, здесь нет; `.block()` в production-коде нет.

Списочный endpoint возвращает `Flux<ModelVersionResponse>` и JSON-массив.
Внутри JPA работает обычный `Page`: не более 50 DTO преобразуются в Flux после закрытия
транзакции. Поэтому наружу не выходят Entity, ленивые связи и вложенные ResponseEntity.

## Зачем нужны транзакции и блокировки

- Регистрация: SQL upsert выдаёт номер версии, затем JPA сохраняет версию.
  JDBC использует то же соединение и транзакцию, что JPA. Если вставка не удалась,
  откатывается и счётчик. `MAX(version) + 1` не используется.
- Публикация: сначала блокируется строка счётчика проекта, затем целевая версия.
  Предыдущая PRODUCTION архивируется, выполняется flush, новая становится PRODUCTION.
  Если второй шаг не удался, прежняя production-версия восстанавливается откатом.
- Stage блокирует изменяемую версию. Конкурентные переходы не обходят проверку состояния.
- Частичный UNIQUE-индекс дополнительно запрещает две PRODUCTION-версии одного проекта.

В БД нет внешних ключей на чужие сервисы, только UUID. HTTP-проверки выполняются до
локальной транзакции: это не распределённая транзакция. Исторический артефакт допустим
даже при `active=false` эксперимента — запись эксперимента сохраняется soft delete.

## API

Префикс: `/api/v1/projects/{projectId}/model-versions`.

| Метод | Путь относительно префикса | Ответ |
|---|---|---|
| POST | базовый путь | 201 + Location; тело запроса `{userId, artifactId}` |
| GET | `/{versionId}` | Одна версия |
| GET | `?page=0&size=20` | Массив, X-Total-Count, максимум 50 |
| GET | `/production` | Текущая production-версия |
| POST | `/{versionId}/stage` | NEW → STAGING; тело `{userId}` |
| POST | `/{versionId}/promote` | STAGING → PRODUCTION; тело `{userId}` |

Ошибки: 400 — валидация, 404 — отсутствующий ресурс, 409 — повтор артефакта,
422 — неверный источник/состояние или отсутствие членства, 503 — недоступная зависимость.
Полноценная аутентификация остаётся для ЛР3: переданный userId ещё не доказывает личность.

Feign использует Eureka; для тестов можно задать `app.project-service.url` и
`app.tracking-service.url`. Таймауты: connect 2 секунды, read 5 секунд.
Circuit Breaker остаётся B4. Для Feign в WebFlux явно подключён `spring-boot-http-converter`.

## Проверка из корня lab-2

```sh
./gradlew :registry-service:test
./gradlew :registry-service:check :registry-service:jacocoTestReport :registry-service:bootJar
python3 scripts/registry-smoke.py  # при запущенном стеке
```

`test` работает без Docker. `check` дополнительно запускает PostgreSQL/Testcontainers
и проверяет минимум 70% покрытия строк. Один PostgreSQL-контейнер переиспользуется
всеми интеграционными тестами registry. В тестах проверяются rollback, гонки,
WebFlux-потоки, реальные Feign-прокси и контракт API. Отчёт покрытия:
`build/reports/jacoco/test/html/index.html` внутри этого модуля.
