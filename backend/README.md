# Backend

Этот каталог содержит только backend-модули продукта:

- `backend/services` — независимо разворачиваемые доменные сервисы;
- `backend/platform` — общие Kotlin/JVM starters и библиотеки без бизнес-логики;
- `backend/tools` — вспомогательные backend-приложения для разработки и эксплуатации;
- `backend/clients` — по одному generated internal client module на вызываемый
  сервис; каталог создаётся только вместе с первым реальным consumer.

Контракты, общая инфраструктура и deployment остаются в корневых `contracts`,
`infra` и `deploy`, потому что используются не только backend-сервисами.
