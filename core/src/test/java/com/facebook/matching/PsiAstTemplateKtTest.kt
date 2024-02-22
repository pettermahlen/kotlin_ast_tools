/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.matching

import com.facebook.asttools.JavaPsiParserUtil
import com.facebook.asttools.KotlinParserUtil
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethodCallExpression
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.Test

/** Tests [PsiAstTemplate] */
class PsiAstTemplateKtTest {

  @Test
  fun `various ways to starts a search with a convenient API`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |class Foo {
          |  @Magic val bar: Bar by SuperDelegate
          |  val bar2: Bar
          |  var bar3: Bar by SuperDelegate
          |  val barString = "Bar".uppercase()
          |}
        """
                .trimMargin())

    assertThat(ktFile.findAllExpressions("\"Bar\".uppercase()").single())
        .isInstanceOf(KtExpression::class.java)

    assertThat(ktFile.findAllProperties("val bar2: Bar").single())
        .isInstanceOf(KtProperty::class.java)

    assertThat(ktFile.findAllAnnotations("@Magic").single())
        .isInstanceOf(KtAnnotationEntry::class.java)
  }

  @Test
  fun `parse variables from # syntax and match`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |class Foo {
          |  @Magic("yay") val barString = "Bar".uppercase()
          |}
        """
                .trimMargin())

    val ktExpression = ktFile.findAllExpressions("#a#.uppercase()").single()
    assertThat(ktExpression).isInstanceOf(KtExpression::class.java)
    assertThat(ktExpression.text).isEqualTo("\"Bar\".uppercase()")

    val ktProperty = ktFile.findAllProperties("val barString = #initializer#").single()
    assertThat(ktProperty).isInstanceOf(KtProperty::class.java)
    assertThat(ktProperty.text).isEqualTo("""@Magic("yay") val barString = "Bar".uppercase()""")

    val ktAnnotationEntry = ktFile.findAllAnnotations("@Magic(#param#)").single()
    assertThat(ktAnnotationEntry).isInstanceOf(KtAnnotationEntry::class.java)
    assertThat(ktAnnotationEntry.text).isEqualTo("""@Magic("yay")""")
  }

  @Test
  fun `when parsing from template, match on properties`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |class Foo {
          |  val bar: Bar by SuperDelegate
          |  val bar2: Bar
          |  var bar3: Bar by SuperDelegate
          |  val barString = "Bar".uppercase()
          |}
        """
                .trimMargin())

    val delegatedPropertyResults: List<KtProperty> =
        ktFile.findAllProperties("val #name#: #type# by SuperDelegate")
    val initializedPropertyResults: List<KtProperty> =
        ktFile.findAllProperties("val #name# = #exp#.uppercase()")

    assertThat(delegatedPropertyResults).hasSize(2)
    assertThat(delegatedPropertyResults[0].text).isEqualTo("val bar: Bar by SuperDelegate")
    assertThat(initializedPropertyResults).hasSize(1)
    assertThat(initializedPropertyResults[0].text).isEqualTo("val barString = \"Bar\".uppercase()")
  }

  @Test
  fun `when parsing from template, match on expression`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |class Foo {
          |  val bar: Int = doIt(1 + 1)
          |}
        """
                .trimMargin())
    val results = ktFile.findAllExpressions("doIt(1 + 1)")

    assertThat(results).hasSize(1)
  }

  @Test
  fun `match template for annotation entry`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo() {
          |  @Magic val a = 5
          |  @NotMagic val b = 5
          |}
        """
                .trimMargin())
    val results = ktFile.findAllAnnotations("@Magic")

    assertThat(results).hasSize(1)
    assertThat(results[0].text).isEqualTo("@Magic")
  }

  @Test
  fun `match template for function call`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo(b: String): Int {
          |  val a = doIt(1, name = b) // yes
          |  val a = doIt(2, name = b) // no
          |  return a
          |}
        """
                .trimMargin())
    val results = ktFile.findAllExpressions("doIt(1, name = b)")

    assertThat(results.map { it.text }).containsExactly("doIt(1, name = b)")
  }

  @Test
  fun `match template for function call with variables`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo() {
          |  doIt(1 + 1) // yes
          |  doIt(1) // no
          |}
        """
                .trimMargin())

    val results: List<KtExpression> =
        ktFile.findAllExpressions(
            "doIt(#a#)", "#a#" to match<KtExpression> { expression -> expression.text == "1 + 1" })

    assertThat(results.map { it.text }).containsExactly("doIt(1 + 1)")
  }

  @Test
  fun `replace using template and variables`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo() {
          |  doIt(1, 2)
          |}
        """
                .trimMargin())

    val newKtFile = ktFile.replaceAllExpressions("doIt(#a#, #b#)", "doIt(#b#, #a#)")

    assertThat(newKtFile.text)
        .isEqualTo(
            """
          |fun foo() {
          |  doIt(2, 1)
          |}
        """
                .trimMargin())
  }

  @Test
  fun `match template for qualified calls`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo() {
          |  a?.b() // yes
          |  a?.c() // no
          |  a.b() // no
          |}
        """
                .trimMargin())

    val results: List<KtExpression> = ktFile.findAllExpressions("a?.b()")

    assertThat(results.map { it.text }).containsExactly("a?.b()")
  }

  @Test
  fun `match template for a class expressions`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo() {
          |  println(Bar::class)
          |  println(Bar
          |      ::
          |      class)
          |}
        """
                .trimMargin())

    val results: List<KtExpression> = ktFile.findAllExpressions("Bar::class")

    assertThat(results.map { it.text })
        .containsExactly("Bar::class", "Bar\n      ::\n" + "      class")
  }

  @Test
  fun `do not match on call expression with receiver`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo(bar: Bar) {
          |  doIt(1)
          |  bar.doIt(2)
          |  doIt(3).again()
          |}
        """
                .trimMargin())
    val results: List<KtExpression> = ktFile.findAllExpressions("doIt(#any#)")

    assertThat(results.map { it.text }).containsExactly("doIt(1)", "doIt(3)")
  }

  @Test
  fun `match with unsafe dereference`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo(bar: Bar) {
          |  doIt(1)!!
          |  doIt(2)
          |  doIt(doIt(3)!!)
          |}
        """
                .trimMargin())
    val results: List<KtExpression> = ktFile.findAllExpressions("doIt(#any#)!!")

    assertThat(results.map { it.text }).containsExactly("doIt(1)!!", "doIt(3)!!")
  }

  @Test
  fun `match with prefix and postfix unary expressions`() {
    val ktFile =
        KotlinParserUtil.parseAsFile(
            """
          |fun foo(i) {
          |  i++
          |  ++i
          |}
        """
                .trimMargin())
    val resultsPrefix: List<KtExpression> = ktFile.findAllExpressions("++#any#")
    val resultsPostfix: List<KtExpression> = ktFile.findAllExpressions("#any#++")

    assertThat(resultsPrefix.map { it.text }).containsExactly("++i")
    assertThat(resultsPostfix.map { it.text }).containsExactly("i++")
  }

  @Test
  fun `various ways to starts a search with a convenient API for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Foo {
          |  public final @Magic Bar bar;
          |  public final Bar bar2;
          |  public final String barString = "Bar".toUpperCase();
          |}
        """
                .trimMargin())

    assertThat(psiJavaFile.findAllExpressions("\"Bar\".toUpperCase()").single())
        .isInstanceOf(PsiMethodCallExpression::class.java)

    assertThat(psiJavaFile.findAllFields("public final Bar bar2").single())
        .isInstanceOf(PsiField::class.java)

    assertThat(psiJavaFile.findAllAnnotations("@Magic").single())
        .isInstanceOf(PsiAnnotation::class.java)
  }

  @Test
  fun `parse variables from # syntax and match for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Foo {
          |  @Magic("yay") String barString = "Bar".toUppercase();
          |}
        """
                .trimMargin())

    val psiExpression = psiJavaFile.findAllExpressions("#a#.toUppercase()").single()
    assertThat(psiExpression).isInstanceOf(PsiExpression::class.java)
    assertThat(psiExpression.text).isEqualTo("\"Bar\".toUppercase()")

    val psiField = psiJavaFile.findAllFields("String barString = #initializer#").single()
    assertThat(psiField).isInstanceOf(PsiField::class.java)
    assertThat(psiField.text).isEqualTo("""@Magic("yay") String barString = "Bar".toUppercase();""")

    val psiAnnotation = psiJavaFile.findAllAnnotations("@Magic(#param#)").single()
    assertThat(psiAnnotation).isInstanceOf(PsiAnnotation::class.java)
    assertThat(psiAnnotation.text).isEqualTo("""@Magic("yay")""")
  }

  @Test
  fun `when parsing from template, match on properties for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Foo {
          |  final Bar bar2;
          |  final String barString = "Bar".toUppercase();
          |}
        """
                .trimMargin())

    val initializedPropertyResults =
        psiJavaFile.findAllFields("final String #name# = #exp#.toUppercase()")

    assertThat(initializedPropertyResults).hasSize(1)
    assertThat(initializedPropertyResults[0].text)
        .isEqualTo("final String barString = \"Bar\".toUppercase();")
  }

  @Test
  fun `when parsing from template, match on expression for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Foo {
          |  final int bar = doIt(1 + 1);
          |}
        """
                .trimMargin())
    val results = psiJavaFile.findAllExpressions("doIt(1 + 1)")

    assertThat(results).hasSize(1)
  }

  @Test
  fun `match template for annotation entry for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  void foo() {
          |    @Magic int a = 5;
          |    @NotMagic int b = 5;
          |  }
          |}
        """
                .trimMargin())
    val results = psiJavaFile.findAllAnnotations("@Magic")

    assertThat(results).hasSize(1)
    assertThat(results[0].text).isEqualTo("@Magic")
  }

  @Test
  fun `match template for function call for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  fun foo(b: String): Int {
          |    int a = doIt(1, b); // yes
          |    int b = doIt(2, b); // no
          |    return a;
          |  }
          |}
        """
                .trimMargin())
    val results = psiJavaFile.findAllExpressions("doIt(1, b)")

    assertThat(results.map { it.text }).containsExactly("doIt(1, b)")
  }

  @Test
  fun `match template for function call with variables for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  void foo() {
          |    doIt(1 + 1); // yes
          |    doIt(1); // no
          |  }
          |}
        """
                .trimMargin())

    val results =
        psiJavaFile.findAllExpressions(
            "doIt(#a#)", "#a#" to match<PsiExpression> { expression -> expression.text == "1 + 1" })

    assertThat(results.map { it.text }).containsExactly("doIt(1 + 1)")
  }

  @Test
  fun `replace using template and variables for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  void foo() {
          |    doIt(1, 2);
          |  }
          |}
        """
                .trimMargin())

    val newKtFile = psiJavaFile.replaceAllExpressions("doIt(#a#, #b#)", "doIt(#b#, #a#)")

    assertThat(newKtFile.text)
        .isEqualTo(
            """
          |public class Test {
          |  void foo() {
          |    doIt(2, 1);
          |  }
          |}
        """
                .trimMargin())
  }

  @Test
  fun `match template for qualified calls for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  public void foo() {
          |    a.b(); // yes
          |    a.c(); // no
          |  }
          |}
        """
                .trimMargin())

    val results = psiJavaFile.findAllExpressions("a.b()")

    assertThat(results.map { it.text }).containsExactly("a.b()")
  }

  @Test
  fun `match template for a class expressions for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  void foo() {
          |  System.out.println((Bar.class);
          |  System.out.println(Bar
          |    .
          |    class);
          |  }
          |}
        """
                .trimMargin())

    val results = psiJavaFile.findAllExpressions("Bar.class")

    assertThat(results.map { it.text }).containsExactly("Bar.class", "Bar\n    .\n" + "    class")
  }

  @Test
  fun `do not match on call expression with receiver for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  void foo(Bar bar) {
          |    doIt(1);
          |    bar.doIt(2);
          |    doIt(3).again();
          |  }
          |}
        """
                .trimMargin())
    val results = psiJavaFile.findAllExpressions("doIt(#any#)")

    assertThat(results.map { it.text }).containsExactly("doIt(1)", "doIt(3)")
  }

  @Test
  fun `match with prefix and postfix unary expressions for Java`() {
    val psiJavaFile =
        JavaPsiParserUtil.parseAsFile(
            """
          |public class Test {
          |  void foo(int i) {
          |    i++;
          |    ++i;
          |  }
          |}
        """
                .trimMargin())
    val resultsPrefix = psiJavaFile.findAllExpressions("++#any#")
    val resultsPostfix = psiJavaFile.findAllExpressions("#any#++")

    assertThat(resultsPrefix.map { it.text }).containsExactly("++i")
    assertThat(resultsPostfix.map { it.text }).containsExactly("i++")
  }
}
