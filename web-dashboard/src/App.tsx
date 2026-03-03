import { Routes, Route, Navigate } from 'react-router-dom';
import AuthLayout from './layouts/AuthLayout';
import MainLayout from './layouts/MainLayout';
import ProtectedRoute from './components/ProtectedRoute';
import OAuthLoginPage from './pages/OAuthLoginPage';
import AdminLoginPage from './pages/AdminLoginPage';
import OAuthCallbackPage from './pages/OAuthCallbackPage';
import TenantSelectPage from './pages/TenantSelectPage';
import OnboardingPage from './pages/OnboardingPage';
import DashboardPage from './pages/DashboardPage';
import UsersListPage from './pages/UsersListPage';
import UserCreatePage from './pages/UserCreatePage';
import UserDetailPage from './pages/UserDetailPage';
import RolesListPage from './pages/RolesListPage';
import RoleCreatePage from './pages/RoleCreatePage';
import RoleDetailPage from './pages/RoleDetailPage';
import DevicesListPage from './pages/DevicesListPage';
import DeviceDetailPage from './pages/DeviceDetailPage';
import DeviceTokensListPage from './pages/DeviceTokensListPage';
import DeviceTokenCreatePage from './pages/DeviceTokenCreatePage';
import AuditLogPage from './pages/AuditLogPage';
import SettingsPage from './pages/SettingsPage';
// ProfilePage merged into SettingsPage
import TenantsPage from './pages/TenantsPage';
import TenantCreatePage from './pages/TenantCreatePage';

function App() {
  return (
    <Routes>
      {/* Public routes -- auth pages */}
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<OAuthLoginPage />} />
        <Route path="/login/admin" element={<AdminLoginPage />} />
      </Route>

      {/* Public routes -- OAuth flow (full-screen, no AuthLayout wrapper) */}
      <Route path="/oauth/callback" element={<OAuthCallbackPage />} />
      <Route path="/select-tenant" element={<TenantSelectPage />} />
      <Route path="/onboarding" element={<OnboardingPage />} />

      {/* Protected routes */}
      <Route
        element={
          <ProtectedRoute>
            <MainLayout />
          </ProtectedRoute>
        }
      >
        <Route
          path="/"
          element={
            <ProtectedRoute permission="DASHBOARD:VIEW">
              <DashboardPage />
            </ProtectedRoute>
          }
        />

        {/* Users */}
        <Route
          path="/users"
          element={
            <ProtectedRoute permission="USERS:READ">
              <UsersListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/users/new"
          element={
            <ProtectedRoute permission="USERS:CREATE">
              <UserCreatePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/users/:id"
          element={
            <ProtectedRoute permission="USERS:READ">
              <UserDetailPage />
            </ProtectedRoute>
          }
        />

        {/* Roles */}
        <Route
          path="/roles"
          element={
            <ProtectedRoute permission="ROLES:READ">
              <RolesListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/roles/new"
          element={
            <ProtectedRoute permission="ROLES:CREATE">
              <RoleCreatePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/roles/:id"
          element={
            <ProtectedRoute permission="ROLES:READ">
              <RoleDetailPage />
            </ProtectedRoute>
          }
        />

        {/* Devices */}
        <Route
          path="/devices"
          element={
            <ProtectedRoute permission="DEVICES:READ">
              <DevicesListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/devices/:id"
          element={
            <ProtectedRoute permission="DEVICES:READ">
              <DeviceDetailPage />
            </ProtectedRoute>
          }
        />

        {/* Device Tokens */}
        <Route
          path="/device-tokens"
          element={
            <ProtectedRoute permission="DEVICE_TOKENS:READ">
              <DeviceTokensListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/device-tokens/create"
          element={
            <ProtectedRoute permission="DEVICE_TOKENS:CREATE">
              <DeviceTokenCreatePage />
            </ProtectedRoute>
          }
        />

        {/* Audit */}
        <Route
          path="/audit"
          element={
            <ProtectedRoute permission="AUDIT:READ">
              <AuditLogPage />
            </ProtectedRoute>
          }
        />

        {/* Tenants */}
        <Route
          path="/tenants"
          element={
            <ProtectedRoute permission="TENANTS:READ">
              <TenantsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/tenants/new"
          element={
            <ProtectedRoute permission="TENANTS:CREATE">
              <TenantCreatePage />
            </ProtectedRoute>
          }
        />

        {/* Settings (includes profile info) - any authenticated user */}
        <Route path="/settings" element={<SettingsPage />} />
      </Route>

      {/* Legacy redirect: /profile -> /settings */}
      <Route path="/profile" element={<Navigate to="/settings" replace />} />

      {/* Catch-all redirect */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;
