import { Fragment, useState } from 'react';
import { Dialog, Transition } from '@headlessui/react';
import type { GroupType, MatchType } from '../../types/catalogs';

interface AddItemModalProps {
  open: boolean;
  groupType: GroupType;
  onClose: () => void;
  onAdd: (data: { pattern: string; match_type: MatchType }) => Promise<void>;
}

const MATCH_TYPES: { value: MatchType; label: string; hint: string }[] = [
  { value: 'EXACT', label: 'Точное совпадение', hint: 'pattern = значение' },
  { value: 'SUFFIX', label: 'Суффикс', hint: 'Для доменов: mail.google.com совпадет с google.com' },
  { value: 'CONTAINS', label: 'Содержит', hint: 'Значение содержит pattern' },
];

export default function AddItemModal({ open, groupType, onClose, onAdd }: AddItemModalProps) {
  const [pattern, setPattern] = useState('');
  const [matchType, setMatchType] = useState<MatchType>('EXACT');
  const [batchMode, setBatchMode] = useState(false);
  const [batchPatterns, setBatchPatterns] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const placeholder = groupType === 'APP' ? 'chrome.exe' : 'google.com';
  const batchPlaceholder = groupType === 'APP'
    ? 'chrome.exe\nfirefox.exe\nmsedge.exe'
    : 'google.com\nyandex.ru\nmail.ru';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);

    try {
      if (batchMode) {
        const patterns = batchPatterns
          .split('\n')
          .map((p) => p.trim())
          .filter((p) => p.length > 0);

        for (const p of patterns) {
          try {
            await onAdd({ pattern: p, match_type: matchType });
          } catch {
            // Skip duplicates
          }
        }
      } else {
        if (!pattern.trim()) return;
        await onAdd({ pattern: pattern.trim(), match_type: matchType });
      }

      setPattern('');
      setBatchPatterns('');
      onClose();
    } catch (err: unknown) {
      if (err && typeof err === 'object' && 'response' in err) {
        const axiosErr = err as { response?: { data?: { error?: string; code?: string } } };
        if (axiosErr.response?.data?.code === 'PATTERN_ALREADY_ASSIGNED') {
          setError('Этот паттерн уже привязан к группе');
        } else {
          setError(axiosErr.response?.data?.error || 'Ошибка при добавлении');
        }
      } else {
        setError('Ошибка при добавлении');
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
                  Добавить {groupType === 'APP' ? 'приложение' : 'сайт'}
                </Dialog.Title>

                <form onSubmit={handleSubmit} className="mt-4 space-y-4">
                  {error && (
                    <div className="rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</div>
                  )}

                  <div className="flex items-center gap-2">
                    <label className="flex items-center gap-2 text-sm text-gray-600">
                      <input
                        type="checkbox"
                        checked={batchMode}
                        onChange={(e) => setBatchMode(e.target.checked)}
                        className="rounded border-gray-300 text-red-600 focus:ring-red-600"
                      />
                      Пакетное добавление
                    </label>
                  </div>

                  {batchMode ? (
                    <div>
                      <label className="label">Паттерны (по одному на строку)</label>
                      <textarea
                        value={batchPatterns}
                        onChange={(e) => setBatchPatterns(e.target.value)}
                        className="input-field mt-1"
                        rows={5}
                        placeholder={batchPlaceholder}
                        required
                      />
                    </div>
                  ) : (
                    <div>
                      <label className="label">Паттерн</label>
                      <input
                        type="text"
                        value={pattern}
                        onChange={(e) => setPattern(e.target.value)}
                        className="input-field mt-1"
                        placeholder={placeholder}
                        maxLength={512}
                        required
                      />
                    </div>
                  )}

                  <div>
                    <label className="label">Тип совпадения</label>
                    <div className="mt-2 space-y-2">
                      {MATCH_TYPES.map((mt) => (
                        <label key={mt.value} className="flex items-start gap-2 cursor-pointer">
                          <input
                            type="radio"
                            name="matchType"
                            value={mt.value}
                            checked={matchType === mt.value}
                            onChange={() => setMatchType(mt.value)}
                            className="mt-0.5 border-gray-300 text-red-600 focus:ring-red-600"
                          />
                          <div>
                            <span className="text-sm font-medium text-gray-700">{mt.label}</span>
                            <p className="text-xs text-gray-400">{mt.hint}</p>
                          </div>
                        </label>
                      ))}
                    </div>
                  </div>

                  <div className="flex justify-end gap-3 pt-2">
                    <button type="button" onClick={onClose} className="btn-secondary">
                      Отмена
                    </button>
                    <button type="submit" disabled={saving} className="btn-primary">
                      {saving ? 'Добавление...' : 'Добавить'}
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
