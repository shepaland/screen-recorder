# T-175.6: Backend — switchTenant через membership, onboarding

**Фаза:** B
**Приоритет:** Medium
**Зависит от:** T-175.5
**Блокирует:** T-175.7

---

## Цель

switchTenant работает через `tenant_memberships`. Onboarding добавляет membership вместо создания нового User. Один человек = один User.

---

## Шаги реализации

### Шаг 1: AuthService.switchTenant() — через membership

```java
// БЫЛО: найти другого User с тем же username в целевом тенанте
// СТАЛО:
TenantMembership membership = membershipRepo
    .findByUserIdAndTenantIdAndIsActiveTrue(userId, targetTenantId)
    .orElseThrow(() -> new ForbiddenException("No access to tenant"));

// Роли из membership (не из user.roles)
List<String> roles = membership.getRoles().stream().map(Role::getCode).toList();
List<String> permissions = membership.getRoles().stream()
    .flatMap(r -> r.getPermissions().stream())
    .map(Permission::getCode).distinct().sorted().toList();

// Выдать JWT с targetTenantId + роли из membership
return generateTokens(user, targetTenantId, roles, permissions);
```

### Шаг 2: OnboardingService.onboard() — membership вместо нового User

```java
// Проверить: существует ли User с этим email
Optional<User> existingUser = userRepository.findByEmailAndIsActiveTrue(
    identity.getEmail().toLowerCase().trim());

User user;
if (existingUser.isPresent()) {
    // User уже есть — добавить membership в новый тенант
    user = existingUser.get();
} else {
    // Создать нового User
    user = userRepository.save(User.builder()
        .username(identity.getEmail().toLowerCase().trim())
        .email(identity.getEmail().toLowerCase().trim())
        .authProvider("oauth")
        .firstName(firstName).lastName(lastName)
        .isActive(true).build());
}

// Создать тенант
Tenant tenant = tenantRepository.save(...);

// Копировать роли из template
copySystemRoles(tenant);

// Создать membership (User → новый тенант, роль OWNER)
Role ownerRole = roleRepository.findByTenantIdAndCode(tenant.getId(), "OWNER");
TenantMembership membership = membershipRepo.save(TenantMembership.builder()
    .user(user).tenant(tenant).isActive(true).isDefault(false)
    .roles(Set.of(ownerRole)).build());

// Выдать JWT для нового тенанта
return generateTokens(user, tenant.getId(), ...);
```

### Шаг 3: JwtTokenProvider — роли из membership

```java
// При генерации токена — получить роли из membership, не из user.roles
TenantMembership membership = membershipRepo
    .findByUserIdAndTenantIdAndIsActiveTrue(userId, tenantId)
    .orElseThrow();

List<String> roles = membership.getRoles().stream().map(Role::getCode).toList();
claims.put("roles", roles);
claims.put("permissions", flatPermissions(membership.getRoles()));
```

### Шаг 4: API — список тенантов пользователя

```java
// GET /api/v1/auth/tenants — список тенантов текущего пользователя
@GetMapping("/tenants")
public List<TenantInfo> getMyTenants(@AuthenticationPrincipal UserPrincipal principal) {
    return membershipRepo.findActiveByUserId(principal.getUserId())
        .stream().map(m -> new TenantInfo(
            m.getTenant().getId(), m.getTenant().getName(),
            m.getTenant().getSlug(), m.isDefault()))
        .toList();
}
```

---

## Ключевые файлы

| Файл | Действие |
|------|----------|
| `auth-service/.../service/AuthService.java` | switchTenant через membership |
| `auth-service/.../service/OnboardingService.java` | membership вместо нового User |
| `auth-service/.../security/JwtTokenProvider.java` | Роли из membership |
| `auth-service/.../controller/AuthController.java` | GET /auth/tenants |
| `auth-service/.../repository/TenantMembershipRepository.java` | Queries |

---

## Тест-кейсы

| # | ID | Шаг | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|----------|
| 1 | TC-175.6.1 | OAuth onboarding: создать новый тенант "Test Org" | Тенант создан, User получил membership с ролью OWNER | |
| 2 | TC-175.6.2 | OAuth onboarding: тот же email, создать второй тенант "Test Org 2" | Тенант создан, **тот же User** получил второй membership | |
| 3 | TC-175.6.3 | GET /api/v1/auth/tenants | Массив с 2 тенантами (Test Org, Test Org 2) | |
| 4 | TC-175.6.4 | POST /api/v1/auth/switch-tenant на "Test Org 2" | Новый JWT с tenant_id = Test Org 2 | |
| 5 | TC-175.6.5 | POST /api/v1/auth/switch-tenant на тенант без membership | **403 Forbidden** | |
| 6 | TC-175.6.6 | Chromium: логин → TenantSwitcher показывает 2 тенанта | Список в sidebar | Скриншот |
| 7 | TC-175.6.7 | Chromium: нажать на другой тенант в switcher | Переключение, Dashboard обновляется | Скриншот |
| 8 | TC-175.6.8 | JWT claims после switchTenant | tenant_id = новый, roles/permissions из нового membership | |
