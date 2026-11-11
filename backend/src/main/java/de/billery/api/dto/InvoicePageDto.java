package de.billery.api.dto;

import java.util.List;

/**
 * One page of the document list ({@code GET /api/invoices/page}).
 *
 * @param items the documents of the page
 * @param page the 0-based page number
 * @param size the requested page size
 * @param total the number of documents matching the filters across all pages
 * @param years the years that have documents (active or archived, all types), for the year filter
 */
public record InvoicePageDto(
    List<InvoiceDto> items, int page, int size, long total, List<Integer> years) {}
