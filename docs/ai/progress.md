# Progress

## Shredder — pathfinder v2 (baritone + tungsten)

### Investigate

- Изучена структура baritone: 341 Java-файл, 75 пакетов
- API surface: 158 файлов в `baritone/api/`, altoclef делает 144 импорта из baritone
- Ключевые точки входа: `BaritoneAPI.java`, `IBaritone.java`, `Settings.java`
- Baritone подключается как Gradle subproject через `namedElements` configuration
- Mixins: 19 клиентских миксинов в `baritone.launch.mixins`
- Внешние зависимости: nether-pathfinder, mixin, jsr305

### Plan

- [x] Выбрать имя → **Shredder**
- [x] Скопировать baritone → shredder (пакеты `baritone.*` сохранены)
- [x] Настроить metadata: build.gradle, fabric.mod.json, mixins.shredder.json
- [x] Зарегистрировать в settings.gradle.kts и build.gradle
- [x] TODO 2.3: Переключить altoclef с `:baritone` на `:shredder`
- [ ] TODO 2.4: Реализовать windMouse / AI smooth camera movement
- [ ] TODO 2.5: Интегрировать tungsten в shredder

### Implement

- [x] Скопирован baritone → shredder/ (341 файлов, пакеты `baritone.*` оставлены как есть)
- [x] shredder/build.gradle — archivesBaseName "shredder", version 0.1.0, group "shredder"
- [x] fabric.mod.json — id "shredder", name "Shredder", автор "3ndetz", GPL-3.0
- [x] mixins.baritone.json → mixins.shredder.json (содержимое без изменений)
- [x] BaritoneMixinConnector → ссылается на `mixins.shredder.json`
- [x] settings.gradle.kts — добавлен `include(":shredder")`
- [x] build.gradle — заменено `:baritone` → `:shredder`, baritone dep убрана
- [x] Altoclef импорты не изменены — `import baritone.*` работает, т.к. shredder экспортирует те же пакеты
