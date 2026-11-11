-- ============================================================================
-- V2: tenant column, per-tenant uniqueness and tenant-consistent foreign keys
-- ============================================================================
--
-- Prepares the schema for multi-tenancy (docs/architecture/multi-tenancy.md).
-- Self-host and desktop keep working unchanged: every row belongs to tenant 1 (the
-- column default), nothing in the repositories mentions tenant_id, and there is no
-- tenant table and no row-level security here (those belong to the SaaS profile).
--
-- Requires PostgreSQL 15 or newer (ON DELETE SET NULL with a column list).
-- ============================================================================

-- 1. The tenant column on every tenant-owned table.
-- document_type and document_status are global lookup tables and stay without it.

ALTER TABLE owner ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE customer ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE equipment ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE article ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE service ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE invoice ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE invoice_item ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE invoice_period ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE invoice_template ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE document_number_series ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE document_history ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE payment ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;
ALTER TABLE user_settings ADD COLUMN tenant_id INT NOT NULL DEFAULT 1;

-- 2. Uniqueness is per tenant: number ranges, settings, document numbers, owner.
ALTER TABLE document_number_series DROP CONSTRAINT unique_doc_type_year;
ALTER TABLE document_number_series
    ADD CONSTRAINT unique_tenant_doc_type_year UNIQUE (tenant_id, document_type_id, doc_year);

ALTER TABLE user_settings DROP CONSTRAINT user_settings_pkey;
ALTER TABLE user_settings ADD PRIMARY KEY (tenant_id, setting_key);

DROP INDEX uq_invoice_document_number;
CREATE UNIQUE INDEX uq_invoice_document_number ON invoice(tenant_id, document_number);

-- One owner (company master data) per tenant.
ALTER TABLE owner ADD CONSTRAINT uq_owner_tenant UNIQUE (tenant_id);

-- 3. Foreign keys carry the tenant.
-- Row-level security filters the rows a statement reads or writes, but PostgreSQL checks
-- foreign keys without it: a plain FK on customer(id) would let tenant A reference the
-- customer id of tenant B, and the error (or its absence) would reveal whether that id
-- exists. Composite keys (tenant_id, id) make such a reference impossible.

ALTER TABLE customer ADD CONSTRAINT uq_customer_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE article ADD CONSTRAINT uq_article_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE equipment ADD CONSTRAINT uq_equipment_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE invoice ADD CONSTRAINT uq_invoice_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE invoice_period ADD CONSTRAINT uq_invoice_period_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE invoice_item ADD CONSTRAINT uq_invoice_item_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE payment ADD CONSTRAINT uq_payment_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE service DROP CONSTRAINT service_article_id_fkey;
ALTER TABLE service ADD CONSTRAINT service_article_id_fkey
    FOREIGN KEY (tenant_id, article_id) REFERENCES article(tenant_id, id) ON DELETE SET NULL (article_id);
ALTER TABLE service DROP CONSTRAINT service_equipment_id_fkey;
ALTER TABLE service ADD CONSTRAINT service_equipment_id_fkey
    FOREIGN KEY (tenant_id, equipment_id) REFERENCES equipment(tenant_id, id) ON DELETE SET NULL (equipment_id);
ALTER TABLE invoice DROP CONSTRAINT invoice_customer_id_fkey;
ALTER TABLE invoice ADD CONSTRAINT invoice_customer_id_fkey
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer(tenant_id, id);
ALTER TABLE invoice DROP CONSTRAINT invoice_cancels_invoice_id_fkey;
ALTER TABLE invoice ADD CONSTRAINT invoice_cancels_invoice_id_fkey
    FOREIGN KEY (tenant_id, cancels_invoice_id) REFERENCES invoice(tenant_id, id);
ALTER TABLE invoice DROP CONSTRAINT invoice_parent_invoice_id_fkey;
ALTER TABLE invoice ADD CONSTRAINT invoice_parent_invoice_id_fkey
    FOREIGN KEY (tenant_id, parent_invoice_id) REFERENCES invoice(tenant_id, id);
ALTER TABLE invoice DROP CONSTRAINT invoice_converted_to_invoice_id_fkey;
ALTER TABLE invoice ADD CONSTRAINT invoice_converted_to_invoice_id_fkey
    FOREIGN KEY (tenant_id, converted_to_invoice_id) REFERENCES invoice(tenant_id, id);
ALTER TABLE invoice DROP CONSTRAINT invoice_period_id_fkey;
ALTER TABLE invoice ADD CONSTRAINT invoice_period_id_fkey
    FOREIGN KEY (tenant_id, period_id) REFERENCES invoice_period(tenant_id, id);
ALTER TABLE invoice_period DROP CONSTRAINT invoice_period_parent_invoice_id_fkey;
ALTER TABLE invoice_period ADD CONSTRAINT invoice_period_parent_invoice_id_fkey
    FOREIGN KEY (tenant_id, parent_invoice_id) REFERENCES invoice(tenant_id, id);
ALTER TABLE invoice_period DROP CONSTRAINT invoice_period_previous_period_id_fkey;
ALTER TABLE invoice_period ADD CONSTRAINT invoice_period_previous_period_id_fkey
    FOREIGN KEY (tenant_id, previous_period_id) REFERENCES invoice_period(tenant_id, id);
ALTER TABLE invoice_item DROP CONSTRAINT invoice_item_invoice_id_fkey;
ALTER TABLE invoice_item ADD CONSTRAINT invoice_item_invoice_id_fkey
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice(tenant_id, id) ON DELETE CASCADE;
ALTER TABLE invoice_item DROP CONSTRAINT invoice_item_parent_item_id_fkey;
ALTER TABLE invoice_item ADD CONSTRAINT invoice_item_parent_item_id_fkey
    FOREIGN KEY (tenant_id, parent_item_id) REFERENCES invoice_item(tenant_id, id) ON DELETE CASCADE;
ALTER TABLE document_history DROP CONSTRAINT document_history_invoice_id_fkey;
ALTER TABLE document_history ADD CONSTRAINT document_history_invoice_id_fkey
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice(tenant_id, id);
ALTER TABLE payment DROP CONSTRAINT payment_invoice_id_fkey;
ALTER TABLE payment ADD CONSTRAINT payment_invoice_id_fkey
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice(tenant_id, id);
ALTER TABLE payment DROP CONSTRAINT payment_reverses_payment_id_fkey;
ALTER TABLE payment ADD CONSTRAINT payment_reverses_payment_id_fkey
    FOREIGN KEY (tenant_id, reverses_payment_id) REFERENCES payment(tenant_id, id);

-- 4. Indexes for the tenant filter on the large tables.

CREATE INDEX idx_customer_tenant ON customer(tenant_id);
CREATE INDEX idx_article_tenant ON article(tenant_id);
CREATE INDEX idx_service_tenant ON service(tenant_id);
CREATE INDEX idx_invoice_tenant ON invoice(tenant_id);
CREATE INDEX idx_invoice_item_tenant ON invoice_item(tenant_id);
CREATE INDEX idx_invoice_period_tenant ON invoice_period(tenant_id);
CREATE INDEX idx_invoice_template_tenant ON invoice_template(tenant_id);
CREATE INDEX idx_document_history_tenant ON document_history(tenant_id);
CREATE INDEX idx_payment_tenant ON payment(tenant_id);
CREATE INDEX idx_equipment_tenant ON equipment(tenant_id);
