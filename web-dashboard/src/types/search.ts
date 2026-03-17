export interface SegmentSearchResult {
  segment_id: string;
  tenant_id: string;
  device_id: string;
  session_id: string;
  sequence_num: number;
  s3_key: string;
  size_bytes: number;
  duration_ms: number;
  checksum_sha256: string;
  timestamp: string;
  metadata: Record<string, unknown>;
}

export interface SearchParams {
  q?: string;
  device_id?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface SearchResponse {
  content: SegmentSearchResult[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}
