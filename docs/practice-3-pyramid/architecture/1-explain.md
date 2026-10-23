# Диаграмма 1. C4 Context

| Связь | Зачем | Код |
|---|---|---|
| POS-терминал -> СМП: AuthorizationRequest, HTTP POST /api/transactions | Терминал отправляет в систему запрос на авторизацию платежа. | terminal-simulator GatewayClient.java:30 -POST на gatewayUrl + /api/transactions; gateway application.yml:65-73 -маршрут switch принимает POST /api/transactions |
| Мерчант / Эквайрер -> СМП: AuthorizationRequest, HTTP POST /api/transactions | Второй источник транзакций  | merchant-acquirer GatewayClient.java:92 -POST /api/transactions; тот же маршрут gateway application.yml:65-73 |
| Пользователь дашборда -> СМП: транзакции, статистика, HTTP REST /api/dashboard/* | Пользователь через веб-интерфейс запрашивает статистику и список транзакций | dashboard useStats.ts:13 -запрос /api/dashboard/stats; useTransactions.ts:49 -запрос /api/transactions/search; dashboard nginx.conf:22 -/api/ проксируется в Gateway |
| СМП -> Пользователь дашборда: лента транзакций, WebSocket /ws/transactions | присылает в браузер каждую новую сохранённую транзакцию | transaction-logger WebSocketConfig.java:31 -обработчик /ws/transactions; TransactionService.java:91 -рассылка после сохранения; dashboard useWebSocket.ts -подключение; nginx.conf:29 -/ws/ направляется в transaction-logger |
| СМП -> BIN Lookup: issuerId по BIN, HTTP GET /api/bin/:bin | Система узнаёт эмитента карты по первым 6 цифрам номера. | authorization BinLookupClient.java:59 -GET /api/bin/{bin}; bin-lookup BinLookupController.java:19, 38|

## Чего не хватало в проекте и что сделано

В проекте не было контекстной диаграммы. В architecture.md есть только общая схема сервисов. На ней не видно, где заканчивается система и начинается внешний мир. Эта диаграмма нарисована с нуля. Вся система показана одним блоком. Снаружи показаны терминал, мерчант, пользователь дашборда и BIN Lookup.
