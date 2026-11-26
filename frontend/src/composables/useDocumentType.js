import { computed } from 'vue'
import { t } from '../i18n/index.js'
import { DOCUMENT_TYPES, getDocumentType, getDocumentTypeById, getDocumentTypeKey, INVOICE, OFFER, COST_CALCULATION, CREDIT_NOTE, isInvoice, isCreditNote } from '../data/documentTypes.js'
import { DRAFT, SENT, ACCEPTED, REJECTED, CONVERTED, PAID, CANCELLED, PARTIALLY_PAID, isDraft, isSent, isAccepted, isRejected, isPaid, isPartiallyPaid, isCancelled, isInvoiceOverdue, isOfferExpired, OFFER_TRANSITIONS } from '../utils/documentStatus.js'

const TAB_KEYS = ['invoice', 'offer', 'costCalculation']

export function useDocumentType() {

  function tabConfig(key) {
    const dt = getDocumentType(key)
    if (!dt) return { key: 'invoice', label: t('documentTypes.invoice.plural'), types: [INVOICE, CREDIT_NOTE] }
    // Stornorechnungen are listed next to the invoices they cancel.
    const types = dt.id === INVOICE ? [INVOICE, CREDIT_NOTE] : [dt.id]
    return { key: dt.key, label: dt.plural || dt.label, types }
  }

  const documentTypeTabs = computed(() => TAB_KEYS.map(tabConfig))

  function activeDocTypes(key) {
    return tabConfig(key).types
  }

  function isInvoiceType(typeId) {
    return isInvoice(typeId)
  }

  function isOfferOrCostCalc(typeId) {
    return typeId === OFFER || typeId === COST_CALCULATION
  }

  function pageTitle(key) {
    const dt = getDocumentType(key)
    return (dt || getDocumentType('invoice')).listTitle
  }

  /** Label of the list's "create" button for a tab key, e.g. "Neues Angebot". */
  function newButtonLabel(key) {
    const dt = getDocumentType(key)
    return (dt || getDocumentType('invoice')).newLabel
  }

  function emptyMessage(key, archived) {
    const dt = getDocumentType(key)
    const type = dt || getDocumentType('invoice')
    return archived ? type.emptyArchived : type.empty
  }

  function documentLabel(typeId) {
    const dt = getDocumentTypeById(typeId)
    return (dt || getDocumentTypeById(INVOICE)).label
  }

  /**
   * Title of the document form: "Neues Angebot" when creating, "Angebot bearbeiten" when editing.
   *
   * @param {number} typeId the document type id
   * @param {boolean} editing whether an existing document is edited
   * @returns {string} the title
   */
  function documentFormTitle(typeId, editing) {
    const dt = getDocumentTypeById(typeId)
    const type = dt || getDocumentTypeById(INVOICE)
    return editing ? type.editLabel : type.newLabel
  }

  /**
   * Status changes a user may request for a document. For invoice types these
   * are bookkeeping steps (see InvoiceLifecycleService): "Gesendet" issues the
   * draft and assigns its number, "Bezahlt" records the open amount as one
   * payment, "Storniert" creates a Stornorechnung. "Teilweise bezahlt" is never
   * chosen, it follows from the payments; credit notes never change status.
   */
  function statusOptions(typeId, currentStatus) {
    const isOfferCalc = isOfferOrCostCalc(typeId)
    if (isCreditNote(typeId)) {
      return [{ value: currentStatus, label: statusText(typeId, currentStatus) }]
    }

    if (isOfferCalc) {
      // Exactly what the backend allows (OFFER_TRANSITIONS). "Umgewandelt" is never a plain
      // button: converting runs through documentConversion.js, which creates the next document.
      const from = currentStatus || DRAFT
      const next = (OFFER_TRANSITIONS[from] || []).filter(status => status !== CONVERTED)
      return [from, ...next].map(value => ({ value, label: statusText(typeId, value) }))
    }

    if (!currentStatus || currentStatus === DRAFT) {
      return [
        { value: DRAFT, label: statusText(typeId, DRAFT) },
        { value: SENT, label: statusText(typeId, SENT) }
      ]
    }
    if (currentStatus === SENT || currentStatus === PARTIALLY_PAID) {
      return [
        { value: currentStatus, label: statusText(typeId, currentStatus) },
        { value: PAID, label: statusText(typeId, PAID) },
        { value: CANCELLED, label: statusText(typeId, CANCELLED) }
      ]
    }
    if (currentStatus === PAID) {
      return [
        { value: PAID, label: statusText(typeId, PAID) },
        { value: CANCELLED, label: statusText(typeId, CANCELLED) }
      ]
    }
    if (currentStatus === CANCELLED) {
      return [{ value: CANCELLED, label: statusText(typeId, CANCELLED) }]
    }

    return [{ value: currentStatus, label: statusText(typeId, currentStatus) }]
  }

  function statusText(typeId, statusId) {
    const isOfferCalc = isOfferOrCostCalc(typeId)
    switch (statusId) {
      case DRAFT: return t('status.draft')
      case SENT: return isCreditNote(typeId) ? t('status.issued') : t('status.sent')
      case PARTIALLY_PAID: return t('status.partiallyPaid')
      case ACCEPTED: return t('status.accepted')
      case REJECTED: return t('status.rejected')
      case CONVERTED: return t('status.converted')
      case PAID: return t('status.paid')
      case CANCELLED: return t('status.cancelled')
      default: return isOfferCalc ? t('status.pending') : t('status.open')
    }
  }

  function statusClass(statusId) {
    switch (statusId) {
      case DRAFT: return 'draft'
      case SENT: return 'sent'
      case ACCEPTED: return 'accepted'
      case REJECTED: return 'rejected'
      case CONVERTED: return 'converted'
      case PAID: return 'paid'
      case PARTIALLY_PAID: return 'partially-paid'
      case CANCELLED: return 'cancelled'
      default: return 'unpaid'
    }
  }

  function statusFilterOptions(typeId) {
    const isInvoice = isInvoiceType(typeId) || !typeId
    if (isInvoice) {
      return [
        { value: '', label: t('filters.all') },
        { value: 'draft', label: t('status.draft') },
        { value: 'sent', label: t('status.sent') },
        { value: 'partiallyPaid', label: t('status.partiallyPaid') },
        { value: 'open', label: t('filters.open') },
        { value: 'paid', label: t('status.paid') },
        { value: 'overdue', label: t('filters.overdue') },
        { value: 'cancelled', label: t('status.cancelled') }
      ]
    }
    return [
      { value: '', label: t('filters.all') },
      { value: 'draft', label: t('status.draft') },
      { value: 'sent', label: t('status.sent') },
      { value: 'accepted', label: t('status.accepted') },
      { value: 'rejected', label: t('status.rejected') },
      { value: 'expired', label: t('filters.expired') }
    ]
  }

  function applyStatusFilter(invoices, filter) {
    if (!filter) return invoices
    switch (filter) {
      case 'paid': return invoices.filter(inv => isPaid(inv.documentStatusId))
      case 'partiallyPaid': return invoices.filter(inv => isPartiallyPaid(inv.documentStatusId))
      case 'cancelled': return invoices.filter(inv => isCancelled(inv.documentStatusId))
      case 'draft': return invoices.filter(inv => isDraft(inv.documentStatusId))
      case 'sent': return invoices.filter(inv => isSent(inv.documentStatusId))
      case 'open': return invoices.filter(inv => isSent(inv.documentStatusId) || isPartiallyPaid(inv.documentStatusId))
      case 'overdue': return invoices.filter(inv => isInvoiceOverdue(inv))
      case 'accepted': return invoices.filter(inv => isAccepted(inv.documentStatusId))
      case 'rejected': return invoices.filter(inv => isRejected(inv.documentStatusId))
      case 'expired': return invoices.filter(inv => isOfferExpired(inv))
      default: return invoices
    }
  }

  return {
    documentTypeTabs,
    tabConfig,
    activeDocTypes,
    isInvoiceType,
    isOfferOrCostCalc,
    pageTitle,
    newButtonLabel,
    documentFormTitle,
    emptyMessage,
    documentLabel,
    statusOptions,
    statusText,
    statusClass,
    statusFilterOptions,
    applyStatusFilter,
    DOCUMENT_TYPES,
    getDocumentType,
    getDocumentTypeById,
    getDocumentTypeKey
  }
}
