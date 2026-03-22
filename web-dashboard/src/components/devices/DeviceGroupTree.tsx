import { useState } from 'react';
import type { DeviceGroupResponse } from '../../types/device-groups';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  ComputerDesktopIcon,
  FolderOpenIcon,
  ChevronRightIcon,
  ChevronDownIcon,
} from '@heroicons/react/24/outline';

interface Props {
  groups: DeviceGroupResponse[];
  selectedGroupId: string | null; // null = all, 'ungrouped' = ungrouped, UUID = group
  onSelectAll: () => void;
  onSelectGroup: (groupId: string) => void;
  onSelectUngrouped: () => void;
  onCreateGroup: (parentId?: string) => void;
  onEditGroup: (group: DeviceGroupResponse) => void;
  onDeleteGroup: (group: DeviceGroupResponse) => void;
  totalDevices: number;
  ungroupedDevices?: number;
}

export default function DeviceGroupTree({
  groups,
  selectedGroupId,
  onSelectAll,
  onSelectGroup,
  onSelectUngrouped,
  onCreateGroup,
  onEditGroup,
  onDeleteGroup,
  totalDevices,
  ungroupedDevices,
}: Props) {
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const isAllSelected = selectedGroupId === null;
  const isUngrouped = selectedGroupId === 'ungrouped';

  // Separate root and child groups
  const rootGroups = groups.filter((g) => g.parent_id === null);
  const childGroups = groups.filter((g) => g.parent_id !== null);
  const childrenByParent = new Map<string, DeviceGroupResponse[]>();
  childGroups.forEach((g) => {
    const parentId = g.parent_id!;
    const list = childrenByParent.get(parentId) || [];
    list.push(g);
    childrenByParent.set(parentId, list);
  });

  // Use explicit ungroupedDevices if provided, otherwise compute from stats
  const ungroupedCount = ungroupedDevices ?? Math.max(0, totalDevices - rootGroups.reduce((sum, g) => sum + (g.stats?.total_devices ?? 0), 0));

  const toggleExpand = (groupId: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      return next;
    });
  };

  const renderGroup = (group: DeviceGroupResponse, depth: number = 0) => {
    const children = childrenByParent.get(group.id) || [];
    const hasChildren = children.length > 0;
    const isExpanded = expandedIds.has(group.id);
    const isSelected = selectedGroupId === group.id;
    const isHovered = hoveredId === group.id;
    const deviceCount = group.stats?.total_devices ?? 0;

    return (
      <li key={group.id}>
        <div
          className="flex items-center"
          onMouseEnter={() => setHoveredId(group.id)}
          onMouseLeave={() => setHoveredId(null)}
        >
          {/* Expand/collapse button for groups with children */}
          {hasChildren ? (
            <button
              onClick={(e) => {
                e.stopPropagation();
                toggleExpand(group.id);
              }}
              className="p-0.5 text-gray-400 hover:text-gray-600 rounded"
            >
              {isExpanded ? (
                <ChevronDownIcon className="h-3.5 w-3.5" />
              ) : (
                <ChevronRightIcon className="h-3.5 w-3.5" />
              )}
            </button>
          ) : (
            <span className="w-5" />
          )}

          <button
            onClick={() => onSelectGroup(group.id)}
            className={`flex-1 flex items-center gap-2 px-2 py-1.5 text-sm rounded-md transition-colors ${
              isSelected
                ? 'bg-red-100 text-red-700 font-medium'
                : 'text-gray-700 hover:bg-gray-100'
            }`}
          >
            <span
              className="h-3 w-3 rounded-full shrink-0"
              style={{ backgroundColor: group.color || '#9CA3AF' }}
            />
            <span className="flex-1 text-left truncate">{group.name}</span>
            {isHovered ? (
              <span className="flex gap-0.5">
                {depth === 0 && (
                  <span
                    role="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onCreateGroup(group.id);
                    }}
                    className="p-0.5 text-gray-400 hover:text-green-600 rounded"
                    title="Добавить подгруппу"
                  >
                    <PlusIcon className="h-3.5 w-3.5" />
                  </span>
                )}
                <span
                  role="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onEditGroup(group);
                  }}
                  className="p-0.5 text-gray-400 hover:text-blue-600 rounded"
                >
                  <PencilIcon className="h-3.5 w-3.5" />
                </span>
                <span
                  role="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onDeleteGroup(group);
                  }}
                  className="p-0.5 text-gray-400 hover:text-red-600 rounded"
                >
                  <TrashIcon className="h-3.5 w-3.5" />
                </span>
              </span>
            ) : (
              <span className="text-xs text-gray-400">{deviceCount}</span>
            )}
          </button>
        </div>

        {/* Children */}
        {hasChildren && isExpanded && (
          <ul className="ml-4 space-y-0.5 mt-0.5">
            {children.map((child) => renderGroup(child, depth + 1))}
          </ul>
        )}
      </li>
    );
  };

  return (
    <div className="shrink-0 border-r border-gray-200 bg-gray-50 overflow-y-auto">
      <div className="p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Группы</h3>
          <button
            onClick={() => onCreateGroup()}
            className="p-1 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded"
            title="Создать группу"
          >
            <PlusIcon className="h-4 w-4" />
          </button>
        </div>

        <ul className="space-y-0.5">
          {/* All devices */}
          <li>
            <button
              onClick={onSelectAll}
              className={`w-full flex items-center gap-2 px-3 py-2 text-sm rounded-md transition-colors ${
                isAllSelected
                  ? 'bg-red-100 text-red-700 font-medium'
                  : 'text-gray-700 hover:bg-gray-100'
              }`}
            >
              <ComputerDesktopIcon className="h-4 w-4 shrink-0" />
              <span className="flex-1 text-left">Все устройства</span>
              <span className="text-xs text-gray-400">{totalDevices}</span>
            </button>
          </li>

          {/* Ungrouped */}
          <li>
            <button
              onClick={onSelectUngrouped}
              className={`w-full flex items-center gap-2 px-3 py-2 text-sm rounded-md transition-colors ${
                isUngrouped
                  ? 'bg-red-100 text-red-700 font-medium'
                  : 'text-gray-700 hover:bg-gray-100'
              }`}
            >
              <FolderOpenIcon className="h-4 w-4 shrink-0 text-gray-400" />
              <span className="flex-1 text-left">Без группы</span>
              <span className="text-xs text-gray-400">{ungroupedCount}</span>
            </button>
          </li>

          {/* Divider */}
          {rootGroups.length > 0 && <li className="border-t border-gray-200 my-2" />}

          {/* Root groups with children */}
          {rootGroups.map((group) => renderGroup(group, 0))}
        </ul>
      </div>
    </div>
  );
}
