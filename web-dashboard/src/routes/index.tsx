/**
 * Route configuration for the application.
 *
 * All routes are defined in App.tsx using react-router-dom v6.
 * This file documents the route structure and permission requirements.
 *
 * Route Map:
 *
 * PUBLIC:
 *   /login                - LoginPage (AuthLayout)
 *
 * PROTECTED (MainLayout):
 *   /                     - DashboardPage    (DASHBOARD:VIEW)
 *   /users                - UsersListPage    (USERS:READ)
 *   /users/new            - UserCreatePage   (USERS:CREATE)
 *   /users/:id            - UserDetailPage   (USERS:READ)
 *   /roles                - RolesListPage    (ROLES:READ)
 *   /roles/new            - RoleCreatePage   (ROLES:CREATE)
 *   /roles/:id            - RoleDetailPage   (ROLES:READ)
 *   /audit                - AuditLogPage     (AUDIT:READ)
 *   /tenants              - TenantsPage      (TENANTS:READ)
 *   /tenants/new          - TenantCreatePage (TENANTS:CREATE)
 *   /profile              - ProfilePage      (authenticated)
 */

export const ROUTES = {
  LOGIN: '/login',
  DASHBOARD: '/',
  USERS: '/users',
  USER_CREATE: '/users/new',
  USER_DETAIL: (id: string) => `/users/${id}`,
  ROLES: '/roles',
  ROLE_CREATE: '/roles/new',
  ROLE_DETAIL: (id: string) => `/roles/${id}`,
  AUDIT: '/audit',
  TENANTS: '/tenants',
  TENANT_CREATE: '/tenants/new',
  PROFILE: '/profile',
} as const;
