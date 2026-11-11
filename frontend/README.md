# frontend

Vue 3 + Vite frontend for the Billery invoice management system.

## Setup

```sh
npm install
npm run dev             # dev server on :5173
npm run build           # production build
npm run test:run        # run tests
npm run test:coverage   # run tests with coverage
```

## Key directories

| Path | Contents |
|------|----------|
| `src/components/` | Template editor, modal dialogs, search |
| `src/services/` | Axios API clients |
| `src/utils/` | Variable registry, block definitions, sanitization |
| `src/views/` | Route-level page components |
| `src/router/` | Vue Router config |

## Documentation

All developer documentation lives at the repository root under
[`docs/`](../docs/README.md). For the template editor specifically
see [`docs/reference/editor.md`](../docs/reference/editor.md),
[`docs/reference/block-types.md`](../docs/reference/block-types.md),
and [`docs/reference/variables.md`](../docs/reference/variables.md).
