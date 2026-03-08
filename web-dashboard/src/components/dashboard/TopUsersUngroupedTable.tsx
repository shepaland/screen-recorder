import { useEffect, useState } from 'react';
import * as catalogsApi from '../../api/catalogs';
import type { GroupType, TopUsersUngroupedResponse } from '../../types/catalogs';

interface TopUsersUngroupedTableProps {
  itemType: GroupType;
  title: string;
}

function formatDuration(ms: number): string {
  const totalMin = Math.floor(ms / 60000);
  if (totalMin < 60) return `${totalMin} мин`;
  const hours = Math.floor(totalMin / 60);
  const min = totalMin % 60;
  return min > 0 ? `${hours}ч ${min}м` : `${hours}ч`;
}

export default function TopUsersUngroupedTable({ itemType, title }: TopUsersUngroupedTableProps) {
  const [data, setData] = useState<TopUsersUngroupedResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await catalogsApi.getTopUsersUngrouped(itemType, undefined, undefined, 10);
      setData(resp);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load data';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [itemType]);

  if (error) {
    return (
      <div className="card">
        <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <p className="text-sm text-red-700">{error}</p>
          <button onClick={load} className="mt-2 text-sm font-medium text-red-700 underline hover:text-red-600">
            Повторить
          </button>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="card">
        <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
        <div className="flex h-48 items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-red-600" />
        </div>
      </div>
    );
  }

  if (!data || data.users.length === 0) {
    return (
      <div className="card">
        <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
        <div className="flex h-32 items-center justify-center text-sm text-gray-400">
          Нет данных
        </div>
      </div>
    );
  }

  return (
    <div className="card">
      <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200">
              <th className="pb-2 pr-3 text-left font-medium text-gray-500">Пользователь</th>
              <th className="pb-2 px-3 text-right font-medium text-gray-500">Неразмеч.</th>
              <th className="pb-2 px-3 text-right font-medium text-gray-500">%</th>
              <th className="pb-2 pl-3 text-left font-medium text-gray-500">Топ неразмеченных</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {data.users.map((user) => (
              <tr key={user.username} className="hover:bg-gray-50">
                <td className="py-2 pr-3">
                  <div className="font-medium text-gray-900 truncate max-w-[180px]" title={user.username}>
                    {user.display_name || user.username}
                  </div>
                  {user.display_name && (
                    <div className="text-xs text-gray-400 truncate max-w-[180px]">{user.username}</div>
                  )}
                </td>
                <td className="py-2 px-3 text-right text-gray-600 whitespace-nowrap">
                  {formatDuration(user.total_ungrouped_duration_ms)}
                </td>
                <td className="py-2 px-3 text-right">
                  <span className="inline-flex items-center rounded-full bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-800">
                    {user.ungrouped_percentage.toFixed(1)}%
                  </span>
                </td>
                <td className="py-2 pl-3">
                  <div className="flex flex-wrap gap-1">
                    {user.top_ungrouped_items.slice(0, 3).map((item) => (
                      <span
                        key={item.name}
                        className="inline-flex items-center rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600"
                        title={`${item.name}: ${formatDuration(item.duration_ms)}`}
                      >
                        {item.name.length > 20 ? item.name.slice(0, 18) + '...' : item.name}
                      </span>
                    ))}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
