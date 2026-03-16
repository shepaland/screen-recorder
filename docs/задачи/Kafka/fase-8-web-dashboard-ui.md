# Фаза 8: Web-dashboard — UI компоненты

> **Влияние на текущую систему: НОЛЬ**
> Новые страницы и компоненты. Старые страницы не трогаются.
> API-вызовы пока через конфигурируемый base URL (port-forward или relative path).

---

## Цель

React-компоненты для поиска записей, HLS-плеера, управления webhooks. Интеграция в существующий sidebar и routing.

## Предусловия

- Фаза 5 (search-service) — API поиска готов
- Фаза 6 (playback-service) — M3U8 + segment proxy готов
- Фаза 7 (webhooks) — CRUD API готов

---

## Задачи

### KFK-070: React — SearchPage

**Что сделать:**

Компонент `src/pages/SearchPage.tsx`:

- Строка поиска (fulltext query)
- Фильтры: device (dropdown), дата с-по (date picker), длительность
- Таблица результатов: device, session, timestamp, duration, size
- Пагинация
- Клик по строке → навигация к `/recordings/:sessionId`

```tsx
// src/pages/SearchPage.tsx
export const SearchPage: React.FC = () => {
  const [query, setQuery] = useState('');
  const [deviceId, setDeviceId] = useState<string>();
  const [dateFrom, setDateFrom] = useState<string>();
  const [dateTo, setDateTo] = useState<string>();
  const [results, setResults] = useState<SegmentSearchResult[]>([]);
  const [page, setPage] = useState(0);

  const handleSearch = async () => {
    const data = await searchApi.searchSegments({
      q: query, device_id: deviceId,
      from: dateFrom, to: dateTo,
      page, size: 20
    });
    setResults(data.content);
  };

  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-4">Поиск записей</h1>
      {/* Search bar + filters */}
      {/* Results table */}
      {/* Pagination */}
    </div>
  );
};
```

**Файлы:**
- `web-dashboard/src/pages/SearchPage.tsx`
- `web-dashboard/src/components/search/SearchFilters.tsx`
- `web-dashboard/src/components/search/SearchResults.tsx`

---

### KFK-071: React — VideoPlayer (HLS.js)

**Что сделать:**

1. Установить HLS.js:
   ```bash
   cd web-dashboard && npm install hls.js
   ```

2. Компонент `src/components/player/VideoPlayer.tsx`:

```tsx
import Hls from 'hls.js';

interface VideoPlayerProps {
  playlistUrl: string;  // /api/playback/v1/playback/sessions/{id}/playlist.m3u8
  token: string;        // JWT для авторизации
}

export const VideoPlayer: React.FC<VideoPlayerProps> = ({ playlistUrl, token }) => {
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    if (!videoRef.current) return;

    if (Hls.isSupported()) {
      const hls = new Hls({
        xhrSetup: (xhr) => {
          xhr.setRequestHeader('Authorization', `Bearer ${token}`);
        },
      });
      hls.loadSource(playlistUrl);
      hls.attachMedia(videoRef.current);
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        videoRef.current?.play();
      });
      return () => hls.destroy();
    } else if (videoRef.current.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari native HLS
      videoRef.current.src = playlistUrl;
    }
  }, [playlistUrl, token]);

  return (
    <div className="relative bg-black rounded-lg overflow-hidden">
      <video
        ref={videoRef}
        controls
        className="w-full"
        style={{ maxHeight: '70vh' }}
      />
    </div>
  );
};
```

**Файлы:**
- `web-dashboard/src/components/player/VideoPlayer.tsx`

---

### KFK-072: React — SessionDetailPage

**Что сделать:**

Страница записи `src/pages/SessionDetailPage.tsx`:

- Метаданные сессии (device, start/end time, segment count, total size)
- VideoPlayer компонент
- Timeline сегментов (визуальная полоса с отметками)
- Кнопки: скачать, поделиться (ссылка)

```tsx
export const SessionDetailPage: React.FC = () => {
  const { sessionId } = useParams<{ sessionId: string }>();
  const { token } = useAuth();

  const playlistUrl = `/api/playback/v1/playback/sessions/${sessionId}/playlist.m3u8`;

  return (
    <div className="p-6">
      <SessionMetadata sessionId={sessionId} />
      <VideoPlayer playlistUrl={playlistUrl} token={token} />
      <SegmentTimeline sessionId={sessionId} />
    </div>
  );
};
```

**Файлы:**
- `web-dashboard/src/pages/SessionDetailPage.tsx`
- `web-dashboard/src/components/player/SegmentTimeline.tsx`
- `web-dashboard/src/components/player/SessionMetadata.tsx`

---

### KFK-073: React — WebhookManagement

**Что сделать:**

Компонент `src/pages/WebhookSettingsPage.tsx`:

- Список webhook-подписок (URL, event types, status, last delivery)
- Создание/редактирование подписки (modal form)
- Toggle active/inactive
- История доставок (expandable row)

**Файлы:**
- `web-dashboard/src/pages/WebhookSettingsPage.tsx`
- `web-dashboard/src/components/webhooks/WebhookForm.tsx`
- `web-dashboard/src/components/webhooks/DeliveryLog.tsx`

---

### KFK-074: React Router — новые маршруты

**Что сделать:**

Добавить в router config:

```tsx
// src/router.tsx — добавить routes
{ path: '/search', element: <SearchPage /> },
{ path: '/recordings/:sessionId', element: <SessionDetailPage /> },
{ path: '/settings/webhooks', element: <WebhookSettingsPage /> },
```

Добавить в sidebar navigation:

```tsx
// Секция "Записи"
{ label: 'Поиск записей', path: '/search', icon: SearchIcon, permission: 'RECORDINGS:VIEW' },

// Секция "Настройки" (уже есть)
{ label: 'Webhooks', path: '/settings/webhooks', icon: WebhookIcon, permission: 'WEBHOOKS:READ' },
```

**Файлы:**
- `web-dashboard/src/router.tsx` (или аналогичный файл routes)
- `web-dashboard/src/components/layout/Sidebar.tsx`

---

### KFK-075: API-клиенты

**Что сделать:**

```typescript
// src/api/search.ts
import axios from './axiosInstance';

export const searchApi = {
  searchSegments: (params: SearchParams) =>
    axios.get<PageResponse<SegmentSearchResult>>('/api/search/v1/search/segments', { params }),
};

// src/api/playback.ts
export const playbackApi = {
  getPlaylistUrl: (sessionId: string) =>
    `/api/playback/v1/playback/sessions/${sessionId}/playlist.m3u8`,
};

// src/api/webhooks.ts
export const webhookApi = {
  list: () => axios.get<WebhookSubscription[]>('/api/cp/v1/webhooks'),
  create: (data: CreateWebhookRequest) => axios.post('/api/cp/v1/webhooks', data),
  update: (id: string, data: UpdateWebhookRequest) => axios.put(`/api/cp/v1/webhooks/${id}`, data),
  delete: (id: string) => axios.delete(`/api/cp/v1/webhooks/${id}`),
  deliveries: (id: string, params?: PaginationParams) =>
    axios.get<PageResponse<WebhookDelivery>>(`/api/cp/v1/webhooks/${id}/deliveries`, { params }),
};
```

**ВАЖНО:** Base URL для search и playback — **пока не доступны через nginx** (routes ещё не добавлены). На этом этапе:
- При `npm run dev` — использовать proxy config в `vite.config.ts` → port-forward
- В production build — пути `/api/search/`, `/api/playback/` будут 404 до Фазы 9

**Файлы:**
- `web-dashboard/src/api/search.ts`
- `web-dashboard/src/api/playback.ts`
- `web-dashboard/src/api/webhooks.ts`
- `web-dashboard/src/types/search.ts` (TypeScript interfaces)
- `web-dashboard/src/types/playback.ts`
- `web-dashboard/src/types/webhooks.ts`

---

## Чеклист завершения фазы

- [ ] SearchPage: поиск + фильтры + результаты + пагинация
- [ ] VideoPlayer: HLS.js воспроизведение через JWT-авторизованный playlist
- [ ] SessionDetailPage: метаданные + плеер + timeline
- [ ] WebhookSettingsPage: CRUD подписок + delivery log
- [ ] React Router: `/search`, `/recordings/:sessionId`, `/settings/webhooks`
- [ ] Sidebar: новые пункты меню
- [ ] API-клиенты: search.ts, playback.ts, webhooks.ts
- [ ] `npm run build` проходит без ошибок
- [ ] `npm run lint` проходит
- [ ] **Старые страницы не затронуты**
- [ ] **Nginx routes ещё НЕ добавлены** (новые страницы покажут ошибку загрузки до Фазы 9)
