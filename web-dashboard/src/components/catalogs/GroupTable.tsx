import { useState } from 'react';
import {
  ChevronDownIcon,
  ChevronRightIcon,
  PencilIcon,
  TrashIcon,
  PlusIcon,
} from '@heroicons/react/24/outline';
import type { AppGroup, GroupType, MatchType } from '../../types/catalogs';
import * as catalogsApi from '../../api/catalogs';
import GroupItemsList from './GroupItemsList';
import AddItemModal from './AddItemModal';
import EditGroupModal from './EditGroupModal';
import ConfirmDialog from '../ConfirmDialog';
import { usePermissions } from '../../hooks/usePermissions';

interface GroupTableProps {
  groups: AppGroup[];
  groupType: GroupType;
  allGroups: AppGroup[];
  onRefresh: () => void;
}

export default function GroupTable({ groups, groupType, allGroups, onRefresh }: GroupTableProps) {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [addItemGroupId, setAddItemGroupId] = useState<string | null>(null);
  const [editGroup, setEditGroup] = useState<AppGroup | null>(null);
  const [deleteGroup, setDeleteGroup] = useState<AppGroup | null>(null);
  const [itemRefresh, setItemRefresh] = useState(0);
  const { hasPermission } = usePermissions();
  const canManage = hasPermission('CATALOGS:MANAGE');

  const toggle = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const handleAddItem = async (data: { pattern: string; match_type: MatchType }) => {
    if (!addItemGroupId) return;
    await catalogsApi.addGroupItem(addItemGroupId, data);
    setItemRefresh((p) => p + 1);
    onRefresh();
  };

  const handleEditGroup = async (groupId: string, data: { name?: string; description?: string; color?: string; sort_order?: number }) => {
    await catalogsApi.updateGroup(groupId, data);
  };

  const handleDeleteGroup = async () => {
    if (!deleteGroup) return;
    try {
      await catalogsApi.deleteGroup(deleteGroup.id);
      setDeleteGroup(null);
      onRefresh();
    } catch {
      // Error handling
    }
  };

  if (groups.length === 0) {
    return (
      <div className="text-center py-12 text-gray-400">
        <p className="text-sm">Нет групп.</p>
      </div>
    );
  }

  return (
    <>
      <div className="overflow-hidden rounded-lg border border-gray-200">
        {groups.map((group, index) => (
          <div key={group.id} className={index > 0 ? 'border-t border-gray-200' : ''}>
            {/* Group header row */}
            <div
              className="flex items-center gap-3 px-4 py-3 hover:bg-gray-50 cursor-pointer"
              onClick={() => toggle(group.id)}
            >
              <button className="shrink-0 text-gray-400">
                {expandedIds.has(group.id) ? (
                  <ChevronDownIcon className="h-4 w-4" />
                ) : (
                  <ChevronRightIcon className="h-4 w-4" />
                )}
              </button>

              {group.color && (
                <span
                  className="h-4 w-4 shrink-0 rounded-full"
                  style={{ backgroundColor: group.color }}
                />
              )}

              <div className="min-w-0 flex-1">
                <span className="text-sm font-medium text-gray-900">{group.name}</span>
                {group.description && (
                  <span className="ml-2 text-xs text-gray-400 hidden sm:inline">{group.description}</span>
                )}
              </div>

              <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                {group.item_count}
              </span>

              {group.is_default && (
                <span className="inline-flex items-center rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-600">
                  по умолч.
                </span>
              )}

              {canManage && (
                <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                  <button
                    onClick={() => setEditGroup(group)}
                    className="p-1 rounded text-gray-400 hover:text-blue-600 hover:bg-blue-50"
                    title="Редактировать"
                  >
                    <PencilIcon className="h-4 w-4" />
                  </button>
                  {!group.is_default && (
                    <button
                      onClick={() => setDeleteGroup(group)}
                      className="p-1 rounded text-gray-400 hover:text-red-600 hover:bg-red-50"
                      title="Удалить"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  )}
                  <button
                    onClick={() => setAddItemGroupId(group.id)}
                    className="p-1 rounded text-gray-400 hover:text-green-600 hover:bg-green-50"
                    title="Добавить элемент"
                  >
                    <PlusIcon className="h-4 w-4" />
                  </button>
                </div>
              )}
            </div>

            {/* Expanded items */}
            {expandedIds.has(group.id) && (
              <div className="bg-gray-50 border-t border-gray-100">
                <GroupItemsList
                  groupId={group.id}
                  groups={allGroups}
                  onAddItem={() => setAddItemGroupId(group.id)}
                  refreshTrigger={itemRefresh}
                />
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Add item modal */}
      <AddItemModal
        open={!!addItemGroupId}
        groupType={groupType}
        onClose={() => setAddItemGroupId(null)}
        onAdd={handleAddItem}
      />

      {/* Edit group modal */}
      <EditGroupModal
        open={!!editGroup}
        group={editGroup}
        onClose={() => setEditGroup(null)}
        onUpdated={onRefresh}
        onUpdate={handleEditGroup}
      />

      {/* Delete confirm dialog */}
      <ConfirmDialog
        open={!!deleteGroup}
        title="Удалить группу"
        message={`Вы уверены, что хотите удалить группу "${deleteGroup?.name}"? Все привязанные элементы будут удалены.`}
        confirmText="Удалить"
        cancelText="Отмена"
        variant="danger"
        onConfirm={handleDeleteGroup}
        onCancel={() => setDeleteGroup(null)}
      />
    </>
  );
}
