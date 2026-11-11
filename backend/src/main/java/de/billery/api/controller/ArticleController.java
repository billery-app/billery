package de.billery.api.controller;

import de.billery.api.dto.ArticleDto;
import de.billery.data.Article;
import de.billery.repository.ArticleRepository;
import de.billery.tax.VatCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/articles")
public class ArticleController {

  private final ArticleRepository articleRepository;

  public ArticleController(ArticleRepository articleRepository) {
    this.articleRepository = articleRepository;
  }

  @GetMapping
  public List<ArticleDto> getAllArticles() {
    List<Article> articles = articleRepository.findAll();
    List<ArticleDto> result = new ArrayList<>();
    for (Article article : articles) {
      result.add(convertToDto(article));
    }
    return result;
  }

  @GetMapping("/{id}")
  public ResponseEntity<ArticleDto> getArticle(@PathVariable Integer id) {
    Article article = articleRepository.findById(id);
    return article != null
        ? ResponseEntity.ok(convertToDto(article))
        : ResponseEntity.notFound().build();
  }

  private ArticleDto convertToDto(Article article) {
    return new ArticleDto(
        article.getId(),
        article.getName(),
        article.getDescription(),
        article.getUnit(),
        article.getPurchaseValue(),
        article.getSalesValue(),
        article.getTaxRatePercent(),
        article.getNote());
  }

  /** The catalog only offers the rates of §12 UStG; anything else falls back to 19 %. */
  static BigDecimal catalogRate(BigDecimal requested) {
    return VatCalculator.isAllowedRate(requested) ? requested : BigDecimal.valueOf(19);
  }

  /** A missing price in the request means 0. */
  static BigDecimal orZero(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  @PostMapping
  public Article createArticle(@RequestBody ArticleDto dto) {
    Article article =
        new Article(
            0,
            dto.name(),
            orZero(dto.purchasePrice()),
            orZero(dto.salesPrice()),
            dto.unit(),
            dto.note() != null ? dto.note() : "",
            dto.description());
    article.setTaxRatePercent(catalogRate(dto.taxRate()));
    return articleRepository.save(article);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Article> updateArticle(
      @PathVariable Integer id, @RequestBody ArticleDto dto) {
    Article existing = articleRepository.findById(id);
    if (existing == null) {
      return ResponseEntity.notFound().build();
    }
    Article updated =
        new Article(
            id,
            dto.name(),
            orZero(dto.purchasePrice()),
            orZero(dto.salesPrice()),
            dto.unit(),
            dto.note() != null ? dto.note() : "",
            dto.description());
    updated.setTaxRatePercent(catalogRate(dto.taxRate()));
    articleRepository.update(updated);
    return ResponseEntity.ok(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteArticle(@PathVariable Integer id) {
    articleRepository.delete(id);
    return ResponseEntity.ok().build();
  }
}
