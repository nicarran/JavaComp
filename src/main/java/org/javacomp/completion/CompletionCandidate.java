package org.javacomp.completion;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import org.javacomp.protocol.textdocument.CompletionItem.ResolveAction;
import org.javacomp.protocol.textdocument.CompletionItem.ResolveActionParams;

public interface CompletionCandidate {
  public enum Kind {
    UNKNOWN,
    CLASS,
    INTERFACE,
    ENUM,
    METHOD,
    VARIABLE,
    FIELD,
    PACKAGE,
    KEYWORD,
  }

  /**
   * The category a candidate belongs to for sorting purposes.
   *
   * <p>Candidates are sorted by SortCategory first, then by their other charactistics such as the
   * label.
   *
   * <p>SortCategory values are compared using their ordianl values. Do not change the order of the
   * values unless there is a good reason.
   */
  public enum SortCategory {
    /** A member name defined by the class of the instance. */
    DIRECT_MEMBER,
    /** A symbol name that's visible in the given scope. */
    ACCESSIBLE_SYMBOL,
    /** Other names in undefined categories that have the normal rank. */
    UNKNOWN,
    /** All Java keywords. */
    KEYWORD,
    /** Entity names that are not visible in the given scope. They need to be imported. */
    TO_IMPORT,
  }

  String getName();

  Kind getKind();

  Optional<String> getDetail();

  default Optional<String> getInsertPlainText() {
    return Optional.empty();
  }

  default Optional<String> getInsertSnippet() {
    return Optional.empty();
  }

  default SortCategory getSortCategory() {
    return SortCategory.UNKNOWN;
  }

  default Map<ResolveAction, ResolveActionParams> getResolveActions() {
    return ImmutableMap.of();
  }
}
