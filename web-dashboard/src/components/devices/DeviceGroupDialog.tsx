import { useState, useEffect } from 'react';
import { XMarkIcon } from '@heroicons/react/24/outline';
import type { DeviceGroupResponse, CreateDeviceGroupRequest, UpdateDeviceGroupRequest } from '../../types/device-groups';

const PRESET_COLORS = [
  '#EF4444', '#F97316', '#EAB308', '#22C55E', '#06B6D4',
  '#3B82F6', '#8B5CF6', '#EC4899', '#6B7280', '#78716C',
];

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: CreateDeviceGroupRequest | UpdateDeviceGroupRequest) => void;
  editGroup?: DeviceGroupResponse | null;
  parentId?: string | null;
  rootGroups?: DeviceGroupResponse[];
}

export default function DeviceGroupDialog({
  isOpen,
  onClose,
  onSubmit,
  editGroup,
  parentId,
  rootGroups = [],
}: Props) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [color, setColor] = useState(PRESET_COLORS[0]);
  const [selectedParentId, setSelectedParentId] = useState<string | null>(null);

  useEffect(() => {
    if (editGroup) {
      setName(editGroup.name);
      setDescription(editGroup.description || '');
      setColor(editGroup.color || PRESET_COLORS[0]);
      setSelectedParentId(editGroup.parent_id);
    } else {
      setName('');
      setDescription('');
      setColor(PRESET_COLORS[0]);
      setSelectedParentId(parentId ?? null);
    }
  }, [editGroup, parentId, isOpen]);

  if (!isOpen) return null;

  const isEditing = !!editGroup;
  const title = isEditing
    ? 'Редактировать группу'
    : selectedParentId
      ? 'Новая подгруппа'
      : 'Новая группа';

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    if (isEditing) {
      const data: UpdateDeviceGroupRequest = {
        name: name.trim(),
        description: description.trim() || undefined,
        color,
      };
      onSubmit(data);
    } else {
      const data: CreateDeviceGroupRequest = {
        name: name.trim(),
        description: description.trim() || undefined,
        color,
        parent_id: selectedParentId,
      };
      onSubmit(data);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-md mx-4">
        <div className="flex items-center justify-between p-4 border-b border-gray-200">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button onClick={onClose} className="p-1 text-gray-400 hover:text-gray-600 rounded">
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Название</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-red-500 focus:ring-1 focus:ring-red-500"
              placeholder="Название группы"
              autoFocus
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Описание</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              className="block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-red-500 focus:ring-1 focus:ring-red-500"
              placeholder="Описание (необязательно)"
            />
          </div>

          {/* Parent group selector -- only for new groups, not editing */}
          {!isEditing && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Родительская группа</label>
              <select
                value={selectedParentId || ''}
                onChange={(e) => setSelectedParentId(e.target.value || null)}
                className="block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-red-500 focus:ring-1 focus:ring-red-500"
              >
                <option value="">Корневая группа</option>
                {rootGroups.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">Цвет</label>
            <div className="flex flex-wrap gap-2">
              {PRESET_COLORS.map((c) => (
                <button
                  key={c}
                  type="button"
                  onClick={() => setColor(c)}
                  className={`h-8 w-8 rounded-full border-2 transition-transform ${
                    color === c ? 'border-gray-900 scale-110' : 'border-transparent hover:scale-105'
                  }`}
                  style={{ backgroundColor: c }}
                />
              ))}
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
            >
              Отмена
            </button>
            <button
              type="submit"
              className="px-4 py-2 text-sm font-medium text-white bg-red-600 rounded-md hover:bg-red-700"
            >
              {isEditing ? 'Сохранить' : 'Создать'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
