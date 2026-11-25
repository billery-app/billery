package de.billery.api.controller;

import de.billery.config.DemoMode;
import de.billery.service.DocumentMailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sending a document to the customer by mail ({@link DocumentMailService}). */
@RestController
@RequestMapping("/api/invoices/{id}/mail")
public class DocumentMailController {

  private final DocumentMailService mail;
  private final DemoMode demo;

  /**
   * @param mail prepares and sends the mail
   * @param demo the public demo, which sends no mail
   */
  public DocumentMailController(DocumentMailService mail, DemoMode demo) {
    this.mail = mail;
    this.demo = demo;
  }

  /**
   * What the send dialog starts with.
   *
   * @param id the document
   * @return recipient, subject, text, accounts and attachment options; 404 for an unknown document
   */
  @GetMapping
  public DocumentMailService.Draft draft(@PathVariable int id) {
    return mail.draft(id);
  }

  /**
   * Sends the document (an invoice draft is issued first when the request confirms it).
   *
   * @param id the document
   * @param request recipients, subject, text and options
   * @return what happened to the document; 400, 404, 409, 429 or 502 with a {@code message}
   */
  @PostMapping
  public DocumentMailService.Result send(
      @PathVariable int id, @RequestBody DocumentMailService.Request request) {
    demo.refuse("Der Mailversand");
    return mail.send(id, request);
  }
}
