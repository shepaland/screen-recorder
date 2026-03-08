import { useEffect, useState } from 'react';
import { TrashIcon, ArrowsRightLeftIcon } from '@heroicons/react/24/outline';
import * as catalogsApi from '../../api/catalogs';
import type { GroupItem, AppGroup } from '../../types/catalogs';
import { usePermissions } from '../../hooks/usePermissions';

interface GroupItemsListProps {
  groupId: string;
  groups: AppGroup[];
  onAddItem: () => void;
  refreshTrigger: number;
}

const MATCH_TYPE_LABELS: Record<string, string> = {
  EXACT: 'Точное',
  SUFFIX: 'Суффикс',
  CONTAINS: 'Содержит',
};

export default function GroupItemsList({ groupId, groups, onAddItem, refreshTrigger }: GroupItemsListProps) {
  const [items, setItems] = useState<GroupItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [movingItem, setMovingItem] = useState<string | null>(null);
  const { hasPermission } = usePermissions();
  const canManage = hasPermission('CATALOGS:MANAGE');

  useEffect(() => {
    let cancelled = false;
    async function load() {
      setLoading(true);
      try {
        const resp = await catalogsApi.getGroupItems(groupId);
        if (!cancelled) setItems(resp.items);
      } catch {
        if (!cancelled) setItems([]);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [groupId, refreshTrigger]);

  const handleDelete = async (itemId: string) => {
    try {
      await catalogsApi.deleteGroupItem(itemId);
      setItems((prev) => prev.filter((i) => i.id !== itemId));
    } catch {
      // Silently fail
    }
  };

  const handleMove = async (itemId: string, targetGroupId: string) => {
    try {
      await catalogsApi.moveGroupItem(itemId, targetGroupId);
      setItems((prev) => prev.filter((i) => i.id !== itemId));
      setMovingItem(null);
    } catch {
      // Silently fail
    }
  };

  if (loading) {
    return (
      <div className="py-3 px-4 text-sm text-gray-400 animate-pulse">
        Загрузка элементов...
      </div>
    );
  }

  const otherGroups = groups.filter((g) => g.id !== groupId);

  return (
    <div className="py-2 px-4">
      {items.length === 0 ? (
        <div className="text-sm text-gray-400 py-2">
          Нет привязанных элементов.
          {canManage && (
            <button onClick={onAddItem} className="ml-2 text-red-600 hover:text-red-500 font-medium">
              Добавить
            </button>
          )}
        </div>
      ) : (
        <div className="space-y-1">
          {items.map((item) => (
            <div
              key={item.id}
              className="flex items-center justify-between rounded-md px-3 py-1.5 text-sm hover:bg-gray-50 group"
            >
              <div className="flex items-center gap-3 min-w-0">
                <span className="font-mono text-gray-900 truncate">{item.pattern}</span>
                {item.display_name && (
                  <span className="text-gray-400 truncate">({item.display_name})</span>
                )}
                <span className="inline-flex items-center rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500">
                  {MATCH_TYPE_LABELS[item.match_type] || item.match_type}
                </span>
              </div>
              {canManage && (
                <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                  <div className="relative">
                    <button
                      onClick={() => setMovingItem(movingItem === item.id ? null : item.id)}
                      className="p-1 rounded text-gray-400 hover:text-blue-600 hover:bg-blue-50"
                      title="Переместить в другую группу"
                    >
                      <ArrowsRightLeftIcon className="h-4 w-4" />
                    </button>
                    {movingItem === item.id && (
                      <div className="absolute right-0 top-full z-20 mt-1 w-48 rounded-lg bg-white shadow-lg ring-1 ring-gray-200">
                        <div className="p-1 max-h-48 overflow-y-auto">
                          {otherGroups.map((g) => (
                            <button
                              key={g.id}
                              onClick={() => handleMove(item.id, g.id)}
                              className="flex w-full items-center gap-2 rounded-md px-3 py-1.5 text-left text-xs hover:bg-gray-100"
                            >
                              {g.color && (
                                <span className="h-2.5 w-2.5 rounded-full shrink-0" style={{ backgroundColor: g.color }} />
                              )}
                              <span className="truncate">{g.name}</span>
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                  <button
                    onClick={() => handleDelete(item.id)}
                    className="p-1 rounded text-gray-400 hover:text-red-600 hover:bg-red-50"
                    title="Удалить"
                  >
                    <TrashIcon className="h-4 w-4" />
                  </button>
                </div>
              )}
            </div>
          ))}
          {canManage && (
            <button
              onClick={onAddItem}
              className="mt-1 text-xs font-medium text-red-600 hover:text-red-500"
            >
              + Добавить элемент
            </button>
          )}
        </div>
      )}
    </div>
  );
}
