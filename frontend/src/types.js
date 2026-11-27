/**
 * Shared JSDoc types of the SPA, for props that the type checker cannot infer (#39). Plain
 * JavaScript: nothing here exists at run time. Each name points at the real type (the API's
 * schemas in `api/types.js`, the editor's in `utils/blockRegistry.ts`), so the two cannot drift.
 *
 * Use in a prop: `type: /** @type {import('vue').PropType<import('@/types.js').CatalogArticle[]>} *\/ (Array)`.
 */

/** An article of the catalog (GET /api/articles). @typedef {import('@/api/types.js').ArticleDto} CatalogArticle */

/** A service of the catalog (GET /api/services). @typedef {import('@/api/types.js').ServiceDto} CatalogService */

/** A piece of equipment (GET /api/equipment). @typedef {import('@/api/types.js').EquipmentDto} Equipment */

/** A block type the template editor offers. @typedef {import('@/utils/blockRegistry.ts').BlockType} BlockDefinition */

/** A block of a template (`form.blocks` in the editor). @typedef {import('@/utils/blockRegistry.ts').Block} TemplateBlock */

export {}
