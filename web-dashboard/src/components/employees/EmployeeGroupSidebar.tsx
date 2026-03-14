import { useState } from 'react';
import type { EmployeeGroup } from '../../types/employee-groups';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  UserGroupIcon,
  FolderOpenIcon,
  ChevronRightIcon,
  ChevronDownIcon,
} from '@heroicons/react/24/outline';

interface Props {
  groups: EmployeeGroup[];
  selectedGroupId: string | null;
  ungrouped: boolean;
  onSelectAll: () => void;
  onSelectGroup: (groupId: string) => void;
  onSelectUngrouped: () => void;
  onCreateGroup: (parentId?: string) => void;
  onEditGroup: (group: EmployeeGroup) => void;
  onDeleteGroup: (group: EmployeeGroup) => void;
  totalEmployees: number;
}

export default function EmployeeGroupSidebar({
  groups,
  selectedGroupId,
  ungrouped,
  onSelectAll,
  onSelectGroup,
  onSelectUngrouped,
  onCreateGroup,
  onEditGroup,
  onDeleteGroup,
  totalEmployees,
}: Props) {
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const isAllSelected = !selectedGroupId && !ungrouped;
  const totalGrouped = groups.reduce((sum, g) => sum + (g.total_member_count ?? g.member_count), 0);
  const ungroupedCount = totalEmployees - totalGrouped;

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const isGroupSelected = (group: EmployeeGroup): boolean => {
    if (selectedGroupId === group.id) return true;
    if (group.children) {
      return group.children.some((c) => c.id === selectedGroupId);
    }
    return false;
  };

  return (
    <div className="w-64 shrink-0 border-r border-gray-200 bg-gray-50 overflow-y-auto">
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

        <ul className="space-y-1">
          {/* All employees */}
          <li>
            <button
              onClick={onSelectAll}
              className={`w-full flex items-center gap-2 px-3 py-2 text-sm rounded-md transition-colors ${
                isAllSelected
                  ? 'bg-red-100 text-red-700 font-medium'
                  : 'text-gray-700 hover:bg-gray-100'
              }`}
            >
              <UserGroupIcon className="h-4 w-4 shrink-0" />
              <span className="flex-1 text-left">Все</span>
              <span className="text-xs text-gray-400">{totalEmployees}</span>
            </button>
          </li>

          {/* Ungrouped */}
          <li>
            <button
              onClick={onSelectUngrouped}
              className={`w-full flex items-center gap-2 px-3 py-2 text-sm rounded-md transition-colors ${
                ungrouped
                  ? 'bg-red-100 text-red-700 font-medium'
                  : 'text-gray-700 hover:bg-gray-100'
              }`}
            >
              <FolderOpenIcon className="h-4 w-4 shrink-0 text-gray-400" />
              <span className="flex-1 text-left">Неразмеченное</span>
              <span className="text-xs text-gray-400">{Math.max(0, ungroupedCount)}</span>
            </button>
          </li>

          {/* Divider */}
          {groups.length > 0 && <li className="border-t border-gray-200 my-2" />}

          {/* Root groups with children */}
          {groups.map((group) => {
            const hasChildren = group.children && group.children.length > 0;
            const isExpanded = expandedIds.has(group.id);

            return (
              <li key={group.id}>
                <div
                  onMouseEnter={() => setHoveredId(group.id)}
                  onMouseLeave={() => setHoveredId(null)}
                >
                  <button
                    onClick={() => {
                      if (hasChildren) {
                        toggleExpand(group.id);
                      }
                      onSelectGroup(group.id);
                    }}
                    className={`w-full flex items-center gap-1.5 px-3 py-2 text-sm rounded-md transition-colors ${
                      isGroupSelected(group)
                        ? 'bg-red-100 text-red-700 font-medium'
                        : 'text-gray-700 hover:bg-gray-100'
                    }`}
                  >
                    {/* Expand/collapse arrow for parent groups */}
                    {hasChildren ? (
                      <span
                        className="shrink-0 text-gray-400"
                        onClick={(e) => { e.stopPropagation(); toggleExpand(group.id); }}
                      >
                        {isExpanded ? (
                          <ChevronDownIcon className="h-3.5 w-3.5" />
                        ) : (
                          <ChevronRightIcon className="h-3.5 w-3.5" />
                        )}
                      </span>
                    ) : (
                      <span className="w-3.5 shrink-0" />
                    )}
                    <span
                      className="h-3 w-3 rounded-full shrink-0"
                      style={{ backgroundColor: group.color || '#9CA3AF' }}
                    />
                    <span className="flex-1 text-left truncate">{group.name}</span>
                    {hoveredId === group.id ? (
                      <span className="flex gap-0.5">
                        {/* Add subgroup button */}
                        <span
                          role="button"
                          onClick={(e) => { e.stopPropagation(); onCreateGroup(group.id); }}
                          className="p-0.5 text-gray-400 hover:text-green-600 rounded"
                          title="Добавить подгруппу"
                        >
                          <PlusIcon className="h-3.5 w-3.5" />
                        </span>
                        <span
                          role="button"
                          onClick={(e) => { e.stopPropagation(); onEditGroup(group); }}
                          className="p-0.5 text-gray-400 hover:text-blue-600 rounded"
                        >
                          <PencilIcon className="h-3.5 w-3.5" />
                        </span>
                        <span
                          role="button"
                          onClick={(e) => { e.stopPropagation(); onDeleteGroup(group); }}
                          className="p-0.5 text-gray-400 hover:text-red-600 rounded"
                        >
                          <TrashIcon className="h-3.5 w-3.5" />
                        </span>
                      </span>
                    ) : (
                      <span className="text-xs text-gray-400">
                        {group.total_member_count ?? group.member_count}
                      </span>
                    )}
                  </button>
                </div>

                {/* Child groups */}
                {hasChildren && isExpanded && (
                  <ul className="pl-4 space-y-0.5 mt-0.5">
                    {group.children!.map((child) => (
                      <li
                        key={child.id}
                        onMouseEnter={() => setHoveredId(child.id)}
                        onMouseLeave={() => setHoveredId(null)}
                      >
                        <button
                          onClick={() => onSelectGroup(child.id)}
                          className={`w-full flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
                            selectedGroupId === child.id
                              ? 'bg-red-100 text-red-700 font-medium'
                              : 'text-gray-600 hover:bg-gray-100'
                          }`}
                        >
                          <span
                            className="h-2.5 w-2.5 rounded-full shrink-0"
                            style={{ backgroundColor: child.color || group.color || '#9CA3AF' }}
                          />
                          <span className="flex-1 text-left truncate">{child.name}</span>
                          {hoveredId === child.id ? (
                            <span className="flex gap-0.5">
                              <span
                                role="button"
                                onClick={(e) => { e.stopPropagation(); onEditGroup(child); }}
                                className="p-0.5 text-gray-400 hover:text-blue-600 rounded"
                              >
                                <PencilIcon className="h-3 w-3" />
                              </span>
                              <span
                                role="button"
                                onClick={(e) => { e.stopPropagation(); onDeleteGroup(child); }}
                                className="p-0.5 text-gray-400 hover:text-red-600 rounded"
                              >
                                <TrashIcon className="h-3 w-3" />
                              </span>
                            </span>
                          ) : (
                            <span className="text-xs text-gray-400">{child.member_count}</span>
                          )}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
