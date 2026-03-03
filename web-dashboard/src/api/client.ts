import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios';

const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || `${basePath}/api/v1`;
const CP_API_BASE_URL = import.meta.env.VITE_CP_API_BASE_URL || `${basePath}/api/cp/v1`;

// --- Token management ---
// Access token is stored in memory only (NOT localStorage)
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

// --- Shared interceptor setup ---
let isRefreshing = false;
let failedQueue: Array<{
  resolve: (token: string) => void;
  reject: (error: unknown) => void;
}> = [];

function processQueue(error: unknown, token: string | null = null): void {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) {
      reject(error);
    } else if (token) {
      resolve(token);
    }
  });
  failedQueue = [];
}

function setupInterceptors(client: AxiosInstance, authClient?: AxiosInstance): void {
  // Request interceptor: attach Authorization header
  client.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      if (accessToken && config.headers) {
        config.headers.Authorization = `Bearer ${accessToken}`;
      }
      return config;
    },
    (error) => Promise.reject(error),
  );

  // Response interceptor: handle 401 with token refresh
  client.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const originalRequest = error.config as InternalAxiosRequestConfig & {
        _retry?: boolean;
      };

      // Skip refresh for login and refresh endpoints
      const isAuthEndpoint =
        originalRequest?.url?.includes('/auth/login') ||
        originalRequest?.url?.includes('/auth/refresh');

      if (error.response?.status === 401 && !originalRequest._retry && !isAuthEndpoint) {
        // Use the auth client (apiClient) for refresh requests
        const refreshClient = authClient || client;

        if (isRefreshing) {
          return new Promise<string>((resolve, reject) => {
            failedQueue.push({ resolve, reject });
          }).then((token) => {
            if (originalRequest.headers) {
              originalRequest.headers.Authorization = `Bearer ${token}`;
            }
            return client(originalRequest);
          });
        }

        originalRequest._retry = true;
        isRefreshing = true;

        try {
          const response = await refreshClient.post('/auth/refresh');
          const newToken = response.data.access_token as string;
          setAccessToken(newToken);
          processQueue(null, newToken);

          if (originalRequest.headers) {
            originalRequest.headers.Authorization = `Bearer ${newToken}`;
          }
          return client(originalRequest);
        } catch (refreshError) {
          processQueue(refreshError, null);
          setAccessToken(null);
          window.location.href = `${import.meta.env.BASE_URL}login`;
          return Promise.reject(refreshError);
        } finally {
          isRefreshing = false;
        }
      }

      return Promise.reject(error);
    },
  );
}

// --- Auth-service API client (default: /api/v1) ---
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
});
setupInterceptors(apiClient);

// --- Control-plane API client (/api/cp/v1) ---
export const cpApiClient = axios.create({
  baseURL: CP_API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
});
setupInterceptors(cpApiClient, apiClient);

export default apiClient;
