Используй субагента security-reviewer. Проведи аудит безопасности: $ARGUMENTS

Если аргумент не указан — аудит всего проекта.

Проверь:
1. **Injection** — SQL injection (параметризация в JPA), XSS (React escaping, dangerouslySetInnerHTML), command injection, SSTI
2. **Аутентификация** — JWT подпись и валидация, token blacklist, refresh flow, bcrypt cost
3. **Авторизация** — RBAC на каждом endpoint, ABAC-проверки, S2S аутентификация (X-Internal-API-Key)
4. **Tenant isolation** — tenant_id фильтрация в каждом SQL/OpenSearch/S3 запросе, NATS subjects
5. **Секреты** — поиск ключей, паролей, токенов в коде, конфигах, логах, Docker images: grep -r "password\|secret\|api.key\|token" --include="*.java" --include="*.yml" --include="*.properties" --include="*.ts"
6. **Зависимости** — ./mvnw dependency:tree, npm audit, известные CVE
7. **Конфигурация k8s** — securityContext, resource limits, networkPolicy, non-root
8. **HTTP-заголовки** — CORS, CSP, X-Frame-Options, Strict-Transport-Security
9. **Логирование** — PII и токены не попадают в логи

Классифицируй: CRITICAL / HIGH / MEDIUM / LOW / INFO.
Для каждой находки: файл, строка, описание, вектор атаки, fix.
