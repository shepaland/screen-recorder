import { useEffect, useState, useMemo } from 'react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import * as catalogsApi from '../../api/catalogs';
import type { GroupType, GroupTimelineResponse } from '../../types/catalogs';

interface GroupTimelineChartProps {
  groupType: GroupType;
  from: string;
  to: string;
  title: string;
  employeeGroupId?: string;
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr + 'T00:00:00');
  return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
}

const UNGROUPED_COLOR = '#9CA3AF';

// Custom tooltip: sorted by percentage desc, compact line spacing
interface CustomTooltipProps {
  active?: boolean;
  payload?: Array<{ name: string; value: number; color: string }>;
  label?: string;
  groups: Array<{ group_id: string; group_name: string; color: string }>;
}

function CustomTooltip({ active, payload, label, groups }: CustomTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;

  // Sort by value descending, filter out zero
  const sorted = [...payload]
    .filter((e) => typeof e.value === 'number' && e.value > 0)
    .sort((a, b) => (b.value as number) - (a.value as number));

  if (sorted.length === 0) return null;

  return (
    <div
      style={{
        backgroundColor: 'white',
        border: '1px solid #e5e7eb',
        borderRadius: '0.375rem',
        fontSize: '12px',
        padding: '6px 10px',
        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
        lineHeight: '1.2',
      }}
    >
      <p style={{ margin: '0 0 3px', fontWeight: 600, color: '#374151', fontSize: '11px' }}>{label}</p>
      {sorted.map((entry) => {
        const group = groups.find((g) => g.group_id === entry.name);
        const displayName = group ? group.group_name : entry.name === 'ungrouped' ? 'Неразмеченные' : entry.name;
        return (
          <div key={entry.name} style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '1px 0' }}>
            <span
              style={{
                width: 7,
                height: 7,
                borderRadius: '50%',
                backgroundColor: entry.color,
                flexShrink: 0,
              }}
            />
            <span style={{ color: '#6b7280', whiteSpace: 'nowrap' }}>{displayName}</span>
            <span style={{ marginLeft: 'auto', paddingLeft: 10, fontWeight: 500, color: '#111827' }}>
              {typeof entry.value === 'number' ? entry.value.toFixed(1) : entry.value}%
            </span>
          </div>
        );
      })}
    </div>
  );
}

export default function GroupTimelineChart({ groupType, from, to, title, employeeGroupId }: GroupTimelineChartProps) {
  const [data, setData] = useState<GroupTimelineResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await catalogsApi.getGroupTimeline(groupType, from, to, undefined, employeeGroupId);
      setData(resp);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load timeline';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [groupType, from, to, employeeGroupId]);

  const chartData = useMemo(() => {
    if (!data) return [];
    return data.days.map((day) => {
      const row: Record<string, string | number> = {
        date: formatDate(day.date),
        dateRaw: day.date,
        ungrouped: day.ungrouped_percentage,
      };
      for (const group of data.groups) {
        const entry = day.breakdown.find((b) => b.group_id === group.group_id);
        row[group.group_id] = entry ? entry.percentage : 0;
      }
      return row;
    });
  }, [data]);

  if (error) {
    return (
      <div className="card">
        <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <p className="text-sm text-red-700">{error}</p>
          <button onClick={load} className="mt-2 text-sm font-medium text-red-700 underline hover:text-red-600">
            Повторить
          </button>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="card">
        <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
        <div className="flex h-64 items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-red-600" />
        </div>
      </div>
    );
  }

  if (!data || data.days.length === 0) {
    return (
      <div className="card">
        <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
        <div className="flex h-64 items-center justify-center text-sm text-gray-400">
          Нет данных за выбранный период
        </div>
      </div>
    );
  }

  return (
    <div className="card">
      <h3 className="text-sm font-semibold text-gray-900 mb-3">{title}</h3>
      <ResponsiveContainer width="100%" height={280}>
        <LineChart data={chartData} margin={{ top: 5, right: 10, left: -10, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis
            dataKey="date"
            tick={{ fontSize: 11, fill: '#6b7280' }}
            axisLine={{ stroke: '#e5e7eb' }}
          />
          <YAxis
            unit="%"
            tick={{ fontSize: 11, fill: '#6b7280' }}
            axisLine={{ stroke: '#e5e7eb' }}
            domain={[0, 100]}
          />
          <Tooltip content={<CustomTooltip groups={data.groups} />} />
          <Legend
            formatter={(value: string) => {
              const group = data.groups.find((g) => g.group_id === value);
              return group ? group.group_name : (value === 'ungrouped' ? 'Неразмеченные' : value);
            }}
            wrapperStyle={{ fontSize: '11px' }}
          />
          {data.groups.map((group) => (
            <Line
              key={group.group_id}
              type="monotone"
              dataKey={group.group_id}
              stroke={group.color}
              strokeWidth={2}
              dot={{ r: 2 }}
              activeDot={{ r: 4 }}
            />
          ))}
          <Line
            type="monotone"
            dataKey="ungrouped"
            stroke={UNGROUPED_COLOR}
            strokeWidth={1.5}
            strokeDasharray="4 4"
            dot={{ r: 2 }}
            activeDot={{ r: 4 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
