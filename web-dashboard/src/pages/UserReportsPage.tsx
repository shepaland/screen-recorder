import { useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import WorktimeReport from '../components/reports/WorktimeReport';
import AppsDomainsReport from '../components/reports/AppsDomainsReport';
import TimeReport from '../components/reports/TimeReport';
import TimesheetReport from '../components/reports/TimesheetReport';
import EmployeeRecordings from '../components/reports/EmployeeRecordings';
import {
  ArrowLeftIcon,
  ClockIcon,
  ComputerDesktopIcon,
  ChartBarIcon,
  TableCellsIcon,
  VideoCameraIcon,
} from '@heroicons/react/24/outline';

type TabId = 'worktime' | 'apps' | 'time' | 'timesheet' | 'recordings';

interface Tab {
  id: TabId;
  name: string;
  icon: typeof ClockIcon;
}

const TABS: Tab[] = [
  { id: 'worktime', name: 'Рабочее время', icon: ClockIcon },
  { id: 'apps', name: 'Приложения и сайты', icon: ComputerDesktopIcon },
  { id: 'time', name: 'Отчет по времени', icon: ChartBarIcon },
  { id: 'timesheet', name: 'Табель', icon: TableCellsIcon },
  { id: 'recordings', name: 'Записи экранов', icon: VideoCameraIcon },
];

function getDefaultDateRange(): { from: string; to: string } {
  const now = new Date();
  const to = now.toISOString().split('T')[0];
  const from = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().split('T')[0];
  return { from, to };
}

function getCurrentMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

export default function UserReportsPage() {
  const { username } = useParams<{ username: string }>();
  const navigate = useNavigate();
  const decodedUsername = username ? decodeURIComponent(username) : '';

  const [activeTab, setActiveTab] = useState<TabId>('worktime');
  const defaults = useMemo(getDefaultDateRange, []);
  const [from, setFrom] = useState(defaults.from);
  const [to, setTo] = useState(defaults.to);
  const [month, setMonth] = useState(getCurrentMonth());

  const displayName = decodedUsername.includes('\\')
    ? decodedUsername.split('\\')[1]
    : decodedUsername;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center gap-4">
        <div className="flex items-center gap-4 flex-1">
          <button
            onClick={() => navigate('/archive/employees')}
            className="p-2 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg"
          >
            <ArrowLeftIcon className="h-5 w-5" />
          </button>
          <div className="flex-1">
            <h1 className="text-2xl font-bold text-gray-900">{displayName}</h1>
            {displayName !== decodedUsername && (
              <p className="text-sm text-gray-500">{decodedUsername}</p>
            )}
          </div>
        </div>

        {/* Date controls */}
        <div className="flex items-center gap-2">
          {activeTab === 'timesheet' ? (
            <input
              type="month"
              value={month}
              onChange={(e) => setMonth(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-red-500 focus:ring-1 focus:ring-red-500"
            />
          ) : (
            <>
              <input
                type="date"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
                className="rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-red-500 focus:ring-1 focus:ring-red-500"
              />
              <span className="text-gray-400">-</span>
              <input
                type="date"
                value={to}
                onChange={(e) => setTo(e.target.value)}
                className="rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-red-500 focus:ring-1 focus:ring-red-500"
              />
            </>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="border-b border-gray-200">
        <nav className="-mb-px flex space-x-2 sm:space-x-8 overflow-x-auto -mx-4 px-4 scrollbar-hide">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 whitespace-nowrap border-b-2 py-3 px-1 text-sm font-medium transition-colors ${
                activeTab === tab.id
                  ? 'border-red-500 text-red-600'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
              }`}
            >
              <tab.icon className="h-5 w-5" />
              {tab.name}
            </button>
          ))}
        </nav>
      </div>

      {/* Tab content */}
      <div>
        {activeTab === 'worktime' && (
          <WorktimeReport username={decodedUsername} from={from} to={to} />
        )}
        {activeTab === 'apps' && (
          <AppsDomainsReport username={decodedUsername} from={from} to={to} />
        )}
        {activeTab === 'time' && (
          <TimeReport username={decodedUsername} from={from} to={to} />
        )}
        {activeTab === 'timesheet' && (
          <TimesheetReport username={decodedUsername} month={month} />
        )}
        {activeTab === 'recordings' && (
          <EmployeeRecordings username={decodedUsername} from={from} to={to} />
        )}
      </div>
    </div>
  );
}
