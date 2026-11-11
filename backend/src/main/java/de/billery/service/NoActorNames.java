package de.billery.service;

import java.util.Collection;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Self-host and desktop: there are no users, so no history row names one. */
@Component
@Profile("!saas")
public class NoActorNames implements ActorNames {

  @Override
  public Map<String, String> namesOf(Collection<String> actorIds) {
    return Map.of();
  }
}
