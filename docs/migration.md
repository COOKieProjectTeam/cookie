# Migration from legacy repositories

Date: 2026-08-10

| Legacy repository | Decision |
|---|---|
| `cookie-backend` | Не импортируется: исполняемого backend-кода нет; новый API начат на Go |
| `cookie-frontend` | Не импортируется: Next.js-каркас не соответствует мобильному Kotlin-клиенту |
| `architecture` | Требования и документы сохранены в `cookie-product/archive/legacy-2026-05/` |
| `.github` | Остаётся служебным репозиторием организации; ссылки обновляются после публикации новых репозиториев |
| `interview_analisys` | Скопирован в `cookie-product/research/interviews/`; typo в имени каталога устранён |

Старые локальные репозитории не удалены. После проверки новых remote и истории их
можно перевести в GitHub в состояние archived/read-only.
