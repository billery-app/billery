package de.billery.saas.identity;

import de.billery.service.ActorNames;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * SaaS: the name of a history row's actor is the user's display name, or the address when the user
 * has none. Users stay after they leave a company, so older rows keep their name.
 */
@Service
@Profile("saas")
public class AppUserActorNames implements ActorNames {

  private final JdbcClient jdbc;

  /**
   * @param jdbc the database access
   */
  public AppUserActorNames(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, String> namesOf(Collection<String> actorIds) {
    List<UUID> ids =
        actorIds.stream()
            .filter(AppUserActorNames::isUuid)
            .map(UUID::fromString)
            .distinct()
            .toList();
    Map<String, String> names = new HashMap<>();
    if (ids.isEmpty()) {
      return names;
    }
    jdbc.sql(
            "SELECT id, COALESCE(NULLIF(display_name, ''), email) AS name FROM app_user"
                + " WHERE id IN (:ids)")
        .param("ids", ids)
        .query(
            (rs, row) -> names.put(rs.getObject("id", UUID.class).toString(), rs.getString("name")))
        .list();
    return names;
  }

  private static boolean isUuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
