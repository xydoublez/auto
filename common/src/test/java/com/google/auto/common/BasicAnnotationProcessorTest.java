/*
 * Copyright (C) 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.auto.common;

import static com.google.common.collect.Multimaps.transformValues;
import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.JavaSourceSubjectFactory.javaSource;
import static com.google.testing.compile.JavaSourcesSubjectFactory.javaSources;
import static javax.tools.StandardLocation.SOURCE_OUTPUT;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.truth.Correspondence;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BasicAnnotationProcessorTest {

  @Retention(RetentionPolicy.SOURCE)
  public @interface RequiresGeneratedCode {}

  /**
   * Rejects elements unless the class generated by {@link GeneratesCode}'s processor is present.
   */
  private static final class RequiresGeneratedCodeProcessor extends BasicAnnotationProcessor {

    int rejectedRounds;
    final ImmutableList.Builder<ImmutableSetMultimap<Class<? extends Annotation>, Element>>
        processArguments = ImmutableList.builder();

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    protected Iterable<? extends ProcessingStep> initSteps() {
      return ImmutableSet.of(
          new ProcessingStep() {
            @Override
            public Set<Element> process(
                SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
              processArguments.add(ImmutableSetMultimap.copyOf(elementsByAnnotation));
              TypeElement requiredClass =
                  processingEnv.getElementUtils().getTypeElement("test.SomeGeneratedClass");
              if (requiredClass == null) {
                rejectedRounds++;
                return ImmutableSet.copyOf(elementsByAnnotation.values());
              }
              generateClass(processingEnv.getFiler(), "GeneratedByRequiresGeneratedCodeProcessor");
              return ImmutableSet.of();
            }

            @Override
            public Set<? extends Class<? extends Annotation>> annotations() {
              return ImmutableSet.of(RequiresGeneratedCode.class);
            }
          },
          new ProcessingStep() {
            @Override
            public Set<? extends Class<? extends Annotation>> annotations() {
              return ImmutableSet.of(AnAnnotation.class);
            }

            @Override
            public Set<? extends Element> process(
                SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
              return ImmutableSet.of();
            }
          });
    }

    ImmutableList<ImmutableSetMultimap<Class<? extends Annotation>, Element>> processArguments() {
      return processArguments.build();
    }
  }

  @Retention(RetentionPolicy.SOURCE)
  public @interface GeneratesCode {}

  /** Generates a class called {@code test.SomeGeneratedClass}. */
  public class GeneratesCodeProcessor extends BasicAnnotationProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    protected Iterable<? extends ProcessingStep> initSteps() {
      return ImmutableSet.of(
          new ProcessingStep() {
            @Override
            public Set<Element> process(
                SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
              generateClass(processingEnv.getFiler(), "SomeGeneratedClass");
              return ImmutableSet.of();
            }

            @Override
            public Set<? extends Class<? extends Annotation>> annotations() {
              return ImmutableSet.of(GeneratesCode.class);
            }
          });
    }
  }

  public @interface AnAnnotation {}

  /** When annotating a type {@code Foo}, generates a class called {@code FooXYZ}. */
  public class AnAnnotationProcessor extends BasicAnnotationProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    protected Iterable<? extends ProcessingStep> initSteps() {
      return ImmutableSet.of(
          new ProcessingStep() {
            @Override
            public Set<Element> process(
                SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
              for (Element element : elementsByAnnotation.values()) {
                generateClass(processingEnv.getFiler(), element.getSimpleName() + "XYZ");
              }
              return ImmutableSet.of();
            }

            @Override
            public Set<? extends Class<? extends Annotation>> annotations() {
              return ImmutableSet.of(AnAnnotation.class);
            }
          });
    }
  }

  @Test public void properlyDefersProcessing_typeElement() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "@" + RequiresGeneratedCode.class.getCanonicalName(),
        "public class ClassA {",
        "  SomeGeneratedClass sgc;",
        "}");
    JavaFileObject classBFileObject = JavaFileObjects.forSourceLines("test.ClassB",
        "package test;",
        "",
        "@" + GeneratesCode.class.getCanonicalName(),
        "public class ClassB {}");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject, classBFileObject))
        .processedWith(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
        .compilesWithoutError()
        .and()
        .generatesFileNamed(
            SOURCE_OUTPUT, "test", "GeneratedByRequiresGeneratedCodeProcessor.java");
    assertThat(requiresGeneratedCodeProcessor.rejectedRounds).isEqualTo(0);
  }

  @Test
  public void properlyDefersProcessing_nestedTypeValidBeforeOuterType() {
    JavaFileObject source =
        JavaFileObjects.forSourceLines(
            "test.ValidInRound2",
            "package test;",
            "",
            "@" + AnAnnotation.class.getCanonicalName(),
            "public class ValidInRound2 {",
            "  ValidInRound1XYZ vir1xyz;",
            "  @" + AnAnnotation.class.getCanonicalName(),
            "  static class ValidInRound1 {}",
            "}");
    assertAbout(javaSource())
        .that(source)
        .processedWith(new AnAnnotationProcessor())
        .compilesWithoutError()
        .and()
        .generatesFileNamed(SOURCE_OUTPUT, "test", "ValidInRound2XYZ.java");
  }

  @Retention(RetentionPolicy.SOURCE)
  public @interface ReferencesAClass {
    Class<?> value();
  }

  @Test public void properlyDefersProcessing_packageElement() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "@" + GeneratesCode.class.getCanonicalName(),
        "public class ClassA {",
        "}");
    JavaFileObject packageFileObject = JavaFileObjects.forSourceLines("test.package-info",
        "@" + RequiresGeneratedCode.class.getCanonicalName(),
        "@" + ReferencesAClass.class.getCanonicalName() + "(SomeGeneratedClass.class)",
        "package test;");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject, packageFileObject))
        .processedWith(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
        .compilesWithoutError()
        .and()
        .generatesFileNamed(
            SOURCE_OUTPUT, "test", "GeneratedByRequiresGeneratedCodeProcessor.java");
    assertThat(requiresGeneratedCodeProcessor.rejectedRounds).isEqualTo(0);
  }

  @Test public void properlyDefersProcessing_argumentElement() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "public class ClassA {",
        "  SomeGeneratedClass sgc;",
        "  public void myMethod(@" + RequiresGeneratedCode.class.getCanonicalName() + " int myInt)",
        "  {}",
        "}");
    JavaFileObject classBFileObject = JavaFileObjects.forSourceLines("test.ClassB",
        "package test;",
        "",
        "public class ClassB {",
        "  public void myMethod(@" + GeneratesCode.class.getCanonicalName() + " int myInt) {}",
        "}");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject, classBFileObject))
        .processedWith(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
        .compilesWithoutError()
        .and()
        .generatesFileNamed(
            SOURCE_OUTPUT, "test", "GeneratedByRequiresGeneratedCodeProcessor.java");
    assertThat(requiresGeneratedCodeProcessor.rejectedRounds).isEqualTo(0);
  }

  @Test
  public void properlyDefersProcessing_rejectsElement() {
    JavaFileObject classAFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "@" + RequiresGeneratedCode.class.getCanonicalName(),
            "public class ClassA {",
            "  @" + AnAnnotation.class.getCanonicalName(),
            "  public void method() {}",
            "}");
    JavaFileObject classBFileObject = JavaFileObjects.forSourceLines("test.ClassB",
        "package test;",
        "",
        "@" + GeneratesCode.class.getCanonicalName(),
        "public class ClassB {}");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject, classBFileObject))
        .processedWith(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
        .compilesWithoutError()
        .and()
        .generatesFileNamed(
            SOURCE_OUTPUT, "test", "GeneratedByRequiresGeneratedCodeProcessor.java");
    assertThat(requiresGeneratedCodeProcessor.rejectedRounds).isEqualTo(1);

    // Re b/118372780: Assert that the right deferred elements are passed back, and not any enclosed
    // elements annotated with annotations from a different step.
    assertThat(requiresGeneratedCodeProcessor.processArguments())
        .comparingElementsUsing(setMultimapValuesByString())
        .containsExactly(
            ImmutableSetMultimap.of(RequiresGeneratedCode.class, "test.ClassA"),
            ImmutableSetMultimap.of(RequiresGeneratedCode.class, "test.ClassA"))
        .inOrder();
  }

  private static <K, V>
      Correspondence<SetMultimap<K, V>, SetMultimap<K, String>> setMultimapValuesByString() {
    return new Correspondence<SetMultimap<K, V>, SetMultimap<K, String>>() {
      @Override
      public boolean compare(SetMultimap<K, V> actual, SetMultimap<K, String> expected) {
        return ImmutableSetMultimap.copyOf(transformValues(actual, Object::toString))
            .equals(expected);
      }

      @Override
      public String toString() {
        return "is equivalent comparing multimap values by `toString()` to";
      }
    };
  }

  @Test public void reportsMissingType() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "@" + RequiresGeneratedCode.class.getCanonicalName(),
        "public class ClassA {",
        "  SomeGeneratedClass bar;",
        "}");
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject))
        .processedWith(new RequiresGeneratedCodeProcessor())
        .failsToCompile()
        .withErrorContaining(RequiresGeneratedCodeProcessor.class.getCanonicalName())
        .in(classAFileObject).onLine(4);
  }

  private static void generateClass(Filer filer, String generatedClassName) {
    PrintWriter writer = null;
    try {
      writer = new PrintWriter(filer.createSourceFile("test." + generatedClassName).openWriter());
      writer.println("package test;");
      writer.println("public class " + generatedClassName + " {}");
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (writer != null) {
        writer.close();
      }
    }
  }
}
