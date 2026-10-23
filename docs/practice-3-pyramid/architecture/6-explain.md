# Диаграмма 6. State-transition

## Card.status

| Связь | Зачем | Код |
|---|---|---|
| [*] -> ACTIVE: createCard | Новая карта сразу создаётся активной. | card-management CardServiceImpl.java:39, 50  CardStatus.ACTIVE; CardController.java:49-51  POST /api/cards |
| ACTIVE -> INACTIVE: patchCard / bulkUpdateStatus | Карту можно приостановить. | CardServiceImpl.java:143-163  patchCard подставляет новый статус без проверки допустимости перехода; :230  bulkUpdateStatus; CardController.java:176, 256 |
| INACTIVE -> ACTIVE: patchCard / bulkUpdateStatus | Карту можно снова активировать. | те же CardServiceImpl.java:143-163, :230 |
| ACTIVE -> BLOCKED: patchCard / bulkUpdateStatus | Карту можно заблокировать, после чего авторизация отклоняется. | те же методы; authorization AuthServiceImpl.java:86-90  отказ CARD_BLOCKED |
| BLOCKED -> ACTIVE: patchCard / bulkUpdateStatus | Карту можно разблокировать. | те же CardServiceImpl.java:143-163, :230 |
| ACTIVE -> EXPIRED: expiryDate раньше даты транзакции | Просрочка определяется при каждой авторизации, статус в БД не меняется. | authorization AuthServiceImpl.java:107-112  последний день месяца expiryDate сравнивается с датой транзакции, отказ CARD_EXPIRED |
| EXPIRED -> ACTIVE: patchCard / bulkUpdateStatus | Статус EXPIRED, записанный в БД вручную, можно сменить обратно; переход не ограничен. | CardStatus.java:7  EXPIRED хранится как статус; CardServiceImpl.java:153  новый статус подставляется без проверки текущего; :230  bulkUpdateStatus |
| EXPIRED -> DELETED: deleteCard | Просроченную карту можно удалить так же, как активную. | CardServiceImpl.java:166-169  deleteCard; Card.java:168-171  запрещено только повторное удаление |
| ACTIVE -> DELETED: deleteCard (мягкое) | Удалённая карта остаётся в таблице со статусом DELETED. | CardServiceImpl.java:166-169  getCardForUpdate(pan).deleted(); Card.java:168-171  повторное удаление запрещено; CardController.java:200 |
| DELETED -> [*] | Конечное состояние карты. | Card.java:168-171 |

## Transaction

| Связь | Зачем | Код |
|---|---|---|
| [*] -> RECEIVED: POST /api/transactions | Switch принял запрос, решения ещё нет. | switch RouteController.java:44-48 |
| RECEIVED -> APPROVED: Authorization APPROVED, резерв создан | Все проверки пройдены и сумма зарезервирована. | switch RouteService.java:69; authorization AuthServiceImpl.java:151, 175 |
| RECEIVED -> DECLINED: unknown BIN, Authorization DECLINED или недоступна | Транзакция отклонена в Switch или в Authorization. | RouteService.java:78  неизвестный BIN; :81  Authorization недоступна; AuthServiceImpl.java  возвраты DeclineOutcome |
| APPROVED -> ROLLED_BACK: reversal  публикация в Logger не удалась, клиенту 96 | Одобренную транзакцию отменяют, если её не удалось отправить в Logger. | RouteService.java:87-91  rollback и код 96; card-management CardServiceImpl.java:213-224; Reservation.java:79  ROLLED_BACK является статусом резерва, в TransactionStatus его нет |
| APPROVED -> LOGGED_A: записано в Logger | Одобренная транзакция сохранена в Logger. | RouteService.java:85; LoggerClient.java:38-45; transaction-logger TransactionLogListener.java:27; TransactionService.java:79 |
| DECLINED -> LOGGED_D: записано в Logger | Отклонённая транзакция тоже сохраняется в Logger. | тот же путь: RouteService.java:85 публикует любой статус |
| DECLINED -> [*]: публикация не удалась (AmqpException): клиенту обычный отказ, в Logger записи нет | Для отказа резерва не было, поэтому откат не нужен, но транзакция нигде не сохраняется. | RouteService.java:85  публикация вернула false; :87  откат только для APPROVED; :98  клиенту возвращается исходный отказ; LoggerClient.java:47-50  перехват AmqpException |
| LOGGED_A -> [*] | Конец жизненного цикла одобренной транзакции. | TransactionService.java:79 |
| LOGGED_D -> [*] | Конец жизненного цикла отклонённой транзакции. | TransactionService.java:79 |
| ROLLED_BACK -> [*] | Конец: в Logger запись не попадает, повторный откат отклоняется. | RouteService.java:91; authorization AuthControllerImpl.java:131  повторный откат даёт HTTP 409 |

## Чего не хватало в проекте и что сделано

Диаграмм состояний в проекте не было. В docs/architecture.md был только список статусов карты. Обе диаграммы нарисованы с нуля
