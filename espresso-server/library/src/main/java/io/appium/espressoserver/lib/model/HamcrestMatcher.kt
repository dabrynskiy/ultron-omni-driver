/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package io.appium.espressoserver.lib.model

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.annotations.JsonAdapter
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.handlers.exceptions.InvalidSelectorException
import io.appium.espressoserver.lib.handlers.exceptions.AppiumException
import io.appium.espressoserver.lib.helpers.GsonParserHelpers
import io.appium.espressoserver.lib.helpers.ReflectionUtils.invokeStaticMethod
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import java.lang.reflect.Type
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import io.appium.espressoserver.lib.helpers.reflection.ClassUtils

const val CLASS_SUFFIX = ".class"

data class ScopedMatcher(
    val matcher: Matcher<*>,
    val scope: Matcher<*>? = null
)

@JsonAdapter(HamcrestMatcher.HamcrestMatcherDeserializer::class)
data class HamcrestMatcher(
    val name: String,
    val args: Array<Any?>,
    val cls: KClass<*>? = null,
    val scope: HamcrestMatcher? = null
) {

    fun invoke(): ScopedMatcher {
        val typedArgs = args
        val matcherClass: KClass<*> = cls ?: Matchers::class
        val matcher = try {
            try {
                invokeStaticMethod(matcherClass.java, name, *typedArgs)
            } catch (e: NoSuchMethodException) {
                val converted = coerceFactoryArguments(matcherClass.java, typedArgs)
                    ?: throw InvalidSelectorException("${matcherClass.qualifiedName} does not implement any $name " +
                        "methods that accept ${typedArgs.contentToString()} argument(s)", e)
                invokeStaticMethod(matcherClass.java, name, *converted)
            }
        } catch (e: InvocationTargetException) {
            throw (e.cause as? AppiumException ?: InvalidSelectorException("Could not create matcher '$name'", e.cause ?: e))
        }
        if (matcher !is Matcher<*>) {
            throw InvalidArgumentException("'${this}' does not return a Matcher when invoked. " +
                    "Found '${matcher?.let { it::class.qualifiedName } ?: "null"}'")
        }
        return ScopedMatcher(matcher, scope?.invoke()?.matcher)
    }

    /** Reflection permits widening primitives but not JSON Double -> float or String -> enum. */
    private fun coerceFactoryArguments(factory: Class<*>, values: Array<out Any?>): Array<Any?>? {
        val candidates = factory.methods.filter {
            it.name == name && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                !it.isVarArgs && it.parameterCount == values.size
        }.mapNotNull { method ->
            val converted = arrayOfNulls<Any?>(values.size)
            for (index in values.indices) {
                val value = values[index]
                val type = method.parameterTypes[index]
                converted[index] = when {
                    ClassUtils.isAssignable(value?.javaClass, type, true) -> value
                    value is String && type == Class::class.java -> {
                        val className = value.removeSuffix(CLASS_SUFFIX)
                        try { Class.forName(className) }
                        catch (_: ClassNotFoundException) {
                            try { Class.forName("java.lang.$className") }
                            catch (_: ClassNotFoundException) { return@mapNotNull null }
                        }
                    }
                    value is Double && (type == Float::class.javaPrimitiveType || type == Float::class.javaObjectType) &&
                        value.isFinite() && value.toFloat().isFinite() -> value.toFloat()
                    value is String && type.isEnum -> type.enumConstants?.firstOrNull { (it as Enum<*>).name == value }
                        ?: return@mapNotNull null
                    else -> return@mapNotNull null
                }
            }
            converted
        }
        return candidates.singleOrNull()
    }

    class HamcrestMatcherDeserializer : JsonDeserializer<HamcrestMatcher> {
        @Throws(JsonParseException::class)
        override fun deserialize(json: JsonElement, paramType: Type?,
                                 paramJsonDeserializationContext: JsonDeserializationContext?): HamcrestMatcher {
            if (json.isJsonObject) {
                val jsonObj = json.asJsonObject

                // Validate and parse the name property
                val nameProp = jsonObj.get("name") ?: throw JsonParseException("'name' property is mandatory")
                val name = requireValidString("name", nameProp)

                val scope = jsonObj.get("scope")?.let {
                    if (!it.isJsonObject) {
                        throw JsonParseException("'scope' value must be a JSON object")
                    }
                    deserialize(it.asJsonObject, null, null)
                }

                // Parse args property
                val listOfArgs = GsonParserHelpers.asArray(jsonObj, "args")
                val args = arrayListOf<Any?>()
                for (arg in listOfArgs) {
                    when {
                        arg.isJsonPrimitive -> args.add(GsonParserHelpers.parsePrimitive(arg.asJsonPrimitive))
                        arg.isJsonNull -> args.add(null)
                        arg.isJsonObject -> args.add(
                            deserialize(arg, null, null).invoke().matcher
                        )
                        else -> throw JsonParseException("Matcher arguments must be primitives, null, or nested matcher objects")
                    }
                }

                // Parse the 'class' property
                val cls = jsonObj.get("class")?.let {
                    val className = requireValidString("class", it)
                    // Try fully casting class as fully qualified name
                    try {
                        return@let Class.forName(className).kotlin
                    } catch (cnfe: ClassNotFoundException) { }
                    // If above didn't work, try prepending 'androidx.test.espresso.matcher' package name
                    for (prefix in listOf("androidx.test.espresso.matcher", "io.appium.espressoserver.lib.llm")) {
                        try { return@let Class.forName("$prefix.$className").kotlin }
                        catch (_: ClassNotFoundException) { }
                    }
                    throw JsonParseException("No such class found '$className'")
                }

                return HamcrestMatcher(name, args.toTypedArray(), cls, scope)
            } else if (json.isJsonPrimitive) {
                // If it's just a primitive, return that as the name and no args
                return HamcrestMatcher(requireValidString(json.asString, json), emptyArray())
            }

            throw JsonParseException("Must be a valid JSON object with a required 'name' property " +
                    "plus optional 'args', 'class' and 'scope' properties. Got '$json' instead")
        }
    }
}

fun requireValidString(name: String, json: JsonElement): String {
    require(json.isJsonPrimitive && json.asJsonPrimitive.isString && json.asString.isNotBlank()) {
        throw JsonParseException("'$name' must be a valid string. Found '$json'")
    }
    return json.asString
}
