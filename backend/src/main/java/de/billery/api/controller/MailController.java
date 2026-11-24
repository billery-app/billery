package de.billery.api.controller;

import de.billery.service.MailAccountService;
import de.billery.service.MailTemplateService;
import de.billery.tenant.ManagerAccess;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Mail-Einstellungen: the company's own SMTP accounts and the templates of the mails to
 * customers. Everyone in the company may read them (the send dialog lists the accounts; passwords
 * are never returned); changing them, and the test mail, is for the people who manage the company
 * ({@link ManagerAccess}: owners and administrators in SaaS).
 */
@RestController
@RequestMapping("/api/mail")
public class MailController {

  private final MailAccountService accounts;
  private final MailTemplateService templates;
  private final ManagerAccess managers;

  /**
   * @param accounts the SMTP accounts
   * @param templates subject and text per kind of document
   * @param managers who may change them
   */
  public MailController(
      MailAccountService accounts, MailTemplateService templates, ManagerAccess managers) {
    this.accounts = accounts;
    this.templates = templates;
    this.managers = managers;
  }

  /**
   * What this installation allows, and the templates.
   *
   * @return {@code passwordsStorable}, {@code restricted}, {@code templates}
   */
  @GetMapping("/settings")
  public Map<String, Object> settings() {
    MailAccountService.Capabilities capabilities = accounts.capabilities();
    return Map.of(
        "passwordsStorable", capabilities.passwordsStorable(),
        "restricted", capabilities.restricted(),
        "templates", templates.all());
  }

  /**
   * Stores the templates.
   *
   * @param body templates keyed by kind ({@code invoice}, {@code credit_note}, {@code offer},
   *     {@code cost_calculation})
   * @return every template afterwards
   */
  @PutMapping("/templates")
  public Map<String, MailTemplateService.Template> saveTemplates(
      @RequestBody Map<String, MailTemplateService.Template> body) {
    managers.requireManager();
    return templates.save(body);
  }

  /**
   * @return the company's accounts, default first, without passwords
   */
  @GetMapping("/accounts")
  public List<MailAccountService.AccountView> list() {
    return accounts.list();
  }

  /**
   * Adds an account.
   *
   * @param input the settings
   * @return 201 with the account
   */
  @PostMapping("/accounts")
  public ResponseEntity<MailAccountService.AccountView> create(
      @RequestBody MailAccountService.AccountInput input) {
    managers.requireManager();
    return ResponseEntity.status(HttpStatus.CREATED).body(accounts.create(input));
  }

  /**
   * Changes an account.
   *
   * @param id the account
   * @param input the settings; a missing password keeps the stored one
   * @return the account, 404 if the company has none with this id
   */
  @PutMapping("/accounts/{id}")
  public ResponseEntity<MailAccountService.AccountView> update(
      @PathVariable int id, @RequestBody MailAccountService.AccountInput input) {
    managers.requireManager();
    MailAccountService.AccountView updated = accounts.update(id, input);
    return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
  }

  /**
   * Deletes an account.
   *
   * @param id the account
   * @return 204, or 404
   */
  @DeleteMapping("/accounts/{id}")
  public ResponseEntity<Void> delete(@PathVariable int id) {
    managers.requireManager();
    return accounts.delete(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  /**
   * Makes an account the default of the send dialog.
   *
   * @param id the account
   * @return 204, or 404
   */
  @PostMapping("/accounts/{id}/default")
  public ResponseEntity<Void> makeDefault(@PathVariable int id) {
    managers.requireManager();
    return accounts.makeDefault(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  /**
   * Sends a test mail through the account to its own sender address.
   *
   * @param id the account
   * @return 204 when sent, 404, or 502 with the reason
   */
  @PostMapping("/accounts/{id}/test")
  public ResponseEntity<Void> test(@PathVariable int id) {
    managers.requireManager();
    return accounts.test(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
