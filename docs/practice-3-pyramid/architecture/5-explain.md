# Диаграмма 5. Топология RabbitMQ

| Связь | Зачем | Код |
|---|---|---|
| Switch LoggerClient -> EXCHANGE smp.transactions: publish key=transaction.log, publisherConfirmType=CORRELATED | Switch публикует каждую транзакцию для записи в Logger. | switch LoggerClient.java:38-45  convertAndSend(EXCHANGE, ROUTING_KEY, transaction); RabbitMQConfig.java:22-23  smp.transactions и transaction.log; :33  topic, durable; application.yml:10  publisher-confirm-type: correlated; сбой публикации определяется по AmqpException (LoggerClient.java:47-50) |
| EXCHANGE smp.transactions -> QUEUE transaction-log: binding with key | Exchange направляет сообщения с ключом transaction.log в очередь Logger. | switch RabbitMQConfig.java:44, 58  очередь durable и binding .with(transaction.log); такой же binding в transaction-logger RabbitMQConfig.java:55 |
| QUEUE transaction-log -> DLX smp.transactions.dlx: dead-letter (reject без requeue) | Отклонённое сообщение брокер переотправляет в DLX. Это аргумент очереди, а не binding. | switch RabbitMQConfig.java:45-46  x-dead-letter-exchange и x-dead-letter-routing-key=transaction-log; :47  x-max-delivery=3, на classic-очередь не действует |
| DLX smp.transactions.dlx -> DLQ transaction-log-dlq: binding with key | DLX направляет сообщение в очередь отказов. | switch RabbitMQConfig.java:64  direct exchange; :74-76  DLQ с TTL 60 с; :86  binding .with(transaction-log) |
| QUEUE transaction-log -> Transaction Logger TransactionLogListener: consume to store | Logger читает очередь и сохраняет транзакцию. | transaction-logger TransactionLogListener.java:27-31  @RabbitListener(queues = transaction-log); TransactionService.java:79  saveAndFlush |
| Card-Management OutboxProcessor -> EXCHANGE smp.card-events: publish key=card.EventClass, publisherConfirmType=CORRELATED | CMS по расписанию публикует события карт из outbox. | card-management OutboxProcessor.java:21-25; OutboxEventProcessorImpl.java:35  convertAndSend(smp.card-events, card. + имя класса события); RabbitMQConfig.java:27, 50  topic, durable; application.properties:74  publisher-confirm-type=correlated |
| EXCHANGE smp.card-events -> QUEUE card-notifications: binding with key | Exchange направляет все события с ключом card.* в очередь уведомлений. | card-management RabbitMQConfig.java:61, 75  очередь durable и binding .with(card.*); такой же binding в notification-service RabbitMQConfig.java:62 |
| QUEUE card-notifications -> DLX smp.card-events.dlx: dead-letter (reject без requeue) | Отклонённое событие брокер переотправляет в DLX. | card-management RabbitMQConfig.java:62-63  x-dead-letter-exchange и x-dead-letter-routing-key=card-notifications; :64  x-max-delivery=3, на classic-очередь не действует |
| DLX smp.card-events.dlx -> DLQ card-notifications-dlq: binding with key | DLX направляет событие в очередь отказов. | card-management RabbitMQConfig.java:81  direct exchange; :89  DLQ; :101  binding .with(card-notifications) |
| QUEUE card-notifications -> Notification Service CardEventListener: consume to do INSERT card_notifications | Сервис уведомлений читает событие и сохраняет его. | notification-service CardEventListener.java:36-48  @RabbitListener, repository.save; при ошибке исключение пробрасывается (:50-52) |

## Чего не хватало в проекте и что сделано

Диаграммы топологии в проекте не было. В docs/architecture.md была только таблица с exchange, очередями и ключами. Эта диаграмма нарисована с нуля. Она построена по этой таблице и по классам RabbitMQConfig в сервисах.
