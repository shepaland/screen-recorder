import { Fragment, useState } from 'react';
import { Dialog, Transition } from '@headlessui/react';
import type { GroupType } from '../../types/catalogs';

interface CreateGroupModalProps {
  open: boolean;
  groupType: GroupType;
  onClose: () => void;
  onCreated: () => void;
  onCreate: (data: { group_type: GroupType; name: string; description?: string; color?: string; sort_order?: number; is_browser_group?: boolean }) => Promise<void>;
}

const DEFAULT_COLORS = [
  '#3B82F6', '#10B981', '#F59E0B', '#EF4444', '#8B5CF6',
  '#EC4899', '#06B6D4', '#84CC16', '#F97316', '#6366F1',
];

export default function CreateGroupModal({ open, groupType, onClose, onCreated, onCreate }: CreateGroupModalProps) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [color, setColor] = useState(DEFAULT_COLORS[0]);
  const [sortOrder, setSortOrder] = useState(0);
  const [isBrowserGroup, setIsBrowserGroup] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    setSaving(true);
    setError(null);
    try {
      await onCreate({
        group_type: groupType,
        name: name.trim(),
        description: description.trim() || undefined,
        color,
        sort_order: sortOrder,
        is_browser_group: groupType === 'APP' ? isBrowserGroup : undefined,
      });
      setName('');
      setDescription('');
      setColor(DEFAULT_COLORS[0]);
      setSortOrder(0);
      setIsBrowserGroup(false);
      onCreated();
      onClose();
    } catch (err: unknown) {
      if (err && typeof err === 'object' && 'response' in err) {
        const axiosErr = err as { response?: { data?: { error?: string; code?: string } } };
        if (axiosErr.response?.data?.code === 'GROUP_NAME_DUPLICATE') {
          setError('Группа с таким названием уже существует');
        } else {
          setError(axiosErr.response?.data?.error || 'Ошибка при создании группы');
        }
      } else {
        setError('Ошибка при создании группы');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <Transition.Root show={open} as={Fragment}>
      <Dialog as="div" className="relative z-50" onClose={onClose}>
        <Transition.Child
          as={Fragment}
          enter="ease-out duration-300" enterFrom="opacity-0" enterTo="opacity-100"
          leave="ease-in duration-200" leaveFrom="opacity-100" leaveTo="opacity-0"
        >
          <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" />
        </Transition.Child>
        <div className="fixed inset-0 z-10 w-screen overflow-y-auto">
          <div className="flex min-h-full items-center justify-center p-4">
            <Transition.Child
              as={Fragment}
              enter="ease-out duration-300" enterFrom="opacity-0 scale-95" enterTo="opacity-100 scale-100"
              leave="ease-in duration-200" leaveFrom="opacity-100 scale-100" leaveTo="opacity-0 scale-95"
            >
              <Dialog.Panel className="w-full max-w-md transform rounded-xl bg-white p-6 shadow-xl transition-all">
                <Dialog.Title className="text-lg font-semibold text-gray-900">
                  Новая группа
                </Dialog.Title>

                <form onSubmit={handleSubmit} className="mt-4 space-y-4">
                  {error && (
                    <div className="rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</div>
                  )}

                  <div>
                    <label className="label">Название</label>
                    <input
                      type="text"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      className="input-field mt-1"
                      placeholder={groupType === 'APP' ? 'Например: VPN-клиенты' : 'Например: Образование'}
                      maxLength={100}
                      required
                    />
                  </div>

                  <div>
                    <label className="label">Описание</label>
                    <textarea
                      value={description}
                      onChange={(e) => setDescription(e.target.value)}
                      className="input-field mt-1"
                      rows={2}
                      placeholder="Необязательное описание"
                      maxLength={500}
                    />
                  </div>

                  <div>
                    <label className="label">Цвет</label>
                    <div className="mt-1 flex flex-wrap gap-2">
                      {DEFAULT_COLORS.map((c) => (
                        <button
                          key={c}
                          type="button"
                          onClick={() => setColor(c)}
                          className={`h-8 w-8 rounded-full border-2 transition-all ${
                            color === c ? 'border-gray-900 scale-110' : 'border-transparent'
                          }`}
                          style={{ backgroundColor: c }}
                          title={c}
                        />
                      ))}
                    </div>
                  </div>

                  <div>
                    <label className="label">Порядок сортировки</label>
                    <input
                      type="number"
                      value={sortOrder}
                      onChange={(e) => setSortOrder(parseInt(e.target.value) || 0)}
                      className="input-field mt-1 w-24"
                      min={0}
                    />
                  </div>

                  {groupType === 'APP' && (
                    <div className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        id="is_browser_group"
                        checked={isBrowserGroup}
                        onChange={(e) => setIsBrowserGroup(e.target.checked)}
                        className="h-4 w-4 rounded border-gray-300 text-red-600 focus:ring-red-500"
                      />
                      <label htmlFor="is_browser_group" className="text-sm text-gray-700">
                        Браузерная группа
                      </label>
                      <span className="text-xs text-gray-400">(показывает группы сайтов в таймлайне)</span>
                    </div>
                  )}

                  <div className="flex justify-end gap-3 pt-2">
                    <button type="button" onClick={onClose} className="btn-secondary">
                      Отмена
                    </button>
                    <button type="submit" disabled={saving || !name.trim()} className="btn-primary">
                      {saving ? 'Создание...' : 'Создать'}
                    </button>
                  </div>
                </form>
              </Dialog.Panel>
            </Transition.Child>
          </div>
        </div>
      </Dialog>
    </Transition.Root>
  );
}
