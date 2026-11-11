-- PostgreSQL counterpart of dummies.sql (test fixture): number-series counters and settings.
-- No explicit ids, so the identity sequence stays in step with the rows. The tenant is named
-- explicitly (tenant 1) because in the SaaS build the column default needs a session setting.

INSERT INTO document_number_series (tenant_id, document_type_id, doc_year, last_number) VALUES
  (1, 1, 2025, 3),
  (1, 2, 2025, 2),
  (1, 3, 2025, 1),
  (1, 4, 2025, 0)
ON CONFLICT (tenant_id, document_type_id, doc_year) DO NOTHING;

INSERT INTO user_settings (tenant_id, setting_key, setting_value) VALUES
  (1, 'prefix_invoice', 'RE'),
  (1, 'prefix_offer', 'AN'),
  (1, 'prefix_cost_calculation', 'KV'),
  (1, 'prefix_parted_invoice', 'TR'),
  (1, 'prefix_final_invoice', 'SR'),
  (1, 'company_name', 'Musterfirma GmbH'),
  (1, 'company_address', 'Musterstraße 1, 12345 Musterstadt'),
  (1, 'company_email', 'info@musterfirma.de'),
  (1, 'default_tax_rate', '19')
ON CONFLICT (tenant_id, setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value;
