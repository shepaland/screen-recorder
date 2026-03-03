import {
  createContext,
  useState,
  useEffect,
  useCallback,
  useRef,
  type ReactNode,
} from 'react';
import type { User, LoginRequest, TenantPreview } from '../types';
import * as authApi from '../api/auth';
import { setAccessToken } from '../api/client';

interface AuthContextValue {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (credentials: LoginRequest) => Promise<void>;
  logout: () => Promise<void>;
  refreshToken: () => Promise<void>;
  switchTenant: (tenantId: string) => Promise<void>;
  currentTenantId: string | null;
  tenants: TenantPreview[];
  setUserFromToken: (token: string) => Promise<void>;
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

/**
 * Check URL for access_token query param (OAuth callback redirect).
 * If found, extract it and clean the URL.
 */
function extractAccessTokenFromUrl(): string | null {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('access_token');
  if (token) {
    params.delete('access_token');
    const newSearch = params.toString();
    const newUrl =
      window.location.pathname + (newSearch ? `?${newSearch}` : '') + window.location.hash;
    window.history.replaceState({}, '', newUrl);
  }
  return token;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [tenants, setTenants] = useState<TenantPreview[]>([]);
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

  const loadTenants = useCallback(async (userData: User) => {
    // Load tenants list for OAuth users
    if (userData.auth_provider === 'oauth') {
      try {
        const data = await authApi.getMyTenants();
        setTenants(data.tenants);
      } catch {
        // Non-critical: tenants list is optional
        setTenants([]);
      }
    }
  }, []);

  const login = useCallback(
    async (credentials: LoginRequest) => {
      const response = await authApi.login(credentials);
      currentTokenRef.current = response.access_token;
      setUser(response.user);
      scheduleRefresh(response.access_token);
      await loadTenants(response.user);
    },
    [scheduleRefresh, loadTenants],
  );

  const logout = useCallback(async () => {
    clearRefreshTimer();
    try {
      await authApi.logout();
    } finally {
      setUser(null);
      setTenants([]);
      currentTokenRef.current = null;
      setAccessToken(null);
    }
  }, [clearRefreshTimer]);

  const refreshToken = useCallback(async () => {
    const response = await authApi.refresh();
    currentTokenRef.current = response.access_token;
    scheduleRefresh(response.access_token);
  }, [scheduleRefresh]);

  const switchTenant = useCallback(
    async (tenantId: string) => {
      const response = await authApi.switchTenant(tenantId);
      currentTokenRef.current = response.access_token;
      setUser(response.user);
      scheduleRefresh(response.access_token);
      // Reload tenants after switch
      await loadTenants(response.user);
    },
    [scheduleRefresh, loadTenants],
  );

  const setUserFromToken = useCallback(
    async (token: string) => {
      setAccessToken(token);
      currentTokenRef.current = token;
      scheduleRefresh(token);

      const userData = await authApi.getMe();
      setUser(userData);
      await loadTenants(userData);
    },
    [scheduleRefresh, loadTenants],
  );

  // On mount: check for access_token in URL, then try to restore session
  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      try {
        // Check if there is an access_token in URL (OAuth redirect)
        const urlToken = extractAccessTokenFromUrl();

        if (urlToken) {
          setAccessToken(urlToken);
          currentTokenRef.current = urlToken;
          scheduleRefresh(urlToken);

          const userData = await authApi.getMe();
          if (cancelled) return;
          setUser(userData);
          await loadTenants(userData);
        } else {
          // Normal flow: try to restore via refresh cookie
          const tokenResponse = await authApi.refresh();
          if (cancelled) return;
          currentTokenRef.current = tokenResponse.access_token;
          scheduleRefresh(tokenResponse.access_token);

          const userData = await authApi.getMe();
          if (cancelled) return;
          setUser(userData);
          await loadTenants(userData);
        }
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
  }, [scheduleRefresh, clearRefreshTimer, loadTenants]);

  const currentTenantId = user?.tenant_id ?? null;

  const value: AuthContextValue = {
    user,
    isAuthenticated: !!user,
    isLoading,
    login,
    logout,
    refreshToken,
    switchTenant,
    currentTenantId,
    tenants,
    setUserFromToken,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
