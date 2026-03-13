import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { AppGroup } from '../../types/catalogs';

interface AssignGroupDropdownProps {
  groups: AppGroup[];
  onSelect: (groupId: string) => void;
  onClose: () => void;
  anchorEl: HTMLElement | null;
}

const DROPDOWN_WIDTH = 224; // w-56 = 14rem
const ITEM_HEIGHT = 36; // px per item (py-2 + text)
const MIN_VISIBLE_ITEMS = 5;
const PADDING = 8; // p-1 top+bottom
const MIN_DROPDOWN_HEIGHT = MIN_VISIBLE_ITEMS * ITEM_HEIGHT + PADDING;
const VIEWPORT_MARGIN = 8; // px from viewport edge

export default function AssignGroupDropdown({ groups, onSelect, onClose, anchorEl }: AssignGroupDropdownProps) {
  const ref = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [style, setStyle] = useState<React.CSSProperties>({ position: 'fixed', opacity: 0 });
  const [showTopShadow, setShowTopShadow] = useState(false);
  const [showBottomShadow, setShowBottomShadow] = useState(false);

  const updatePosition = useCallback(() => {
    if (!anchorEl) return;
    const rect = anchorEl.getBoundingClientRect();
    const viewportH = window.innerHeight;
    const viewportW = window.innerWidth;

    // Space available below and above the anchor
    const spaceBelow = viewportH - rect.bottom - VIEWPORT_MARGIN;
    const spaceAbove = rect.top - VIEWPORT_MARGIN;

    // Determine max height: at least MIN_DROPDOWN_HEIGHT, capped by available space
    let openBelow = true;
    let maxH: number;

    if (spaceBelow >= MIN_DROPDOWN_HEIGHT) {
      // Enough space below
      maxH = spaceBelow;
      openBelow = true;
    } else if (spaceAbove >= MIN_DROPDOWN_HEIGHT) {
      // Open upward
      maxH = spaceAbove;
      openBelow = false;
    } else {
      // Neither side has enough — pick the larger one
      openBelow = spaceBelow >= spaceAbove;
      maxH = Math.max(spaceBelow, spaceAbove);
    }

    // Content height (all items + padding)
    const contentH = groups.length * ITEM_HEIGHT + PADDING;
    const dropdownH = Math.min(contentH, maxH);

    // Horizontal position: align right edge with anchor, clamp to viewport
    let left = rect.right - DROPDOWN_WIDTH;
    if (left < VIEWPORT_MARGIN) left = VIEWPORT_MARGIN;
    if (left + DROPDOWN_WIDTH > viewportW - VIEWPORT_MARGIN) {
      left = viewportW - VIEWPORT_MARGIN - DROPDOWN_WIDTH;
    }

    const top = openBelow ? rect.bottom + 4 : rect.top - dropdownH - 4;

    setStyle({
      position: 'fixed',
      top,
      left,
      zIndex: 9999,
      width: DROPDOWN_WIDTH,
      maxHeight: maxH,
      opacity: 1,
    });
  }, [anchorEl, groups.length]);

  // Initial position + recalc on scroll/resize
  useEffect(() => {
    if (!anchorEl) return;
    updatePosition();

    // Listen on all scrollable ancestors + window
    const handleUpdate = () => updatePosition();
    window.addEventListener('scroll', handleUpdate, true); // capture phase — catches all scroll events
    window.addEventListener('resize', handleUpdate);
    return () => {
      window.removeEventListener('scroll', handleUpdate, true);
      window.removeEventListener('resize', handleUpdate);
    };
  }, [anchorEl, updatePosition]);

  // Close on click outside
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

  // Close on Escape
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [onClose]);

  // Scroll shadow indicators
  const updateScrollShadows = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    setShowTopShadow(el.scrollTop > 4);
    setShowBottomShadow(el.scrollHeight - el.scrollTop - el.clientHeight > 4);
  }, []);

  useEffect(() => {
    updateScrollShadows();
  }, [groups, style, updateScrollShadows]);

  return createPortal(
    <div
      ref={ref}
      style={style}
      className="rounded-lg bg-white shadow-lg ring-1 ring-gray-200 flex flex-col overflow-hidden"
    >
      {/* Top scroll indicator */}
      {showTopShadow && (
        <div className="h-6 pointer-events-none absolute top-0 left-0 right-0 z-10 bg-gradient-to-b from-white to-transparent rounded-t-lg" />
      )}

      <div
        ref={listRef}
        className="p-1 overflow-y-auto flex-1"
        onScroll={updateScrollShadows}
      >
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

      {/* Bottom scroll indicator */}
      {showBottomShadow && (
        <div className="h-6 pointer-events-none absolute bottom-0 left-0 right-0 z-10 bg-gradient-to-t from-white to-transparent rounded-b-lg" />
      )}
    </div>,
    document.body
  );
}
