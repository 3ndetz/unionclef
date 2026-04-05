# Shredder → 1.21.11 Migration Guide

## Текущее состояние

Shredder (наш форк baritone) скомпилирован под MC 1.21.1 (yarn mappings).
На 1.21.11 он работает в **noop-режиме**: `BaritoneAPI` ловит `NoClassDefFoundError`
при инициализации `BaritoneProvider` и подставляет `NoopBaritoneProvider` —
dynamic proxy, все методы возвращают safe defaults (null/false/emptyList).

### Что работает на 1.21.11 сейчас

- Minecraft запускается, мир загружается
- Altoclef команды (`@help`, `@goto` и т.д.) — принимаются, но без pathfinding
- Tab-complete для `@` команд — работает (перенесён в altoclef'овский mixin)
- Подсветка синтаксиса команд — работает
- Tungsten (line rendering, A* movement) — работает независимо

### Что НЕ работает

- **Pathfinding** — полностью отключен (noop)
- **`#` команды shredder** — не регистрируются, не исполняются
- **Все altoclef tasks, зависящие от baritone** — получают noop-ответы, ничего не делают
  - GoToTask, MineTask, GetItemTask, KillTask и т.д.
- **TungstenBridge** — не активируется (PathExecutor noop)
- **God Bridge / Jump Bridge** — не работают
- **Chunk caching** — не работает

## Что нужно для миграции

### Ключевая проблема: маппинги

Upstream [cabaletta/baritone](https://github.com/cabaletta/baritone) уже имеет ветку
для 1.21.11, но использует **mojmap** (Mojang official mappings). Наш shredder — **yarn**.

Между 1.21.1 и 1.21.11 Mojang переименовал/изменил ряд MC классов и методов.
Baritone upstream уже адаптировал свой код под эти изменения (на mojmap).
Нам нужно перенести эти адаптации, но в yarn терминах.

### Три стратегии миграции

#### Стратегия A: Адаптировать diff upstream'а под наш код

**Суть**: взять diff между baritone 1.21.1 и 1.21.11 (на mojmap), перевести
изменённые имена в yarn, и точечно применить к нашему shredder.

**Плюсы**:
- Минимальный объём работы — трогаем только то, что изменилось
- Сохраняем все наши кастомные фичи (TungstenBridge, GodBridge, jump bridge)
- Не нужно заново мигрировать 345 файлов

**Минусы**:
- Нужно вручную маппить каждое mojmap-имя → yarn-имя в diff'е
- Если upstream менял архитектуру (не только имена), могут быть конфликты
- Рискуем пропустить неочевидные изменения

**Оценка сложности**: средняя. Хороший вариант если diff между версиями небольшой.

#### Стратегия B: Заново мигрировать upstream 1.21.11 в yarn, потом влить наши фичи

**Суть**: взять чистый cabaletta/baritone ветку 1.21.11 (mojmap), прогнать
через `migrateMappings` в yarn (как делали при создании shredder), и потом
cherry-pick/merge наши кастомные изменения поверх.

**Плюсы**:
- Чистая база — гарантировано совместима с 1.21.11
- `migrateMappings` автоматизирует бо́льшую часть переименований
- Проще верифицировать корректность

**Минусы**:
- `migrateMappings` не идеален — часть кода придётся фиксить вручную
- Нужно заново вносить ВСЕ наши кастомные изменения (может потеряться что-то)
- Наши файлы структурно отличаются от upstream — merge будет не тривиален

**Оценка сложности**: высокая. Но результат надёжнее.

#### Стратегия C: Взять upstream 1.21.11 за основу, портировать наши фичи поверх

**Суть**: использовать cabaletta/baritone 1.21.11 AS IS (mojmap или мигрировать
в yarn), и вносить наши нововведения как патчи поверх.

**Плюсы**:
- Самая чистая база, 100% upstream совместимость
- Проще поддерживать в будущем (обновления upstream → merge)

**Минусы**:
- Самый большой объём ручной работы
- Нужно портировать все кастомные фичи заново
- Если оставить mojmap — нужны compat-слои для altoclef (yarn)
- Если мигрировать в yarn — двойная работа

**Оценка сложности**: очень высокая. Имеет смысл только если планируем
регулярно синхронизироваться с upstream.

### Рекомендация

**Стратегия A** — самый прагматичный выбор. Наши кастомные изменения затрагивают
~10 файлов из 345. Остальные 335 — это upstream baritone код, уже на yarn.
Достаточно:

1. Получить diff между cabaletta/baritone 1.21.1 и 1.21.11
2. Перевести mojmap имена в yarn (таблица маппингов ниже)
3. Применить изменения к нашему shredder
4. Проверить что наши фичи не сломались

## Наши кастомные файлы (дельта от upstream)

### Новые файлы (отсутствуют в upstream)

| Файл | Назначение |
|------|------------|
| `baritone/tungsten/TungstenBridge.java` | Мост к tungsten physics movement |
| `baritone/utils/GodBridgeClickHelper.java` | Render-frame jitter clicks для god bridge |
| `baritone/api/noop/NoopBaritone.java` | Noop-прокси для несовместимых версий |
| `baritone/api/noop/NoopBaritoneProvider.java` | Noop-провайдер |

### Модифицированные файлы (отличаются от upstream)

| Файл | Что изменено |
|------|-------------|
| `baritone/pathing/path/PathExecutor.java` | TungstenBridge интеграция, jump bridge state machine |
| `baritone/pathing/movement/movements/MovementTraverse.java` | God bridge mode |
| `baritone/api/Settings.java` | +5 настроек: bridgingMode, godBridgeEdgeDistance, useTungsten, tungstenMinSegment, experimentalPathfinding |
| `baritone/launch/mixins/MixinMinecraft.java` | Render-frame hook для GodBridgeClickHelper, joinWorld перенесён |
| `baritone/api/BaritoneAPI.java` | Noop fallback при ошибке инициализации |
| `baritone/BaritoneProvider.java` | Noop-aware инициализация |

### Зарегистрированные миксины (10 штук)

Все в `mixins.shredder.json`:
MixinChunkArray, MixinClientChunkProvider, MixinClientPlayNetHandler,
MixinCommandSuggestionHelper, MixinEntity, MixinFireworkRocketEntity,
MixinItemStack, MixinLivingEntity, MixinMinecraft, MixinNetworkManager.

Проверено: все target-методы существуют в 1.21.11 yarn маппингах.
Миксины сами по себе совместимы — проблема в инициализации core-классов.

## Пошаговый план миграции (стратегия A)

### Шаг 1: Получить upstream diff

```bash
# Клонировать upstream baritone
git clone https://github.com/cabaletta/baritone.git /tmp/baritone-upstream
cd /tmp/baritone-upstream

# Найти ветки/теги для 1.21.1 и 1.21.11
git branch -r | grep 1.21

# Получить diff
git diff <1.21.1-branch>..<1.21.11-branch> -- src/main/java/ > upstream-diff.patch
```

### Шаг 2: Составить таблицу маппингов mojmap → yarn

Для каждого переименованного класса/метода в diff'е найти yarn-эквивалент.
Использовать [Yarn browser](https://mappings.dev/) или tiny-файл:
`versions/1.21.11/.gradle/loom-cache/source_mappings/*.tiny`

Известные различия mojmap → yarn:
- `Minecraft` → `MinecraftClient`
- `LocalPlayer` → `ClientPlayerEntity`
- `MultiPlayerGameMode` → `ClientPlayerInteractionManager`
- `Connection` → `ClientConnection`
- `Level` → `World`
- `net.minecraft.core.BlockPos` → `net.minecraft.util.math.BlockPos`
- И т.д. — полный список нужно составить по diff'у

### Шаг 3: Применить изменения к shredder

Для каждого изменённого файла в upstream diff:
1. Найти соответствующий файл в `shredder/src/main/java/`
2. Перевести mojmap имена → yarn
3. Применить изменение
4. Если файл из нашего "модифицированного" списка — merge аккуратно

### Шаг 4: Проверить инициализацию

Убедиться что `BaritoneProvider` создаёт `Baritone` без ошибок на 1.21.11.
Если какие-то классы MC изменились структурно — починить.

### Шаг 5: Проверить mixins

Все 10 зарегистрированных миксинов уже совместимы по target-методам.
Но если upstream добавил новые миксины для 1.21.11 — перенести их тоже.

### Шаг 6: Тестирование

- Запуск на 1.21.11, вход в мир
- `#goto 100 64 100` — базовый pathfinding
- `#mine diamond_ore` — mining
- God bridge на плоскости
- TungstenBridge делегация на ровных участках

## Разведка upstream diff (04.04.2026)

### Ветки cabaletta/baritone

Существуют ветки: `1.21`, `1.21.1`, `1.21.3`, `1.21.4`, `1.21.5`, `1.21.8`,
`1.21.10`, `1.21.11`. Чистая линейная цепочка, без расхождений.
Тегов для 1.21.x нет — diff только по веткам.

### Общий diff `1.21.1...1.21.11`

- **65 коммитов**, 0 behind
- **76 файлов** изменено (из ~345 в baritone)
- **+840 / -615 строк** (net +225)

### Инкрементальные шаги

| Шаг             | Коммиты | Файлов |
| --------------- | ------- | ------ |
| 1.21.1 → 1.21.3 | 8 | 29 |
| 1.21.3 → 1.21.4 | 10 | 7 |
| 1.21.4 → 1.21.5 | 15 | 38 |
| 1.21.5 → 1.21.8 | 10 | 12 |
| 1.21.8 → 1.21.10 | 8 | 13 |
| 1.21.10 → 1.21.11 | 14 | 41 |

Два крупных скачка: **1.21.4→1.21.5** (38 файлов) и **1.21.10→1.21.11** (41 файл).

### Ключевые области изменений

**Рендеринг (основной объём):**
- `IRenderer.java` — +141/-57 (крупная переработка)
- `PathRenderer.java` — +97/-62 (крупная переработка)
- 4 новых файла: `MixinRenderPipelines`, `MixinRenderType`, `IRenderPipelines`, `IRenderType`
- ⚠️ Render pipeline переработан в MC 1.21.11 — это самая трудоёмкая часть миграции.
  Upstream добавил 4 новых файла (миксины + accessor'ы) специально для этого.

**Player input/movement:**
- `PlayerMovementInput.java` — +28/-17

**Tools & inventory:**
- `ToolSet.java` — +35/-18
- `InventoryBehavior.java` — +15/-14

**Block handling:**
- `BlockOptionalMeta.java` — +33/-51
- `ChunkPacker.java` — +10/-16
- `BaritoneToast.java` — +4/-56 (упрощён)

**Schematics:**
- `LitematicaSchematic.java`, `MCEditSchematic.java`, `SpongeSchematic.java` — мелкие правки

**Миксины:**
- Обновлены: `MixinClientPlayerEntity`, `MixinLivingEntity`, `MixinScreen`,
  `MixinWorldRenderer`, `MixinMinecraft`, `MixinNetworkManager`, `MixinEntityRenderManager`
- 2 новых миксина в `mixins.baritone.json`

**Build/config:**
- `gradle.properties`, `build.gradle`, `fabric.mod.json` — обновления версий

### Выводы из разведки

1. **Объём умеренный.** 76 файлов, но реальная суть — 10-15 файлов. Остальные — мелкие
   правки импортов, версий, API tweaks.
2. **Стратегия A подтверждена** как оптимальная. Diff обозрим, архитектурных переломов нет.
3. **Рендеринг — самая трудоёмкая часть.** IRenderer/PathRenderer сильно переработаны,
   плюс 4 новых файла. MC 1.21.11 изменил render pipeline.
4. **Preprocessor** стоит добавить в shredder для multi-version, инфраструктура
   уже есть в проекте.
5. **Все имена в upstream diff — mojmap.** Перед применением нужна таблица mojmap→yarn
   для каждого изменённого символа.

## Заметки

- Shredder build.gradle сейчас хардкодит `minecraft "com.mojang:minecraft:1.21.1"`.
  Для 1.21.11 нужно либо сделать multi-version (preprocessor), либо отдельный build.
- altoclef'овский `build.gradle` уже исключает shredder JAR для 1.21.11:
  `if (mcVersion < 12111) { include project(":shredder") }`.
  После миграции это условие нужно убрать.
- Tab-complete для `@` команд altoclef уже работает без shredder (перенесён
  в ChatInputSuggestorMixin). Но `#` команды shredder по-прежнему зависят
  от MixinCommandSuggestionHelper.
