package org.javacomp.model;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.javacomp.logging.JLogger;

/**
 * A scope containing a set of classes and the packages defined under the root (unnamed) package.
 *
 * <p>A ModuleScope may be created from a set of Java files, index cache files, or JAR archives.
 */
public class ModuleScope implements EntityScope {
  private static final JLogger logger = JLogger.createForEnclosingClass();

  // Map of simple names -> FileScope that defines the name.
  private final Multimap<String, FileScope> nameToFileMap;
  // Map of filename -> FileScope.
  private final Map<String, FileScope> fileScopeMap;
  private final PackageScope rootPackage;
  private final List<ModuleScope> dependingModuleScopes;

  public ModuleScope() {
    this.nameToFileMap = HashMultimap.create();
    this.fileScopeMap = new HashMap<>();
    this.rootPackage = new PackageScope();
    this.dependingModuleScopes = new ArrayList<>();
  }

  @Override
  public synchronized List<Entity> getEntitiesWithName(final String simpleName) {
    return FluentIterable.from(nameToFileMap.get(simpleName))
        .transformAndConcat(fileScope -> fileScope.getGlobalEntitiesWithName(simpleName))
        .append(rootPackage.getEntitiesWithName(simpleName))
        .toList();
  }

  @Override
  public synchronized Optional<Entity> getEntityWithNameAndKind(
      String simpleName, Entity.Kind entityKind) {
    for (Entity entity : getEntitiesWithName(simpleName)) {
      if (entity.getKind() == entityKind) {
        return Optional.of(entity);
      }
    }
    return Optional.empty();
  }

  @Override
  public synchronized Multimap<String, Entity> getAllEntities() {
    return FluentIterable.from(fileScopeMap.values())
        .transformAndConcat(fileScope -> fileScope.getGlobalEntities().values())
        .append(rootPackage.getAllEntities().values())
        .index(entity -> entity.getSimpleName());
  }

  @Override
  public synchronized Multimap<String, Entity> getMemberEntities() {
    return rootPackage.getMemberEntities();
  }

  @Override
  public void addEntity(Entity entity) {
    throw new UnsupportedOperationException();
  }

  public synchronized void addOrReplaceFileScope(FileScope fileScope) {
    logger.fine("Adding file: %s: %s", fileScope.getFilename(), fileScope.getAllEntities());
    FileScope existingFileScope = fileScopeMap.get(fileScope.getFilename());
    // Add the new file scope to the package first, so that we don't GC the pacakge if
    // the new file and old file are in the same pacakge and is the only file in the package.
    addFileToPackage(fileScope);

    if (existingFileScope != null) {
      // Remove old entity scopees.
      for (String entityName : existingFileScope.getGlobalEntities().keys()) {
        nameToFileMap.remove(entityName, existingFileScope);
      }
      removeFileFromPacakge(existingFileScope);
    }
    fileScopeMap.put(fileScope.getFilename(), fileScope);
    for (String entityName : fileScope.getGlobalEntities().keys()) {
      nameToFileMap.put(entityName, fileScope);
    }
  }

  public synchronized void removeFile(Path filePath) {
    FileScope existingFileScope = fileScopeMap.get(filePath.toString());
    if (existingFileScope != null) {
      removeFileFromPacakge(existingFileScope);
    }
  }

  public synchronized Optional<FileScope> getFileScope(String filename) {
    return Optional.ofNullable(fileScopeMap.get(filename));
  }

  public synchronized PackageScope getRootPackage() {
    return rootPackage;
  }

  public synchronized PackageScope getPackageForFile(FileScope fileScope) {
    List<String> currentQualifiers = new ArrayList<>();
    PackageScope currentPackage = rootPackage;
    for (String qualifier : fileScope.getPackageQualifiers()) {
      Optional<Entity> packageEntity =
          currentPackage.getEntityWithNameAndKind(qualifier, Entity.Kind.QUALIFIER);
      if (packageEntity.isPresent()) {
        currentPackage = ((PackageEntity) packageEntity.get()).getChildScope();
      } else {
        PackageScope packageScope = new PackageScope();
        currentPackage.addEntity(new PackageEntity(qualifier, currentQualifiers, packageScope));
        currentPackage = packageScope;
      }
      currentQualifiers.add(qualifier);
    }
    return currentPackage;
  }

  public synchronized List<FileScope> getAllFiles() {
    return ImmutableList.copyOf(fileScopeMap.values());
  }

  private void addFileToPackage(FileScope fileScope) {
    getPackageForFile(fileScope).addFile(fileScope);
  }

  private void removeFileFromPacakge(FileScope fileScope) {
    Deque<PackageEntity> stack = new ArrayDeque<>();
    PackageScope currentPackage = rootPackage;
    for (String qualifier : fileScope.getPackageQualifiers()) {
      Optional<Entity> optionalPackageEntity =
          currentPackage.getEntityWithNameAndKind(qualifier, Entity.Kind.QUALIFIER);
      if (!optionalPackageEntity.isPresent()) {
        throw new RuntimeException("Package " + qualifier + " not found");
      }
      PackageEntity packageEntity = (PackageEntity) optionalPackageEntity.get();
      stack.addFirst(packageEntity);
      currentPackage = packageEntity.getChildScope();
    }
    currentPackage.removeFile(fileScope);
    while (!currentPackage.hasChildren() && !stack.isEmpty()) {
      PackageEntity packageEntity = stack.removeFirst();
      currentPackage = stack.isEmpty() ? rootPackage : stack.peekFirst().getChildScope();
      currentPackage.removePackage(packageEntity);
    }
  }

  @Override
  public Optional<EntityScope> getParentScope() {
    return Optional.empty();
  }

  public void addDependingModuleScope(ModuleScope dependingModuleScope) {
    dependingModuleScopes.add(dependingModuleScope);
  }

  public List<ModuleScope> getDependingModuleScopes() {
    return ImmutableList.copyOf(dependingModuleScopes);
  }
}
