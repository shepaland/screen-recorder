import { useEffect, useRef } from 'react';
import type { AppGroup } from '../../types/catalogs';

interface AssignGroupDropdownProps {
  groups: AppGroup[];
  onSelect: (groupId: string) => void;
  onClose: () => void;
}

export default function AssignGroupDropdown({ groups, onSelect, onClose }: AssignGroupDropdownProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        onClose();
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [onClose]);

  return (
    <div
      ref={ref}
      className="absolute right-0 top-full z-20 mt-1 w-56 rounded-lg bg-white shadow-lg ring-1 ring-gray-200"
    >
      <div className="p-1 max-h-48 overflow-y-auto">
        {groups.map((group) => (
          <button
            key={group.id}
            onClick={() => onSelect(group.id)}
            className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm hover:bg-gray-100 transition-colors"
          >
            {group.color && (
              <span
                className="inline-block h-3 w-3 shrink-0 rounded-full"
                style={{ backgroundColor: group.color }}
              />
            )}
            <span className="truncate text-gray-700">{group.name}</span>
            <span className="ml-auto text-xs text-gray-400">{group.item_count}</span>
          </button>
        ))}
        {groups.length === 0 && (
          <div className="px-3 py-2 text-sm text-gray-400">Нет групп</div>
        )}
      </div>
    </div>
  );
}
