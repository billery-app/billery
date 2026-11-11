package de.billery;

import de.billery.api.controller.ArticleController;
import de.billery.api.controller.CustomerController;
import de.billery.api.controller.EquipmentController;
import de.billery.api.controller.ExportController;
import de.billery.api.controller.InvoiceController;
import de.billery.api.controller.InvoiceTemplateController;
import de.billery.api.controller.OwnerController;
import de.billery.api.controller.SearchController;
import de.billery.api.controller.ServiceController;
import de.billery.config.DbConfig;
import de.billery.data.Database;
import de.billery.document.DocumentStrategyRegistry;
import de.billery.pdf.TemplateBasedPdfGenerator;
import de.billery.repository.ArticleRepository;
import de.billery.repository.CustomerRepository;
import de.billery.repository.DocumentHistoryRepository;
import de.billery.repository.DocumentNumberSeriesRepository;
import de.billery.repository.EquipmentRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.InvoiceTemplateRepository;
import de.billery.repository.OwnerRepository;
import de.billery.repository.PaymentRepository;
import de.billery.repository.ServiceRepository;
import de.billery.repository.UserSettingsRepository;
import de.billery.service.DocumentNumberService;
import de.billery.service.DocumentPdfService;
import de.billery.service.DocumentService;
import de.billery.service.EInvoiceService;
import de.billery.service.GobdExportService;
import de.billery.service.InvoiceLifecycleService;
import de.billery.statistics.InvoiceStatisticsService;
import de.billery.storage.LocalFileStorage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hand-written object graph for tests.
 *
 * <p>Tests run without a Spring context, so the constructor injection that Spring does in
 * production is mirrored here once. Every factory returns a fresh, fully wired instance; the
 * repositories are stateless, so sharing or not sharing them between instances makes no difference.
 * When a constructor signature changes, only this class has to follow.
 */
public final class TestWiring {

  private TestWiring() {}

  /**
   * Returns a transaction template configured like the application's ({@link
   * DbConfig#newTransactionManager}) on the DataSource {@link Database} currently uses.
   */
  public static TransactionTemplate transactions() {
    return new TransactionTemplate(DbConfig.newTransactionManager(Database.dataSource()));
  }

  /** Returns a JdbcClient on the DataSource {@link Database} currently uses. */
  public static JdbcClient jdbcClient() {
    return JdbcClient.create(Database.dataSource());
  }

  /** Returns a new {@link CustomerRepository}. */
  public static CustomerRepository customerRepository() {
    return new CustomerRepository(jdbcClient());
  }

  /** Returns a new {@link OwnerRepository}. */
  public static OwnerRepository ownerRepository() {
    return new OwnerRepository(jdbcClient());
  }

  /** Returns a new {@link ArticleRepository}. */
  public static ArticleRepository articleRepository() {
    return new ArticleRepository(jdbcClient());
  }

  /** Returns a new {@link ServiceRepository}. */
  public static ServiceRepository serviceRepository() {
    return new ServiceRepository(jdbcClient());
  }

  /** Returns a new {@link EquipmentRepository}. */
  public static EquipmentRepository equipmentRepository() {
    return new EquipmentRepository(jdbcClient());
  }

  /** Returns a new {@link InvoiceRepository}. */
  public static InvoiceRepository invoiceRepository() {
    return new InvoiceRepository(jdbcClient());
  }

  /** Returns a new {@link InvoiceTemplateRepository}. */
  public static InvoiceTemplateRepository templateRepository() {
    return new InvoiceTemplateRepository(jdbcClient());
  }

  /** Returns a new {@link PaymentRepository}. */
  public static PaymentRepository paymentRepository() {
    return new PaymentRepository(jdbcClient());
  }

  /** Returns a new {@link DocumentHistoryRepository}. */
  public static DocumentHistoryRepository historyRepository() {
    return new DocumentHistoryRepository(jdbcClient());
  }

  /** Returns a new {@link DocumentNumberSeriesRepository}. */
  public static DocumentNumberSeriesRepository seriesRepository() {
    return new DocumentNumberSeriesRepository(jdbcClient(), transactions());
  }

  /** Returns a new {@link UserSettingsRepository}. */
  public static UserSettingsRepository settingsRepository() {
    return new UserSettingsRepository(jdbcClient());
  }

  /** Returns a new {@link DocumentNumberService}. */
  public static DocumentNumberService documentNumberService() {
    return new DocumentNumberService(settingsRepository(), seriesRepository());
  }

  /** Returns a new {@link TemplateBasedPdfGenerator}. */
  public static TemplateBasedPdfGenerator pdfGenerator() {
    return new TemplateBasedPdfGenerator(
        customerRepository(),
        ownerRepository(),
        invoiceRepository(),
        templateRepository(),
        new LocalFileStorage());
  }

  /** Returns a new {@link DocumentService}. */
  public static DocumentService documentService() {
    return new DocumentService(
        invoiceRepository(),
        historyRepository(),
        ownerRepository(),
        documentNumberService(),
        new DocumentStrategyRegistry(),
        pdfGenerator(),
        transactions());
  }

  /** Returns a new {@link InvoiceLifecycleService}. */
  public static InvoiceLifecycleService lifecycleService() {
    return new InvoiceLifecycleService(
        invoiceRepository(),
        documentNumberService(),
        paymentRepository(),
        historyRepository(),
        templateRepository(),
        customerRepository(),
        ownerRepository(),
        pdfGenerator(),
        transactions());
  }

  /** Returns a new {@link EInvoiceService}. */
  public static EInvoiceService eInvoiceService() {
    return new EInvoiceService(invoiceRepository(), customerRepository(), ownerRepository());
  }

  /** Returns a new {@link GobdExportService}. */
  public static GobdExportService gobdExportService() {
    return new GobdExportService(
        invoiceRepository(),
        paymentRepository(),
        historyRepository(),
        customerRepository(),
        eInvoiceService(),
        documentPdfService());
  }

  /** Returns a new {@link DocumentPdfService}. */
  public static DocumentPdfService documentPdfService() {
    return new DocumentPdfService(pdfGenerator(), eInvoiceService());
  }

  /** Returns a new {@link InvoiceStatisticsService}. */
  public static InvoiceStatisticsService statisticsService() {
    return new InvoiceStatisticsService(invoiceRepository(), articleRepository());
  }

  /** Returns a new {@link InvoiceController}. */
  public static InvoiceController invoiceController() {
    return new InvoiceController(
        invoiceRepository(),
        customerRepository(),
        paymentRepository(),
        historyRepository(),
        documentPdfService(),
        documentService(),
        lifecycleService(),
        eInvoiceService(),
        new DocumentStrategyRegistry(),
        seriesRepository(),
        settingsRepository(),
        statisticsService(),
        new de.billery.service.NoActorNames());
  }

  /** Returns a new {@link InvoiceTemplateController}. */
  public static InvoiceTemplateController templateController() {
    return new InvoiceTemplateController(
        templateRepository(), invoiceRepository(), pdfGenerator(), transactions());
  }

  /** Returns a new {@link CustomerController}. */
  public static CustomerController customerController() {
    return new CustomerController(customerRepository(), invoiceRepository());
  }

  /** Returns a new {@link ArticleController}. */
  public static ArticleController articleController() {
    return new ArticleController(articleRepository());
  }

  /** Returns a new {@link ServiceController}. */
  public static ServiceController serviceController() {
    return new ServiceController(serviceRepository());
  }

  /** Returns a new {@link EquipmentController}. */
  public static EquipmentController equipmentController() {
    return new EquipmentController(equipmentRepository());
  }

  /** Returns a new {@link OwnerController}. */
  public static OwnerController ownerController() {
    return new OwnerController(ownerRepository());
  }

  /** Returns a new {@link SearchController}. */
  public static SearchController searchController() {
    return new SearchController(
        customerRepository(), articleRepository(), serviceRepository(), invoiceRepository());
  }

  /** Returns a new {@link ExportController}. */
  public static ExportController exportController() {
    return new ExportController(gobdExportService());
  }
}
