Используй субагента developer. Создай Flyway миграцию для: $ARGUMENTS

Порядок работы:
1. Посмотри последнюю миграцию в migrations/ — определи следующий номер версии
2. Создай файл V{version}__{description}.sql
3. SQL должен быть:
   - Идемпотентный (IF NOT EXISTS где применимо)
   - Обратно-совместимый (не удаляй колонки, не переименовывай без этапа deprecation)
   - С учётом мультитенантности (tenant_id в новых таблицах, FK на tenant)
   - С индексами на поля, по которым будет фильтрация (tenant_id, created_ts, FK)
4. Если таблица большая и time-series — рассмотри партиционирование (RANGE по created_ts)
5. Если нужны seed-данные — отдельная миграция V{version+1}__seed_{description}.sql
6. Проверь миграцию: ./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://172.17.0.1:5432/prg_dev
7. Покажи итог: DDL, индексы, constraints, обратная совместимость
