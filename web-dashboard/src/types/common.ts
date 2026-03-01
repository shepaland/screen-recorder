export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export interface ErrorResponse {
  error: string;
  code: string;
}

export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
}
