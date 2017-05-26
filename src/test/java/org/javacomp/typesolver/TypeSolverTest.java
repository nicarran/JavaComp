package org.javacomp.typesolver;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.truth.Truth8;
import java.util.Optional;
import org.javacomp.model.ClassEntity;
import org.javacomp.model.MethodEntity;
import org.javacomp.model.ModuleScope;
import org.javacomp.model.SolvedType;
import org.javacomp.model.TypeReference;
import org.javacomp.testing.TestUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TypeSolverTest {
  private static final String TEST_DATA_DIR = "src/test/java/org/javacomp/typesolver/testdata/";
  private static final String[] TEST_FILES = {
    "BaseInterface.java", "TestClass.java",
  };
  private static final String[] OTHER_FILES = {
    "other/BaseClass.java", "other/Shadow.java",
  };

  private static final String[] ON_DEMAND_FILES = {
    "ondemand/OnDemand.java", "ondemand/Shadow.java",
  };

  private static final String TEST_DATA_PACKAGE = "org.javacomp.typesolver.testdata";
  private static final String TEST_DATA_OTHER_PACKAGE = TEST_DATA_PACKAGE + ".other";
  private static final String TEST_DATA_ONDEMAND_PACKAGE = TEST_DATA_PACKAGE + ".ondemand";
  private static final String TEST_CLASS_FULL_NAME = TEST_DATA_PACKAGE + ".TestClass";
  private static final String TEST_CLASS_FACTORY_FULL_NAME =
      TEST_CLASS_FULL_NAME + ".TestClassFactory";
  private static final String BASE_INTERFACE_FULL_NAME = TEST_DATA_PACKAGE + ".BaseInterface";
  private static final String BASE_INTERFACE_FACTORY_FULL_NAME =
      BASE_INTERFACE_FULL_NAME + ".BaseInterfaceFactory";
  private static final String BASE_CLASS_FULL_NAME = TEST_DATA_OTHER_PACKAGE + ".BaseClass";
  private static final String OTHER_SHADOW_CLASS_FULL_NAME = TEST_DATA_OTHER_PACKAGE + ".Shadow";
  private static final String BASE_INNER_CLASS_FULL_NAME = BASE_CLASS_FULL_NAME + ".BaseInnerClass";
  private static final String ON_DEMAND_CLASS_FULL_NAME = TEST_DATA_ONDEMAND_PACKAGE + ".OnDemand";
  private static final Joiner QUALIFIER_JOINER = Joiner.on(".");

  private final TypeSolver typeSolver = new TypeSolver();

  private ModuleScope testModuleScope;
  private ModuleScope otherModuleScope;
  private ModuleScope onDemandModuleScope;

  @Before
  public void setUpTestScope() throws Exception {
    testModuleScope = TestUtil.parseFiles(TEST_DATA_DIR, TEST_FILES);
    otherModuleScope = TestUtil.parseFiles(TEST_DATA_DIR, OTHER_FILES);
    onDemandModuleScope = TestUtil.parseFiles(TEST_DATA_DIR, ON_DEMAND_FILES);
    testModuleScope.addDependingModuleScope(otherModuleScope);
    testModuleScope.addDependingModuleScope(onDemandModuleScope);
  }

  @Test
  public void solveBaseInterfaceInTheSamePackage() {
    ClassEntity testClass =
        (ClassEntity) TestUtil.lookupEntity(TEST_CLASS_FULL_NAME, testModuleScope);
    TypeReference baseInterfaceReference = testClass.getInterfaces().get(0);
    Optional<SolvedType> solvedType =
        typeSolver.solve(baseInterfaceReference, testModuleScope, testClass);
    Truth8.assertThat(solvedType).isPresent();
    assertThat(solvedType.get().getEntity())
        .isSameAs(TestUtil.lookupEntity(BASE_INTERFACE_FULL_NAME, testModuleScope));
  }

  @Test
  public void solveClassDefinedInSuperInterface() {
    ClassEntity testClass =
        (ClassEntity) TestUtil.lookupEntity(TEST_CLASS_FACTORY_FULL_NAME, testModuleScope);
    TypeReference baseInterfaceReference = testClass.getInterfaces().get(0);
    Optional<SolvedType> solvedType =
        typeSolver.solve(baseInterfaceReference, testModuleScope, testClass);
    Truth8.assertThat(solvedType).isPresent();
    assertThat(solvedType.get().getEntity())
        .isSameAs(TestUtil.lookupEntity(BASE_INTERFACE_FACTORY_FULL_NAME, testModuleScope));
  }

  @Test
  public void solveInnerClass() {
    SolvedType baseInterface = solveMethodReturnType(TEST_CLASS_FULL_NAME + ".newFactory");
    assertThat(baseInterface.getEntity())
        .isSameAs(TestUtil.lookupEntity(TEST_CLASS_FACTORY_FULL_NAME, testModuleScope));
  }

  @Test
  public void solveBaseClassInOtherPackage() {
    ClassEntity testClass =
        (ClassEntity) TestUtil.lookupEntity(TEST_CLASS_FULL_NAME, testModuleScope);
    TypeReference baseClassReference = testClass.getSuperClass().get();
    Optional<SolvedType> solvedType =
        typeSolver.solve(baseClassReference, testModuleScope, testClass);
    Truth8.assertThat(solvedType).isPresent();
    assertThat(solvedType.get().getEntity())
        .isSameAs(TestUtil.lookupEntity(BASE_CLASS_FULL_NAME, otherModuleScope));
  }

  @Test
  public void solveInnerClassInBaseClassFromOtherPackage() {
    ClassEntity testClass =
        (ClassEntity) TestUtil.lookupEntity(TEST_CLASS_FACTORY_FULL_NAME, testModuleScope);
    TypeReference baseInnerClassReference = testClass.getSuperClass().get();
    Optional<SolvedType> solvedType =
        typeSolver.solve(baseInnerClassReference, testModuleScope, testClass);
    Truth8.assertThat(solvedType).isPresent();
    assertThat(solvedType.get().getEntity())
        .isSameAs(TestUtil.lookupEntity(BASE_INNER_CLASS_FULL_NAME, otherModuleScope));
  }

  @Test
  public void solveOnDemandClassImport() {
    SolvedType onDemandClass = solveMethodReturnType(TEST_CLASS_FULL_NAME + ".getOnDemand");
    assertThat(onDemandClass.getEntity())
        .isSameAs(TestUtil.lookupEntity(ON_DEMAND_CLASS_FULL_NAME, onDemandModuleScope));
  }

  @Test
  public void onDemandPackageClassShouldBeShadowed() {
    // both other and ondemand package define Shadow class. Shadow from other package should be used
    // since it's explicitly imported.
    SolvedType shadowClass = solveMethodReturnType(TEST_CLASS_FULL_NAME + ".getShadow");
    assertThat(shadowClass.getEntity())
        .isSameAs(TestUtil.lookupEntity(OTHER_SHADOW_CLASS_FULL_NAME, otherModuleScope));
  }

  @Test
  public void solveFullyQualifiedType() {
    SolvedType fullyQualifiedBaseClass =
        solveMethodReturnType(TEST_CLASS_FULL_NAME + ".getFullyQualifiedBaseClass");
    assertThat(fullyQualifiedBaseClass.getEntity())
        .isSameAs(TestUtil.lookupEntity(BASE_CLASS_FULL_NAME, otherModuleScope));
  }

  @Test
  public void solveNonExistentType() {
    // Should not throw exceptions.
    assertNotSolved(TEST_CLASS_FULL_NAME + ".returnTypeNotExistMethod");
    assertNotSolved(TEST_CLASS_FULL_NAME + ".returnInnerTypeNotExistMethod");
    assertNotSolved(TEST_CLASS_FULL_NAME + ".returnTypePackageNotExistMethod");
    assertNotSolved(TEST_CLASS_FULL_NAME + ".returnTypeRootPackageNotExistMethod");
  }

  private SolvedType solveMethodReturnType(String qualifiedMethodName) {
    MethodEntity method =
        (MethodEntity) TestUtil.lookupEntity(qualifiedMethodName, testModuleScope);
    assertThat(method).named(qualifiedMethodName).isNotNull();
    TypeReference methodReturnType = method.getReturnType();
    Optional<SolvedType> solvedType =
        typeSolver.solve(
            methodReturnType, testModuleScope, method.getChildScope().getParentScope().get());
    Truth8.assertThat(solvedType).named(methodReturnType.toString()).isPresent();
    return solvedType.get();
  }

  private void assertNotSolved(String qualifiedMethodName) {
    MethodEntity method =
        (MethodEntity) TestUtil.lookupEntity(qualifiedMethodName, testModuleScope);
    assertThat(method).named(qualifiedMethodName).isNotNull();
    TypeReference methodReturnType = method.getReturnType();
    Optional<SolvedType> solvedType =
        typeSolver.solve(
            methodReturnType, testModuleScope, method.getChildScope().getParentScope().get());
    Truth8.assertThat(solvedType).named(methodReturnType.toString()).isEmpty();
  }
}
