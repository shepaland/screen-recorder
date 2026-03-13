import { useEffect, useState } from 'react';
import { PlusIcon, MagnifyingGlassIcon } from '@heroicons/react/24/outline';
import * as catalogsApi from '../../api/catalogs';
import type { GroupType, UngroupedResponse, AppGroup } from '../../types/catalogs';
import AssignGroupDropdown from './AssignGroupDropdown';

interface UngroupedTableProps {
  itemType: GroupType;
  groups: AppGroup[];
  onAssigned: () => void;
}

function formatDuration(ms: number): string {
  const totalMin = Math.floor(ms / 60000);
  if (totalMin < 60) return `${totalMin} мин`;
  const hours = Math.floor(totalMin / 60);
  const min = totalMin % 60;
  return min > 0 ? `${hours}ч ${min}м` : `${hours}ч`;
}

export default function UngroupedTable({ itemType, groups, onAssigned }: UngroupedTableProps) {
  const [data, setData] = useState<UngroupedResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [assigningItem, setAssigningItem] = useState<{ name: string; el: HTMLElement } | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await catalogsApi.getUngrouped(itemType, {
        page,
        size: 20,
        search: search || undefined,
      });
      setData(resp);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [itemType, page, search]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setSearch(searchInput);
  };

  const handleAssign = async (groupId: string, pattern: string) => {
    try {
      await catalogsApi.addGroupItem(groupId, { pattern, match_type: 'EXACT' });
      setAssigningItem(null);
      onAssigned();
      load();
    } catch {
      // Error handling
    }
  };

  const handleOpenAssign = (e: React.MouseEvent<HTMLButtonElement>, name: string) => {
    setAssigningItem({ name, el: e.currentTarget });
  };

  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <p className="text-sm text-red-700">{error}</p>
        <button onClick={load} className="mt-2 text-sm font-medium text-red-700 underline">Повторить</button>
      </div>
    );
  }

  return (
    <div>
      {/* Search */}
      <form onSubmit={handleSearch} className="mb-4 flex gap-2">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Поиск..."
            className="input-field pl-9"
          />
        </div>
        <button type="submit" className="btn-secondary">Найти</button>
      </form>

      {loading ? (
        <div className="flex h-32 items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-red-600" />
        </div>
      ) : !data || data.content.length === 0 ? (
        <div className="text-center py-8 text-sm text-gray-400">
          {search ? 'Ничего не найдено' : 'Все элементы распределены по группам'}
        </div>
      ) : (
        <>
          <div className="mb-2 text-xs text-gray-400">
            Всего неразмеченных: {data.total_elements} (суммарное время: {formatDuration(data.total_ungrouped_duration_ms)})
          </div>
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead>
                <tr className="border-b border-gray-200">
                  <th className="pb-2 pr-3 text-left font-medium text-gray-500">Название</th>
                  <th className="pb-2 px-3 text-right font-medium text-gray-500">Время</th>
                  <th className="pb-2 px-3 text-right font-medium text-gray-500">Польз.</th>
                  <th className="pb-2 px-3 text-right font-medium text-gray-500">Инт.</th>
                  <th className="pb-2 pl-3 text-right font-medium text-gray-500"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {data.content.map((item) => (
                  <tr key={item.name} className="hover:bg-amber-50/50">
                    <td className="py-2 pr-3">
                      <div className="font-medium text-gray-900 truncate max-w-[250px]" title={item.name}>
                        {item.display_name || item.name}
                      </div>
                      {item.display_name && (
                        <div className="text-xs text-gray-400">{item.name}</div>
                      )}
                    </td>
                    <td className="py-2 px-3 text-right text-gray-600 whitespace-nowrap">
                      {formatDuration(item.total_duration_ms)}
                    </td>
                    <td className="py-2 px-3 text-right text-gray-600">{item.user_count}</td>
                    <td className="py-2 px-3 text-right text-gray-600">{item.interval_count}</td>
                    <td className="py-2 pl-3 text-right">
                      <button
                        onClick={(e) => handleOpenAssign(e, item.name)}
                        className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50 transition-colors"
                      >
                        <PlusIcon className="h-3.5 w-3.5" />
                        В группу
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {data.total_pages > 1 && (
            <div className="mt-4 flex items-center justify-between">
              <p className="text-xs text-gray-400">
                Стр. {data.page + 1} из {data.total_pages}
              </p>
              <div className="flex gap-2">
                <button
                  onClick={() => setPage(Math.max(0, page - 1))}
                  disabled={page === 0}
                  className="btn-secondary text-xs px-3 py-1"
                >
                  Назад
                </button>
                <button
                  onClick={() => setPage(Math.min(data.total_pages - 1, page + 1))}
                  disabled={page >= data.total_pages - 1}
                  className="btn-secondary text-xs px-3 py-1"
                >
                  Далее
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {assigningItem && (
        <AssignGroupDropdown
          groups={groups}
          anchorEl={assigningItem.el}
          onSelect={(groupId) => handleAssign(groupId, assigningItem.name)}
          onClose={() => setAssigningItem(null)}
        />
      )}
    </div>
  );
}
