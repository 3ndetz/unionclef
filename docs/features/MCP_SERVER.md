# MCP-сервер в моде (LAN control surface)

Мод сам хостит **MCP-сервер** (Model Context Protocol) — когнитивный агент (Клод)
подключается по сети и рулит ботом теми же рычагами, что и py4j, но напрямую, без
docker-exec и loopback-затыка. Один источник правды: MCP оборачивает методы
`Py4jEntryPoint` (см. [AGENT_PY4J_LEVERS.md](AGENT_PY4J_LEVERS.md)).

## Что это

- Транспорт: **Streamable HTTP**, JSON-RPC 2.0, один эндпоинт `POST /mcp`.
- Реализация: `com.sun.net.httpserver` (встроен в JDK, без зависимостей),
  `adris.altoclef.mcp.McpServer`. Bind на `0.0.0.0` — доступен по LAN.
- Методы протокола: `initialize`, `tools/list`, `tools/call`, `ping`.
- Инструменты: курируемый набор рычагов (перцепция / движение / бой /
  строительство+WorldEdit / защита / меню / команды), каждый с описанием и
  JSON-схемой — агент понимает что делает каждый.

## Настройки (`altoclef_settings.json` / Settings)

| Поле | Дефолт | Что |
|---|---|---|
| `mcpEnabled` | `true` | Поднимать ли MCP-сервер |
| `mcpPort` | `25350` | Порт (bind 0.0.0.0) |

Стартует автоматически после py4j-шлюза. В логе: `MCP server started on
0.0.0.0:25350`.

## Как подключить Claude

Эндпоинт: `http://<ip-машины-с-ботом>:25350/mcp` (на LAN — напр.
`http://192.168.1.20:25350/mcp`).

Claude Code (HTTP-транспорт):

```bash
claude mcp add --transport http unionclef http://192.168.1.20:25350/mcp
```

Или в `.mcp.json`:

```json
{ "mcpServers": { "unionclef": { "type": "http", "url": "http://192.168.1.20:25350/mcp" } } }
```

Docker-стенд публикует порт наружу (`compose.test.yml`: `25350:25350`). Нативный
клиент на хосте биндит 0.0.0.0 сам — виден по LAN без публикации.

## Проверка

`deploy/runner/mcp_test.py` — initialize + tools/list + getGameState (чтение) +
fillSelection (действие) по HTTP. Или руками:

```bash
curl -s http://127.0.0.1:25350/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | python3 -m json.tool
```

## Добавление инструментов

Новый рычаг = метод в `Py4jEntryPoint` + одна строка `tool(...)` в
`McpServer.registerTools()` (имя, описание, `schema(...)`, лямбда к методу). Не
дублируем логику — только обёртка. Держать синхронным с py4j-каталогом.
