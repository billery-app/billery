package de.billery.saas.db;

import de.billery.data.Database;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Wraps the application's pooled {@code dataSource} bean in a {@link TenantAwareDataSource}, so the
 * repositories (through the {@code JdbcClient}), the transaction manager and plain-JDBC code that
 * asks {@link Database} all borrow tenant-aware connections. The core stays unaware of tenants.
 * Spring still closes the original pool on shutdown.
 */
@Component
@Profile("saas")
public class TenantDataSourcePostProcessor implements BeanPostProcessor {

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if ("dataSource".equals(beanName) && bean instanceof DataSource pool) {
      DataSource tenantAware = new TenantAwareDataSource(pool);
      Database.useDataSource(tenantAware);
      return tenantAware;
    }
    return bean;
  }
}
