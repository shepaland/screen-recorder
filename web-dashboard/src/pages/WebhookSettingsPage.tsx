import { useState, useEffect } from 'react';
import { PlusIcon, TrashIcon } from '@heroicons/react/24/outline';
import { listWebhooks, createWebhook, updateWebhook, deleteWebhook, getWebhookDeliveries } from '../api/webhooks';
import type { WebhookSubscription, WebhookDelivery } from '../types/webhooks';

export default function WebhookSettingsPage() {
  const [webhooks, setWebhooks] = useState<WebhookSubscription[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [formUrl, setFormUrl] = useState('');
  const [formEvents, setFormEvents] = useState('segment.confirmed');
  const [formSecret, setFormSecret] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [deliveries, setDeliveries] = useState<WebhookDelivery[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const data = await listWebhooks();
      setWebhooks(data);
    } catch (err) {
      console.error('Failed to load webhooks:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    try {
      await createWebhook({
        url: formUrl,
        event_types: formEvents.split(',').map((s) => s.trim()),
        secret: formSecret || undefined,
        active: true,
      });
      setShowForm(false);
      setFormUrl('');
      setFormEvents('segment.confirmed');
      setFormSecret('');
      load();
    } catch (err) {
      console.error('Failed to create webhook:', err);
    }
  };

  const handleToggle = async (wh: WebhookSubscription) => {
    await updateWebhook(wh.id, { active: !wh.active });
    load();
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Удалить webhook?')) return;
    await deleteWebhook(id);
    load();
  };

  const handleExpand = async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(id);
    const data = await getWebhookDeliveries(id, { size: 10 });
    setDeliveries(data.content);
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-white">Webhooks</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-lg"
        >
          <PlusIcon className="w-4 h-4" />
          Добавить
        </button>
      </div>

      {showForm && (
        <div className="bg-white/10 rounded-lg p-4 mb-6 space-y-3">
          <input
            value={formUrl}
            onChange={(e) => setFormUrl(e.target.value)}
            placeholder="URL (https://...)"
            className="w-full px-3 py-2 bg-white/10 border border-white/20 rounded text-white placeholder-gray-400"
          />
          <input
            value={formEvents}
            onChange={(e) => setFormEvents(e.target.value)}
            placeholder="События (segment.confirmed, device.online)"
            className="w-full px-3 py-2 bg-white/10 border border-white/20 rounded text-white placeholder-gray-400"
          />
          <input
            value={formSecret}
            onChange={(e) => setFormSecret(e.target.value)}
            placeholder="HMAC Secret (опционально)"
            className="w-full px-3 py-2 bg-white/10 border border-white/20 rounded text-white placeholder-gray-400"
          />
          <button
            onClick={handleCreate}
            className="px-4 py-2 bg-green-600 hover:bg-green-700 text-white rounded"
          >
            Создать
          </button>
        </div>
      )}

      <div className="space-y-3">
        {webhooks.map((wh) => (
          <div key={wh.id} className="bg-white/5 rounded-lg overflow-hidden">
            <div className="flex items-center justify-between p-4">
              <div className="flex-1 cursor-pointer" onClick={() => handleExpand(wh.id)}>
                <div className="text-white font-mono text-sm">{wh.url}</div>
                <div className="text-gray-400 text-xs mt-1">
                  {wh.event_types.join(', ')} · {wh.active ? '✓ Активен' : '✗ Неактивен'}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => handleToggle(wh)}
                  className={`px-3 py-1 rounded text-xs ${wh.active ? 'bg-green-600/20 text-green-400' : 'bg-gray-600/20 text-gray-400'}`}
                >
                  {wh.active ? 'Вкл' : 'Выкл'}
                </button>
                <button
                  onClick={() => handleDelete(wh.id)}
                  className="p-1 text-red-400 hover:text-red-300"
                >
                  <TrashIcon className="w-4 h-4" />
                </button>
              </div>
            </div>

            {expandedId === wh.id && (
              <div className="border-t border-white/10 p-4">
                <h3 className="text-sm text-gray-300 mb-2">История доставок</h3>
                {deliveries.length === 0 ? (
                  <p className="text-gray-500 text-xs">Нет доставок</p>
                ) : (
                  <table className="w-full text-xs text-gray-300">
                    <thead>
                      <tr className="text-gray-500">
                        <td className="py-1">Событие</td>
                        <td>Статус</td>
                        <td>Код</td>
                        <td>Попытки</td>
                        <td>Время</td>
                      </tr>
                    </thead>
                    <tbody>
                      {deliveries.map((d) => (
                        <tr key={d.id}>
                          <td className="py-1">{d.event_type}</td>
                          <td>
                            <span className={d.status === 'success' ? 'text-green-400' : 'text-red-400'}>
                              {d.status}
                            </span>
                          </td>
                          <td>{d.response_code || '-'}</td>
                          <td>{d.attempts}</td>
                          <td>{d.last_attempt_ts ? new Date(d.last_attempt_ts).toLocaleString('ru-RU') : '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}
          </div>
        ))}
        {webhooks.length === 0 && !loading && (
          <p className="text-gray-400 text-center py-8">Нет webhook-подписок</p>
        )}
      </div>
    </div>
  );
}
