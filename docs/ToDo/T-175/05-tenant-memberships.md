# T-175.5: Таблица tenant_memberships + миграция данных

**Фаза:** B
**Приоритет:** Medium
**Зависит от:** T-175.1-4 (Фаза A завершена)
**Блокирует:** T-175.6

---

## Цель

Один User может быть членом нескольких тенантов через таблицу `tenant_memberships`. Убрать дублирование записей User.

---

## Шаги реализации

### Шаг 1: Flyway V44 — новая таблица + миграция

```sql
-- V44__tenant_memberships.sql

-- 1. Новая таблица
CREATE TABLE tenant_memberships (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    joined_ts   TIMESTAMPTZ NOT NULL DEFAULT now(),
    invited_by  UUID REFERENCES users(id),
    UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_tm_user ON tenant_memberships(user_id) WHERE is_active = TRUE;
CREATE INDEX idx_tm_tenant ON tenant_memberships(tenant_id) WHERE is_active = TRUE;

-- 2. Таблица ролей в membership (замена user_roles)
CREATE TABLE membership_roles (
    membership_id UUID NOT NULL REFERENCES tenant_memberships(id) ON DELETE CASCADE,
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (membership_id, role_id)
);

-- 3. Миграция: для каждого активного User создать membership
INSERT INTO tenant_memberships (user_id, tenant_id, is_default, joined_ts)
SELECT id, tenant_id, TRUE, created_ts
FROM users WHERE is_active = TRUE;

-- 4. Миграция: перенести user_roles → membership_roles
INSERT INTO membership_roles (membership_id, role_id)
SELECT tm.id, ur.role_id
FROM user_roles ur
JOIN users u ON ur.user_id = u.id
JOIN tenant_memberships tm ON tm.user_id = u.id AND tm.tenant_id = u.tenant_id;
```

### Шаг 2: Entity TenantMembership.java

```java
@Entity
@Table(name = "tenant_memberships")
public class TenantMembership {
    @Id private UUID id;
    @ManyToOne private User user;
    @ManyToOne private Tenant tenant;
    @ManyToMany private Set<Role> roles;
    private boolean isActive;
    private boolean isDefault;
    private Instant joinedTs;
    @ManyToOne private User invitedBy;
}
```

### Шаг 3: User.java — добавить связь

```java
// ДОБАВИТЬ:
@OneToMany(mappedBy = "user")
private Set<TenantMembership> memberships;

// НЕ УДАЛЯТЬ tenant FK пока (обратная совместимость):
@ManyToOne private Tenant tenant;  // deprecated, использовать memberships
```

---

## Ключевые файлы

| Файл | Действие |
|------|----------|
| `auth-service/.../db/migration/V44__tenant_memberships.sql` | НОВЫЙ |
| `auth-service/.../entity/TenantMembership.java` | НОВЫЙ entity |
| `auth-service/.../entity/User.java` | Добавить `memberships` |
| `auth-service/.../repository/TenantMembershipRepository.java` | НОВЫЙ repository |

---

## Тест-кейсы

| # | ID | Шаг | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|----------|
| 1 | TC-175.5.1 | Flyway V44 applied | Таблицы `tenant_memberships`, `membership_roles` созданы | |
| 2 | TC-175.5.2 | Проверить миграцию данных: `SELECT COUNT(*) FROM tenant_memberships` | = количество активных users | |
| 3 | TC-175.5.3 | Проверить роли: `SELECT COUNT(*) FROM membership_roles` | = количество записей в user_roles | |
| 4 | TC-175.5.4 | Проверить UNIQUE: INSERT дубликат (user_id, tenant_id) | ERROR: unique violation | |
| 5 | TC-175.5.5 | Chromium: логин → Dashboard работает (обратная совместимость) | Логин успешен, Dashboard загружается | Скриншот |
| 6 | TC-175.5.6 | Chromium: список пользователей отображается корректно | Список не пустой, роли видны | Скриншот |
