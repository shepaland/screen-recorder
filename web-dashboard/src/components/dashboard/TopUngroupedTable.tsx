import { useEffect, useState } from 'react';
import { PlusIcon } from '@heroicons/react/24/outline';
import * as catalogsApi from '../../api/catalogs';
import type { GroupType, TopUngroupedResponse, AppGroup } from '../../types/catalogs';
import AssignGroupDropdown from '../catalogs/AssignGroupDropdown';

interface TopUngroupedTableProps {
  itemType: GroupType;
  title: string;
  from: string;
  to: string;
}

function formatDuration(ms: number): string {
  const totalMin = Math.floor(ms / 60000);
  if (totalMin < 60) return `${totalMin} мин`;
  const hours = Math.floor(totalMin / 60);
  const min = totalMin % 60;
  return min > 0 ? `${hours}ч ${min}м` : `${hours}ч`;
}

export default function TopUngroupedTable({ itemType, title, from, to }: TopUngroupedTableProps) {
  const [data, setData] = useState<TopUngroupedResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [groups, setGroups] = useState<AppGroup[]>([]);
  const [assigningItem, setAssigningItem] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [ungrouped, groupsResp] = await Promise.all([
        catalogsApi.getTopUngrouped(itemType, from, to, 10),
        catalogsApi.getGroups(itemType),
      ]);
      setData(ungrouped);
      setGroups(groupsResp);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load data';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [itemType, from, to]);

  const handleAssign = async (groupId: string, pattern: string) => {
    try {
      await catalogsApi.addGroupItem(groupId, { pattern, match_type: 'EXACT' });
      setAssigningItem(null);
      // Remove the assigned item from the list
      if (data) {
        setData({
          ...data,
          items: data.items.filter((item) => item.name !== pattern),
        });
      }
    } catch {
      // Error is handled by the dropdown
    }
  };

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

  if (!data || data.items.length === 0) {
    return (
      <div className="card">
        <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
        <div className="flex h-32 items-center justify-center text-sm text-gray-400">
          Все элементы распределены по группам
        </div>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-gray-900">{title}</h3>
        <span className="inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
          {data.items.length} неразмеч.
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200">
              <th className="pb-2 pr-3 text-left font-medium text-gray-500">Название</th>
              <th className="pb-2 px-3 text-right font-medium text-gray-500">Время</th>
              <th className="pb-2 px-3 text-right font-medium text-gray-500">%</th>
              <th className="pb-2 px-3 text-right font-medium text-gray-500">Польз.</th>
              <th className="pb-2 pl-3 text-right font-medium text-gray-500"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {data.items.map((item) => (
              <tr key={item.name} className="hover:bg-amber-50/50">
                <td className="py-2 pr-3">
                  <div className="font-medium text-gray-900 truncate max-w-[200px]" title={item.name}>
                    {item.display_name || item.name}
                  </div>
                  {item.display_name && (
                    <div className="text-xs text-gray-400 truncate max-w-[200px]">{item.name}</div>
                  )}
                </td>
                <td className="py-2 px-3 text-right text-gray-600 whitespace-nowrap">
                  {formatDuration(item.total_duration_ms)}
                </td>
                <td className="py-2 px-3 text-right">
                  <span className="inline-flex items-center rounded-full bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-800">
                    {item.percentage.toFixed(1)}%
                  </span>
                </td>
                <td className="py-2 px-3 text-right text-gray-600">
                  {item.user_count}
                </td>
                <td className="py-2 pl-3 text-right relative">
                  {assigningItem === item.name ? (
                    <AssignGroupDropdown
                      groups={groups}
                      onSelect={(groupId) => handleAssign(groupId, item.name)}
                      onClose={() => setAssigningItem(null)}
                    />
                  ) : (
                    <button
                      onClick={() => setAssigningItem(item.name)}
                      className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50 transition-colors"
                      title="В группу"
                    >
                      <PlusIcon className="h-3.5 w-3.5" />
                      В группу
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
