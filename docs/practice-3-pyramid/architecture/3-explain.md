# Диаграмма 3. C4 Component (Authorization, Card-Management)

| Связь | Зачем | Код |
|---|---|---|
| Switch -> AuthControllerImpl: HTTP | Switch передаёт запрос на авторизацию или откат. | switch AuthorizationClient.java:78, 103  POST /api/internal/authorize и /rollback; authorization AuthControllerImpl.java:38, 90  приём |
| AuthControllerImpl -> AuthServiceImpl | Контроллер передаёт запрос в бизнес-логику и переводит результат в HTTP-статус. | AuthControllerImpl.java:68  authService.authorize; :120  authService.rollback; :71-87  выбор HTTP-статуса по причине отказа |
| AuthServiceImpl -> CardManagementClientImpl | Сервис получает карту, резервирует сумму и отменяет резерв. | AuthServiceImpl.java:46  getCard; :151  reserve; :225  rollback |
| AuthServiceImpl -> BinLookupClient | Сервис запрашивает issuerId по BIN. | AuthServiceImpl.java:66-70  getIssuerId, результат только логируется |
| AuthServiceImpl -> LimitUsageRepository | Сервис проверяет и увеличивает использованный лимит, а также берёт блок номеров для RRN. | AuthServiceImpl.java:180  upsertLimitUsage; :197  fetchRrnBlock |
| AuthServiceImpl -> AuthorizationEventNotifier | Сервис фиксирует в логе каждый исход авторизации и отката. | AuthServiceImpl.java:48, 174 и другие  eventNotifier.notify в каждой ветке |
| CardManagementClientImpl -> CardController: HTTP | Клиент вызывает API карт по сети. | CardManagementClientImpl.java:36  GET /api/cards/{pan}, :70  /reserve, :105  /rollback; CardController.java:73, 218, 242  приём |
| BinLookupClient -> BIN Lookup: HTTP | Клиент обращается к внешнему справочнику BIN. | BinLookupClient.java:59  GET /api/bin/{bin}; :65-68  при ошибке возвращается пустой результат |
| LimitUsageRepository -> PostgreSQL: JDBC | Лимиты проверяются и обновляются одним SQL-запросом. | LimitUsageRepository.java:62-89  native upsert с условием по дневному и месячному лимиту; LimitUsage.java:34  таблица limit_usage |
| Gateway -> CardController: HTTP | Внешние клиенты работают с картами через Gateway. | gateway application.yml:98-105  маршрут /api/cards/** |
| CardController -> CardServiceImpl | Контроллер передаёт операции с картами в сервис. | CardController.java:51  createCard; :182  patchCard; :202  deleteCard; :224  reserve; :248  rollback; :260  bulkUpdateStatus |
| CardServiceImpl -> CardRepository | Сервис читает карту с блокировкой строки и сохраняет изменённую. | CardServiceImpl.java:266  getCardForUpdate; :168  cardRepository.update |
| CardServiceImpl -> ReservationRepository | Сервис создаёт резерв и помечает его откатанным. | CardServiceImpl.java:202-205  проверка уникальности и сохранение резерва; :216-222  поиск резерва по rrn и pan, сохранение отката |
| CardServiceImpl -> CardEventNotifierImpl | Сервис сообщает о каждом изменении карты. | CardServiceImpl.java:68, 160, 169, 207, 224  notifyListeners для создания, изменения, удаления, резерва и отката |
| CardEventNotifierImpl -> OutboxEventProcessorImpl | Событие сохраняется в таблицу outbox вместо прямой отправки в брокер. | CardEventNotifierImpl.java:17  outboxEventProcessor.save; OutboxEventProcessorImpl.java:27-28  запись |
| OutboxProcessor -> OutboxEventProcessorImpl | Планировщик раз в секунду забирает неотправленные события и передаёт их на публикацию. | OutboxProcessor.java:21  @Scheduled; :24  findPending; :25  processSingleEvent |
| CardRepository -> PostgreSQL: JDBC | Хранение карт. | CardEntity.java:15  таблица cards |
| ReservationRepository -> PostgreSQL: JDBC | Хранение резервов и откатов. | ReservationEntity.java:14  таблица резервов; ReservationRollbackEntity.java:18  таблица reservation_rollbacks |
| OutboxEventProcessorImpl -> PostgreSQL: JDBC | Хранение событий outbox и их статуса отправки. | OutboxEventProcessorImpl.java:28, 45  outboxRepository.save; CardOutboxEventDataEntity.java:12  таблица outbox_event |
| OutboxEventProcessorImpl -> RabbitMQ smp.card-events: AMQP | Публикация события карты в брокер. | OutboxEventProcessorImpl.java:35  convertAndSend в smp.card-events с ключом card. + имя класса события |

## Чего не хватало в проекте и что сделано

Компонентной диаграммы в проекте не было. Эта диаграмма нарисована с нуля 
