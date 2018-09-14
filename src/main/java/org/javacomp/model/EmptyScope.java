package org.javacomp.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.util.List;
import java.util.Optional;

/** An scope containing no entity. */
public class EmptyScope implements EntityScope {
  public static final EmptyScope INSTANCE = new EmptyScope();

  private EmptyScope() {}

  @Override
  public Multimap<String, Entity> getMemberEntities() {
    return ImmutableMultimap.of();
  }

  @Override
  public List<EntityScope> getChildScopes() {
    return ImmutableList.of();
  }

  @Override
  public void addEntity(Entity entity) {
    throw new UnsupportedOperationException("No entity is allowed to be added to a EmptyScope.");
  }

  @Override
  public void addChildScope(EntityScope childScope) {
    throw new UnsupportedOperationException("No scope is allowed to be added to a EmptyScope.");
  }

  @Override
  public Optional<EntityScope> getParentScope() {
    return Optional.empty();
  }

  @Override
  public Optional<Entity> getDefiningEntity() {
    return Optional.empty();
  }
}
