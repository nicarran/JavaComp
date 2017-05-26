package org.javacomp.storage.testdata;

import org.javacomp.storage.testdata.other.OtherPackageClass;

public class TestClass {
  public interface InnerInterface {
    InnerClassB interfaceMethod();
  }

  public class InnerClass {
    TestClass innerClassMethod();
  }

  private final String memberField;

  public org.javacomp.storage.testdata.other.OtherPackageClass testMethod(
      InnerClassA innerAParam, OtherClass otherClassParam) {
    InnerClassA innerAVar = innerAParam.getTestBInA().testAInB.getTestClassInA().innerA;
    OtherClass.InnerClassA otherInnerAVar = otherClassParam.getTestClass().otherClass.getInnerA();
    OtherPackageClass otherPackageInnerAVar = innerAVar.testClassInA.getOtherPackageClass().innerA;
    InnerClassB ignore = innerB;

    getInnerA().testBInA = null;
    getOtherClass().getTestClass();
    overloadMethod(innerA, innerAParam.testClassInA);
    overloadMethod(getInnerA());
    overloadMethod();
  }
}