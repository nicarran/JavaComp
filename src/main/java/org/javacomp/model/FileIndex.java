package org.javacomp.model;

import com.google.common.base.Optional;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.RangeMap;
import java.util.List;
import javax.annotation.Nullable;

/** Index of symbols in the scope of a Java source file. */
public class FileIndex implements SymbolIndex {
  // Map of simple names -> symbols.
  private final Multimap<String, Symbol> symbols;
  private final SymbolIndex parentIndex;
  private RangeMap<Integer, SymbolIndex> indexRangeMap = null;

  public FileIndex(SymbolIndex parentIndex) {
    this.symbols = HashMultimap.create();
    this.parentIndex = parentIndex;
  }

  @Override
  public List<Symbol> getSymbolsWithName(String simpleName) {
    ImmutableList.Builder<Symbol> builder = new ImmutableList.Builder<>();
    builder.addAll(symbols.get(simpleName));
    builder.addAll(parentIndex.getSymbolsWithName(simpleName));
    return builder.build();
  }

  @Override
  public Optional<Symbol> getSymbolWithNameAndKind(String simpleName, Symbol.Kind symbolKind) {
    for (Symbol symbol : symbols.get(simpleName)) {
      if (symbol.getKind() == symbolKind) {
        return Optional.of(symbol);
      }
    }
    return parentIndex.getSymbolWithNameAndKind(simpleName, symbolKind);
  }

  @Override
  public Multimap<String, Symbol> getAllSymbols() {
    ImmutableMultimap.Builder<String, Symbol> builder = new ImmutableMultimap.Builder<>();
    builder.putAll(symbols);
    builder.putAll(parentIndex.getAllSymbols());
    return builder.build();
  }

  @Override
  public void addSymbol(Symbol symbol) {
    symbols.put(symbol.getSimpleName(), symbol);
  }

  public void setIndexRangeMap(RangeMap<Integer, SymbolIndex> indexRangeMap) {
    this.indexRangeMap = indexRangeMap;
  }

  @Nullable
  public SymbolIndex getSymbolIndexAt(int position) {
    return indexRangeMap.get(position);
  }
}