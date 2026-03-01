import { useCallback } from 'react';
import { useAuth } from './useAuth';

export function usePermissions() {
  const { user } = useAuth();

  const hasPermission = useCallback(
    (code: string): boolean => {
      if (!user || !user.permissions) return false;
      return user.permissions.includes(code);
    },
    [user],
  );

  const hasAnyPermission = useCallback(
    (codes: string[]): boolean => {
      if (!user || !user.permissions) return false;
      return codes.some((code) => user.permissions.includes(code));
    },
    [user],
  );

  const hasAllPermissions = useCallback(
    (codes: string[]): boolean => {
      if (!user || !user.permissions) return false;
      return codes.every((code) => user.permissions.includes(code));
    },
    [user],
  );

  return { hasPermission, hasAnyPermission, hasAllPermissions };
}
