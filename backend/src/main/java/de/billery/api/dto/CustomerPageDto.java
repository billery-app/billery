package de.billery.api.dto;

import de.billery.data.Customer;
import java.util.List;

/**
 * One page of the customer list ({@code GET /api/customers/page}).
 *
 * @param items the customers of the page
 * @param page the 0-based page number
 * @param size the requested page size
 * @param total the number of customers matching the search across all pages
 */
public record CustomerPageDto(List<Customer> items, int page, int size, long total) {}
