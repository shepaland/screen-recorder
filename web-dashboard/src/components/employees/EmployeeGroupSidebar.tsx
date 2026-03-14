import { useState } from 'react';
import type { EmployeeGroup } from '../../types/employee-groups';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  UserGroupIcon,
  FolderOpenIcon,
} from '@heroicons/react/24/outline';

interface Props {
  groups: EmployeeGroup[];
  selectedGroupId: string | null;
  ungrouped: boolean;
  onSelectAll: () => void;
  onSelectGroup: (groupId: string) => void;
  onSelectUngrouped: () => void;
  onCreateGroup: () => void;
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

  const isAllSelected = !selectedGroupId && !ungrouped;
  const ungroupedCount = totalEmployees - groups.reduce((sum, g) => sum + g.member_count, 0);

  return (
    <div className="w-64 shrink-0 border-r border-gray-200 bg-gray-50 overflow-y-auto">
      <div className="p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Группы</h3>
          <button
            onClick={onCreateGroup}
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

          {/* Custom groups */}
          {groups.map((group) => (
            <li
              key={group.id}
              onMouseEnter={() => setHoveredId(group.id)}
              onMouseLeave={() => setHoveredId(null)}
            >
              <button
                onClick={() => onSelectGroup(group.id)}
                className={`w-full flex items-center gap-2 px-3 py-2 text-sm rounded-md transition-colors ${
                  selectedGroupId === group.id
                    ? 'bg-red-100 text-red-700 font-medium'
                    : 'text-gray-700 hover:bg-gray-100'
                }`}
              >
                <span
                  className="h-3 w-3 rounded-full shrink-0"
                  style={{ backgroundColor: group.color || '#9CA3AF' }}
                />
                <span className="flex-1 text-left truncate">{group.name}</span>
                {hoveredId === group.id ? (
                  <span className="flex gap-0.5">
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
                  <span className="text-xs text-gray-400">{group.member_count}</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
