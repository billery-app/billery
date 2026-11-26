/**
 * Shared JSDoc types of the SPA, for props and service results that the type checker cannot
 * infer (#39). Plain JavaScript: nothing here exists at run time.
 *
 * Use in a prop: `type: /** @type {import('vue').PropType<import('@/types.js').CatalogArticle[]>} *\/ (Array)`.
 */

/**
 * An article of the catalog (GET /api/articles).
 * @typedef {Object} CatalogArticle
 * @property {number} id
 * @property {string} name
 * @property {string} [description]
 * @property {number|string} [salesPrice] - net sales price
 * @property {number|string} [purchasePrice]
 * @property {string} [unit]
 * @property {number} [taxRate] - percent, 19 or 7
 */

/**
 * A service of the catalog (GET /api/services).
 * @typedef {Object} CatalogService
 * @property {number} id
 * @property {string} name
 * @property {string} [description]
 * @property {number|string} [hourlyRate] - net price per unit
 * @property {string} [unit]
 * @property {number} [taxRate] - percent, 19 or 7
 * @property {number|null} [articleId] - the linked article
 * @property {number|null} [equipmentId] - the linked equipment (amortisation)
 */

/**
 * A piece of equipment (GET /api/equipment).
 * @typedef {Object} Equipment
 * @property {number} id
 * @property {string} name
 */

/**
 * A block type the template editor offers (`blockRegistry.js`).
 * @typedef {Object} BlockDefinition
 * @property {string} type
 * @property {string} name
 * @property {string} [description]
 * @property {string} [icon]
 */

/**
 * A block of a template (`form.blocks` in the editor).
 * @typedef {Object} TemplateBlock
 * @property {string} id
 * @property {string} type
 * @property {Object} [data]
 * @property {Object} [config]
 */

export {}
