Используй субагента developer. Реализуй фичу: $ARGUMENTS

Порядок работы:
1. Изучи существующий код затронутых сервисов — паттерны, структуру пакетов, naming
2. Если нужны изменения БД — создай Flyway миграцию V{next}__{description}.sql
3. Реализуй: entity, repository, service, DTO, controller, маппинг
4. Добавь валидацию входных данных (Bean Validation)
5. Обеспечь tenant isolation (tenant_id из JWT во всех запросах)
6. Добавь structured logging (MDC: tenant_id, user_id, correlation_id)
7. Напиши unit-тесты (JUnit 5 + Mockito)
8. Проверь компиляцию: ./mvnw compile
9. Прогони тесты: ./mvnw test
10. Покажи итог: какие файлы созданы/изменены, что проверено
