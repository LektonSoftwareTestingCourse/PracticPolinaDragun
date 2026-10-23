# Диаграмма 2. C4 Container

| Связь | Зачем | Код |
|---|---|---|
| Terminal Simulator -> Gateway: HTTP POST /api/transactions | Симулятор терминала отправляет транзакции в единую точку входа. | terminal-simulator GatewayClient.java:30  POST /api/transactions; gateway application.yml:65-73  маршрут switch |
| Merchant+Acquirer Simulator -> Gateway: HTTP POST /api/transactions | Симулятор мерчанта отправляет транзакции туда же. | merchant-acquirer GatewayClient.java:92  POST /api/transactions |
| Gateway -> Switch: HTTP POST /api/internal/route | Gateway пересылает транзакцию в Switch, заменяя публичный путь на внутренний. | gateway application.yml:73  RewritePath=/api/transactions, /api/internal/route; switch RouteController.java:44-49  приёмник |
| Switch -> Authorization: HTTP /authorize, /rollback | Switch запрашивает решение по транзакции а при сбое публикации отменяет резерв | switch RouteService.java:69 и AuthorizationClient.java:78  /api/internal/authorize; RouteService.java:89 и AuthorizationClient.java:103  /api/internal/rollback; authorization AuthControllerImpl.java:38, 90  приёмник |
| Switch -> Merchant+Acquirer Simulator: HTTP POST /api/simulator/merchant/fee | После авторизации Switch запрашивает комиссию эквайринга, чтобы записать её в транзакцию | switch RouteService.java:70  fetchAcquiringFee; MerchantAcquirerClient.java:47  вызов /api/simulator/merchant/fee; merchant-acquirer SimulationController.java:121-124  приёмник |
| Authorization -> Bin Lookup: HTTP /api/bin | Authorization получает issuerId по BIN карты. | authorization BinLookupClient.java:59  GET /api/bin/{bin}; AuthServiceImpl.java:66  вызов |
| Authorization -> Card Management: HTTP /api/cards/{pan} | Authorization получает данные карты, резервирует и возвращает деньги. | authorization CardManagementClientImpl.java:36  GET /api/cards/{pan}, :70  /reserve, :105  /rollback; card-management CardController.java:73, 218, 242  приёмники |
| Authorization -> PostgreSQL: JDBC limit_usage | Authorization учитывает израсходованные дневные и месячные лимиты. | authorization LimitUsage.java:34  таблица limit_usage; LimitUsageRepository.java:62-89  upsertLimitUsage; AuthServiceImpl.java:180  вызов |
| Card Management -> PostgreSQL: JDBC cards, outbox_event | CMS хранит карты и события для последующей отправки в брокер. | card-management CardEntity.java:15  таблица cards; CardOutboxEventDataEntity.java:12  таблица outbox_event; CardEventNotifierImpl.java:17  запись события |
| Notification -> PostgreSQL: JDBC card_notifications | Сервис уведомлений сохраняет полученные события карт. | notification-service CardNotification.java:25  таблица card_notifications; CardEventListener.java:48  repository.save |
| Transaction Logger -> PostgreSQL: JDBC transactions | Logger сохраняет каждую транзакцию из очереди | transaction-logger Transaction.java:23  таблица transactions; TransactionService.java:79  saveAndFlush |
| Merchant+Acquirer Simulator -> PostgreSQL: JDBC merchants, terminals, acquirer_fee | Симулятор мерчанта хранит справочники мерчантов, терминалов и тарифов комиссии | merchant-acquirer Merchant.java:24, Terminal.java:20, AcquirerFee.java:16  сущности; db/migration V71, V73, V74  создание таблиц |
| Web Dashboard -> Gateway: REST | Дашборд запрашивает статистику и поиск транзакций через Gateway. | dashboard useStats.ts:13  /api/dashboard/stats; useTransactions.ts:49  /api/transactions/search; nginx.conf:22  /api/ в Gateway |
| Web Dashboard -> Transaction Logger: WebSocket | Logger в реальном времени присылает новые транзакции в дашборд | dashboard nginx.conf:29  /ws/ в transaction-logger; transaction-logger WebSocketConfig.java:31  /ws/transactions; TransactionService.java:91  broadcast |
| Switch -> RabbitMQ: AMQP smp.transactions | Switch асинхронно публикует итог транзакции для записи в Logger. | switch LoggerClient.java:38-45  convertAndSend; RabbitMQConfig.java:22-23  exchange smp.transactions, ключ transaction.log; RouteService.java:85  вызов |
| RabbitMQ -> Transaction Logger: consume smp.transactions | Logger забирает транзакции из очереди и сохраняет их | transaction-logger TransactionLogListener.java:27-31  @RabbitListener на очередь transaction-log |
| Card Management -> RabbitMQ: AMQP smp.card-events | CMS по расписанию отправляет накопленные события карт в брокер. | card-management OutboxProcessor.java:21-25  опрос outbox; OutboxEventProcessorImpl.java:35  convertAndSend в smp.card-events |
| RabbitMQ -> Notification: consume smp.card-events | Сервис уведомлений получает события карт. | notification-service CardEventListener.java:36-48  @RabbitListener на очередь card-notifications |
| Gateway -> Card Management: HTTP /api/cards/** | Gateway открывает внешним клиентам API карт. | gateway application.yml:98-105  маршрут cards с фильтром ResponseCaching |
| Gateway -> Transaction Logger: HTTP /api/transactions/**, /api/dashboard/** | Gateway направляет запросы поиска транзакций и статистики в Logger. | gateway application.yml:74-79  маршрут logger; :80-85  маршрут dashboard |
| Gateway -> Terminal Simulator: HTTP /api/simulator/terminal/** | Через Gateway запускается и останавливается симуляция терминала. | gateway application.yml:86-91  маршрут terminal |
| Gateway -> Merchant+Acquirer Simulator: HTTP /api/simulator/merchant/** | Через Gateway запускается симуляция мерчанта. | gateway application.yml:92-97  маршрут merchant |
| Terminal Simulator -> Card Management: HTTP /api/cards, тестовые карты | Симулятор берёт существующие номера карт для генерации транзакций, напрямую, не через Gateway. | terminal-simulator GatewayClient.java:35-41  GET cardManagementUrl + /api/cards; docker-compose.yaml  переменная CARD_MGMT_URL |

## Чего не хватало в проекте и что сделано

Основа взята из docs/architecture-diagram.puml. Всё что добавлено или переделано покрашено зелёным цветом на диаграмме. Это связи которые есть согласно реализации, но например не упомянуты в требованиях
