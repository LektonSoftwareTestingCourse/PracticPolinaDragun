# Диаграмма 4. Sequence: путь транзакции

| Связь | Зачем | Код |
|---|---|---|
| Terminal Simulator -> Gateway: POST /api/transactions (auth request) | Терминал отправляет запрос на авторизацию платежа. | terminal-simulator GatewayClient.java:30; gateway application.yml:65-73  маршрут switch |
| Gateway -> Gateway: validate request | Gateway проверяет тело запроса до передачи дальше. | gateway TransactionValidationFilter.java:33, 47-78  глобальный фильтр читает тело и разбирает AuthorizationRequest (:67) |
| Gateway -> Switch: forward transaction | Gateway пересылает запрос в Switch на внутренний адрес. | gateway application.yml:73  RewritePath на /api/internal/route; switch RouteController.java:44-48 |
| Switch -> Switch: extract BIN, route to issuer | Switch по первым 6 цифрам карты определяет эмитента. | switch RouteService.java:67; RoutingService.java:30-40  BIN из pan, issuerId из таблицы маршрутизации; неизвестный BIN даёт отказ (RouteService.java:78) |
| Switch -> Authorization: POST /api/internal/authorize (AuthorizationRequest + issuerId) | Switch запрашивает решение по транзакции. | switch RouteService.java:68-69  withIssuerId и authorize; AuthorizationClient.java:78; authorization AuthControllerImpl.java:38, 68 |
| Authorization -> Card Management: GET /api/cards/{pan} | Authorization получает статус, срок, баланс и лимиты карты. | authorization AuthServiceImpl.java:46; CardManagementClientImpl.java:36; card-management CardController.java:73 |
| Card Management -> Authorization: Card {id, status, limits, ...} | CMS возвращает данные карты. | CardController.java:75; ошибки CMS превращаются в отказ в AuthServiceImpl.java:47-63 |
| Authorization -> Bin Lookup: GET /api/bin/{bin} (enrich issuerId) | Authorization обогащает данные эмитентом из внешнего справочника. | AuthServiceImpl.java:66; BinLookupClient.java:59 |
| Bin Lookup -> Authorization: BinLookupResponse {issuerId, ...} | Справочник возвращает issuerId. | BinLookupClient.java:59-68  при ошибке пустой результат; AuthServiceImpl.java:67-70  результат только пишется в лог |
| Authorization -> Authorization: check status, expiry, balance, limits; generate RRN | Authorization по очереди проверяет условия одобрения и выдаёт номер RRN. | AuthServiceImpl.java:72-104  статус; :107-112  срок действия; :114  баланс; :125  лимиты через upsert в limit_usage; :149  generateRRN |
| alt [approved] | Ветка, где все проверки пройдены и деньги резервируются. | AuthServiceImpl.java:149-175 |
| Authorization -> Card Management: POST /api/cards/{pan}/reserve {amount, rrn} | Authorization резервирует сумму на карте. | AuthServiceImpl.java:151; CardManagementClientImpl.java:70; CardController.java:218; CardServiceImpl.java:198-207; Card.java:130  availableBalance уменьшается |
| Card Management -> Authorization: OK | CMS подтверждает резерв. | CardController.java:224 |
| Card Management -> RabbitMQ: Publish card event (outbox) | CMS сообщает о резерве через outbox, отправка идёт отдельно от запроса. | CardServiceImpl.java:207  событие резерва; CardEventNotifierImpl.java:17  запись в outbox; OutboxProcessor.java:21-25 и OutboxEventProcessorImpl.java:35  отправка в smp.card-events |
| Authorization -> Authorization: generate authCode | Authorization формирует код авторизации. | AuthServiceImpl.java:172 |
| Authorization -> Switch: AuthResponse {approved, rrn, authCode} | Authorization возвращает одобрение. | AuthServiceImpl.java:175; AuthControllerImpl.java:73  HTTP 200 |
| [declined] Authorization -> Switch: AuthResponse {declined, reason} | Authorization возвращает отказ с причиной. | AuthServiceImpl.java  возвраты DeclineOutcome в каждой проверке; AuthControllerImpl.java:76-84  HTTP-статус по причине; switch AuthorizationClient.java:81  тело читается при любом статусе |
| Switch -> RabbitMQ: Publish Transaction (async) | Switch публикует итог транзакции для записи в Logger, для одобренных и отклонённых одинаково. | RouteService.java:84-85; LoggerClient.java:38-45  convertAndSend в smp.transactions с ключом transaction.log |
| alt [convertAndSend без AmqpException] | Ветка, где брокер принял сообщение. | LoggerClient.java:45  возврат true |
| Switch -> Gateway: TransactionResponse | Switch возвращает результат авторизации. | RouteService.java:98; RouteController.java:49  всегда HTTP 200 |
| Gateway -> Terminal Simulator: HTTP 200 {approved\|declined, ...} | Клиент получает ответ. | gateway application.yml:65-73  ответ Switch проксируется без изменений |
| RabbitMQ -> Logger: Consume Transaction | Logger забирает транзакцию из очереди. | transaction-logger TransactionLogListener.java:27-31 |
| Logger -> Logger: Save to DB | Logger сохраняет транзакцию и рассылает её в WebSocket. | TransactionService.java:79  saveAndFlush; :91  broadcast |
| RabbitMQ -> Notification Service: Consume card event | Сервис уведомлений получает событие резерва. | notification-service CardEventListener.java:36 |
| Notification Service -> Notification Service: Save notification | Событие сохраняется в card_notifications. | CardEventListener.java:48 |
| [AmqpException при convertAndSend] RabbitMQ -> Switch: AmqpException (нет соединения с брокером) | Ветка, где публикация не удалась. | LoggerClient.java:47-50  перехват AmqpException, возврат false |
| alt [транзакция была APPROVED] | Отмена нужна только если деньги уже зарезервированы. | RouteService.java:87  условие not logged и статус APPROVED |
| Switch -> Authorization: POST /api/internal/rollback (RollbackRequest {rrn, pan, amount}) | Switch просит отменить резерв. | RouteService.java:89; AuthorizationClient.java:99-103; AuthControllerImpl.java:90, 120 |
| Authorization -> Card Management: POST /api/cards/{pan}/rollback {amount, rrn} | Authorization передаёт отмену в CMS. | AuthServiceImpl.java:225; CardManagementClientImpl.java:105; CardController.java:242 |
| Card Management -> Card Management: findByRrnAndPanForUpdate(rrn, pan) | CMS находит резерв транзакции и блокирует его. | CardServiceImpl.java:215-218 |
| Card Management -> Card Management: reservation RESERVED -> ROLLED_BACK, availableBalance += amount | CMS помечает резерв отменённым и возвращает деньги на баланс. | CardServiceImpl.java:221-222; Reservation.java:79  статус ROLLED_BACK; Card.java:157  availableBalance.add |
| Card Management -> Authorization: OK | CMS подтверждает отмену. | CardController.java:248 |
| Card Management -> RabbitMQ: Publish card event (outbox) | CMS сообщает о возврате денег через outbox. | CardServiceImpl.java:224  событие отката; дальше та же отправка через outbox |
| Authorization -> Switch: RollbackResponse {approved} | Authorization подтверждает отмену. | AuthServiceImpl.java:244; switch RouteService.java:90  результат только логируется |
| Switch -> Gateway: TransactionResponse {declined, responseCode 96} | Клиенту сообщается системная ошибка вместо одобрения. | RouteService.java:91  systemError; AuthorizationResponse.java:64-69  код 96 |
| Gateway -> Terminal Simulator: HTTP 200 {declined, responseCode 96} | Клиент получает отказ. | RouteController.java:49 |
| RabbitMQ -> Notification Service: Consume card event | Сервис уведомлений получает события резерва и отката. | CardEventListener.java:36 |
| Notification Service -> Notification Service: Save notification | События сохраняются. | CardEventListener.java:48 |
| alt [транзакция была DECLINED] Switch -> Gateway: TransactionResponse {declined, reason} | Для отказа резерва нет, поэтому клиенту возвращается обычный ответ. | RouteService.java:87  условие не выполняется; :98  возврат исходного ответа |
| Gateway -> Terminal Simulator: HTTP 200 {declined, ...} | Клиент получает отказ. | RouteController.java:49 |

## Чего не хватало в проекте и что сделано

Основа взята из диаграммы последовательности в docs/architecture.md. В ней были только одобрение и простой отказ. Новые ветки добавлены на оранжевоми красном фоне. Первая ветка показывает сбой публикации в RabbitMQ для одобренной транзакции. Вторая ветка показывает такой же сбой для отклонённой транзакции. В этом случае отменять нечего и клиент получает обычный отказ.
