package org.javacomp.parser;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Modifier;
import org.javacomp.logging.JLogger;
import org.javacomp.model.BlockScope;
import org.javacomp.model.ClassEntity;
import org.javacomp.model.Entity;
import org.javacomp.model.EntityScope;
import org.javacomp.model.FileScope;
import org.javacomp.model.MethodEntity;
import org.javacomp.model.TypeParameter;
import org.javacomp.model.TypeReference;
import org.javacomp.model.VariableEntity;
import org.javacomp.model.util.NestedRangeMapBuilder;
import org.javacomp.options.IndexOptions;

public class AstScanner extends TreePathScanner<Void, EntityScope> {
  private static final List<String> UNAVAILABLE_QUALIFIERS = ImmutableList.of();
  private static final String ON_DEMAND_IMPORT_WILDCARD = "*";

  private static final JLogger logger = JLogger.createForEnclosingClass();

  private final TypeReferenceScanner typeReferenceScanner;
  private final ParameterScanner parameterScanner;
  private final IndexOptions indexOptions;

  private FileScope fileScope = null;
  private List<String> currentQualifiers = new ArrayList<>();
  private EndPosTable endPosTable = null;
  private NestedRangeMapBuilder<EntityScope> scopeRangeBuilder = null;
  private String filename = null;
  private String content = null;
  private Set<Modifier> implicitModifiers = ImmutableSet.of();

  public AstScanner(IndexOptions indexOptions) {
    this.typeReferenceScanner = new TypeReferenceScanner();
    this.parameterScanner = new ParameterScanner(typeReferenceScanner);
    this.indexOptions = indexOptions;
  }

  public FileScope startScan(JCCompilationUnit node, String filename, CharSequence content) {
    this.filename = filename;
    this.content = content.toString();
    super.scan(node, null);
    this.filename = null;
    this.content = null;
    return this.fileScope;
  }

  @Override
  public Void visitCompilationUnit(CompilationUnitTree node, EntityScope unused) {
    // Find or create package scope
    if (node.getPackageName() != null) {
      List<String> qualifiers = nameToQualifiers(node.getPackageName());
      this.currentQualifiers.addAll(qualifiers);
    }

    JCCompilationUnit compilationUnit = (JCCompilationUnit) node;
    this.fileScope =
        FileScope.createFromSource(
            filename, this.currentQualifiers, compilationUnit, content.length());
    this.scopeRangeBuilder = new NestedRangeMapBuilder<>();
    this.endPosTable = compilationUnit.endPositions;
    addScopeRange(compilationUnit, this.fileScope);

    // Handle imports
    for (ImportTree importTree : node.getImports()) {
      List<String> qualifiers = nameToQualifiers(importTree.getQualifiedIdentifier());
      if (qualifiers.isEmpty()) {
        continue;
      }
      if (ON_DEMAND_IMPORT_WILDCARD.equals(qualifiers.get(qualifiers.size() - 1))) {
        if (importTree.isStatic()) {
          this.fileScope.addOnDemandStaticImport(qualifiers.subList(0, qualifiers.size() - 1));
        } else {
          this.fileScope.addOnDemandClassImport(qualifiers.subList(0, qualifiers.size() - 1));
        }
      } else {
        if (importTree.isStatic()) {
          this.fileScope.addImportedStaticMembers(qualifiers);
        } else {
          this.fileScope.addImportedClass(qualifiers);
        }
      }
    }

    // Handle toplevel type declarations (class, interface, enum, annotation, etc).
    for (Tree decl : node.getTypeDecls()) {
      this.scan(decl, this.fileScope);
    }
    this.fileScope.setScopeRangeMap(scopeRangeBuilder.build());

    // Cleanup
    this.currentQualifiers.clear();
    this.scopeRangeBuilder = null;
    this.endPosTable = null;
    return null;
  }

  @Override
  public Void visitClass(ClassTree node, EntityScope currentScope) {
    if (!shouldScanWithModifiers(currentScope, node.getModifiers().getFlags())) {
      return null;
    }

    Entity.Kind entityKind;
    switch (node.getKind()) {
      case CLASS:
        entityKind = Entity.Kind.CLASS;
        break;
      case INTERFACE:
        entityKind = Entity.Kind.INTERFACE;
        // All members in interface are considered public by default.
        break;
      case ENUM:
        entityKind = Entity.Kind.ENUM;
        break;
      case ANNOTATION_TYPE:
        entityKind = Entity.Kind.ANNOTATION;
        break;
      default:
        logger.severe("Unknown entity kind for class: %s", node.getKind());
        return null;
    }
    ImmutableList.Builder<TypeReference> interfaceBuilder = new ImmutableList.Builder<>();
    Optional<TypeReference> superClass = Optional.empty();
    if (node.getExtendsClause() != null) {
      superClass = Optional.of(typeReferenceScanner.getTypeReference(node.getExtendsClause()));
    }
    for (Tree implementClause : node.getImplementsClause()) {
      interfaceBuilder.add(typeReferenceScanner.getTypeReference(implementClause));
    }
    Range<Integer> classNameRange = getClassNameRange((JCClassDecl) node);
    boolean isStatic =
        (currentScope instanceof FileScope) // Top-level class is considered static.
            || isStatic(node.getModifiers());
    ClassEntity classEntity =
        new ClassEntity(
            node.getSimpleName().toString(),
            entityKind,
            this.currentQualifiers,
            isStatic,
            currentScope,
            superClass,
            interfaceBuilder.build(),
            convertTypeParameters(node.getTypeParameters()),
            classNameRange,
            getNodeRange(node));
    currentScope.addEntity(classEntity);
    addScopeRange((JCTree) node, classEntity);
    if (this.currentQualifiers != UNAVAILABLE_QUALIFIERS) {
      this.currentQualifiers.add(classEntity.getSimpleName());
    }

    for (Tree member : node.getMembers()) {
      scan(member, classEntity);
    }

    if (this.currentQualifiers != UNAVAILABLE_QUALIFIERS) {
      this.currentQualifiers.remove(this.currentQualifiers.size() - 1);
    }
    return null;
  }

  private ImmutableList<TypeParameter> convertTypeParameters(
      List<? extends TypeParameterTree> typeParameterTrees) {
    return typeParameterTrees
        .stream()
        .map(
            node -> {
              ImmutableList<TypeReference> extendBounds =
                  node.getBounds()
                      .stream()
                      .map(bound -> typeReferenceScanner.getTypeReference(bound))
                      .collect(ImmutableList.toImmutableList());
              return TypeParameter.create(node.getName().toString(), extendBounds);
            })
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Void visitMethod(MethodTree node, EntityScope currentScope) {
    if (!shouldScanWithModifiers(currentScope, node.getModifiers().getFlags())) {
      return null;
    }

    checkArgument(
        currentScope instanceof ClassEntity, "Method's parent scope must be a class entity");
    TypeReference returnType;
    if (node.getReturnType() == null) {
      // Constructor doesn't have return type.
      returnType = TypeReference.EMPTY_TYPE;
    } else {
      returnType = typeReferenceScanner.getTypeReference(node.getReturnType());
    }
    ImmutableList<TypeParameter> typeParameters = convertTypeParameters(node.getTypeParameters());
    ClassEntity classEntity = (ClassEntity) currentScope;
    Range<Integer> range = getMethodNameRange((JCMethodDecl) node, classEntity.getSimpleName());
    MethodEntity methodEntity =
        new MethodEntity(
            node.getName().toString(),
            this.currentQualifiers,
            isStatic(node.getModifiers()),
            returnType,
            ImmutableList.of() /* parameters */,
            typeParameters,
            classEntity,
            range,
            getNodeRange(node));
    ImmutableList.Builder<VariableEntity> parameterListBuilder = new ImmutableList.Builder<>();
    for (Tree parameter : node.getParameters()) {
      parameterListBuilder.add(parameterScanner.getParameter(parameter, methodEntity));
    }
    methodEntity.setParameters(parameterListBuilder.build());

    // TODO: distinguish between static and non-static methods.
    currentScope.addEntity(methodEntity);
    List<String> previousQualifiers = this.currentQualifiers;
    // No entity defined inside method scope is qualified.
    this.currentQualifiers = UNAVAILABLE_QUALIFIERS;
    if (node.getBody() != null && indexOptions.shouldIndexMethodContent()) {
      scan(node.getBody(), methodEntity);
      addScopeRange((JCTree) node, methodEntity);
    }
    this.currentQualifiers = previousQualifiers;
    return null;
  }

  @Override
  public Void visitVariable(VariableTree node, EntityScope currentScope) {
    if (!shouldScanWithModifiers(currentScope, node.getModifiers().getFlags())) {
      return null;
    }

    Entity.Kind variableKind =
        (currentScope instanceof ClassEntity) ? Entity.Kind.FIELD : Entity.Kind.VARIABLE;
    Range<Integer> range = getVariableNameRange((JCVariableDecl) node);

    TypeReference variableType;
    if (node.getType() == null) {
      // This can happen in the case of untyped lambda function parameters.
      variableType = TypeReference.EMPTY_TYPE;
    } else {
      variableType = typeReferenceScanner.getTypeReference(node.getType());
    }

    VariableEntity variableEntity =
        new VariableEntity(
            node.getName().toString(),
            variableKind,
            this.currentQualifiers,
            isStatic(node.getModifiers()),
            variableType,
            currentScope,
            range,
            getNodeRange(node));
    currentScope.addEntity(variableEntity);
    addScopeRange((JCTree) node, variableEntity);
    // TODO: add entity to module if it's a non-private static entity.
    return null;
  }

  @Override
  public Void visitBlock(BlockTree node, EntityScope currentScope) {
    boolean isMethodBlock =
        (currentScope instanceof MethodEntity)
            && (getCurrentPath().getParentPath().getLeaf() instanceof MethodTree);
    if (!isMethodBlock) {
      BlockScope blockScope = new BlockScope(currentScope, getNodeRange(node));
      currentScope.addChildScope(blockScope);
      currentScope = blockScope;
    }
    for (StatementTree statement : node.getStatements()) {
      this.scan(statement, currentScope);
    }
    addScopeRange((JCTree) node, currentScope);
    return null;
  }

  private static List<String> nameToQualifiers(Tree name) {
    Deque<String> stack = new ArrayDeque<>();
    while (name instanceof MemberSelectTree) {
      MemberSelectTree qualifiedName = (MemberSelectTree) name;
      stack.addFirst(qualifiedName.getIdentifier().toString());
      name = qualifiedName.getExpression();
    }
    stack.addFirst(((IdentifierTree) name).getName().toString());
    return ImmutableList.copyOf(stack);
  }

  @Override
  public Void visitForLoop(ForLoopTree node, EntityScope currentScope) {
    BlockScope blockScope = new BlockScope(currentScope, getNodeRange(node));
    addScopeRange((JCTree) node, blockScope);
    for (StatementTree initializer : node.getInitializer()) {
      this.scan(initializer, blockScope);
    }
    this.scan(node.getCondition(), blockScope);
    for (ExpressionStatementTree update : node.getUpdate()) {
      this.scan(update, blockScope);
    }
    this.scan(node.getStatement(), blockScope);
    return null;
  }

  @Override
  public Void visitEnhancedForLoop(EnhancedForLoopTree node, EntityScope currentScope) {
    BlockScope blockScope = new BlockScope(currentScope, getNodeRange(node));
    addScopeRange((JCTree) node, blockScope);
    this.scan(node.getVariable(), blockScope);
    this.scan(node.getExpression(), blockScope);
    this.scan(node.getStatement(), blockScope);
    return null;
  }

  @Override
  public Void visitWhileLoop(WhileLoopTree node, EntityScope currentScope) {
    BlockScope blockScope = new BlockScope(currentScope, getNodeRange(node));
    addScopeRange((JCTree) node, blockScope);
    this.scan(node.getCondition(), blockScope);
    this.scan(node.getStatement(), blockScope);
    return null;
  }

  @Override
  public Void visitIf(IfTree node, EntityScope currentScope) {
    BlockScope blockScope = new BlockScope(currentScope, getNodeRange(node));
    addScopeRange((JCTree) node, blockScope);
    this.scan(node.getCondition(), blockScope);
    this.scan(node.getThenStatement(), blockScope);
    if (node.getElseStatement() != null) {
      this.scan(node.getElseStatement(), blockScope);
    }
    return null;
  }

  private Range<Integer> getNodeRange(Tree node) {
    checkArgument(node instanceof JCTree, "%s is not a JCTree", node);
    JCTree jcTree = (JCTree) node;
    int start = jcTree.getStartPosition();
    int end = jcTree.getEndPosition(endPosTable);
    if (end < 0) {
      // The file is syntactically incorrect, likely incomplete blocks. Use
      // length of 1 to avoid overlapping.
      end = start + 1;
    }
    return Range.closed(start, end);
  }

  private void addScopeRange(JCTree node, EntityScope scope) {
    Range<Integer> range = getNodeRange(node);
    scopeRangeBuilder.put(range, scope);
  }

  private boolean shouldScanWithModifiers(EntityScope scope, Set<Modifier> modifiers) {
    if (scope instanceof ClassEntity) {
      Entity.Kind parentEntityKind = ((ClassEntity) scope).getKind();
      if (parentEntityKind == Entity.Kind.INTERFACE || parentEntityKind == Entity.Kind.ANNOTATION) {
        // Interface and annotation members are public by default.
        return true;
      }
    }
    if (!indexOptions.shouldIndexNonPublic() && !modifiers.contains(Modifier.PUBLIC)) {
      return false;
    }
    return true;
  }

  private Range<Integer> getVariableNameRange(JCVariableDecl node) {
    if (node.getName() != null) {
      String name = node.getName().toString();
      List<? extends JCTree> precedentNodes;
      if (node.getType() != null) {
        precedentNodes = ImmutableList.of(node.getType());
      } else {
        precedentNodes = ImmutableList.of();
      }
      return getNodeNameRangeAfter(node, name, precedentNodes);
    } else if (node.getNameExpression() != null) {
      return getNodeRange(node.getNameExpression());
    }
    return getNodeRange(node);
  }

  private Range<Integer> getClassNameRange(JCClassDecl node) {
    if (node.getSimpleName() == null) {
      return getNodeRange(node);
    }

    String name = node.getSimpleName().toString();
    List<? extends JCTree> precedentNodes;
    if (node.getModifiers() != null && node.getModifiers().getAnnotations() != null) {
      precedentNodes = node.getModifiers().getAnnotations();
    } else {
      precedentNodes = ImmutableList.of();
    }
    return getNodeNameRangeAfter(node, name, precedentNodes);
  }

  private Range<Integer> getMethodNameRange(JCMethodDecl node, String className) {
    if (node.getName() == null) {
      return getNodeRange(node);
    }

    String name = node.getName().toString();
    if (name.equals("<init>")) {
      // Constructor name is the class name.
      name = className;
    }
    List<JCTree> precedentNodes = new ArrayList<>();
    if (node.getModifiers() != null && node.getModifiers().getAnnotations() != null) {
      precedentNodes.addAll(node.getModifiers().getAnnotations());
    }
    if (node.getTypeParameters() != null) {
      precedentNodes.addAll(node.getTypeParameters());
    }
    if (node.getReturnType() != null) {
      precedentNodes.add(node.getReturnType());
    }
    return getNodeNameRangeAfter(node, name, precedentNodes);
  }

  private Range<Integer> getNodeNameRangeAfter(
      JCTree node, String name, List<? extends JCTree> precedentNodes) {
    int start = node.getStartPosition();
    for (JCTree precedentNode : precedentNodes) {
      start = Math.max(start, precedentNode.getEndPosition(endPosTable));
    }
    start = content.indexOf(name, start);
    if (start > -1 && start < node.getEndPosition(endPosTable)) {
      return Range.closed(start, start + name.length());
    }

    return getNodeRange(node);
  }

  private boolean isStatic(ModifiersTree modifierTree) {
    return modifierTree.getFlags().contains(Modifier.STATIC);
  }

  private class ParameterScanner extends TreeScanner<Void, Void> {
    private final TypeReferenceScanner typeReferenceScanner;
    private String name = "";
    private TypeReference type = TypeReference.EMPTY_TYPE;

    private ParameterScanner(TypeReferenceScanner typeReferenceScanner) {
      this.typeReferenceScanner = typeReferenceScanner;
    }

    private VariableEntity getParameter(Tree node, EntityScope currentScope) {
      name = "";
      type = TypeReference.EMPTY_TYPE;
      scan(node, null);

      Range<Integer> range = getVariableNameRange((JCVariableDecl) node);
      VariableEntity variableEntity =
          new VariableEntity(
              name,
              Entity.Kind.VARIABLE,
              ImmutableList.of() /* qualifiers */,
              false /* isStatic */,
              type,
              currentScope,
              range,
              getNodeRange(node));
      addScopeRange((JCTree) node, variableEntity);
      return variableEntity;
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) {
      name = node.getName().toString();
      type = typeReferenceScanner.getTypeReference(node.getType());
      return null;
    }
  }
}
