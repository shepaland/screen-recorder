import axios from 'axios';
import { getAccessToken } from './client';
import type { SearchParams, SearchResponse } from '../types/search';

const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
const SEARCH_API_BASE_URL = import.meta.env.VITE_SEARCH_API_BASE_URL || `${basePath}/api/search/v1/search`;

const searchApiClient = axios.create({
  baseURL: SEARCH_API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
});

searchApiClient.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export async function searchSegments(params: SearchParams): Promise<SearchResponse> {
  const response = await searchApiClient.get<SearchResponse>('/segments', { params });
  return response.data;
}
