import { ArrowDownTrayIcon } from '@heroicons/react/24/outline';
import { Link } from 'react-router-dom';

export default function DownloadPage() {
  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Скачать клиент</h1>
        <p className="mt-2 text-sm text-gray-600">
          Скачайте и установите клиент Кадеро для записи экрана на рабочих станциях.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {/* Windows client card */}
        <div className="card p-6 flex flex-col items-center text-center">
          <div className="w-16 h-16 bg-blue-50 rounded-2xl flex items-center justify-center mb-4">
            <svg className="w-10 h-10 text-blue-600" viewBox="0 0 24 24" fill="currentColor">
              <path d="M0 3.449L9.75 2.1v9.451H0m10.949-9.602L24 0v11.4H10.949M0 12.6h9.75v9.451L0 20.699M10.949 12.6H24V24l-12.9-1.801"/>
            </svg>
          </div>
          <h3 className="text-lg font-semibold text-gray-900">Windows</h3>
          <p className="text-sm text-gray-500 mt-1 mb-4">Windows 7 и выше</p>
          <p className="text-xs text-gray-400 mb-4">Версия 2026.3.20.1 &middot; ~91 МБ</p>
          <a
            href="https://kadero.ru/distrib/KaderoAgentSetup.exe?v=20260320"
            className="inline-flex items-center gap-2 rounded-md bg-red-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-red-500 transition-colors"
          >
            <ArrowDownTrayIcon className="h-5 w-5" />
            Скачать (.exe)
          </a>
        </div>
      </div>

      {/* Installation instructions */}
      <div className="mt-10">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Инструкция по установке</h2>
        <div className="card p-6">
          <ol className="list-decimal list-inside space-y-3 text-sm text-gray-700">
            <li>Скачайте установочный файл и запустите его на рабочей станции оператора</li>
            <li>Следуйте инструкциям мастера установки (требуются права администратора)</li>
            <li>После установки откроется окно настройки подключения</li>
            <li>
              Введите <strong>токен регистрации</strong> — его можно сгенерировать в разделе{' '}
              <Link to="/device-tokens" className="text-red-600 hover:text-red-500 underline">
                Токены регистрации
              </Link>
            </li>
            <li>Нажмите «Подключить» — клиент начнёт работу как служба Windows</li>
            <li>
              Управлять записью можно из раздела{' '}
              <Link to="/devices" className="text-red-600 hover:text-red-500 underline">
                Устройства
              </Link>{' '}
              — кнопка «Начать запись»
            </li>
          </ol>
        </div>
      </div>

      {/* System requirements */}
      <div className="mt-6">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Системные требования</h2>
        <div className="card p-6">
          <ul className="space-y-2 text-sm text-gray-700">
            <li><strong>ОС:</strong> Windows 7 SP1 / 8.1 / 10 / 11 (64-bit)</li>
            <li><strong>RAM:</strong> 256 МБ свободной оперативной памяти</li>
            <li><strong>Диск:</strong> 500 МБ для установки + до 2 ГБ для буфера записей</li>
            <li><strong>Сеть:</strong> стабильное подключение к серверу (HTTPS)</li>
            <li><strong>Права:</strong> администратор (для установки службы)</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
