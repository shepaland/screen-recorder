import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import type { DeviceGroupResponse } from '../../types/device-groups';

interface Props {
  groups: DeviceGroupResponse[];
  currentGroupId: string | null;
  onAssign: (groupId: string | null) => void;
}

export default function AssignGroupDropdown({ groups, currentGroupId, onAssign }: Props) {
  const [isOpen, setIsOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ top: 0, left: 0 });

  const currentGroup = groups.find((g) => g.id === currentGroupId);

  // Build tree: root groups with children
  const rootGroups = groups.filter((g) => g.parent_id === null);
  const childGroups = groups.filter((g) => g.parent_id !== null);
  const childrenByParent = new Map<string, DeviceGroupResponse[]>();
  childGroups.forEach((g) => {
    const parentId = g.parent_id!;
    const list = childrenByParent.get(parentId) || [];
    list.push(g);
    childrenByParent.set(parentId, list);
  });

  useEffect(() => {
    if (!isOpen) return;

    const updatePosition = () => {
      if (!buttonRef.current) return;
      const rect = buttonRef.current.getBoundingClientRect();
      const dropdownHeight = 250;
      const spaceBelow = window.innerHeight - rect.bottom;

      setPosition({
        top: spaceBelow < dropdownHeight ? rect.top - dropdownHeight : rect.bottom + 4,
        left: rect.left,
      });
    };

    updatePosition();

    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        !buttonRef.current?.contains(e.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [isOpen]);

  const handleSelect = (groupId: string | null) => {
    onAssign(groupId);
    setIsOpen(false);
  };

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(!isOpen);
        }}
        className="text-xs text-gray-500 hover:text-gray-700 truncate max-w-[120px] inline-flex items-center gap-1"
        title={currentGroup?.name || 'Без группы'}
      >
        {currentGroup && (
          <span
            className="h-2 w-2 rounded-full shrink-0"
            style={{ backgroundColor: currentGroup.color || '#9CA3AF' }}
          />
        )}
        <span className="truncate">{currentGroup?.name || 'Без группы'}</span>
      </button>

      {isOpen &&
        createPortal(
          <div
            ref={dropdownRef}
            className="fixed z-50 w-56 rounded-md bg-white shadow-lg ring-1 ring-black/5 overflow-y-auto"
            style={{ top: position.top, left: position.left, maxHeight: 250 }}
          >
            <ul className="py-1">
              <li>
                <button
                  onClick={() => handleSelect(null)}
                  className={`w-full text-left px-3 py-1.5 text-sm hover:bg-gray-100 ${
                    currentGroupId === null ? 'font-medium text-red-600' : 'text-gray-700'
                  }`}
                >
                  Без группы
                </button>
              </li>
              {rootGroups.map((group) => (
                <li key={group.id}>
                  <button
                    onClick={() => handleSelect(group.id)}
                    className={`w-full text-left px-3 py-1.5 text-sm hover:bg-gray-100 flex items-center gap-2 ${
                      currentGroupId === group.id ? 'font-medium text-red-600' : 'text-gray-700'
                    }`}
                  >
                    <span
                      className="h-2.5 w-2.5 rounded-full shrink-0"
                      style={{ backgroundColor: group.color || '#9CA3AF' }}
                    />
                    {group.name}
                  </button>
                  {/* Children */}
                  {(childrenByParent.get(group.id) || []).map((child) => (
                    <button
                      key={child.id}
                      onClick={() => handleSelect(child.id)}
                      className={`w-full text-left pl-8 pr-3 py-1.5 text-sm hover:bg-gray-100 flex items-center gap-2 ${
                        currentGroupId === child.id ? 'font-medium text-red-600' : 'text-gray-500'
                      }`}
                    >
                      <span
                        className="h-2 w-2 rounded-full shrink-0"
                        style={{ backgroundColor: child.color || '#9CA3AF' }}
                      />
                      {child.name}
                    </button>
                  ))}
                </li>
              ))}
            </ul>
          </div>,
          document.body,
        )}
    </div>
  );
}
