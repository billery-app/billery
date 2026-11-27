/**
 * Types of the backend's API for the JavaScript code (#39, step 2), named after the schemas of the
 * OpenAPI spec. The schemas themselves are generated into `schema.d.ts` (`npm run api:types`,
 * from `backend/target/openapi.json`, see `docs/architecture/frontend.md > Typed API`); a renamed
 * or retyped DTO field therefore fails `npm run typecheck` wherever the services' answers are used.
 *
 * Use: `@returns {Promise<Response<InvoiceDto>>}` in a service, `import('@/api/types.js').InvoiceDto`
 * elsewhere. The SaaS endpoints (`/api/me`, team, tenants) are not in the self-host spec and keep
 * their hand-written JSDoc types.
 */

/** @typedef {import('./schema').components['schemas']} Schemas */

/**
 * An axios answer with a typed body.
 * @template T
 * @typedef {import('axios').AxiosResponse<T>} Response
 */

/** @typedef {Schemas['InvoiceDto']} InvoiceDto - A document as the API sends and takes it (invoice, offer, cost estimate, credit note) */
/** @typedef {Schemas['InvoiceItemDto']} InvoiceItemDto - One line of a document, with its sub-lines in `children` */
/** @typedef {Schemas['InvoicePageDto']} InvoicePageDto - One page of the document list (`GET /api/invoices/page`) */
/** @typedef {Schemas['InvoiceStatistics']} InvoiceStatistics - Figures of the dashboard (`GET /api/invoices/statistics`) */
/** @typedef {Schemas['DocumentHistoryEntry']} DocumentHistoryEntry - One entry of a document's history */
/** @typedef {Schemas['Payment']} Payment - A recorded payment (reversals have a negative amount) */
/** @typedef {Schemas['PaymentRequest']} PaymentRequest - A payment to record */
/** @typedef {Schemas['StatusChangeRequest']} StatusChangeRequest - Body of `PATCH /api/invoices/{id}/status` */
/** @typedef {Schemas['CustomerDto']} CustomerDto - A customer as the SPA sends it */
/** @typedef {Schemas['Customer']} Customer - A customer as the API answers */
/** @typedef {Schemas['CustomerPageDto']} CustomerPageDto - One page of the customer list */
/** @typedef {Schemas['ArticleDto']} ArticleDto - A catalog article */
/** @typedef {Schemas['Article']} Article - An article as the API answers a write */
/** @typedef {Schemas['ServiceDto']} ServiceDto - A catalog service */
/** @typedef {Schemas['Service']} Service - A service as the API answers a write */
/** @typedef {Schemas['EquipmentDto']} EquipmentDto - An item of equipment */
/** @typedef {Schemas['EquipmentSummaryDto']} EquipmentSummaryDto - Amortisation of one item of equipment */
/** @typedef {Schemas['EquipmentUsageDto']} EquipmentUsageDto - Where an item of equipment was used */
/** @typedef {Schemas['OwnerDto']} OwnerDto - The company details as the SPA sends them */
/** @typedef {Schemas['Owner']} Owner - The company details as the API answers */
/** @typedef {Schemas['InvoiceTemplateDto']} InvoiceTemplateDto - A template (blocks, CSS, margins) */
/** @typedef {Schemas['CloneForDocumentRequest']} CloneForDocumentRequest - Body of `POST /api/templates/from-document/{documentId}` */
/** @typedef {Schemas['PreviewRequest']} PreviewRequest - Body of `POST /api/templates/preview` */
/** @typedef {Schemas['ExportRequest']} ExportRequest - Body of `POST /api/documents/export` (DOCX / ODT) */
/** @typedef {Schemas['DocumentSection']} DocumentSection - One section of the DOCX / ODT exchange */
/** @typedef {Schemas['SearchResult']} SearchResult - Answer of the quick search */
/** @typedef {Schemas['BackupStatus']} BackupStatus - Backup settings and the backups of the desktop app */
/** @typedef {Schemas['SettingsRequest']} SettingsRequest - New backup settings */
/** @typedef {Schemas['RestoreRequest']} RestoreRequest - Body of a restore */
/** @typedef {Schemas['AccountView']} AccountView - A company mail account as the API shows it (never the password) */
/** @typedef {Schemas['AccountInput']} AccountInput - A company mail account to save */
/** @typedef {Schemas['Draft']} MailDraft - The prepared mail of a document (`GET /api/invoices/{id}/mail`) */
/** @typedef {Schemas['Request']} MailRequest - A mail to send (`POST /api/invoices/{id}/mail`) */
/** @typedef {Schemas['Result']} MailResult - Answer of sending a mail */
/** @typedef {Schemas['Template']} MailTemplate - The mail texts per document kind */

export {}
