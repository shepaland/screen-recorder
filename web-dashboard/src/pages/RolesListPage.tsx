import { useState, useEffect, useCallback, Fragment } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon } from '@heroicons/react/20/solid';
import { DocumentDuplicateIcon } from '@heroicons/react/24/outline';
import { Dialog, Transition } from '@headlessui/react';
import DataTable, { type Column } from '../components/DataTable';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import { getRoles, deleteRole, cloneRole } from '../api/roles';
import type { RoleResponse } from '../types';
import { useToast } from '../contexts/ToastContext';

export default function RolesListPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(20);

  // Delete dialog
  const [deleteTarget, setDeleteTarget] = useState<RoleResponse | null>(null);

  // Clone dialog
  const [cloneSource, setCloneSource] = useState<RoleResponse | null>(null);
  const [cloneCode, setCloneCode] = useState('');
  const [cloneName, setCloneName] = useState('');
  const [cloneDescription, setCloneDescription] = useState('');
  const [cloning, setCloning] = useState(false);

  const fetchRoles = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getRoles({ page, size });
      setRoles(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      addToast('error', 'Не удалось загрузить роли');
    } finally {
      setLoading(false);
    }
  }, [page, size, addToast]);

  useEffect(() => {
    fetchRoles();
  }, [fetchRoles]);

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteRole(deleteTarget.id);
      addToast('success', `Роль "${deleteTarget.name}" удалена`);
      fetchRoles();
    } catch {
      addToast('error', 'Не удалось удалить роль');
    }
    setDeleteTarget(null);
  };

  const openCloneDialog = (role: RoleResponse) => {
    setCloneSource(role);
    setCloneCode(`${role.code}_COPY`);
    setCloneName(`${role.name} (копия)`);
    setCloneDescription('');
    setCloning(false);
  };

  const handleClone = async () => {
    if (!cloneSource) return;
    setCloning(true);
    try {
      await cloneRole(cloneSource.id, {
        code: cloneCode.toUpperCase().replace(/[^A-Z0-9_]/g, '_'),
        name: cloneName,
        description: cloneDescription || undefined,
      });
      addToast('success', `Роль "${cloneName}" создана`);
      setCloneSource(null);
      fetchRoles();
    } catch {
      addToast('error', 'Не удалось клонировать роль');
    } finally {
      setCloning(false);
    }
  };

  const columns: Column<RoleResponse>[] = [
    {
      key: 'code',
      title: 'Код',
      className: 'hidden sm:table-cell',
      render: (role) => (
        <span className="font-mono text-sm font-medium text-gray-900">{role.code}</span>
      ),
    },
    {
      key: 'name',
      title: 'Название',
      render: (role) => <span className="text-gray-900">{role.name}</span>,
    },
    {
      key: 'is_system',
      title: 'Тип',
      className: 'hidden md:table-cell',
      render: (role) => (
        <StatusBadge
          active={role.is_system}
          activeText="Системная"
          inactiveText="Пользовательская"
        />
      ),
    },
    {
      key: 'permissions_count',
      title: 'Разрешения',
      render: (role) => (
        <span className="text-gray-500">{role.permissions_count ?? '--'}</span>
      ),
    },
    {
      key: 'users_count',
      title: 'Пользователи',
      render: (role) => (
        <span className="text-gray-500">{role.users_count ?? '--'}</span>
      ),
    },
    {
      key: 'actions',
      title: 'Действия',
      render: (role) => (
        <div className="flex items-center gap-3">
          <PermissionGate permission="ROLES:CREATE">
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                openCloneDialog(role);
              }}
              className="text-sm text-indigo-600 hover:text-indigo-800"
              title="Клонировать"
            >
              Клонировать
            </button>
          </PermissionGate>
          <PermissionGate permission="ROLES:DELETE">
            {!role.is_system && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setDeleteTarget(role);
                }}
                className="text-sm text-red-600 hover:text-red-800"
              >
                Удалить
              </button>
            )}
          </PermissionGate>
        </div>
      ),
    },
  ];

  return (
    <div>
      {/* Page header */}
      <div className="sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">Роли</h1>
          <p className="mt-2 text-sm text-gray-600">
            Управление ролями и разрешениями.
          </p>
        </div>
        <PermissionGate permission="ROLES:CREATE">
          <button
            type="button"
            onClick={() => navigate('/roles/new')}
            className="btn-primary mt-4 sm:mt-0"
          >
            <PlusIcon className="-ml-0.5 mr-1.5 h-5 w-5" aria-hidden="true" />
            Создать роль
          </button>
        </PermissionGate>
      </div>

      {/* Table */}
      <div className="mt-6">
        <DataTable<RoleResponse>
          columns={columns}
          data={roles}
          loading={loading}
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={totalPages}
          onPageChange={setPage}
          onRowClick={(role) => navigate(`/roles/${role.id}`)}
          keyExtractor={(role) => role.id}
          emptyMessage="Роли не найдены"
        />
      </div>

      {/* Delete confirmation dialog */}
      <ConfirmDialog
        open={!!deleteTarget}
        title="Удалить роль"
        message={`Вы уверены, что хотите удалить роль "${deleteTarget?.name}"? Это действие необратимо. Роль будет отвязана от всех пользователей.`}
        confirmText="Удалить"
        cancelText="Отмена"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {/* Clone dialog */}
      <Transition.Root show={!!cloneSource} as={Fragment}>
        <Dialog as="div" className="relative z-50" onClose={() => setCloneSource(null)}>
          <Transition.Child
            as={Fragment}
            enter="ease-out duration-300"
            enterFrom="opacity-0"
            enterTo="opacity-100"
            leave="ease-in duration-200"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
          >
            <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" />
          </Transition.Child>

          <div className="fixed inset-0 z-10 w-screen overflow-y-auto">
            <div className="flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0">
              <Transition.Child
                as={Fragment}
                enter="ease-out duration-300"
                enterFrom="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
                enterTo="opacity-100 translate-y-0 sm:scale-100"
                leave="ease-in duration-200"
                leaveFrom="opacity-100 translate-y-0 sm:scale-100"
                leaveTo="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
              >
                <Dialog.Panel className="relative transform overflow-hidden rounded-lg bg-white px-4 pb-4 pt-5 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6">
                  <div className="sm:flex sm:items-start">
                    <div className="mx-auto flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-full bg-indigo-100 sm:mx-0 sm:h-10 sm:w-10">
                      <DocumentDuplicateIcon className="h-6 w-6 text-indigo-600" aria-hidden="true" />
                    </div>
                    <div className="mt-3 text-center sm:ml-4 sm:mt-0 sm:text-left flex-1">
                      <Dialog.Title as="h3" className="text-base font-semibold leading-6 text-gray-900">
                        Клонировать роль
                      </Dialog.Title>
                      <p className="mt-1 text-sm text-gray-500">
                        Создать копию роли &laquo;{cloneSource?.name}&raquo; со всеми разрешениями.
                      </p>
                      <div className="mt-4 space-y-3">
                        <div>
                          <label htmlFor="cloneCode" className="label">Код роли</label>
                          <input
                            id="cloneCode"
                            type="text"
                            value={cloneCode}
                            onChange={(e) => setCloneCode(e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '_'))}
                            className="input-field mt-1"
                            placeholder="ROLE_CODE"
                          />
                        </div>
                        <div>
                          <label htmlFor="cloneName" className="label">Название</label>
                          <input
                            id="cloneName"
                            type="text"
                            value={cloneName}
                            onChange={(e) => setCloneName(e.target.value)}
                            className="input-field mt-1"
                            placeholder="Название роли"
                          />
                        </div>
                        <div>
                          <label htmlFor="cloneDescription" className="label">Описание (необязательно)</label>
                          <input
                            id="cloneDescription"
                            type="text"
                            value={cloneDescription}
                            onChange={(e) => setCloneDescription(e.target.value)}
                            className="input-field mt-1"
                            placeholder="Описание роли"
                          />
                        </div>
                      </div>
                    </div>
                  </div>
                  <div className="mt-5 sm:mt-4 sm:flex sm:flex-row-reverse">
                    <button
                      type="button"
                      disabled={!cloneCode || !cloneName || cloning}
                      className="inline-flex w-full justify-center rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 sm:ml-3 sm:w-auto disabled:opacity-50 disabled:cursor-not-allowed"
                      onClick={handleClone}
                    >
                      {cloning ? 'Создание...' : 'Клонировать'}
                    </button>
                    <button
                      type="button"
                      className="mt-3 inline-flex w-full justify-center rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50 sm:mt-0 sm:w-auto"
                      onClick={() => setCloneSource(null)}
                    >
                      Отмена
                    </button>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition.Root>
    </div>
  );
}
