# AI Progress Documentation

## Structure of progress.md

The `progress.md` file is kept using the **IPI** methodology (Investigate → Plan → Implement).

### Format

```markdown
# Progress

## <Task Name>

### Investigate
- What was studied, which files were read, conclusions

### Plan
- Concrete plan of action, architecture decisions

### Implement
- What was done, which files were changed, result
- [ ] subtask in progress
- [x] subtask done
```

Every task contains all three IPI sections. Subtasks can be added to any section.

## Archiving

When `progress.md` exceeds **500 lines** or a large block of tasks is completed:
1. Move the content into `docs/ai/archive/DD-MM-YYYY-task-name.md`
2. Clear `progress.md`, keeping only the header and active tasks

### Archive naming

Format: `DD-MM-YYYY-short-task-name.md`

Examples:
- `03-03-2026-baritone-yarn-migration.md`
- `15-03-2026-Multi-versioning-Guide-setup.md`

## Relation to TODOS.md

- `TODOS.md` (repo root) — the top-level task list from the user
- `docs/ai/progress.md` — the AI's detailed progress on those tasks
