import type { ReactNode } from 'react';
import { useAuth } from '../hooks/useAuth';
import { isAdmin } from '../utils/roles';

interface AdminGateProps {
  children: ReactNode;
}

export default function AdminGate({ children }: AdminGateProps) {
  const { user } = useAuth();
  if (!isAdmin(user)) return null;
  return <>{children}</>;
}
