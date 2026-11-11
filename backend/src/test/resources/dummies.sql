-- H2-compatible dummy data for tests
-- Uses MERGE ... KEY (H2 syntax) and escapes reserved keywords

-- Document Number Series (initialize counters) - uses MERGE with KEY
MERGE INTO document_number_series (id, document_type_id, doc_year, last_number) KEY(id)
SELECT * FROM (VALUES
  (1, 1, 2025, 3),
  (2, 2, 2025, 2),
  (3, 3, 2025, 1),
  (4, 4, 2025, 0)
) AS v(id, document_type_id, doc_year, last_number)
WHERE NOT EXISTS (SELECT 1 FROM document_number_series WHERE doc_year = 2025);

-- User Settings (use MERGE for upsert behavior)
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('prefix_invoice', 'RE');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('prefix_offer', 'AN');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('prefix_cost_calculation', 'KV');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('prefix_parted_invoice', 'TR');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('prefix_final_invoice', 'SR');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('company_name', 'Musterfirma GmbH');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('company_address', 'Musterstraße 1, 12345 Musterstadt');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('company_email', 'info@musterfirma.de');
MERGE INTO user_settings (setting_key, setting_value) KEY(setting_key) VALUES ('default_tax_rate', '19');
