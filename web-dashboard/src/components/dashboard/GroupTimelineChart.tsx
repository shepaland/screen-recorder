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
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr + 'T00:00:00');
  return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
}

const UNGROUPED_COLOR = '#9CA3AF';

export default function GroupTimelineChart({ groupType, from, to, title }: GroupTimelineChartProps) {
  const [data, setData] = useState<GroupTimelineResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await catalogsApi.getGroupTimeline(groupType, from, to);
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
  }, [groupType, from, to]);

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
          <Tooltip
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            formatter={(value: any, name: any) => {
              const numValue = typeof value === 'number' ? value : Number(value);
              const nameStr = String(name);
              const group = data.groups.find((g) => g.group_id === nameStr);
              const label = group ? group.group_name : (nameStr === 'ungrouped' ? 'Неразмеченные' : nameStr);
              return [`${numValue.toFixed(1)}%`, label];
            }}
            contentStyle={{
              backgroundColor: 'white',
              border: '1px solid #e5e7eb',
              borderRadius: '0.5rem',
              fontSize: '12px',
            }}
          />
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
