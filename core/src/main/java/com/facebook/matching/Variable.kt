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

import com.intellij.psi.PsiElement

/**
 * Represents a variagle in a template
 *
 * A variable is declared in a template with the syntax "#name{arg1=value1, arg2=value2}(?)#
 * - <name> is the name to be used later in templates
 * - <arg1 ... arg2> allows defining common predicates on the variable, such as 'text=.*MyRegex.*'
 * - (?) an optional quetion mark means this variable will be optional, if the node is missing it
 *   will match, and if it is available it needs to satisfy the other requirements
 */
class Variable(
    val name: String,
    val matcher: PsiAstMatcher<*>,
    val isOptional: Boolean,
    val isKotlin: Boolean,
    arguments: String?
) {

  private var textMatchArgument: Regex? = null

  init {
    matcher.variableName = name
    matcher.shouldMatchToNull = isOptional
    if (arguments != null) {
      val argumentsClean = arguments.removeSurrounding("{", "}")
      val regex = " *(?<name>[a-zA-Z0-9]+) *= *(?<value>[^,]+)".toRegex()
      var i = 0
      while (i < argumentsClean.length) {
        val match =
            regex.matchAt(argumentsClean, i)
                ?: error("Syntax error in matcher argument string $argumentsClean, $i")
        i = match.range.last + 1
        val argumentName = checkNotNull(match.groups["name"]).value
        val argumentValue = checkNotNull(match.groups["value"]).value
        when (argumentName) {
          "text" -> textMatchArgument = argumentValue.toRegex()
          else -> error("Unknown template argument to variable $name: $argumentName")
        }
      }
    }
  }

  fun addConditionsFromVariable(matcher: PsiAstMatcher<*>) {
    textMatchArgument?.let { regex ->
      matcher.addChildMatcher { it is PsiElement && it.text.matches(regex) }
    }
  }

  val parsableCodeString: String = if (isKotlin) "`$$name$`" else "$$name$"
  val templateString: String = "#$name${if (isOptional) "?" else ""}#"

  companion object {
    val ANY_SENTINEL: PsiAstMatcher<PsiElement> = PsiAstMatcher(PsiElement::class.java)
    val TEMPLATE_VARIABLE_REGEX: Regex =
        "#(?<name>[A-Za-z0-9_]+)(?<arguments>\\{[^}]*\\})?(?<isOptional>[?]?)#".toRegex()
  }
}
