import { Routes, Route, Navigate } from 'react-router-dom';
import AuthLayout from './layouts/AuthLayout';
import MainLayout from './layouts/MainLayout';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import OAuthCallbackPage from './pages/OAuthCallbackPage';
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
import RecordingsPage from './pages/RecordingsPage';
import DeviceGridPage from './pages/DeviceGridPage';
import DeviceRecordingsPage from './pages/DeviceRecordingsPage';
import UserListPage from './pages/UserListPage';
import UserReportsPage from './pages/UserReportsPage';
import DownloadPage from './pages/DownloadPage';
import RecordingSettingsPage from './pages/RecordingSettingsPage';

function App() {
  return (
    <Routes>
      {/* Public routes -- auth pages */}
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginPage />} />
      </Route>

      {/* Public routes -- OAuth flow (full-screen, no AuthLayout wrapper) */}
      <Route path="/oauth/callback" element={<OAuthCallbackPage />} />
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

        {/* Recordings Archive */}
        <Route path="/archive/devices" element={<DeviceGridPage />} />
        <Route path="/archive/devices/:deviceId" element={<DeviceRecordingsPage />} />
        <Route path="/archive/users" element={<UserListPage />} />
        <Route path="/archive/users/:username" element={<UserReportsPage />} />
        {/* Legacy redirects */}
        <Route path="/recordings" element={<Navigate to="/archive/devices" replace />} />
        <Route path="/recordings/:deviceId" element={<DeviceRecordingsPage />} />
        <Route path="/recordings-old" element={<RecordingsPage />} />

        {/* Download */}
        <Route path="/download" element={<DownloadPage />} />

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

        {/* Recording Settings */}
        <Route
          path="/recording-settings"
          element={
            <ProtectedRoute permission="DEVICES:READ">
              <RecordingSettingsPage />
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
        <Route path="/tenants" element={<TenantsPage />} />
        <Route
          path="/tenants/new"
          element={
            <ProtectedRoute>
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
