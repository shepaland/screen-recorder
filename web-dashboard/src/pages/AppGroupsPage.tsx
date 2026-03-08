import { useEffect, useState, useCallback } from 'react';
import { PlusIcon, ArrowPathIcon } from '@heroicons/react/24/outline';
import * as catalogsApi from '../api/catalogs';
import type { AppGroup, GroupType, CreateGroupRequest } from '../types/catalogs';
import GroupTable from '../components/catalogs/GroupTable';
import UngroupedTable from '../components/catalogs/UngroupedTable';
import CreateGroupModal from '../components/catalogs/CreateGroupModal';
import { usePermissions } from '../hooks/usePermissions';

const GROUP_TYPE: GroupType = 'APP';

export default function AppGroupsPage() {
  const [groups, setGroups] = useState<AppGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [activeTab, setActiveTab] = useState<'groups' | 'ungrouped'>('groups');
  const { hasPermission } = usePermissions();
  const canManage = hasPermission('CATALOGS:MANAGE');

  const loadGroups = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await catalogsApi.getGroups(GROUP_TYPE);
      setGroups(resp.groups);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load groups';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadGroups();
  }, [loadGroups]);

  const handleCreate = async (data: CreateGroupRequest) => {
    await catalogsApi.createGroup(data);
  };

  return (
    <div>
      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">
            Группы приложений
          </h1>
          <p className="mt-1 text-sm text-gray-500">
            Управление группами для классификации приложений
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button onClick={loadGroups} className="btn-secondary" title="Обновить">
            <ArrowPathIcon className="h-4 w-4" />
          </button>
          {canManage && (
            <button onClick={() => setShowCreate(true)} className="btn-primary">
              <PlusIcon className="h-4 w-4 mr-1.5" />
              Новая группа
            </button>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="mb-6 border-b border-gray-200">
        <nav className="-mb-px flex gap-x-6">
          <button
            onClick={() => setActiveTab('groups')}
            className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'groups'
                ? 'border-red-600 text-red-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            Группы ({groups.length})
          </button>
          <button
            onClick={() => setActiveTab('ungrouped')}
            className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'ungrouped'
                ? 'border-red-600 text-red-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            Неразмеченные
          </button>
        </nav>
      </div>

      {/* Content */}
      {error ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <p className="text-sm text-red-700">{error}</p>
          <button onClick={loadGroups} className="mt-2 text-sm font-medium text-red-700 underline">
            Повторить
          </button>
        </div>
      ) : loading && activeTab === 'groups' ? (
        <div className="flex h-64 items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-red-600" />
        </div>
      ) : activeTab === 'groups' ? (
        <GroupTable
          groups={groups}
          groupType={GROUP_TYPE}
          allGroups={groups}
          onRefresh={loadGroups}
        />
      ) : (
        <UngroupedTable
          itemType={GROUP_TYPE}
          groups={groups}
          onAssigned={loadGroups}
        />
      )}

      {/* Create group modal */}
      <CreateGroupModal
        open={showCreate}
        groupType={GROUP_TYPE}
        onClose={() => setShowCreate(false)}
        onCreated={loadGroups}
        onCreate={handleCreate}
      />
    </div>
  );
}
