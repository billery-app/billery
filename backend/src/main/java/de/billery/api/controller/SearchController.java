package de.billery.api.controller;

import de.billery.api.dto.SearchResult;
import de.billery.repository.ArticleRepository;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.ServiceRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {

  private final CustomerRepository customerRepository;
  private final ArticleRepository articleRepository;
  private final ServiceRepository serviceRepository;
  private final InvoiceRepository invoiceRepository;

  public SearchController(
      CustomerRepository customerRepository,
      ArticleRepository articleRepository,
      ServiceRepository serviceRepository,
      InvoiceRepository invoiceRepository) {
    this.customerRepository = customerRepository;
    this.articleRepository = articleRepository;
    this.serviceRepository = serviceRepository;
    this.invoiceRepository = invoiceRepository;
  }

  @GetMapping
  public ResponseEntity<SearchResult> search(@RequestParam("q") String query) {
    if (query == null || query.trim().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    List<SearchResult.SearchCustomer> customers = customerRepository.search(query);
    List<SearchResult.SearchArticle> articles = articleRepository.search(query);
    List<SearchResult.SearchService> services = serviceRepository.search(query);
    List<SearchResult.SearchInvoice> allInvoices = invoiceRepository.search(query);

    List<SearchResult.SearchInvoice> invoices =
        allInvoices.stream().filter(i -> i.documentTypeId() == 1).toList();
    List<SearchResult.SearchInvoice> offers =
        allInvoices.stream().filter(i -> i.documentTypeId() == 2).toList();
    List<SearchResult.SearchInvoice> calculations =
        allInvoices.stream().filter(i -> i.documentTypeId() == 3).toList();

    SearchResult result =
        new SearchResult(customers, articles, services, invoices, offers, calculations);

    return ResponseEntity.ok(result);
  }
}
