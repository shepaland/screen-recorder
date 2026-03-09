import { useState, useMemo } from 'react';
import { useAuth } from '../hooks/useAuth';
import MetricsRow from '../components/dashboard/MetricsRow';
import GroupTimelineChart from '../components/dashboard/GroupTimelineChart';
import TopUngroupedTable from '../components/dashboard/TopUngroupedTable';
import TopUsersUngroupedTable from '../components/dashboard/TopUsersUngroupedTable';

type DateRange = '7' | '14' | '30';

function getDateRange(days: DateRange): { from: string; to: string } {
  const to = new Date();
  const from = new Date();
  from.setDate(from.getDate() - parseInt(days));
  return {
    from: from.toISOString().slice(0, 10),
    to: to.toISOString().slice(0, 10),
  };
}

export default function DashboardPage() {
  const { user, tenants } = useAuth();
  const [dateRange, setDateRange] = useState<DateRange>('7');

  const { from, to } = useMemo(() => getDateRange(dateRange), [dateRange]);

  // Get tenant name from tenants list
  const tenantName = useMemo(() => {
    if (!user?.tenant_id) return '';
    const tenant = tenants.find((t) => t.id === user.tenant_id);
    return tenant?.name || '';
  }, [user?.tenant_id, tenants]);

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">
            {tenantName ? `${tenantName}` : 'Dashboard'}
          </h1>
          <p className="mt-1 text-sm text-gray-500">
            Обзор активности, статистика устройств и распределение по группам
          </p>
        </div>

        {/* Period selector */}
        <div className="flex items-center gap-1 rounded-lg bg-gray-100 p-1">
          {([
            { value: '7' as DateRange, label: '7 дней' },
            { value: '14' as DateRange, label: '14 дней' },
            { value: '30' as DateRange, label: '30 дней' },
          ]).map(({ value, label }) => (
            <button
              key={value}
              onClick={() => setDateRange(value)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                dateRange === value
                  ? 'bg-white text-gray-900 shadow-sm'
                  : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* Row 1: Metrics */}
      <MetricsRow />

      {/* Row 2: Group Timeline Charts */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <GroupTimelineChart
          groupType="APP"
          from={from}
          to={to}
          title="Приложения по группам"
        />
        <GroupTimelineChart
          groupType="SITE"
          from={from}
          to={to}
          title="Сайты по группам"
        />
      </div>

      {/* Row 3: Top Ungrouped */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <TopUngroupedTable
          itemType="APP"
          from={from}
          to={to}
          title="Неразмеченные приложения"
        />
        <TopUngroupedTable
          itemType="SITE"
          from={from}
          to={to}
          title="Неразмеченные сайты"
        />
      </div>

      {/* Row 4: Top Users by Ungrouped */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <TopUsersUngroupedTable
          itemType="APP"
          from={from}
          to={to}
          title="Пользователи: неразмеч. приложения"
        />
        <TopUsersUngroupedTable
          itemType="SITE"
          from={from}
          to={to}
          title="Пользователи: неразмеч. сайты"
        />
      </div>
    </div>
  );
}
