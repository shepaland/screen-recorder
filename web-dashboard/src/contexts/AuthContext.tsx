import {
  createContext,
  useState,
  useEffect,
  useCallback,
  useRef,
  type ReactNode,
} from 'react';
import type { User, LoginRequest } from '../types';
import * as authApi from '../api/auth';
import { setAccessToken } from '../api/client';

interface AuthContextValue {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (credentials: LoginRequest) => Promise<void>;
  logout: () => Promise<void>;
  refreshToken: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

// Parse JWT payload to get expiration
function getTokenExpiry(token: string): number | null {
  try {
    const payload = token.split('.')[1];
    const decoded = JSON.parse(atob(payload));
    return decoded.exp ? decoded.exp * 1000 : null; // Convert to ms
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const currentTokenRef = useRef<string | null>(null);

  const clearRefreshTimer = useCallback(() => {
    if (refreshTimerRef.current) {
      clearTimeout(refreshTimerRef.current);
      refreshTimerRef.current = null;
    }
  }, []);

  const scheduleRefresh = useCallback(
    (token: string) => {
      clearRefreshTimer();
      const expiry = getTokenExpiry(token);
      if (!expiry) return;

      // Refresh 1 minute before expiry
      const refreshAt = expiry - Date.now() - 60_000;
      if (refreshAt <= 0) return;

      refreshTimerRef.current = setTimeout(async () => {
        try {
          const response = await authApi.refresh();
          currentTokenRef.current = response.access_token;
          scheduleRefresh(response.access_token);
        } catch {
          // Refresh failed, user will be redirected on next API call
          setUser(null);
          currentTokenRef.current = null;
        }
      }, refreshAt);
    },
    [clearRefreshTimer],
  );

  const login = useCallback(
    async (credentials: LoginRequest) => {
      const response = await authApi.login(credentials);
      currentTokenRef.current = response.access_token;
      setUser(response.user);
      scheduleRefresh(response.access_token);
    },
    [scheduleRefresh],
  );

  const logout = useCallback(async () => {
    clearRefreshTimer();
    try {
      await authApi.logout();
    } finally {
      setUser(null);
      currentTokenRef.current = null;
      setAccessToken(null);
    }
  }, [clearRefreshTimer]);

  const refreshToken = useCallback(async () => {
    const response = await authApi.refresh();
    currentTokenRef.current = response.access_token;
    scheduleRefresh(response.access_token);
  }, [scheduleRefresh]);

  // On mount: try to restore session via refresh cookie
  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      try {
        const tokenResponse = await authApi.refresh();
        if (cancelled) return;
        currentTokenRef.current = tokenResponse.access_token;
        scheduleRefresh(tokenResponse.access_token);

        const userData = await authApi.getMe();
        if (cancelled) return;
        setUser(userData);
      } catch {
        // No valid session, user needs to login
        if (!cancelled) {
          setUser(null);
          currentTokenRef.current = null;
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    }

    restoreSession();

    return () => {
      cancelled = true;
      clearRefreshTimer();
    };
  }, [scheduleRefresh, clearRefreshTimer]);

  const value: AuthContextValue = {
    user,
    isAuthenticated: !!user,
    isLoading,
    login,
    logout,
    refreshToken,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
