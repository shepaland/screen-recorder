import type { User } from '../types';

const ADMIN_ROLES = ['OWNER', 'TENANT_ADMIN', 'SUPER_ADMIN'];

export function isAdmin(user: User | null | undefined): boolean {
  return user?.roles?.some(r => ADMIN_ROLES.includes(r)) ?? false;
}

export function hasRole(user: User | null | undefined, role: string): boolean {
  return user?.roles?.includes(role) ?? false;
}
