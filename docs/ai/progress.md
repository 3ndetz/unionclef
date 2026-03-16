# Progress

## Shredder — новый pathfinder (baritone + tungsten)

### Investigate

- Изучена структура baritone: 341 Java-файл, 75 пакетов
- API surface: 158 файлов в `baritone/api/`, altoclef делает 144 импорта из baritone
- Ключевые точки входа: `BaritoneAPI.java`, `IBaritone.java`, `Settings.java`
- Baritone подключается как Gradle subproject через `namedElements` configuration
- Mixins: 19 клиентских миксинов в `baritone.launch.mixins`
- Внешние зависимости: nether-pathfinder, mixin, jsr305

### Plan

- [x] Выбрать имя → **Shredder**
- [x] Скопировать baritone → shredder, переименовать пакеты
- [x] Настроить build.gradle, fabric.mod.json, mixins.shredder.json
- [x] Зарегистрировать в settings.gradle.kts и build.gradle
- [ ] TODO 2.3: Заменить вызовы baritone в altoclef на shredder
- [ ] TODO 2.4: Реализовать windMouse / AI smooth camera movement
- [ ] TODO 2.5: Интегрировать tungsten в shredder

### Implement

- [x] Скопирован baritone → shredder/ (341 файлов)
- [x] Пакеты переименованы: `baritone.*` → `shredder.*` (package, import, static import)
- [x] Строковые ссылки на пакеты обновлены (Class.forName и т.д.)
- [x] shredder/build.gradle — версия 0.1.0, group "shredder"
- [x] fabric.mod.json — id "shredder", автор "3ndetz"
- [x] mixins.shredder.json — package "shredder.launch.mixins"
- [x] settings.gradle.kts — добавлен `include(":shredder")`
- [x] build.gradle — добавлена зависимость на `:shredder`
- Имена классов (IBaritone, BaritoneAPI и т.д.) пока оставлены — будут переименованы при рефакторинге
