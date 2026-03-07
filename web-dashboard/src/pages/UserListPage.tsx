import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { getUsers } from '../api/user-activity';
import type { UserSummary } from '../types/user-activity';
import LoadingSpinner from '../components/LoadingSpinner';
import {
  UserGroupIcon,
  MagnifyingGlassIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';

const PAGE_SIZE = 20;

function formatTimeAgo(isoDate: string): string {
  const diff = Date.now() - new Date(isoDate).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'только что';
  if (minutes < 60) return `${minutes} мин. назад`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} ч. назад`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} дн. назад`;
  return new Date(isoDate).toLocaleDateString('ru-RU');
}

function getInitials(username: string): string {
  const name = username.includes('\\') ? username.split('\\')[1] : username;
  return name.substring(0, 2).toUpperCase();
}

export default function UserListPage() {
  const navigate = useNavigate();
  const [users, setUsers] = useState<UserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [filter, setFilter] = useState<'all' | 'active' | 'inactive'>('all');

  const fetchUsers = useCallback(async () => {
    try {
      setLoading(true);
      const resp = await getUsers({
        page,
        size: PAGE_SIZE,
        search: search || undefined,
        sortBy: 'last_seen_ts',
        sortDir: 'desc',
        isActive: filter === 'all' ? undefined : filter === 'active',
      });
      setUsers(resp.content);
      setTotalPages(resp.total_pages);
      setTotalElements(resp.total_elements);
    } catch (err) {
      console.error('Failed to load users', err);
    } finally {
      setLoading(false);
    }
  }, [page, search, filter]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setSearch(searchInput);
  };

  const handleUserClick = (username: string) => {
    navigate(`/archive/users/${encodeURIComponent(username)}`);
  };

  if (loading && users.length === 0) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Пользователи</h1>
          <p className="mt-1 text-sm text-gray-500">
            Активность пользователей на устройствах
          </p>
        </div>
        <div className="text-sm text-gray-500">
          {totalElements} пользовател{totalElements === 1 ? 'ь' : 'ей'}
        </div>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-4">
        <form onSubmit={handleSearch} className="flex-1 max-w-md">
          <div className="relative">
            <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-gray-400" />
            <input
              type="text"
              placeholder="Поиск по имени..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="block w-full rounded-lg border border-gray-300 py-2 pl-10 pr-3 text-sm placeholder:text-gray-400 focus:border-red-500 focus:ring-1 focus:ring-red-500"
            />
          </div>
        </form>

        <div className="flex rounded-lg border border-gray-300 overflow-hidden">
          {(['all', 'active', 'inactive'] as const).map((f) => (
            <button
              key={f}
              onClick={() => { setFilter(f); setPage(0); }}
              className={`px-3 py-2 text-sm font-medium ${
                filter === f
                  ? 'bg-red-600 text-white'
                  : 'bg-white text-gray-700 hover:bg-gray-50'
              }`}
            >
              {f === 'all' ? 'Все' : f === 'active' ? 'Активные' : 'Неактивные'}
            </button>
          ))}
        </div>
      </div>

      {/* Table */}
      {users.length === 0 ? (
        <div className="text-center py-12">
          <UserGroupIcon className="mx-auto h-12 w-12 text-gray-400" />
          <h3 className="mt-2 text-sm font-semibold text-gray-900">Нет пользователей</h3>
          <p className="mt-1 text-sm text-gray-500">
            Пользователи появятся после начала записи на устройствах
          </p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 shadow-sm">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Пользователь
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Домен
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Устройства
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Последняя активность
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                  Статус
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 bg-white">
              {users.map((user) => (
                <tr
                  key={user.username}
                  onClick={() => handleUserClick(user.username)}
                  className="hover:bg-gray-50 cursor-pointer transition-colors"
                >
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center gap-3">
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-red-100 text-red-700 font-bold text-sm">
                        {getInitials(user.username)}
                      </div>
                      <div>
                        <div className="text-sm font-medium text-gray-900">
                          {user.display_name || user.username}
                        </div>
                        {user.display_name && (
                          <div className="text-xs text-gray-500">{user.username}</div>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                    {user.windows_domain || '-'}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                    {user.device_count}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                    {formatTimeAgo(user.last_seen_ts)}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span
                      className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ${
                        user.is_active
                          ? 'bg-green-100 text-green-700'
                          : 'bg-gray-100 text-gray-500'
                      }`}
                    >
                      {user.is_active ? 'Активен' : 'Неактивен'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-4 pt-4">
          <button
            onClick={() => setPage(Math.max(0, page - 1))}
            disabled={page === 0}
            className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <ChevronLeftIcon className="h-4 w-4" />
            Назад
          </button>
          <span className="text-sm text-gray-700">
            Страница {page + 1} из {totalPages}
          </span>
          <button
            onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
            disabled={page >= totalPages - 1}
            className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Далее
            <ChevronRightIcon className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  );
}
