package org.javacomp.completion;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.sun.source.tree.ExpressionTree;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import org.javacomp.logging.JLogger;
import org.javacomp.model.ClassEntity;
import org.javacomp.model.Entity;
import org.javacomp.model.EntityWithContext;
import org.javacomp.project.PositionContext;
import org.javacomp.typesolver.ExpressionSolver;
import org.javacomp.typesolver.TypeSolver;

/** An action to get completion candidates for member selection. */
class CompleteMemberAction implements CompletionAction {
  private static final JLogger logger = JLogger.createForEnclosingClass();

  private static final ClassMemberCompletor.Options MEMBER_SELECT_OPTIONS =
      ClassMemberCompletor.Options.builder()
          .allowedKinds(Sets.immutableEnumSet(EnumSet.allOf(Entity.Kind.class)))
          .addBothInstanceAndStaticMembers(false)
          .includeAllMethodOverloads(true)
          .build();
  private static final ClassMemberCompletor.Options METHOD_REFERENCE_OPTIONS =
      ClassMemberCompletor.Options.builder()
          .allowedKinds(Sets.immutableEnumSet(Entity.Kind.METHOD))
          .addBothInstanceAndStaticMembers(false)
          .includeAllMethodOverloads(false)
          .build();
  private final ExpressionTree parentExpression;
  private final TypeSolver typeSolver;
  private final ExpressionSolver expressionSolver;
  private final ClassMemberCompletor.Options options;

  private CompleteMemberAction(
      ExpressionTree parentExpression,
      TypeSolver typeSolver,
      ExpressionSolver expressionSolver,
      ClassMemberCompletor.Options options) {
    this.parentExpression = parentExpression;
    this.typeSolver = typeSolver;
    this.expressionSolver = expressionSolver;
    this.options = options;
  }

  static CompleteMemberAction forMemberSelect(
      ExpressionTree parentExpression, TypeSolver typeSolver, ExpressionSolver expressionSolver) {
    return new CompleteMemberAction(
        parentExpression, typeSolver, expressionSolver, MEMBER_SELECT_OPTIONS);
  }

  static CompleteMemberAction forMethodReference(
      ExpressionTree parentExpression, TypeSolver typeSolver, ExpressionSolver expressionSolver) {
    return new CompleteMemberAction(
        parentExpression, typeSolver, expressionSolver, METHOD_REFERENCE_OPTIONS);
  }

  @Override
  public ImmutableList<CompletionCandidate> getCompletionCandidates(
      PositionContext positionContext, String completionPrefix) {
    Optional<EntityWithContext> solvedParent =
        expressionSolver.solve(
            parentExpression,
            positionContext.getModule(),
            positionContext.getScopeAtPosition(),
            positionContext.getPosition());
    logger.fine("Solved parent expression: %s", solvedParent);
    if (!solvedParent.isPresent()) {
      return ImmutableList.of();
    }

    // TODO: handle array type
    if (solvedParent.get().getArrayLevel() > 0) {
      return ImmutableList.of();
    }

    if (solvedParent.get().getEntity() instanceof ClassEntity) {
      return new ClassMemberCompletor(typeSolver, expressionSolver)
          .getClassMembers(
              solvedParent.get(), positionContext.getModule(), completionPrefix, options);
    }

    // Parent is a package.
    return completePackageMembers(
        solvedParent.get().getEntity().getScope().getMemberEntities().values(), completionPrefix);
  }

  private ImmutableList<CompletionCandidate> completePackageMembers(
      Collection<Entity> entities, String completionPrefix) {
    return entities
        .stream()
        .filter(
            (entity) -> {
              return options.allowedKinds().contains(entity.getKind())
                  && CompletionPrefixMatcher.matches(entity.getSimpleName(), completionPrefix);
            })
        .map(
            (entity) ->
                new EntityCompletionCandidate(
                    entity, CompletionCandidate.SortCategory.DIRECT_MEMBER))
        .collect(ImmutableList.toImmutableList());
  }
}
