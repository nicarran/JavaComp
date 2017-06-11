package org.javacomp.typesolver;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.javacomp.logging.JLogger;
import org.javacomp.model.AggregatePackageScope;
import org.javacomp.model.ClassEntity;
import org.javacomp.model.Entity;
import org.javacomp.model.EntityScope;
import org.javacomp.model.FileScope;
import org.javacomp.model.MethodEntity;
import org.javacomp.model.Module;
import org.javacomp.model.PackageEntity;
import org.javacomp.model.PackageScope;
import org.javacomp.model.PrimitiveEntity;
import org.javacomp.model.SolvedArrayType;
import org.javacomp.model.SolvedPackageType;
import org.javacomp.model.SolvedPrimitiveType;
import org.javacomp.model.SolvedReferenceType;
import org.javacomp.model.SolvedType;
import org.javacomp.model.SolvedTypeParameters;
import org.javacomp.model.TypeArgument;
import org.javacomp.model.TypeParameter;
import org.javacomp.model.TypeReference;
import org.javacomp.model.VariableEntity;
import org.javacomp.model.WildcardTypeArgument;

/** Logic for solvfing the type of a given entity. */
public class TypeSolver {
  private static final JLogger logger = JLogger.createForEnclosingClass();

  private static final Optional<SolvedType> UNSOLVED = Optional.empty();
  private static final Set<Entity.Kind> CLASS_KINDS = ClassEntity.ALLOWED_KINDS;
  public static final List<String> JAVA_LANG_QUALIFIERS = ImmutableList.of("java", "lang");
  public static final List<String> JAVA_LANG_OBJECT_QUALIFIERS =
      ImmutableList.of("java", "lang", "Object");
  public static final List<String> JAVA_LANG_STRING_QUALIFIERS =
      ImmutableList.of("java", "lang", "String");

  public Optional<SolvedType> solve(
      TypeReference typeReference, Module module, EntityScope parentScope) {
    return solve(
        typeReference, solveTypeParametersInScope(parentScope, module), module, parentScope);
  }

  public Optional<SolvedType> solve(
      TypeReference typeReference,
      SolvedTypeParameters contextTypeParameters,
      Module module,
      EntityScope parentScope) {
    if (typeReference.isPrimitive()) {
      return Optional.of(
          createSolvedType(
              PrimitiveEntity.get(typeReference.getSimpleName()),
              typeReference,
              contextTypeParameters,
              parentScope,
              module));
    }

    List<String> fullName = typeReference.getFullName();

    if (fullName.isEmpty()) {
      // There can be two casese where the type reference can be empty:
      //   1) The return type of class constructors.
      //   2) The type of implicit lambda function.
      //
      // Returning empty solved type for now.
      //
      // TODO: solve() should never be called for case 1. For case 2 we should infer the type
      // from the context.
      return Optional.empty();
    }

    // Try to lookup in type parameters first.
    if (fullName.size() == 1) {
      Optional<SolvedType> typeInTypeParameters =
          contextTypeParameters.getTypeParameter(typeReference.getSimpleName());
      if (typeInTypeParameters.isPresent()) {
        return typeInTypeParameters;
      }
    }

    Optional<Entity> currentClass =
        findEntityInScope(
            fullName.get(0),
            module,
            parentScope,
            -1 /* position not useful for solving types */,
            CLASS_KINDS);
    // Find the rest of the name parts, if exist.
    for (int i = 1; currentClass.isPresent() && i < fullName.size(); i++) {
      String innerClassName = fullName.get(i);
      currentClass =
          findClassMember(innerClassName, (ClassEntity) currentClass.get(), module, CLASS_KINDS);
      if (!currentClass.isPresent()) {
        return Optional.empty();
      }
    }
    if (currentClass.isPresent()) {
      return Optional.of(
          createSolvedType(
              currentClass.get(), typeReference, contextTypeParameters, parentScope, module));
    }

    // The first part of the type full name is not known class inside the package. Try to find in
    // global package.
    Optional<Entity> classInModule = findClassInModule(typeReference.getFullName(), module);
    if (classInModule.isPresent()) {
      return Optional.of(
          createSolvedType(
              classInModule.get(), typeReference, contextTypeParameters, parentScope, module));
    }

    return Optional.empty();
  }

  public Optional<Entity> findClassOrPackage(List<String> qualifiers, Module module) {
    return findClassOrPackage(qualifiers, module, false /* useCanonicalName */);
  }

  /**
   * @param useCanonicalName if set to true, consider qualifiers the canonical name of the class or
   *     package to look for, otherwise it's the fully qualified name. The differences are
   *     documented by JLS 6.7. In short, fully qualified name allows inner classes declared in
   *     super classes or interfaces, while canonical name only allows inner classes declared in the
   *     parent class itself. See
   *     https://docs.oracle.com/javase/specs/jls/se8/html/jls-6.html#jls-6.7
   */
  private Optional<Entity> findClassOrPackage(
      List<String> qualifiers, Module module, boolean useCanonicalName) {
    EntityScope currentScope = getAggregateRootPackageScope(module);
    if (qualifiers.isEmpty()) {
      return Optional.empty();
    }

    Entity currentEntity = null;
    for (String qualifier : qualifiers) {
      if (currentScope instanceof PackageScope
          || (currentScope instanceof ClassEntity && useCanonicalName)) {
        Collection<Entity> entities = currentScope.getMemberEntities().get(qualifier);
        List<Entity> filteredEntities =
            entities
                .stream()
                .filter(
                    entity -> (entity instanceof PackageEntity || entity instanceof ClassEntity))
                .collect(Collectors.toList());
        if (filteredEntities.isEmpty()) {
          // Either not found, or is ambiguous.
          currentEntity = null;
          break;
        } else if (filteredEntities.size() > 1) {
          logger.warning("More than one class %s are found in package: %s", qualifier, entities);
        }

        currentEntity = filteredEntities.get(0);
      } else if (currentScope instanceof ClassEntity) {
        currentEntity =
            findClassMember(qualifier, (ClassEntity) currentScope, module, CLASS_KINDS)
                .orElse(null);
        if (currentEntity == null) {
          break;
        }
      }

      if (currentEntity == null) {
        break;
      }

      currentScope = currentEntity.getChildScope();
    }
    if (currentEntity != null) {
      return Optional.of(currentEntity);
    }

    // Try finding in java.lang
    Optional<Entity> classInJavaLang =
        findEntityInPackage(qualifiers, JAVA_LANG_QUALIFIERS, module, CLASS_KINDS);
    if (classInJavaLang.isPresent()) {
      return Optional.of(classInJavaLang.get());
    }

    return Optional.empty();
  }

  public AggregatePackageScope getAggregateRootPackageScope(Module module) {
    AggregatePackageScope aggregatedPackageScope = new AggregatePackageScope();
    fillAggregateRootPackageScope(aggregatedPackageScope, module, new HashSet<Module>());
    return aggregatedPackageScope;
  }

  private void fillAggregateRootPackageScope(
      AggregatePackageScope aggregatePackageScope, Module module, Set<Module> visitedModules) {
    if (visitedModules.contains(module)) {
      return;
    }
    visitedModules.add(module);
    aggregatePackageScope.addPackageScope(module.getRootPackage());

    for (Module dependingModule : module.getDependingModules()) {
      fillAggregateRootPackageScope(aggregatePackageScope, dependingModule, visitedModules);
    }
  }

  private Optional<Entity> findEntityInPackage(
      List<String> entityQualifiers,
      List<String> packageQualifiers,
      Module module,
      Set<Entity.Kind> allowedKinds) {
    if (entityQualifiers.isEmpty()) {
      return Optional.empty();
    }

    Optional<PackageScope> packageScope = findPackage(packageQualifiers, module);
    if (!packageScope.isPresent()) {
      return Optional.empty();
    }

    Optional<Entity> currentEntity =
        findClassInPackage(entityQualifiers.get(0), packageScope.get());
    for (int i = 1; i < entityQualifiers.size() && currentEntity.isPresent(); i++) {
      currentEntity =
          findEntityMember(entityQualifiers.get(i), currentEntity.get(), module, allowedKinds);
    }

    return currentEntity;
  }

  public Optional<SolvedType> solveJavaLangObject(Module module) {
    return findClassInModule(JAVA_LANG_OBJECT_QUALIFIERS, module)
        .map(
            entity ->
                createSolvedEntityType(
                    entity,
                    ImmutableList.<TypeArgument>of(),
                    SolvedTypeParameters.EMPTY,
                    entity.getChildScope(),
                    module));
  }

  public Optional<Entity> findClassInModule(List<String> qualifiers, Module module) {
    return findClassInModule(qualifiers, module, false /* useCanonicalName */);
  }

  private Optional<Entity> findClassInModule(
      List<String> qualifiers, Module module, boolean useCanonicalName) {
    Optional<Entity> classInModule = findClassOrPackage(qualifiers, module, useCanonicalName);
    if (classInModule.isPresent() && classInModule.get() instanceof ClassEntity) {
      return classInModule;
    }

    return Optional.empty();
  }

  /**
   * @param position the position in the file that the expression is being solved. It's useful for
   *     filtering out variables defined after the position. It's ignored if set to negative value.
   */
  Optional<Entity> findEntityInScope(
      String name,
      Module module,
      EntityScope baseScope,
      int position,
      Set<Entity.Kind> allowedKinds) {
    List<Entity> entities = findEntitiesInScope(name, module, baseScope, position, allowedKinds);
    if (entities.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(entities.get(0));
  }

  /**
   * @param position the position in the file that the expression is being solved. It's useful for
   *     filtering out variables defined after the position. It's ignored if set to negative value.
   */
  public List<Entity> findEntitiesInScope(
      String name,
      Module module,
      EntityScope baseScope,
      int position,
      Set<Entity.Kind> allowedKinds) {
    // Search class from the narrowest scope to wider scope.
    List<Entity> foundEntities = ImmutableList.of();
    FileScope fileScope = null;
    for (Optional<EntityScope> currentScope = Optional.of(baseScope);
        currentScope.isPresent();
        currentScope = currentScope.get().getParentScope()) {
      if (currentScope.get() instanceof ClassEntity) {
        ClassEntity classEntity = (ClassEntity) currentScope.get();
        foundEntities = findClassMembers(name, classEntity, module, allowedKinds);
        if (!foundEntities.isEmpty()) {
          return foundEntities;
        }
        if (allowedKinds.contains(classEntity.getKind())
            && Objects.equals(name, classEntity.getSimpleName())) {
          return ImmutableList.of(classEntity);
        }
      } else if (currentScope.get() instanceof FileScope) {
        fileScope = (FileScope) currentScope.get();
        foundEntities = findEntitiesInFile(name, fileScope, module, allowedKinds);
        if (!foundEntities.isEmpty()) {
          return foundEntities;
        }
      } else {
        // Block-like scopes (method, if, for, etc...)
        foundEntities =
            findEntitiesInBlock(name, currentScope.get(), module, position, allowedKinds);
        if (!foundEntities.isEmpty()) {
          return foundEntities;
        }
      }
      // TODO: handle annonymous class
    }

    // Not found in current file. Try to find in the same package.
    if (fileScope != null) {
      List<String> packageQualifiers = fileScope.getPackageQualifiers();
      Optional<PackageScope> packageScope = findPackage(packageQualifiers, module);
      if (packageScope.isPresent()) {
        Optional<Entity> foundEntity = findClassInPackage(name, packageScope.get());
        if (foundEntity.isPresent()) {
          return ImmutableList.of(foundEntity.get());
        }
      }
    }
    return foundEntities;
  }

  Optional<Entity> findEntityMember(
      String name, Entity entity, Module module, Set<Entity.Kind> allowedKinds) {
    if (entity instanceof ClassEntity) {
      return findClassMember(name, (ClassEntity) entity, module, allowedKinds);
    } else {
      return findDirectMember(name, entity.getChildScope(), allowedKinds);
    }
  }

  Optional<Entity> findClassMember(
      String name, ClassEntity classEntity, Module module, Set<Entity.Kind> allowedKinds) {
    for (ClassEntity classInHierarchy : classHierarchy(classEntity, module)) {
      Optional<Entity> memberEntity = findDirectMember(name, classInHierarchy, allowedKinds);
      if (memberEntity.isPresent()) {
        return memberEntity;
      }
    }
    return Optional.empty();
  }

  List<Entity> findClassMembers(
      String name, ClassEntity classEntity, Module module, Set<Entity.Kind> allowedKinds) {
    // Non-method members can have only one entity.
    if (!allowedKinds.contains(Entity.Kind.METHOD)) {
      Optional<Entity> classMember = findClassMember(name, classEntity, module, allowedKinds);
      if (classMember.isPresent()) {
        return ImmutableList.of(classMember.get());
      } else {
        return ImmutableList.of();
      }
    }

    ImmutableList.Builder<Entity> builder = new ImmutableList.Builder<>();
    if (allowedKinds.size() > 1) {
      // Contains non-method members, don't look for all of them, just get the applicable one.
      Set<Entity.Kind> nonMethodKinds =
          Sets.filter(allowedKinds, kind -> kind != Entity.Kind.METHOD);
      Optional<Entity> nonMemberEntity = findClassMember(name, classEntity, module, nonMethodKinds);
      if (nonMemberEntity.isPresent()) {
        builder.add(nonMemberEntity.get());
      }
    }

    for (ClassEntity classInHierarchy : classHierarchy(classEntity, module)) {
      builder.addAll(classInHierarchy.getMethodsWithName(name));
    }

    return builder.build();
  }

  List<Entity> findClassMethods(String name, ClassEntity classEntity, Module module) {
    return findClassMembers(name, classEntity, module, Sets.immutableEnumSet(Entity.Kind.METHOD));
  }

  Optional<Entity> findDirectMember(
      String name, EntityScope entityScope, Set<Entity.Kind> allowedKinds) {
    for (Entity member : entityScope.getMemberEntities().get(name)) {
      if (allowedKinds.contains(member.getKind())) {
        return Optional.of(member);
      }
    }
    return Optional.empty();
  }

  private List<Entity> findEntitiesInFile(
      String name, FileScope fileScope, Module module, Set<Entity.Kind> allowedKinds) {
    ImmutableList.Builder<Entity> builder = new ImmutableList.Builder<>();
    if (!Sets.intersection(allowedKinds, ClassEntity.ALLOWED_KINDS).isEmpty()) {
      Optional<Entity> foundClass = findClassInFile(name, fileScope, module);
      if (foundClass.isPresent()) {
        builder.add(foundClass.get());
      }
    }

    if (allowedKinds.contains(Entity.Kind.METHOD)) {
      builder.addAll(findImportedMethodsInFile(name, fileScope, module));
    }

    if (allowedKinds.contains(Entity.Kind.FIELD)) {
      Optional<VariableEntity> foundField = findImportedFieldInFile(name, fileScope, module);
      if (foundField.isPresent()) {
        builder.add(foundField.get());
      }
    }
    return builder.build();
  }

  /**
   * @param position the position in the file that the expression is being solved. It's useful for
   *     filtering out variables defined after the position. It's ignored if set to negative value.
   */
  private List<Entity> findEntitiesInBlock(
      String name,
      EntityScope baseScope,
      Module module,
      int position,
      Set<Entity.Kind> allowedKinds) {
    ImmutableList.Builder<Entity> builder = new ImmutableList.Builder<>();
    if (allowedKinds.contains(Entity.Kind.VARIABLE)) {
      allowedKinds = Sets.difference(allowedKinds, EnumSet.of(Entity.Kind.VARIABLE));

      while (baseScope != null && !(baseScope instanceof ClassEntity)) {
        baseScope
            .getMemberEntities()
            .get(name)
            .stream()
            .filter(
                entity -> {
                  if (position >= 0
                      && entity.getKind() == Entity.Kind.VARIABLE
                      && entity.getSymbolRange().lowerEndpoint() > position) {
                    // Filter out variables defined after position.
                    return false;
                  }
                  return true;
                })
            .forEach(entity -> builder.add(entity));

        baseScope = baseScope.getParentScope().orElse(null);
      }
    }

    if (baseScope instanceof ClassEntity && !allowedKinds.isEmpty()) {
      builder.addAll(findClassMembers(name, (ClassEntity) baseScope, module, allowedKinds));
    }

    return builder.build();
  }

  private Optional<Entity> findClassInFile(String name, FileScope fileScope, Module module) {
    Collection<Entity> entities = fileScope.getMemberEntities().get(name);
    for (Entity entity : entities) {
      if (entity instanceof ClassEntity) {
        return Optional.of(entity);
      }
    }
    // Not declared in the file, try imported classes.
    Optional<List<String>> importedClass = fileScope.getImportedClass(name);
    if (importedClass.isPresent()) {
      Optional<Entity> classInModule =
          findClassInModule(importedClass.get(), module, true /* useCanonicalName */);
      if (classInModule.isPresent()) {
        return classInModule;
      }
    }
    // Not directly imported, try on-demand imports (e.g. import foo.bar.*).
    for (List<String> onDemandClassQualifiers : fileScope.getOnDemandClassImportQualifiers()) {
      Optional<Entity> classOrPackage =
          findClassOrPackage(onDemandClassQualifiers, module, true /* useCanonicalName */);
      if (classOrPackage.isPresent()) {
        Optional<Entity> classEntity =
            findDirectMember(name, classOrPackage.get().getChildScope(), ClassEntity.ALLOWED_KINDS);
        if (classEntity.isPresent()) {
          return classEntity;
        }
      }
    }

    return Optional.empty();
  }

  private List<MethodEntity> findImportedMethodsInFile(
      String name, FileScope fileScope, Module module) {
    // TODO: handle static import.
    return ImmutableList.of();
  }

  private Optional<VariableEntity> findImportedFieldInFile(
      String name, FileScope fileScope, Module module) {
    // TODO: handle static import.
    return Optional.empty();
  }

  /**
   * Finds a class with given {@code name} in the {@code baseScope}.
   *
   * @param baseScope where to find the class. Must be either a {@link PackageScope} or a {@link
   *     ClassEntity}
   */
  private Optional<Entity> findClass(String name, EntityScope baseScope, Module module) {
    if (baseScope instanceof PackageScope) {
      return findClassInPackage(name, (PackageScope) baseScope);
    } else if (baseScope instanceof ClassEntity) {
      return findClassMember(name, (ClassEntity) baseScope, module, CLASS_KINDS);
    }
    return Optional.empty();
  }

  private Optional<Entity> findClassInPackage(String name, PackageScope packageScope) {
    for (Entity entity : packageScope.getMemberEntities().get(name)) {
      if (entity instanceof ClassEntity) {
        return Optional.of(entity);
      }
    }
    return Optional.empty();
  }

  public Optional<PackageScope> findPackage(List<String> packageQualifiers, Module module) {
    PackageScope currentScope = getAggregateRootPackageScope(module);
    for (String qualifier : packageQualifiers) {
      PackageScope nextScope = null;
      for (Entity entity : currentScope.getMemberEntities().get(qualifier)) {
        if (entity instanceof PackageEntity) {
          nextScope = (PackageScope) entity.getChildScope();
          break;
        }
      }
      if (nextScope == null) {
        return Optional.empty();
      }
      currentScope = nextScope;
    }
    return Optional.of(currentScope);
  }

  private SolvedTypeParameters solveTypeParameters(
      List<TypeParameter> typeParameters,
      List<TypeArgument> typeArguments,
      SolvedTypeParameters contextTypeParameters,
      EntityScope baseScope,
      Module module) {
    if (typeParameters.isEmpty()) {
      return SolvedTypeParameters.EMPTY;
    }

    SolvedTypeParameters.Builder builder = SolvedTypeParameters.builder();

    for (int i = 0; i < typeParameters.size(); i++) {
      TypeParameter typeParameter = typeParameters.get(i);
      Optional<SolvedType> solvedTypeParameter;
      if (i < typeArguments.size()) {
        TypeArgument typeArgument = typeArguments.get(i);
        solvedTypeParameter =
            solveTypeArgument(typeArgument, contextTypeParameters, baseScope, module);
      } else {
        // Not enough type arguments. This can be caused by a) using raw type, or b) the code is
        // incorrect. Use the bounds of the type parameters.
        solvedTypeParameter =
            solveTypeParameterBounds(typeParameter, contextTypeParameters, baseScope, module);
      }
      if (solvedTypeParameter.isPresent()) {
        builder.putTypeParameter(typeParameter.getName(), solvedTypeParameter.get());
      }
    }
    return builder.build();
  }

  private Optional<SolvedType> solveTypeArgument(
      TypeArgument typeArgument,
      SolvedTypeParameters contextTypeParameters,
      EntityScope baseScope,
      Module module) {
    if (typeArgument instanceof TypeReference) {
      return solve((TypeReference) typeArgument, contextTypeParameters, module, baseScope);
    } else if (typeArgument instanceof WildcardTypeArgument) {
      WildcardTypeArgument wildCardTypeArgument = (WildcardTypeArgument) typeArgument;
      Optional<WildcardTypeArgument.Bound> bound = wildCardTypeArgument.getBound();
      if (bound.isPresent() && bound.get().getKind() == WildcardTypeArgument.Bound.Kind.EXTENDS) {
        return solve(bound.get().getTypeReference(), contextTypeParameters, module, baseScope);
      } else {
        return solveJavaLangObject(module);
      }
    } else {
      logger.warning("Unsupported type of type argument: %s", typeArgument);
      return Optional.empty();
    }
  }

  private Optional<SolvedType> solveTypeParameterBounds(
      TypeParameter typeParameter,
      SolvedTypeParameters contextTypeParameters,
      EntityScope baseScope,
      Module module) {
    List<TypeReference> bounds = typeParameter.getExtendBounds();
    if (bounds.isEmpty()) {
      // No bound defined, Object is the bound.
      return solveJavaLangObject(module);
    } else {
      // TODO: support multiple bounds.
      TypeReference bound = bounds.get(0);
      return solve(bound, contextTypeParameters, module, baseScope);
    }
  }

  /**
   * Solve type parameter bindings based on the type parameters declared in the given scope and its
   * parent scopes.
   */
  private SolvedTypeParameters solveTypeParametersInScope(EntityScope baseScope, Module module) {
    Deque<List<TypeParameter>> typeParametersStack = new ArrayDeque<>();
    Deque<EntityScope> entityScopeStack = new ArrayDeque<>();
    for (EntityScope currentScope = baseScope;
        currentScope != null;
        currentScope = currentScope.getParentScope().orElse(null)) {
      List<TypeParameter> typeParameters = ImmutableList.of();
      if (currentScope instanceof ClassEntity) {
        typeParameters = ((ClassEntity) currentScope).getTypeParameters();
      } else if (currentScope instanceof MethodEntity) {
        typeParameters = ((MethodEntity) currentScope).getTypeParameters();
      }
      if (!typeParameters.isEmpty()) {
        typeParametersStack.push(typeParameters);
        entityScopeStack.push(currentScope);
      }
    }

    SolvedTypeParameters solvedTypeParameters = SolvedTypeParameters.EMPTY;
    // Solve type parameters from parent scopes to child scopes. Child scopes may reference type
    // parameters defined by parent scopes.
    while (!typeParametersStack.isEmpty()) {
      SolvedTypeParameters.Builder builder = solvedTypeParameters.toBuilder();
      List<TypeParameter> typeParameters = typeParametersStack.pop();
      EntityScope typeParametersScope = entityScopeStack.pop();
      for (TypeParameter typeParameter : typeParameters) {
        Optional<SolvedType> solvedBounds =
            solveTypeParameterBounds(
                typeParameter, solvedTypeParameters, typeParametersScope, module);
        if (solvedBounds.isPresent()) {
          builder.putTypeParameter(typeParameter.getName(), solvedBounds.get());
        }
      }
      solvedTypeParameters = builder.build();
    }
    return solvedTypeParameters;
  }

  private SolvedType createSolvedType(
      Entity solvedEntity,
      TypeReference typeReference,
      SolvedTypeParameters contextTypeParameters,
      EntityScope baseScope,
      Module module) {
    if (typeReference.isArray()) {
      return SolvedArrayType.create(
          createSolvedEntityType(
              solvedEntity,
              typeReference.getTypeArguments(),
              contextTypeParameters,
              baseScope,
              module));
    }
    return createSolvedEntityType(
        solvedEntity, typeReference.getTypeArguments(), contextTypeParameters, baseScope, module);
  }

  private SolvedType createSolvedEntityType(
      Entity solvedEntity,
      List<TypeArgument> typeArguments,
      SolvedTypeParameters contextTypeParameters,
      EntityScope baseScope,
      Module module) {
    if (solvedEntity instanceof ClassEntity) {
      ClassEntity classEntity = (ClassEntity) solvedEntity;
      return SolvedReferenceType.create(
          classEntity,
          solveTypeParameters(
              classEntity.getTypeParameters(),
              typeArguments,
              contextTypeParameters,
              baseScope,
              module));
    } else if (solvedEntity instanceof PrimitiveEntity) {
      return SolvedPrimitiveType.create((PrimitiveEntity) solvedEntity);
    } else if (solvedEntity instanceof PackageEntity) {
      return SolvedPackageType.create((PackageEntity) solvedEntity);
    } else {
      throw new RuntimeException(
          "Unsupported type of entity for creating solved type: " + solvedEntity);
    }
  }

  /** Returns an iterable over a class and all its ancestor classes and interfaces. */
  public Iterable<ClassEntity> classHierarchy(ClassEntity classEntity, Module module) {
    return new Iterable<ClassEntity>() {
      @Override
      public Iterator<ClassEntity> iterator() {
        return new ClassHierarchyIterator(classEntity, module);
      }
    };
  }

  /** An iterator walking through a class and all its ancestor classes and interfaces */
  public class ClassHierarchyIterator extends AbstractIterator<ClassEntity> {
    private class ClassReference {
      private final TypeReference classType;
      private final EntityScope baseScope;

      private ClassReference(TypeReference classType, EntityScope baseScope) {
        this.classType = classType;
        this.baseScope = baseScope;
      }
    }

    private final Deque<ClassReference> classQueue;
    private final Set<Entity> visitedClassEntity;
    private final ClassEntity classEntity;
    private final Module module;

    private boolean firstItem;
    private boolean javaLangObjectAdded;

    public ClassHierarchyIterator(ClassEntity classEntity, Module module) {
      this.classEntity = classEntity;
      this.module = module;
      this.classQueue = new ArrayDeque<>();
      this.visitedClassEntity = new HashSet<>();
      this.firstItem = true;
    }

    @Override
    protected ClassEntity computeNext() {
      if (firstItem) {
        firstItem = false;
        visitClass(classEntity);
        return classEntity;
      }

      while (!classQueue.isEmpty()) {
        ClassReference classReference = classQueue.removeFirst();
        Optional<Entity> solvedEntity;
        if (classReference.baseScope == null) {
          solvedEntity = findClassInModule(classReference.classType.getFullName(), module);
        } else {
          solvedEntity =
              solve(classReference.classType, module, classReference.baseScope)
                  .filter(t -> t instanceof SolvedReferenceType)
                  .map(t -> ((SolvedReferenceType) t).getEntity());
        }
        if (!solvedEntity.isPresent()) {
          continue;
        }
        Entity entity = solvedEntity.get();
        if (!(entity instanceof ClassEntity)) {
          throw new RuntimeException(classReference.classType + " " + entity);
        }

        if (visitedClassEntity.contains(entity)) {
          continue;
        }

        visitClass((ClassEntity) entity);
        return (ClassEntity) entity;
      }

      if (!javaLangObjectAdded) {
        javaLangObjectAdded = true;
        Optional<Entity> javaLangObject = findClassInModule(JAVA_LANG_OBJECT_QUALIFIERS, module);
        if (javaLangObject.isPresent()) {
          return (ClassEntity) javaLangObject.get();
        }
      }
      return endOfData();
    }

    private void visitClass(ClassEntity classEntity) {
      visitedClassEntity.add(classEntity);
      if ("java.lang.Object".equals(classEntity.getQualifiedName())) {
        javaLangObjectAdded = true;
      }
      enqueueSuperClassAndInterfaces(classEntity);
    }

    private void enqueueSuperClassAndInterfaces(ClassEntity classEntity) {
      if (classEntity.getSuperClass().isPresent() && classEntity.getParentScope().isPresent()) {
        classQueue.addLast(
            new ClassReference(
                classEntity.getSuperClass().get(), classEntity.getParentScope().get()));
      } else if (classEntity.getKind() == Entity.Kind.ENUM) {
        classQueue.addLast(
            new ClassReference(TypeReference.JAVA_LANG_ENUM, classEntity.getParentScope().get()));
      }
      for (TypeReference iface : classEntity.getInterfaces()) {
        classQueue.addLast(new ClassReference(iface, classEntity.getParentScope().get()));
      }
    }
  }
}
