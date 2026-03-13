import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { AppGroup } from '../../types/catalogs';

interface AssignGroupDropdownProps {
  groups: AppGroup[];
  onSelect: (groupId: string) => void;
  onClose: () => void;
  anchorEl: HTMLElement | null;
}

export default function AssignGroupDropdown({ groups, onSelect, onClose, anchorEl }: AssignGroupDropdownProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [style, setStyle] = useState<React.CSSProperties>({ position: 'fixed', top: 0, left: 0 });

  useEffect(() => {
    if (!anchorEl) return;
    const rect = anchorEl.getBoundingClientRect();
    const dropdownWidth = 224; // w-56 = 14rem = 224px
    let left = rect.right - dropdownWidth;
    if (left < 8) left = 8;
    setStyle({
      position: 'fixed',
      top: rect.bottom + 4,
      left,
      zIndex: 9999,
      width: dropdownWidth,
    });
  }, [anchorEl]);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (
        ref.current &&
        !ref.current.contains(e.target as Node) &&
        anchorEl !== e.target &&
        !anchorEl?.contains(e.target as Node)
      ) {
        onClose();
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [onClose, anchorEl]);

  return createPortal(
    <div ref={ref} style={style} className="rounded-lg bg-white shadow-lg ring-1 ring-gray-200">
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
    </div>,
    document.body
  );
}
