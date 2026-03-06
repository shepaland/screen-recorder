import { useState, useEffect, type FormEvent } from 'react';
import type { DeviceSettings } from '../types';
import LoadingSpinner from './LoadingSpinner';

const DEFAULT_SETTINGS: DeviceSettings = {
  capture_fps: 1,
  resolution: '720p',
  quality: 'low',
  segment_duration_sec: 30,
  session_max_duration_hours: 24,
  auto_start: true,
};

const RESOLUTION_OPTIONS = [
  { label: '720p (1280x720)', value: '720p' },
  { label: '1080p (1920x1080)', value: '1080p' },
  { label: 'Нативное', value: 'native' },
];

const QUALITY_OPTIONS = [
  { label: 'Низкое', value: 'low' },
  { label: 'Среднее', value: 'medium' },
  { label: 'Высокое', value: 'high' },
];

interface DeviceSettingsFormProps {
  deviceId: string;
  currentSettings: DeviceSettings | null;
  onSave: (settings: DeviceSettings) => Promise<void>;
  onCancel: () => void;
}

export default function DeviceSettingsForm({
  deviceId: _deviceId,
  currentSettings,
  onSave,
  onCancel,
}: DeviceSettingsFormProps) {
  const [settings, setSettings] = useState<DeviceSettings>(DEFAULT_SETTINGS);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<keyof DeviceSettings, string>>>({});

  useEffect(() => {
    if (currentSettings) {
      setSettings({
        capture_fps: currentSettings.capture_fps ?? DEFAULT_SETTINGS.capture_fps,
        resolution: currentSettings.resolution ?? DEFAULT_SETTINGS.resolution,
        quality: currentSettings.quality ?? DEFAULT_SETTINGS.quality,
        segment_duration_sec: currentSettings.segment_duration_sec ?? DEFAULT_SETTINGS.segment_duration_sec,
        session_max_duration_hours: currentSettings.session_max_duration_hours ?? DEFAULT_SETTINGS.session_max_duration_hours,
        auto_start: currentSettings.auto_start ?? DEFAULT_SETTINGS.auto_start,
      });
    }
  }, [currentSettings]);

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof DeviceSettings, string>> = {};

    if (settings.capture_fps < 1 || settings.capture_fps > 30) {
      newErrors.capture_fps = 'Допустимые значения: 1-30';
    }
    if (!Number.isInteger(settings.capture_fps)) {
      newErrors.capture_fps = 'Должно быть целым числом';
    }

    if (settings.segment_duration_sec < 5 || settings.segment_duration_sec > 60) {
      newErrors.segment_duration_sec = 'Допустимые значения: 5-60';
    }
    if (!Number.isInteger(settings.segment_duration_sec)) {
      newErrors.segment_duration_sec = 'Должно быть целым числом';
    }

    if (settings.session_max_duration_hours < 1 || settings.session_max_duration_hours > 48) {
      newErrors.session_max_duration_hours = 'Допустимые значения: 1-48';
    }
    if (!Number.isInteger(settings.session_max_duration_hours)) {
      newErrors.session_max_duration_hours = 'Должно быть целым числом';
    }

    if (!['720p', '1080p', 'native'].includes(settings.resolution)) {
      newErrors.resolution = 'Недопустимое значение';
    }

    if (!['low', 'medium', 'high'].includes(settings.quality)) {
      newErrors.quality = 'Недопустимое значение';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsSubmitting(true);
    try {
      await onSave(settings);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleNumberChange = (field: keyof DeviceSettings, value: string) => {
    const num = parseInt(value, 10);
    if (!isNaN(num)) {
      setSettings((prev) => ({ ...prev, [field]: num }));
    }
    // Clear error on change
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: undefined }));
    }
  };

  const handleSelectChange = (field: keyof DeviceSettings, value: string) => {
    setSettings((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: undefined }));
    }
  };

  // Suppress unused variable warning — deviceId is accepted for interface consistency
  void _deviceId;

  return (
    <form onSubmit={handleSubmit} className="space-y-5">
      {/* Capture FPS */}
      <div>
        <label htmlFor="capture_fps" className="label">
          Частота кадров (FPS)
        </label>
        <input
          id="capture_fps"
          type="number"
          min={1}
          max={30}
          step={1}
          value={settings.capture_fps}
          onChange={(e) => handleNumberChange('capture_fps', e.target.value)}
          className={`input-field mt-1 max-w-xs ${errors.capture_fps ? 'ring-red-500 focus:ring-red-500' : ''}`}
        />
        {errors.capture_fps ? (
          <p className="mt-1 text-xs text-red-600">{errors.capture_fps}</p>
        ) : (
          <p className="mt-1 text-xs text-gray-500">От 1 до 30 кадров в секунду</p>
        )}
      </div>

      {/* Resolution */}
      <div>
        <label htmlFor="resolution" className="label">
          Разрешение
        </label>
        <select
          id="resolution"
          value={settings.resolution}
          onChange={(e) => handleSelectChange('resolution', e.target.value)}
          className={`input-field mt-1 max-w-xs ${errors.resolution ? 'ring-red-500 focus:ring-red-500' : ''}`}
        >
          {RESOLUTION_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        {errors.resolution && (
          <p className="mt-1 text-xs text-red-600">{errors.resolution}</p>
        )}
      </div>

      {/* Quality */}
      <div>
        <label htmlFor="quality" className="label">
          Качество записи
        </label>
        <select
          id="quality"
          value={settings.quality}
          onChange={(e) => handleSelectChange('quality', e.target.value)}
          className={`input-field mt-1 max-w-xs ${errors.quality ? 'ring-red-500 focus:ring-red-500' : ''}`}
        >
          {QUALITY_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        {errors.quality && (
          <p className="mt-1 text-xs text-red-600">{errors.quality}</p>
        )}
      </div>

      {/* Segment Duration */}
      <div>
        <label htmlFor="segment_duration_sec" className="label">
          Длительность сегмента (сек)
        </label>
        <input
          id="segment_duration_sec"
          type="number"
          min={5}
          max={60}
          step={1}
          value={settings.segment_duration_sec}
          onChange={(e) => handleNumberChange('segment_duration_sec', e.target.value)}
          className={`input-field mt-1 max-w-xs ${errors.segment_duration_sec ? 'ring-red-500 focus:ring-red-500' : ''}`}
        />
        {errors.segment_duration_sec ? (
          <p className="mt-1 text-xs text-red-600">{errors.segment_duration_sec}</p>
        ) : (
          <p className="mt-1 text-xs text-gray-500">От 5 до 60 секунд</p>
        )}
      </div>

      {/* Session Max Duration */}
      <div>
        <label htmlFor="session_max_duration_hours" className="label">
          Макс. длительность сессии (часов)
        </label>
        <input
          id="session_max_duration_hours"
          type="number"
          min={1}
          max={48}
          step={1}
          value={settings.session_max_duration_hours}
          onChange={(e) => handleNumberChange('session_max_duration_hours', e.target.value)}
          className={`input-field mt-1 max-w-xs ${errors.session_max_duration_hours ? 'ring-red-500 focus:ring-red-500' : ''}`}
        />
        {errors.session_max_duration_hours ? (
          <p className="mt-1 text-xs text-red-600">{errors.session_max_duration_hours}</p>
        ) : (
          <p className="mt-1 text-xs text-gray-500">От 1 до 48 часов</p>
        )}
      </div>

      {/* Auto Start */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          role="switch"
          aria-checked={settings.auto_start}
          onClick={() => setSettings((prev) => ({ ...prev, auto_start: !prev.auto_start }))}
          className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-red-600 focus:ring-offset-2 ${
            settings.auto_start ? 'bg-red-600' : 'bg-gray-200'
          }`}
        >
          <span
            aria-hidden="true"
            className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
              settings.auto_start ? 'translate-x-5' : 'translate-x-0'
            }`}
          />
        </button>
        <label className="text-sm font-medium text-gray-900">
          Автостарт записи
        </label>
      </div>
      <p className="text-xs text-gray-500 -mt-3 ml-14">
        Автоматически начинать запись при входе оператора
      </p>

      {/* Buttons */}
      <div className="flex justify-end gap-3 pt-4 border-t border-gray-200">
        <button
          type="button"
          onClick={onCancel}
          className="btn-secondary"
          disabled={isSubmitting}
        >
          Отмена
        </button>
        <button type="submit" className="btn-primary" disabled={isSubmitting}>
          {isSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
          {isSubmitting ? 'Сохранение...' : 'Сохранить настройки'}
        </button>
      </div>
    </form>
  );
}
