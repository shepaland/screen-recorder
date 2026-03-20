# 14. CSP блокирует воспроизведение blob: video в плеере

## Описание

При попытке воспроизвести сегмент в модальном плеере на странице "Поиск записей" — видео не загружается, плеер показывает чёрный экран с кнопками управления, но без изображения.

## Диагностика

Playwright-тест показал:
- API `GET /recordings/{id}/segments` → 200, 182 сегмента
- API `GET /playback/sessions/{id}/segments/{segId}` → 200, 6.4 MB (сегмент загружен!)
- `video.src = blob:https://services-test.shepaland.ru/2091d292-...` — blob URL создан
- `video.error.code = 4`, `video.error.message = "MEDIA_ELEMENT_ERROR: Media load rejected by URL safety check"`

**Console error:**
```
Loading media from 'blob:https://services-test.shepaland.ru/...' violates the following
Content Security Policy directive: "default-src 'self'".
Note that 'media-src' was not explicitly set, so 'default-src' is used as a fallback.
```

## Корневая причина

nginx `Content-Security-Policy` header не содержит `media-src` директиву:
```
default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self';
```

Без `media-src`, браузер использует `default-src 'self'` как fallback. `blob:` URL не совпадает с `'self'` → загрузка видео блокируется.

## Решение

Добавить `media-src 'self' blob:;` в CSP header:
```
media-src 'self' blob:;
```

**Файл:** `deploy/docker/web-dashboard/nginx.conf`

## Статус

**ИСПРАВЛЕНО** — CSP обновлён, web-dashboard пересобран и задеплоен.
