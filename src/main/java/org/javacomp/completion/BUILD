package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "completion",
    srcs = glob(["*.java"]),
    deps = [
        "//src/main/java/org/javacomp/file",
        "//src/main/java/org/javacomp/logging",
        "//src/main/java/org/javacomp/model",
        "//src/main/java/org/javacomp/parser",
        "//src/main/java/org/javacomp/project:modulemanager",
        "//src/main/java/org/javacomp/project:positioncontext",
        "//src/main/java/org/javacomp/protocol",
        "//src/main/java/org/javacomp/typesolver",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:javac",
        "//third_party:jsr305",
    ],
)
