package org.javacomp.completion.testdata;

public class CompleteInMethod {
  public static final int STATIC_FIELD;
  public CompleteInMethod self = new CompleteInMethod();
  public FakeString fakeString;

  /** The class above. */
  public class AboveClass {
    public static final int STATIC_ABOVE_FIELD;
    public final int aboveField;

    public static void staticAboveMethod() {}
    public void aboveMethod() {}
  }

  public void completeMethod() {
    AboveClass above = new AboveClass();
    BelowClass below = new BelowClass();
    /** @insert */
  }

  public static void staticMethod();
  public static void staticMethod(int value);

  /** The class below. */
  public class BelowClass {
    public static final int STATIC_BELOW_FIELD;
    public final int belowField;

    public static void staticBelowMethod() {}
    public void belowMethod() {}
  }
}
