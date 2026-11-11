-- ============================================================================
-- H2 Schema for Invoice System (Test Mode with MariaDB Compatibility)
-- ============================================================================

-- ============================================================================
-- 1. BASE TABLES (v1)
-- ============================================================================

-- Unternehmerdaten (nur ein Datensatz erwartet)
CREATE TABLE IF NOT EXISTS owner (
    id INT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    company_type VARCHAR(255),
    owner_postal_code VARCHAR(20),
    owner_city VARCHAR(255),
    owner_street VARCHAR(255),
    owner_house_number INT,
    phone VARCHAR(50),
    email VARCHAR(255),
    url VARCHAR(255),
    tax_id VARCHAR(50),
    iban VARCHAR(255),
    is_small_business BOOLEAN DEFAULT FALSE,
    note TEXT
);

-- Kunden
CREATE TABLE IF NOT EXISTS customer (
    id INT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    postal_code VARCHAR(20),
    city VARCHAR(255),
    street VARCHAR(255),
    house_number INT,
    phone VARCHAR(50),
    email VARCHAR(255),
    tax_id VARCHAR(50),
    note TEXT,
    -- XRechnung BT-10 Käuferreferenz, e.g. the Leitweg-ID of a public-sector customer.
    buyer_reference VARCHAR(255)
);

-- Betriebsausstattung / Maschinen / Werkzeuge
CREATE TABLE IF NOT EXISTS equipment (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    purchase_value DECIMAL(10,2),
    acquisition_date DATE,
    expected_lifetime_months INT,
    monthly_operating_cost DECIMAL(10,2) DEFAULT 0.00,
    residual_value DECIMAL(10,2) DEFAULT 0.00,
    status VARCHAR(50) DEFAULT 'active',
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Artikel (z.B. Produkte oder Mietobjekte)
CREATE TABLE IF NOT EXISTS article (
    id INT AUTO_INCREMENT PRIMARY KEY,
    article_name VARCHAR(255) NOT NULL,
    purchase_value DECIMAL(10,2),
    sales_value DECIMAL(10,2) NOT NULL,
    unit VARCHAR(50),
    article_description TEXT,
    -- Internal note (not printed on documents).
    note TEXT,
    -- Default VAT rate in percent for new invoice lines (19 or 7).
    tax_rate_percent DECIMAL(5,2) NOT NULL DEFAULT 19.00
);

-- Dienstleistungen (z.B. Arbeitszeit mit optionalem Artikelbezug)
CREATE TABLE IF NOT EXISTS service (
    id INT AUTO_INCREMENT PRIMARY KEY,
    servicename VARCHAR(255) NOT NULL,
    sales_value DECIMAL(10,2) NOT NULL,
    article_id INT,
    equipment_id INT,
    unit VARCHAR(50),
    service_description TEXT,
    -- Default VAT rate in percent for new invoice lines (19 or 7).
    tax_rate_percent DECIMAL(5,2) NOT NULL DEFAULT 19.00,
    FOREIGN KEY (article_id) REFERENCES article(id) ON DELETE SET NULL,
    FOREIGN KEY (equipment_id) REFERENCES equipment(id) ON DELETE SET NULL
);

-- ============================================================================
-- 2. DOCUMENT TYPES & STATUS (v2)
-- ============================================================================

CREATE TABLE IF NOT EXISTS document_type (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

-- H2 compatible: Use MERGE with KEY clause for idempotent inserts
MERGE INTO document_type (id, name) KEY(id) VALUES (1, 'INVOICE');
MERGE INTO document_type (id, name) KEY(id) VALUES (2, 'OFFER');
MERGE INTO document_type (id, name) KEY(id) VALUES (3, 'COST_CALCULATION');
MERGE INTO document_type (id, name) KEY(id) VALUES (4, 'PARTED_INVOICE');
MERGE INTO document_type (id, name) KEY(id) VALUES (5, 'FINAL_INVOICE');
MERGE INTO document_type (id, name) KEY(id) VALUES (6, 'CREDIT_NOTE');

CREATE TABLE IF NOT EXISTS document_status (
    id INT PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

MERGE INTO document_status (id, name) KEY(id) VALUES (1, 'DRAFT');
MERGE INTO document_status (id, name) KEY(id) VALUES (2, 'SENT');
MERGE INTO document_status (id, name) KEY(id) VALUES (3, 'ACCEPTED');
MERGE INTO document_status (id, name) KEY(id) VALUES (4, 'REJECTED');
MERGE INTO document_status (id, name) KEY(id) VALUES (5, 'CONVERTED');
MERGE INTO document_status (id, name) KEY(id) VALUES (6, 'PAID');
MERGE INTO document_status (id, name) KEY(id) VALUES (8, 'CANCELLED');
MERGE INTO document_status (id, name) KEY(id) VALUES (9, 'PARTIALLY_PAID');

-- ============================================================================
-- 3. INVOICE TABLE (v1 + v2 extensions)
-- ============================================================================

CREATE TABLE IF NOT EXISTS invoice (
    id INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL,
    invoice_date DATE NOT NULL,
    due_date DATE,
    total_price DECIMAL(10,2) NOT NULL,
    tax_rate DECIMAL(5,4) NOT NULL,
    paid_date DATE,
    notes TEXT,
    document_number VARCHAR(50),
    version INT DEFAULT 1,
    is_latest BOOLEAN DEFAULT TRUE,
    version_group_id INT,
    document_type_id INT DEFAULT 1,
    document_status_id INT DEFAULT 1,
    work_start_date DATE,
    work_end_date DATE,
    parent_invoice_id INT,
    period_id INT,
    offer_valid_until DATE,
    converted_to_invoice_id INT,
    cost_calculation_basis TEXT,
    terms_and_conditions TEXT,
    rendered_html TEXT,
    archived INT DEFAULT 0,
    template_id INT NULL,
    -- Set on a CREDIT_NOTE (Stornorechnung): the invoice it cancels.
    cancels_invoice_id INT NULL,
    -- STANDARD, SMALL_BUSINESS (§19), REVERSE_CHARGE (§13b) or TAX_EXEMPT (§4).
    tax_treatment VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    -- Legal basis printed for TAX_EXEMPT, e.g. 'gemäß § 4 Nr. 14 UStG'.
    tax_exemption_reason VARCHAR(255) NULL,
    -- Seller and buyer data frozen when the document is issued (JSON), used for XRechnung.
    party_snapshot TEXT NULL,
    -- RESTRICT: issued documents must be retained, so their customer cannot be deleted.
    FOREIGN KEY (customer_id) REFERENCES customer(id),
    FOREIGN KEY (cancels_invoice_id) REFERENCES invoice(id),
    FOREIGN KEY (parent_invoice_id) REFERENCES invoice(id),
    FOREIGN KEY (converted_to_invoice_id) REFERENCES invoice(id),
    CHECK (tax_rate >= 0 AND tax_rate <= 1),
    CONSTRAINT chk_invoice_paid_date_status CHECK (paid_date IS NULL OR document_status_id = 6)
);

-- ============================================================================
-- 4. INVOICE PERIODS (v2 - for parted invoices)
-- ============================================================================

CREATE TABLE IF NOT EXISTS invoice_period (
    id INT AUTO_INCREMENT PRIMARY KEY,
    parent_invoice_id INT,
    period_number INT DEFAULT 1,
    period_start_date DATE NOT NULL,
    period_end_date DATE NOT NULL,
    description TEXT,
    percentage_of_total INT,
    previous_period_id INT,
    FOREIGN KEY (parent_invoice_id) REFERENCES invoice(id),
    FOREIGN KEY (previous_period_id) REFERENCES invoice_period(id)
);

-- Add FK from invoice to invoice_period (circular reference, must use ALTER TABLE)
ALTER TABLE invoice ADD FOREIGN KEY (period_id) REFERENCES invoice_period(id);

-- Rechnungsposten (Positionen: Artikel oder Service)
CREATE TABLE IF NOT EXISTS invoice_item (
    id INT AUTO_INCREMENT PRIMARY KEY,
    invoice_id INT NOT NULL,
    item_type VARCHAR(20) NOT NULL,
    item_id INT NOT NULL,
    item_name VARCHAR(255) NULL,
    item_description TEXT NULL,
    quantity DECIMAL(10,2) NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    total_price DECIMAL(10,2) NOT NULL,
    parent_item_id INT NULL,
    -- Unit and VAT rate (percent) are stored on the line, frozen like the price.
    unit VARCHAR(50) NULL,
    tax_rate_percent DECIMAL(5,2) NOT NULL DEFAULT 19.00,
    FOREIGN KEY (invoice_id) REFERENCES invoice(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_item_id) REFERENCES invoice_item(id) ON DELETE CASCADE,
    CHECK (quantity >= 0),
    CHECK (unit_price >= 0),
    CHECK (total_price >= 0)
);

-- ============================================================================
-- 5. INVOICE TEMPLATES (v2)
-- ============================================================================

-- H2: Use VARCHAR instead of ENUM
CREATE TABLE IF NOT EXISTS invoice_template (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    document_type_id INT NOT NULL,
    template_format VARCHAR(20) DEFAULT 'HTML',
    html_content TEXT,
    binary_content BLOB,
    file_name VARCHAR(255),
    css_content TEXT,
    header_html TEXT,
    footer_html TEXT,
    is_default BOOLEAN DEFAULT FALSE,
    margin_top_mm INT DEFAULT 10,
    margin_bottom_mm INT DEFAULT 10,
    margin_left_mm INT DEFAULT 10,
    margin_right_mm INT DEFAULT 10,
    blocks TEXT,
    is_document_specific BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- H2: No ON UPDATE CURRENT_TIMESTAMP, use DEFAULT instead
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Default templates (use MERGE with KEY for AUTO_INCREMENT tables in H2)
MERGE INTO invoice_template (id, name, document_type_id, html_content, is_default, blocks) KEY(id) VALUES
(1, 'Default Invoice', 1, '<!-- Invoice template -->', TRUE,
'[{"id":"h2-hdr","type":"header","order":0,"data":{"companyName":"{{firma}}","companySlogan":"","logoUrl":"","invoiceTitle":"Rechnung"},"config":{"layout":"logo-left","showSlogan":true}},{"id":"h2-addr-s","type":"address","order":1,"data":{"role":"sender","name":"{{firma}}","address":"{{adresse}}","email":"{{email}}","phone":"{{telefon}}","vatId":"{{ust_id}}"},"config":{"showLabels":true,"label":"Absender"}},{"id":"h2-addr-r","type":"address","order":2,"data":{"role":"receiver","name":"{{kunde}}","address":"{{kunde_strasse}}, {{kunde_plz_ort}}","email":"{{kunde_email}}","phone":"{{kunde_telefon}}","vatId":""},"config":{"showLabels":true,"label":"Empfänger"}},{"id":"h2-meta","type":"invoice-meta","order":3,"data":{"invoiceId":"{{rechnungsnummer}}","invoiceDate":"{{rechnungsdatum}}","dueDate":"{{faelligkeitsdatum}}","orderNumber":""},"config":{"dateFormat":"DD.MM.YYYY","showOrderNumber":true,"alignment":"right"}},{"id":"h2-items","type":"line-items","order":4,"data":{"items":[{"description":"Beispielposition 1","quantity":1,"unit":"Stück","rate":100,"amount":100},{"description":"Beispielposition 2","quantity":2,"unit":"Stück","rate":50,"amount":100}],"columns":[{"key":"description","label":"Beschreibung","show":true,"width":"50%"},{"key":"quantity","label":"Menge","show":true,"width":"15%"},{"key":"unit","label":"Einheit","show":true,"width":"10%"},{"key":"rate","label":"Preis","show":true,"width":"12%"},{"key":"amount","label":"Gesamt","show":true,"width":"13%"}]},"config":{"showUnit":true,"showRate":true,"currency":"EUR","decimalPlaces":2}},{"id":"h2-totals","type":"totals","order":5,"data":{"subtotal":200,"taxRate":19,"taxAmount":38,"total":238,"discount":0,"currency":"EUR"},"config":{"showDiscount":false,"showTax":true,"labelSubtotal":"Zwischensumme","labelTax":"MwSt.","labelTotal":"Gesamtbetrag","alignment":"right"}},{"id":"h2-pay","type":"payment","order":6,"data":{"paymentMethod":"Banküberweisung","bankName":"{{bank_name}}","iban":"{{iban}}","bic":"{{bic}}","reference":"{{verwendungszweck}}"},"config":{"showBic":true}},{"id":"h2-foot","type":"footer","order":7,"data":{"text":"Vielen Dank für Ihr Vertrauen!","website":"{{website}}","registrationNumber":"","taxNumber":"{{ust_id}}"},"config":{"layout":"centered","columns":1}}]');
MERGE INTO invoice_template (id, name, document_type_id, html_content, is_default, blocks) KEY(id) VALUES
(2, 'Default Offer', 2, '<!-- Offer template -->', TRUE,
'[{"id":"h2-hdr","type":"header","order":0,"data":{"companyName":"{{firma}}","companySlogan":"","logoUrl":"","invoiceTitle":"Angebot"},"config":{"layout":"logo-left","showSlogan":true}},{"id":"h2-addr-s","type":"address","order":1,"data":{"role":"sender","name":"{{firma}}","address":"{{adresse}}","email":"{{email}}","phone":"{{telefon}}","vatId":"{{ust_id}}"},"config":{"showLabels":true,"label":"Absender"}},{"id":"h2-addr-r","type":"address","order":2,"data":{"role":"receiver","name":"{{kunde}}","address":"{{kunde_strasse}}, {{kunde_plz_ort}}","email":"{{kunde_email}}","phone":"{{kunde_telefon}}","vatId":""},"config":{"showLabels":true,"label":"Empfänger"}},{"id":"h2-meta","type":"invoice-meta","order":3,"data":{"invoiceId":"{{rechnungsnummer}}","invoiceDate":"{{rechnungsdatum}}","dueDate":"{{faelligkeitsdatum}}","orderNumber":""},"config":{"dateFormat":"DD.MM.YYYY","showOrderNumber":true,"alignment":"right"}},{"id":"h2-items","type":"line-items","order":4,"data":{"items":[{"description":"Beispielposition 1","quantity":1,"unit":"Stück","rate":100,"amount":100},{"description":"Beispielposition 2","quantity":2,"unit":"Stück","rate":50,"amount":100}],"columns":[{"key":"description","label":"Beschreibung","show":true,"width":"50%"},{"key":"quantity","label":"Menge","show":true,"width":"15%"},{"key":"unit","label":"Einheit","show":true,"width":"10%"},{"key":"rate","label":"Preis","show":true,"width":"12%"},{"key":"amount","label":"Gesamt","show":true,"width":"13%"}]},"config":{"showUnit":true,"showRate":true,"currency":"EUR","decimalPlaces":2}},{"id":"h2-totals","type":"totals","order":5,"data":{"subtotal":200,"taxRate":19,"taxAmount":38,"total":238,"discount":0,"currency":"EUR"},"config":{"showDiscount":false,"showTax":true,"labelSubtotal":"Zwischensumme","labelTax":"MwSt.","labelTotal":"Gesamtbetrag","alignment":"right"}},{"id":"h2-pay","type":"payment","order":6,"data":{"paymentMethod":"Banküberweisung","bankName":"{{bank_name}}","iban":"{{iban}}","bic":"{{bic}}","reference":"{{verwendungszweck}}"},"config":{"showBic":true}},{"id":"h2-foot","type":"footer","order":7,"data":{"text":"Vielen Dank für Ihr Vertrauen!","website":"{{website}}","registrationNumber":"","taxNumber":"{{ust_id}}"},"config":{"layout":"centered","columns":1}}]');
MERGE INTO invoice_template (id, name, document_type_id, html_content, is_default, blocks) KEY(id) VALUES
(3, 'Default Cost Calculation', 3, '<!-- Cost calc template -->', TRUE,
'[{"id":"h2-hdr","type":"header","order":0,"data":{"companyName":"{{firma}}","companySlogan":"","logoUrl":"","invoiceTitle":"Kostenberechnung"},"config":{"layout":"logo-left","showSlogan":true}},{"id":"h2-addr-s","type":"address","order":1,"data":{"role":"sender","name":"{{firma}}","address":"{{adresse}}","email":"{{email}}","phone":"{{telefon}}","vatId":"{{ust_id}}"},"config":{"showLabels":true,"label":"Absender"}},{"id":"h2-addr-r","type":"address","order":2,"data":{"role":"receiver","name":"{{kunde}}","address":"{{kunde_strasse}}, {{kunde_plz_ort}}","email":"{{kunde_email}}","phone":"{{kunde_telefon}}","vatId":""},"config":{"showLabels":true,"label":"Empfänger"}},{"id":"h2-meta","type":"invoice-meta","order":3,"data":{"invoiceId":"{{rechnungsnummer}}","invoiceDate":"{{rechnungsdatum}}","dueDate":"{{faelligkeitsdatum}}","orderNumber":""},"config":{"dateFormat":"DD.MM.YYYY","showOrderNumber":true,"alignment":"right"}},{"id":"h2-items","type":"line-items","order":4,"data":{"items":[{"description":"Beispielposition 1","quantity":1,"unit":"Stück","rate":100,"amount":100},{"description":"Beispielposition 2","quantity":2,"unit":"Stück","rate":50,"amount":100}],"columns":[{"key":"description","label":"Beschreibung","show":true,"width":"50%"},{"key":"quantity","label":"Menge","show":true,"width":"15%"},{"key":"unit","label":"Einheit","show":true,"width":"10%"},{"key":"rate","label":"Preis","show":true,"width":"12%"},{"key":"amount","label":"Gesamt","show":true,"width":"13%"}]},"config":{"showUnit":true,"showRate":true,"currency":"EUR","decimalPlaces":2}},{"id":"h2-totals","type":"totals","order":5,"data":{"subtotal":200,"taxRate":19,"taxAmount":38,"total":238,"discount":0,"currency":"EUR"},"config":{"showDiscount":false,"showTax":true,"labelSubtotal":"Zwischensumme","labelTax":"MwSt.","labelTotal":"Gesamtbetrag","alignment":"right"}},{"id":"h2-pay","type":"payment","order":6,"data":{"paymentMethod":"Banküberweisung","bankName":"{{bank_name}}","iban":"{{iban}}","bic":"{{bic}}","reference":"{{verwendungszweck}}"},"config":{"showBic":true}},{"id":"h2-foot","type":"footer","order":7,"data":{"text":"Vielen Dank für Ihr Vertrauen!","website":"{{website}}","registrationNumber":"","taxNumber":"{{ust_id}}"},"config":{"layout":"centered","columns":1}}]');
MERGE INTO invoice_template (id, name, document_type_id, html_content, is_default, blocks) KEY(id) VALUES
(4, 'Default Parted Invoice', 4, '<!-- Parted invoice template -->', TRUE,
'[{"id":"h2-hdr","type":"header","order":0,"data":{"companyName":"{{firma}}","companySlogan":"","logoUrl":"","invoiceTitle":"Teilrechnung"},"config":{"layout":"logo-left","showSlogan":true}},{"id":"h2-addr-s","type":"address","order":1,"data":{"role":"sender","name":"{{firma}}","address":"{{adresse}}","email":"{{email}}","phone":"{{telefon}}","vatId":"{{ust_id}}"},"config":{"showLabels":true,"label":"Absender"}},{"id":"h2-addr-r","type":"address","order":2,"data":{"role":"receiver","name":"{{kunde}}","address":"{{kunde_strasse}}, {{kunde_plz_ort}}","email":"{{kunde_email}}","phone":"{{kunde_telefon}}","vatId":""},"config":{"showLabels":true,"label":"Empfänger"}},{"id":"h2-meta","type":"invoice-meta","order":3,"data":{"invoiceId":"{{rechnungsnummer}}","invoiceDate":"{{rechnungsdatum}}","dueDate":"{{faelligkeitsdatum}}","orderNumber":""},"config":{"dateFormat":"DD.MM.YYYY","showOrderNumber":true,"alignment":"right"}},{"id":"h2-items","type":"line-items","order":4,"data":{"items":[{"description":"Beispielposition 1","quantity":1,"unit":"Stück","rate":100,"amount":100},{"description":"Beispielposition 2","quantity":2,"unit":"Stück","rate":50,"amount":100}],"columns":[{"key":"description","label":"Beschreibung","show":true,"width":"50%"},{"key":"quantity","label":"Menge","show":true,"width":"15%"},{"key":"unit","label":"Einheit","show":true,"width":"10%"},{"key":"rate","label":"Preis","show":true,"width":"12%"},{"key":"amount","label":"Gesamt","show":true,"width":"13%"}]},"config":{"showUnit":true,"showRate":true,"currency":"EUR","decimalPlaces":2}},{"id":"h2-totals","type":"totals","order":5,"data":{"subtotal":200,"taxRate":19,"taxAmount":38,"total":238,"discount":0,"currency":"EUR"},"config":{"showDiscount":false,"showTax":true,"labelSubtotal":"Zwischensumme","labelTax":"MwSt.","labelTotal":"Gesamtbetrag","alignment":"right"}},{"id":"h2-pay","type":"payment","order":6,"data":{"paymentMethod":"Banküberweisung","bankName":"{{bank_name}}","iban":"{{iban}}","bic":"{{bic}}","reference":"{{verwendungszweck}}"},"config":{"showBic":true}},{"id":"h2-foot","type":"footer","order":7,"data":{"text":"Vielen Dank für Ihr Vertrauen!","website":"{{website}}","registrationNumber":"","taxNumber":"{{ust_id}}"},"config":{"layout":"centered","columns":1}}]');

-- ============================================================================
-- 6. DOCUMENT NUMBER SERIES (v2)
-- ============================================================================

CREATE TABLE IF NOT EXISTS document_number_series (
    id INT AUTO_INCREMENT PRIMARY KEY,
    document_type_id INT NOT NULL,
    doc_year INT NOT NULL,
    last_number INT DEFAULT 0,
    UNIQUE (document_type_id, doc_year)
);

-- ============================================================================
-- 7. DOCUMENT HISTORY / AUDIT LOG (v2)
-- ============================================================================

CREATE TABLE IF NOT EXISTS document_history (
    id INT AUTO_INCREMENT PRIMARY KEY,
    invoice_id INT NOT NULL,
    action VARCHAR(50) NOT NULL,
    action_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    performed_by VARCHAR(255),
    notes TEXT,
    ip_address VARCHAR(50),
    FOREIGN KEY (invoice_id) REFERENCES invoice(id)
);

-- ============================================================================
-- 8. PAYMENT TRACKING (v2)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment (
    id INT AUTO_INCREMENT PRIMARY KEY,
    invoice_id INT NOT NULL,
    payment_date DATE NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    payment_method VARCHAR(50),
    reference VARCHAR(255),
    notes TEXT,
    -- Set on a reversal row (negative amount): the payment it reverses. Payments are never
    -- updated or deleted: a mistaken entry is corrected by a reversal.
    reverses_payment_id INT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (invoice_id) REFERENCES invoice(id),
    FOREIGN KEY (reverses_payment_id) REFERENCES payment(id)
);

-- ============================================================================
-- 9. USER SETTINGS (v2)
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 10. INDEXES for performance
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_invoice_customer ON invoice(customer_id);
CREATE INDEX IF NOT EXISTS idx_invoice_date ON invoice(invoice_date);
CREATE INDEX IF NOT EXISTS idx_invoice_document_number ON invoice(document_number);
CREATE INDEX IF NOT EXISTS idx_invoice_version_group ON invoice(version_group_id);
CREATE INDEX IF NOT EXISTS idx_invoice_is_latest ON invoice(is_latest);
CREATE INDEX IF NOT EXISTS idx_invoice_document_type ON invoice(document_type_id);
CREATE INDEX IF NOT EXISTS idx_invoice_document_status ON invoice(document_status_id);
CREATE INDEX IF NOT EXISTS idx_invoice_parent ON invoice(parent_invoice_id);
CREATE INDEX IF NOT EXISTS idx_invoice_period ON invoice(period_id);
CREATE INDEX IF NOT EXISTS idx_invoice_work_dates ON invoice(work_start_date, work_end_date);
CREATE INDEX IF NOT EXISTS idx_invoice_item_invoice ON invoice_item(invoice_id);
CREATE INDEX IF NOT EXISTS idx_invoice_period_parent ON invoice_period(parent_invoice_id);
CREATE INDEX IF NOT EXISTS idx_document_history_invoice ON document_history(invoice_id);
CREATE INDEX IF NOT EXISTS idx_payment_invoice ON payment(invoice_id);
-- Document numbers are unique. Drafts of invoice types have none (NULL) until issued.
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoice_document_number ON invoice(document_number);
