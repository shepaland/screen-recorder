/**
 * Format an ISO timestamp string for display.
 */
export function formatDateTime(isoString: string | null | undefined): string {
  if (!isoString) return '--';
  try {
    return new Date(isoString).toLocaleString();
  } catch {
    return isoString;
  }
}

/**
 * Format an ISO timestamp string as date only.
 */
export function formatDate(isoString: string | null | undefined): string {
  if (!isoString) return '--';
  try {
    return new Date(isoString).toLocaleDateString();
  } catch {
    return isoString;
  }
}

/**
 * Truncate a UUID to first 8 characters for display.
 */
export function truncateUuid(uuid: string | null | undefined): string {
  if (!uuid) return '--';
  return uuid.length > 8 ? `${uuid.substring(0, 8)}...` : uuid;
}

/**
 * Build a full name from first_name and last_name, falling back to username.
 */
export function displayName(
  firstName: string | null | undefined,
  lastName: string | null | undefined,
  username?: string,
): string {
  const parts = [firstName, lastName].filter(Boolean);
  if (parts.length > 0) return parts.join(' ');
  return username || '--';
}
