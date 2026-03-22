import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { getUsers } from '../api/user-activity';
import {
  getEmployeeGroups,
  createEmployeeGroup,
  updateEmployeeGroup,
  deleteEmployeeGroup,
  addEmployeeGroupMember,
  getGroupMetrics,
} from '../api/employee-groups';
import type { UserSummary } from '../types/user-activity';
import type { EmployeeGroup, GroupMetricsResponse } from '../types/employee-groups';
import LoadingSpinner from '../components/LoadingSpinner';
import EmployeeGroupSidebar from '../components/employees/EmployeeGroupSidebar';
import CreateEmployeeGroupModal from '../components/employees/CreateEmployeeGroupModal';
import GroupTimelineChart from '../components/dashboard/GroupTimelineChart';
import {
  UserGroupIcon,
  MagnifyingGlassIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  UsersIcon,
  CheckCircleIcon,
  FunnelIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline';

const PAGE_SIZE = 20;

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

/** Search recursively for group name by id (supports nested groups) */
function findGroupName(groups: EmployeeGroup[], id: string): string {
  for (const g of groups) {
    if (g.id === id) return g.name;
    if (g.children) {
      for (const child of g.children) {
        if (child.id === id) return `${g.name} / ${child.name}`;
      }
    }
  }
  return '';
}

export default function EmployeeListPage() {
  const navigate = useNavigate();
  const [users, setUsers] = useState<UserSummary[]>([]);
  const [groups, setGroups] = useState<EmployeeGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [filter, setFilter] = useState<'all' | 'active' | 'inactive'>('all');

  // Group selection
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);
  const [showUngrouped, setShowUngrouped] = useState(false);

  // Modal
  const [showGroupModal, setShowGroupModal] = useState(false);
  const [editingGroup, setEditingGroup] = useState<EmployeeGroup | null>(null);
  const [createParentId, setCreateParentId] = useState<string | null>(null);

  // Metrics
  const [metrics, setMetrics] = useState<GroupMetricsResponse | null>(null);

  // Chart date range
  const [dateRange, setDateRange] = useState<DateRange>('7');
  const { from: chartFrom, to: chartTo } = useMemo(() => getDateRange(dateRange), [dateRange]);

  // Mobile group panel
  const [groupPanelOpen, setGroupPanelOpen] = useState(false);

  // Assign to group
  const [assigningUser, setAssigningUser] = useState<string | null>(null);
  const [showAssignMenu, setShowAssignMenu] = useState(false);
  const [assignMenuPos, setAssignMenuPos] = useState({ x: 0, y: 0 });

  const fetchGroups = useCallback(async () => {
    try {
      const data = await getEmployeeGroups();
      setGroups(data);
    } catch (err) {
      console.error('Failed to load employee groups', err);
    }
  }, []);

  const fetchMetrics = useCallback(async () => {
    try {
      const data = await getGroupMetrics(selectedGroupId || undefined);
      setMetrics(data);
    } catch (err) {
      console.error('Failed to load group metrics', err);
    }
  }, [selectedGroupId]);

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
        groupId: selectedGroupId || undefined,
        ungrouped: showUngrouped || undefined,
      });
      setUsers(resp.content);
      setTotalPages(resp.total_pages);
      setTotalElements(resp.total_elements);
    } catch (err) {
      console.error('Failed to load users', err);
    } finally {
      setLoading(false);
    }
  }, [page, search, filter, selectedGroupId, showUngrouped]);

  useEffect(() => {
    fetchGroups();
  }, [fetchGroups]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  useEffect(() => {
    fetchMetrics();
  }, [fetchMetrics]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setSearch(searchInput);
  };

  const handleUserClick = (username: string) => {
    navigate(`/archive/employees/${encodeURIComponent(username)}`);
  };

  const handleSelectAll = () => {
    setSelectedGroupId(null);
    setShowUngrouped(false);
    setPage(0);
  };

  const handleSelectGroup = (groupId: string) => {
    setSelectedGroupId(groupId);
    setShowUngrouped(false);
    setPage(0);
  };

  const handleSelectUngrouped = () => {
    setSelectedGroupId(null);
    setShowUngrouped(true);
    setPage(0);
  };

  const handleCreateGroup = (parentId?: string) => {
    setEditingGroup(null);
    setCreateParentId(parentId || null);
    setShowGroupModal(true);
  };

  const handleEditGroup = (group: EmployeeGroup) => {
    setEditingGroup(group);
    setShowGroupModal(true);
  };

  const handleDeleteGroup = async (group: EmployeeGroup) => {
    if (!confirm(`Удалить группу "${group.name}"? Сотрудники будут перемещены в "Неразмеченное".`)) return;
    try {
      await deleteEmployeeGroup(group.id);
      if (selectedGroupId === group.id) handleSelectAll();
      await fetchGroups();
      await fetchUsers();
    } catch (err) {
      console.error('Failed to delete group', err);
    }
  };

  const handleGroupSubmit = async (data: { name: string; description?: string; color?: string; parent_id?: string }) => {
    try {
      if (editingGroup) {
        await updateEmployeeGroup(editingGroup.id, data);
      } else {
        await createEmployeeGroup(data);
      }
      setShowGroupModal(false);
      setCreateParentId(null);
      await fetchGroups();
      await fetchMetrics();
    } catch (err) {
      console.error('Failed to save group', err);
      alert('Ошибка: ' + (err instanceof Error ? err.message : 'Не удалось сохранить'));
    }
  };

  const handleAssignClick = (e: React.MouseEvent, username: string) => {
    e.stopPropagation();
    setAssigningUser(username);
    setAssignMenuPos({ x: e.clientX, y: e.clientY });
    setShowAssignMenu(true);
  };

  const handleAssignToGroup = async (groupId: string) => {
    if (!assigningUser) return;
    try {
      await addEmployeeGroupMember(groupId, { username: assigningUser });
      setShowAssignMenu(false);
      setAssigningUser(null);
      await fetchGroups();
      await fetchUsers();
    } catch (err) {
      console.error('Failed to assign employee', err);
    }
  };

  // Close assign menu on click outside
  useEffect(() => {
    if (!showAssignMenu) return;
    const handler = () => setShowAssignMenu(false);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [showAssignMenu]);

  return (
    <div className="flex h-[calc(100vh-4rem)]">
      {/* Desktop sidebar — groups */}
      <div className="hidden lg:block">
        <EmployeeGroupSidebar
          groups={groups}
          selectedGroupId={selectedGroupId}
          ungrouped={showUngrouped}
          onSelectAll={handleSelectAll}
          onSelectGroup={(id) => { handleSelectGroup(id); setGroupPanelOpen(false); }}
          onSelectUngrouped={() => { handleSelectUngrouped(); setGroupPanelOpen(false); }}
          onCreateGroup={handleCreateGroup}
          onEditGroup={handleEditGroup}
          onDeleteGroup={handleDeleteGroup}
          totalEmployees={totalElements}
        />
      </div>

      {/* Mobile slide-over for groups */}
      {groupPanelOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="fixed inset-0 bg-gray-600/75" onClick={() => setGroupPanelOpen(false)} />
          <div className="fixed inset-y-0 left-0 w-72 bg-white shadow-xl z-50 overflow-y-auto">
            <div className="flex items-center justify-between p-4 border-b">
              <h2 className="text-lg font-semibold text-gray-900">Группы</h2>
              <button onClick={() => setGroupPanelOpen(false)} className="p-1 text-gray-400 hover:text-gray-600">
                <XMarkIcon className="h-5 w-5" />
              </button>
            </div>
            <EmployeeGroupSidebar
              groups={groups}
              selectedGroupId={selectedGroupId}
              ungrouped={showUngrouped}
              onSelectAll={() => { handleSelectAll(); setGroupPanelOpen(false); }}
              onSelectGroup={(id) => { handleSelectGroup(id); setGroupPanelOpen(false); }}
              onSelectUngrouped={() => { handleSelectUngrouped(); setGroupPanelOpen(false); }}
              onCreateGroup={handleCreateGroup}
              onEditGroup={handleEditGroup}
              onDeleteGroup={handleDeleteGroup}
              totalEmployees={totalElements}
            />
          </div>
        </div>
      )}

      {/* Right panel — table */}
      <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-6">
        {/* Mobile: group filter button */}
        <div className="lg:hidden">
          <button
            onClick={() => setGroupPanelOpen(true)}
            className="w-full flex items-center justify-center gap-2 px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
          >
            <FunnelIcon className="h-5 w-5" />
            Группы {selectedGroupId ? `(${findGroupName(groups, selectedGroupId)})` : showUngrouped ? '(Неразмеченные)' : '(Все)'}
          </button>
        </div>
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Сотрудники</h1>
            <p className="mt-1 text-sm text-gray-500">
              {selectedGroupId
                ? `Группа: ${findGroupName(groups, selectedGroupId)}`
                : showUngrouped
                  ? 'Неразмеченные сотрудники'
                  : 'Все сотрудники'}
            </p>
          </div>
          <div className="text-sm text-gray-500">
            {totalElements} сотрудник{totalElements === 1 ? '' : totalElements < 5 ? 'а' : 'ов'}
          </div>
        </div>

        {/* Metrics cards */}
        {metrics && (
          <div className="grid grid-cols-2 gap-4">
            <div className="bg-white rounded-lg border border-gray-200 p-4 flex items-center gap-3">
              <div className="p-2 bg-blue-50 rounded-lg">
                <UsersIcon className="h-6 w-6 text-blue-600" />
              </div>
              <div>
                <p className="text-2xl font-bold text-gray-900">{metrics.total_employees}</p>
                <p className="text-sm text-gray-500">Всего сотрудников</p>
              </div>
            </div>
            <div className="bg-white rounded-lg border border-gray-200 p-4 flex items-center gap-3">
              <div className="p-2 bg-green-50 rounded-lg">
                <CheckCircleIcon className="h-6 w-6 text-green-600" />
              </div>
              <div>
                <p className="text-2xl font-bold text-gray-900">{metrics.active_employees}</p>
                <p className="text-sm text-gray-500">Активных</p>
              </div>
            </div>
          </div>
        )}

        {/* Period selector + Timeline charts */}
        <div className="flex items-center justify-end">
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
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <GroupTimelineChart
            groupType="APP"
            from={chartFrom}
            to={chartTo}
            title="Приложения"
            employeeGroupId={selectedGroupId || undefined}
          />
          <GroupTimelineChart
            groupType="SITE"
            from={chartFrom}
            to={chartTo}
            title="Сайты"
            employeeGroupId={selectedGroupId || undefined}
          />
        </div>

        {/* Filters */}
        <div className="flex flex-col sm:flex-row sm:items-center gap-4">
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
        {loading && users.length === 0 ? (
          <div className="flex items-center justify-center h-64">
            <LoadingSpinner size="lg" />
          </div>
        ) : users.length === 0 ? (
          <div className="text-center py-12">
            <UserGroupIcon className="mx-auto h-12 w-12 text-gray-400" />
            <h3 className="mt-2 text-sm font-semibold text-gray-900">Нет сотрудников</h3>
            <p className="mt-1 text-sm text-gray-500">
              Сотрудники появятся после начала записи на устройствах
            </p>
          </div>
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 shadow-sm">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Сотрудник
                  </th>
                  <th className="hidden sm:table-cell px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
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
                  <th className="hidden md:table-cell px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Группа
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
                    <td className="hidden sm:table-cell px-6 py-4 whitespace-nowrap text-sm text-gray-500">
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
                    <td className="hidden md:table-cell px-6 py-4 whitespace-nowrap">
                      <div className="flex items-center gap-1.5 flex-wrap">
                        {user.groups && user.groups.length > 0 ? (
                          user.groups.map((gn, i) => (
                            <span key={i} className="inline-flex items-center rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700">
                              {gn}
                            </span>
                          ))
                        ) : null}
                        <button
                          onClick={(e) => handleAssignClick(e, user.username)}
                          className="text-xs text-gray-400 hover:text-red-600 hover:bg-red-50 px-1.5 py-0.5 rounded transition-colors"
                          title="Назначить группу"
                        >
                          +
                        </button>
                      </div>
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

      {/* Assign dropdown — show flat list of all leaf groups */}
      {showAssignMenu && groups.length > 0 && (
        <div
          className="fixed z-50 bg-white rounded-lg shadow-lg border border-gray-200 py-1 min-w-[200px] max-h-[300px] overflow-y-auto"
          style={{
            left: Math.min(assignMenuPos.x, window.innerWidth - 220),
            top: Math.min(assignMenuPos.y, window.innerHeight - 320),
          }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="px-3 py-1.5 text-xs font-medium text-gray-500 uppercase">В группу</div>
          {groups.map((g) => {
            const hasChildren = g.children && g.children.length > 0;
            if (hasChildren) {
              // Show parent as label, children as options
              return (
                <div key={g.id}>
                  <div className="px-3 py-1 text-xs font-semibold text-gray-400 mt-1">{g.name}</div>
                  {g.children!.map((child) => (
                    <button
                      key={child.id}
                      onClick={() => handleAssignToGroup(child.id)}
                      className="w-full flex items-center gap-2 px-5 py-1.5 text-sm text-gray-700 hover:bg-gray-100"
                    >
                      <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: child.color || g.color || '#9CA3AF' }} />
                      {child.name}
                    </button>
                  ))}
                </div>
              );
            }
            // Leaf group at root level
            return (
              <button
                key={g.id}
                onClick={() => handleAssignToGroup(g.id)}
                className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100"
              >
                <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: g.color || '#9CA3AF' }} />
                {g.name}
              </button>
            );
          })}
        </div>
      )}

      {/* Create/Edit modal */}
      <CreateEmployeeGroupModal
        isOpen={showGroupModal}
        onClose={() => { setShowGroupModal(false); setCreateParentId(null); }}
        onSubmit={handleGroupSubmit}
        editGroup={editingGroup}
        parentId={createParentId}
        parentName={createParentId ? groups.find(g => g.id === createParentId)?.name || null : null}
      />
    </div>
  );
}
