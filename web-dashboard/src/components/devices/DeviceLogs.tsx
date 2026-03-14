import { useState, useEffect, useCallback } from 'react';
import { sendCommand, getDeviceLogs, type DeviceLogEntry } from '../../api/devices';
import { ArrowPathIcon } from '@heroicons/react/24/outline';

interface Props {
  deviceId: string;
}

export default function DeviceLogs({ deviceId }: Props) {
  const [logs, setLogs] = useState<DeviceLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [requesting, setRequesting] = useState(false);
  const [activeTab, setActiveTab] = useState<string>('');

  const fetchLogs = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getDeviceLogs(deviceId);
      setLogs(data);
      if (data.length > 0 && !activeTab) {
        setActiveTab(data[0].log_type);
      }
    } catch (err) {
      console.error('Failed to load device logs', err);
    } finally {
      setLoading(false);
    }
  }, [deviceId, activeTab]);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  const handleRequestLogs = async () => {
    setRequesting(true);
    try {
      await sendCommand(deviceId, { command_type: 'UPLOAD_LOGS' });
      // Wait for agent to process and upload
      setTimeout(() => {
        fetchLogs();
        setRequesting(false);
      }, 10000);
    } catch (err) {
      console.error('Failed to request logs', err);
      setRequesting(false);
    }
  };

  const logTypes = [...new Set(logs.map((l) => l.log_type))].sort();
  const activeLog = logs.find((l) => l.log_type === activeTab);

  const tabLabels: Record<string, string> = {
    'kadero-agent': 'Основной',
    'kadero-http': 'HTTP',
    'kadero-pipe': 'IPC Pipe',
  };

  return (
    <div className="card mt-6">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-900">Логи приложения</h2>
        <button
          onClick={handleRequestLogs}
          disabled={requesting}
          className="inline-flex items-center gap-2 rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <ArrowPathIcon className={`h-4 w-4 ${requesting ? 'animate-spin' : ''}`} />
          {requesting ? 'Запрос отправлен...' : 'Обновить логи'}
        </button>
      </div>

      {logTypes.length === 0 && !loading ? (
        <p className="text-sm text-gray-500">
          Логи ещё не загружены. Нажмите «Обновить логи» для запроса с агента.
        </p>
      ) : (
        <>
          {/* Tabs */}
          <div className="border-b border-gray-200 mb-4">
            <nav className="-mb-px flex gap-x-4">
              {logTypes.map((type) => (
                <button
                  key={type}
                  onClick={() => setActiveTab(type)}
                  className={`whitespace-nowrap border-b-2 py-2 px-1 text-sm font-medium ${
                    activeTab === type
                      ? 'border-indigo-500 text-indigo-600'
                      : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
                  }`}
                >
                  {tabLabels[type] || type}
                </button>
              ))}
            </nav>
          </div>

          {/* Log content */}
          {activeLog && (
            <div>
              <div className="flex items-center gap-3 mb-2 text-xs text-gray-500">
                <span>Загружено: {new Date(activeLog.uploaded_at).toLocaleString('ru-RU')}</span>
                {activeLog.log_from_ts && (
                  <span>Период: {new Date(activeLog.log_from_ts).toLocaleString('ru-RU')} — {activeLog.log_to_ts ? new Date(activeLog.log_to_ts).toLocaleString('ru-RU') : 'сейчас'}</span>
                )}
              </div>
              <pre className="bg-gray-900 text-green-400 text-xs font-mono p-4 rounded-lg overflow-auto max-h-[500px] whitespace-pre-wrap break-all">
                {activeLog.content}
              </pre>
            </div>
          )}
        </>
      )}
    </div>
  );
}
