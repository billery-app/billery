/**
 * Base stylesheet of a rendered document, identical to
 * `TemplateBasedPdfGenerator.getBaseStyles()` (Java). The editor preview and
 * the HTML stored in `htmlContent` use it so they match the PDF; keep both in
 * sync when changing it.
 */
export const TEMPLATE_BASE_CSS = `
.invoice-box {
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
  color: #1f2937;
  line-height: 1.5;
}
.invoice-box .header { margin-bottom: 40px; }
.invoice-box .brand h1 { margin: 0; letter-spacing: -1px; }
.invoice-box .brand p { margin: 5px 0 0 0; color: #6b7280; }
.invoice-box .address-block { margin-bottom: 30px; }
.invoice-box .section-title {
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  color: #6b7280;
  margin-bottom: 8px;
  display: block;
}
.invoice-box table { width: 100%; border-collapse: collapse; margin-top: 20px; }
.invoice-box th {
  background: #f3f4f6;
  text-align: left;
  padding: 12px;
  font-size: 13px;
  border-bottom: 2px solid #e5e7eb;
}
.invoice-box td { padding: 12px; border-bottom: 1px solid #f3f4f6; font-size: 14px; }
.invoice-box .payment-block {
  margin-top: 40px;
  padding: 20px;
  background: #f9fafb;
  border-radius: 8px;
}
.invoice-box .footer {
  margin-top: 60px;
  padding-top: 20px;
  border-top: 1px solid #eee;
  font-size: 12px;
  color: #9ca3af;
  text-align: center;
}
`
