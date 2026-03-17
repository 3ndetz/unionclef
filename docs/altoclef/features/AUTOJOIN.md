# Autojoin — как работает и как добавлять

## Суть

Autojoin — автоматический вход в мини-игру на сервере. Два этапа:
1. **Клик компаса** в лобби (открывает chest-меню выбора режима)
2. **Клик слота** в chest-меню (выбирает конкретную игру)

## Где что живёт

| Что | Файл |
|-----|------|
| Общий autojoin (SW/BW/MM) | `src/main/java/adris/altoclef/chains/GameMenuTaskChain.java` |
| SkyPvP autojoin | `src/main/java/adris/altoclef/tasks/multiplayer/minigames/SkyPvpTask.java` |
| Клик по кастом-итему | `src/main/java/adris/altoclef/util/helpers/ItemHelper.java` — `clickCustomItem()` |
| Поиск слота по имени | `src/main/java/adris/altoclef/util/helpers/ItemHelper.java` — `getCustomItemSlot()` |
| Pipeline enum | `src/main/java/adris/altoclef/butler/Pipeline.java` |
| Настройка autoJoin | `src/main/java/adris/altoclef/butler/ButlerConfig.java` |

## Два подхода

### 1. Через GameMenuTaskChain (SW, BW, MurderMystery)

`GameMenuTaskChain.getPriority()` делает:
- Кликает компас (`clickCustomItem("Выбор сервера", "Выбор лобби", "Выбор режима")`)
- Когда chest открылся — ищет слот "мини-игры", потом слот конкретной игры
- Возвращает priority 90 пока menu открыто (блокирует другие chain'ы)

Требует `ButlerConfig.autoJoin = true`.

### 2. Через сам Task (SkyPvP)

`SkyPvpTask.onTick()` делает всё сам:
- Детектит лобби по наличию компаса "Выбор режима" в инвентаре
- Кликает компас через `clickCustomItem`
- Когда chest открылся — сам ищет слот "SkyPvP" и кликает

Не зависит от `autoJoin`. GameMenuTaskChain НЕ держит priority 90 для SkyPvP.

## Как добавить новый autojoin

### Вариант A: через GameMenuTaskChain (если сервер похож на SW/BW)

1. Добавить pipeline в `Pipeline.java`
2. Добавить case в `GameMenuTaskChain` switch (строка ~160):
   ```java
   case MyGame:
       ClickTitles = new String[]{"MyGame", "mygame"};
       break;
   ```
3. Добавить pipeline в `isMinigamePipeline()`
4. Убедиться что заголовок chest-меню содержит одну из строк в `isAutoJoinMenu`

### Вариант B: через Task (если сервер нестандартный)

1. В `onTick()` таска — сначала проверить открыт ли chest с нужным заголовком → кликнуть слот
2. Потом проверить лобби → кликнуть компас через `clickCustomItem`
3. **Не** добавлять обработку в `GameMenuTaskChain` (иначе priority 90 заблокирует таск)

## Подводные камни

### `clickCustomItem` нельзя вызывать при открытом screen
Внутри есть guard `instanceof PlayerScreenHandler`. Если chest/любой GUI открыт — возвращает false. Иначе `forceEquipSlot` делает SWAP через чужой screen handler и ломает GUI.

### Серверные меню-предметы неперемещаемы
`clickCustomItem` для хотбар-слотов (0-8) переключает `selectedSlot` вместо SWAP. Серверы блокируют перемещение меню-предметов, SWAP молча откатывается сервером.

### `PlayerInteractionFixChain` закрывает screen при смене rotation
Если rotation изменился на >0.1° пока screen открыт → `closeScreen()`. Поэтому GameMenuTaskChain возвращает priority 90 — блокирует другие chain'ы от изменения rotation. Если таск хендлит menu сам — нужно обрабатывать chest за 1-2 тика, пока rotation не сменился.

### `tryAvoidingInteractable` закрывает screen
`LookHelper.tryAvoidingInteractable()` вызывается внутри `clickCustomItem`. Если screen открыт и cursor пустой — вызывает `closeScreen()`. Guard в `clickCustomItem` предотвращает это, но если вызываешь `tryAvoidingInteractable` отдельно — имей в виду.

### `getCustomItemSlot` матчит partial в обе стороны
```java
checkLower.equals(itemName) || checkLower.contains(itemName) || itemName.contains(checkLower)
```
Поиск "SkyWars" найдёт и "SkyWars", и "SkyWars [клик]", и даже предмет "sw" (потому что "skywars".contains("sw")). Используй достаточно уникальные строки.

### Заголовок chest-меню — всегда lowercase check
Заголовки проверяются через `title.getString().toLowerCase().contains(...)`. Убедись что строка в коде — lowercase.
