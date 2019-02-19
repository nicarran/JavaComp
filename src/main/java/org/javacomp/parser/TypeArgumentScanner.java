package org.javacomp.parser;

import com.google.common.flogger.FluentLogger;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreeScanner;
import java.util.Optional;
import org.javacomp.model.TypeArgument;
import org.javacomp.model.WildcardTypeArgument;

/** Converts a Java source tree to a {@link TypeArgument}. */
public class TypeArgumentScanner extends TreeScanner<TypeArgument, Void> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public TypeArgument getTypeArgument(Tree node) {
    return scan(node, null);
  }

  @Override
  public TypeArgument scan(Tree node, Void unused) {
    if (node instanceof WildcardTree) {
      return createWildcardTypeArgument((WildcardTree) node);
    }
    return new TypeReferenceScanner(this).getTypeReference(node);
  }

  private WildcardTypeArgument createWildcardTypeArgument(WildcardTree node) {
    Optional<WildcardTypeArgument.Bound> bound;
    switch (node.getKind()) {
      case SUPER_WILDCARD:
        bound =
            Optional.of(
                WildcardTypeArgument.Bound.create(
                    WildcardTypeArgument.Bound.Kind.SUPER,
                    new TypeReferenceScanner().getTypeReference(node.getBound())));
        break;
      case EXTENDS_WILDCARD:
        bound =
            Optional.of(
                WildcardTypeArgument.Bound.create(
                    WildcardTypeArgument.Bound.Kind.EXTENDS,
                    new TypeReferenceScanner().getTypeReference(node.getBound())));
        break;
      case UNBOUNDED_WILDCARD:
        bound = Optional.empty();
        break;
      default:
        logger.atWarning().log("Unknown wildcard type varialbe kind: %s", node.getKind());
        bound = Optional.empty();
    }
    return WildcardTypeArgument.create(bound);
  }
}