package de.billery.repository;

import de.billery.data.InvoiceTemplate;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link InvoiceTemplate}s: the global templates per document type (one of them the
 * type default) and the per-document clones ({@code is_document_specific = 1}), which the listing
 * methods hide unless asked. All statements run through the shared {@link JdbcClient}, so they join
 * the caller's transaction; database errors surface as {@code DatabaseException} (see {@link
 * BaseRepository}).
 */
@Repository
public class InvoiceTemplateRepository extends BaseRepository {

  /** Maps an {@code invoice_template} row. */
  private static final RowMapper<InvoiceTemplate> TEMPLATE_MAPPER =
      (rs, rowNum) -> {
        InvoiceTemplate template =
            new InvoiceTemplate(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getInt("document_type_id"),
                rs.getString("html_content"),
                rs.getString("css_content"),
                rs.getString("header_html"),
                rs.getString("footer_html"),
                rs.getInt("is_default"),
                rs.getInt("margin_top_mm"),
                rs.getInt("margin_bottom_mm"),
                rs.getInt("margin_left_mm"),
                rs.getInt("margin_right_mm"),
                rs.getString("created_at"),
                rs.getString("updated_at"),
                rs.getString("blocks"));
        template.setDocumentSpecific(rs.getInt("is_document_specific") == 1);
        return template;
      };

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public InvoiceTemplateRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Returns the global templates (per-document clones excluded), ordered by name.
   *
   * @return the global templates
   */
  public List<InvoiceTemplate> findAll() {
    return run(
        "findAll templates",
        () ->
            jdbc.sql("SELECT * FROM invoice_template WHERE is_document_specific = 0 ORDER BY name")
                .query(TEMPLATE_MAPPER)
                .list());
  }

  /**
   * Returns every template (including per-document clones). Used by the {@code /templates} view
   * when the user has toggled "show document-specific templates" on. Mostly here so the controller
   * has a single repository call for the toggle; the default {@link #findAll()} stays filtered.
   */
  public List<InvoiceTemplate> findAllIncludingDocumentSpecific() {
    return run(
        "findAllIncludingDocumentSpecific templates",
        () ->
            jdbc.sql("SELECT * FROM invoice_template ORDER BY name").query(TEMPLATE_MAPPER).list());
  }

  /**
   * Returns global templates for a given document type (excluding per-document clones). The picker
   * in the document modal calls this so it never offers another document's custom layout.
   *
   * @param documentTypeId the document type id
   * @return ordered list (default first, then by name) of templates that are not scoped to a
   *     specific document
   */
  public List<InvoiceTemplate> findByDocumentTypeId(int documentTypeId) {
    return findByDocumentTypeId(documentTypeId, false);
  }

  /**
   * Like {@link #findByDocumentTypeId(int)} but with an explicit toggle for whether to include
   * per-document clones. Currently only the {@code /templates} view consumes the inclusive variant;
   * the document modal stays exclusive.
   */
  public List<InvoiceTemplate> findByDocumentTypeId(
      int documentTypeId, boolean includeDocumentSpecific) {
    String sql =
        includeDocumentSpecific
            ? "SELECT * FROM invoice_template WHERE document_type_id = :type"
                + " ORDER BY is_default DESC, name"
            : "SELECT * FROM invoice_template WHERE document_type_id = :type"
                + " AND is_document_specific = 0"
                + " ORDER BY is_default DESC, name";
    return run(
        "findByDocumentTypeId templates",
        () -> jdbc.sql(sql).param("type", documentTypeId).query(TEMPLATE_MAPPER).list());
  }

  /**
   * Returns the default template of a document type.
   *
   * @param documentTypeId the document type id
   * @return the default template, or null if the type has none
   */
  public InvoiceTemplate findDefaultByDocumentTypeId(int documentTypeId) {
    return run(
        "findDefaultByDocumentTypeId",
        () ->
            jdbc.sql(
                    "SELECT * FROM invoice_template WHERE document_type_id = :type"
                        + " AND is_default = 1 LIMIT 1")
                .param("type", documentTypeId)
                .query(TEMPLATE_MAPPER)
                .optional()
                .orElse(null));
  }

  /**
   * Finds a template by id (global or per-document).
   *
   * @param id the template id
   * @return the template, or null if not found
   */
  public InvoiceTemplate findById(int id) {
    return run(
        "findById template",
        () ->
            jdbc.sql("SELECT * FROM invoice_template WHERE id = :id")
                .param("id", id)
                .query(TEMPLATE_MAPPER)
                .optional()
                .orElse(null));
  }

  /**
   * Inserts a template; the generated id is set on the passed object. A null {@code blocks} is
   * stored as an empty string.
   *
   * @param template the template to save
   * @return the same template, now carrying its id
   */
  public InvoiceTemplate save(InvoiceTemplate template) {
    return run(
        "save template",
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          bind(
                  jdbc.sql(
                      """
                      INSERT INTO invoice_template (
                          name, document_type_id, html_content, css_content, header_html,
                          footer_html, is_default, margin_top_mm, margin_bottom_mm,
                          margin_left_mm, margin_right_mm, blocks, is_document_specific
                      ) VALUES (:name, :type, :html, :css, :header, :footer, :isDefault,
                                :marginTop, :marginBottom, :marginLeft, :marginRight, :blocks,
                                :documentSpecific)
                      """),
                  template)
              .update(keys);
          template.setId(generatedId(keys));
          return template;
        });
  }

  /**
   * Overwrites every column of an existing template, identified by its id.
   *
   * @param template the template with updated content
   * @return the same template
   */
  public InvoiceTemplate update(InvoiceTemplate template) {
    return run(
        "update template",
        () -> {
          bind(
                  jdbc.sql(
                      """
                      UPDATE invoice_template SET
                          name = :name,
                          document_type_id = :type,
                          html_content = :html,
                          css_content = :css,
                          header_html = :header,
                          footer_html = :footer,
                          is_default = :isDefault,
                          margin_top_mm = :marginTop,
                          margin_bottom_mm = :marginBottom,
                          margin_left_mm = :marginLeft,
                          margin_right_mm = :marginRight,
                          blocks = :blocks,
                          is_document_specific = :documentSpecific
                      WHERE id = :id
                      """),
                  template)
              .param("id", template.getId())
              .update();
          return template;
        });
  }

  /**
   * Deletes a template.
   *
   * @param id the template id
   * @return true if a row was deleted
   */
  public boolean delete(int id) {
    return run(
        "delete template",
        () -> jdbc.sql("DELETE FROM invoice_template WHERE id = :id").param("id", id).update() > 0);
  }

  /**
   * Removes the default flag from every template of a document type.
   *
   * @param documentTypeId the document type id
   * @return always true (failures throw)
   */
  public boolean clearDefaultForDocumentType(int documentTypeId) {
    return run(
        "clearDefaultForDocumentType",
        () -> {
          jdbc.sql("UPDATE invoice_template SET is_default = 0 WHERE document_type_id = :type")
              .param("type", documentTypeId)
              .update();
          return true;
        });
  }

  /**
   * Makes a template the default of its document type, clearing the flag on the others. Does
   * nothing if the template does not exist. Callers that need both updates to be atomic run this
   * inside a transaction.
   *
   * @param id the template id
   */
  public void setAsDefault(int id) {
    InvoiceTemplate template = findById(id);
    if (template == null) {
      return;
    }

    clearDefaultForDocumentType(template.getDocumentTypeId());
    run(
        "setAsDefault",
        () ->
            jdbc.sql("UPDATE invoice_template SET is_default = 1 WHERE id = :id")
                .param("id", id)
                .update());
  }

  /** Binds the template's column values. */
  private static JdbcClient.StatementSpec bind(
      JdbcClient.StatementSpec spec, InvoiceTemplate template) {
    return spec.param("name", template.getName())
        .param("type", template.getDocumentTypeId())
        .param("html", template.getHtmlContent())
        .param("css", template.getCssContent())
        .param("header", template.getHeaderHtml())
        .param("footer", template.getFooterHtml())
        .param("isDefault", template.getIsDefault())
        .param("marginTop", template.getMarginTopMm())
        .param("marginBottom", template.getMarginBottomMm())
        .param("marginLeft", template.getMarginLeftMm())
        .param("marginRight", template.getMarginRightMm())
        .param("blocks", template.getBlocks() != null ? template.getBlocks() : "")
        .param("documentSpecific", template.isDocumentSpecific() ? 1 : 0);
  }
}
