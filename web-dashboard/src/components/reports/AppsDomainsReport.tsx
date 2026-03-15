import { useState, useEffect } from 'react';
import { getUserApps, getUserDomains } from '../../api/user-activity';
import type { AppsReportResponse, DomainsReportResponse } from '../../types/user-activity';
import LoadingSpinner from '../LoadingSpinner';

interface AppsDomainsReportProps {
  username: string;
  from: string;
  to: string;
  deviceId?: string;
}

function formatDuration(ms: number): string {
  if (ms < 60000) return `${Math.round(ms / 1000)}с`;
  const minutes = Math.floor(ms / 60000);
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (hours === 0) return `${mins}м`;
  return `${hours}ч ${mins}м`;
}

export default function AppsDomainsReport({ username, from, to, deviceId }: AppsDomainsReportProps) {
  const [apps, setApps] = useState<AppsReportResponse | null>(null);
  const [domains, setDomains] = useState<DomainsReportResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);

    Promise.all([
      getUserApps(username, from, to, { size: 20, deviceId }),
      getUserDomains(username, from, to, { size: 20, deviceId }),
    ])
      .then(([appsResp, domainsResp]) => {
        if (!cancelled) {
          setApps(appsResp);
          setDomains(domainsResp);
        }
      })
      .catch(console.error)
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [username, from, to, deviceId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Apps */}
      <div className="bg-white rounded-lg border border-gray-200 p-5">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Приложения</h3>
        {apps && apps.content.length > 0 ? (
          <div className="space-y-3">
            {apps.content.map((app) => (
              <div key={app.process_name} className="group">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium text-gray-900 truncate mr-2" title={app.process_name}>
                    {app.process_name}
                  </span>
                  <span className="text-sm text-gray-500 shrink-0">
                    {formatDuration(app.total_duration_ms)}
                  </span>
                </div>
                <div className="w-full bg-gray-100 rounded-full h-2">
                  <div
                    className="bg-red-500 h-2 rounded-full transition-all"
                    style={{ width: `${Math.min(app.percentage, 100)}%` }}
                  />
                </div>
                <p className="text-xs text-gray-400 mt-0.5 truncate" title={app.window_title_sample}>
                  {app.window_title_sample || '\u00A0'}
                </p>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-gray-500">Нет данных</p>
        )}
        {apps && apps.total_active_ms > 0 && (
          <div className="mt-4 text-xs text-gray-400 space-y-0.5">
            <p>Общее время: {formatDuration(apps.total_active_ms)}</p>
            <p className="text-green-600">Реальная активность: {formatDuration(apps.real_active_ms)} ({Math.round(apps.real_active_ms / apps.total_active_ms * 100)}%)</p>
          </div>
        )}
      </div>

      {/* Domains */}
      <div className="bg-white rounded-lg border border-gray-200 p-5">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Сайты</h3>
        {domains && domains.content.length > 0 ? (
          <div className="space-y-3">
            {domains.content.map((domain) => (
              <div key={`${domain.domain}-${domain.browser_name}`} className="group">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium text-gray-900 truncate mr-2" title={domain.domain}>
                    {domain.domain}
                  </span>
                  <span className="text-sm text-gray-500 shrink-0">
                    {formatDuration(domain.total_duration_ms)}
                  </span>
                </div>
                <div className="w-full bg-gray-100 rounded-full h-2">
                  <div
                    className="bg-blue-500 h-2 rounded-full transition-all"
                    style={{ width: `${Math.min(domain.percentage, 100)}%` }}
                  />
                </div>
                <div className="flex items-center gap-2 mt-0.5">
                  <span className="text-xs text-gray-400">{domain.browser_name || '-'}</span>
                  <span className="text-xs text-gray-400">{domain.visit_count} визит{domain.visit_count === 1 ? '' : 'ов'}</span>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-gray-500">Нет данных</p>
        )}
        {domains && domains.total_browser_ms > 0 && (
          <p className="mt-4 text-xs text-gray-400">
            Всего в браузерах: {formatDuration(domains.total_browser_ms)}
          </p>
        )}
      </div>
    </div>
  );
}
