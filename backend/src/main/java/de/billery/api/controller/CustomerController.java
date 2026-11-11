package de.billery.api.controller;

import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.CustomerPageDto;
import de.billery.data.Customer;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

  /** Default page size of {@link #getCustomerPage}. */
  static final int DEFAULT_PAGE_SIZE = 50;

  /** Largest page {@link #getCustomerPage} returns. */
  static final int MAX_PAGE_SIZE = 200;

  /** Highest page number {@link #getCustomerPage} accepts (bounded offset, no int overflow). */
  static final int MAX_PAGE = 10_000;

  private final CustomerRepository customerRepository;
  private final InvoiceRepository invoiceRepository;

  public CustomerController(
      CustomerRepository customerRepository, InvoiceRepository invoiceRepository) {
    this.customerRepository = customerRepository;
    this.invoiceRepository = invoiceRepository;
  }

  /**
   * One page of customers, searched in the database: first name, last name, "first last", street,
   * city, postal code, phone or e-mail contain {@code q} (case-insensitive, {@code %} and {@code _}
   * literal).
   *
   * @param page 0-based page number, 0 to {@value #MAX_PAGE}; a page past the end is empty
   * @param size customers per page, 1 to {@value #MAX_PAGE_SIZE}
   * @param q search text, optional
   * @param sort {@code id} (default, the customer list) or {@code name} (last name, first name; the
   *     customer picker)
   * @return the page; 400 for a page or size out of range or an unknown sort
   */
  @GetMapping("/page")
  public ResponseEntity<CustomerPageDto> getCustomerPage(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "id") String sort) {
    if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
      return ResponseEntity.badRequest().build();
    }
    if (!"id".equals(sort) && !"name".equals(sort)) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(
        new CustomerPageDto(
            customerRepository.findPage(q, "name".equals(sort), page * size, size),
            page,
            size,
            customerRepository.count(q)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<Customer> getCustomer(@PathVariable Integer id) {
    Customer customer = customerRepository.findById(id);
    return customer != null ? ResponseEntity.ok(customer) : ResponseEntity.notFound().build();
  }

  @PostMapping
  public Customer createCustomer(@RequestBody CustomerDto customerDto) {
    Customer customer =
        new Customer(
            0,
            customerDto.firstName(),
            customerDto.lastName(),
            customerDto.postalCode(),
            customerDto.city(),
            customerDto.street(),
            houseNumberOrZero(customerDto.houseNumber()),
            customerDto.phone(),
            customerDto.email(),
            customerDto.taxId(),
            customerDto.note());
    customer.setBuyerReference(customerDto.buyerReference());
    return customerRepository.save(customer);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Customer> updateCustomer(
      @PathVariable Integer id, @RequestBody CustomerDto customerDto) {
    Customer existing = customerRepository.findById(id);
    if (existing == null) {
      return ResponseEntity.notFound().build();
    }
    Customer updated =
        new Customer(
            id,
            customerDto.firstName(),
            customerDto.lastName(),
            customerDto.postalCode(),
            customerDto.city(),
            customerDto.street(),
            houseNumberOrZero(customerDto.houseNumber()),
            customerDto.phone(),
            customerDto.email(),
            customerDto.taxId(),
            customerDto.note());
    updated.setBuyerReference(customerDto.buyerReference());
    customerRepository.update(updated);
    return ResponseEntity.ok(updated);
  }

  /**
   * Deletes a customer that has no documents. The database cascades a customer delete to all of the
   * customer's documents, and issued invoices must be retained (GoBD), so customers with documents
   * are refused with 409.
   *
   * @param id the customer id
   * @return 200, 404 if unknown, 409 with a message if the customer has documents
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Map<String, String>> deleteCustomer(@PathVariable Integer id) {
    if (customerRepository.findById(id) == null) {
      return ResponseEntity.notFound().build();
    }
    if (!invoiceRepository.findByCustomerId(id).isEmpty()) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              Map.of(
                  "message",
                  "Der Kunde hat Dokumente und kann nicht gelöscht werden"
                      + " (Aufbewahrungspflicht)."));
    }
    customerRepository.delete(id);
    return ResponseEntity.ok().build();
  }

  /** The house number is optional (e.g. P.O. boxes); the entity stores 0 for "none". */
  private static int houseNumberOrZero(Integer houseNumber) {
    return houseNumber != null ? houseNumber : 0;
  }
}
