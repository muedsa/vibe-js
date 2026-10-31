package com.muedsa.js.runtime

import com.muedsa.js.ast.ArrayLiteral
import com.muedsa.js.ast.ArrowFunction
import com.muedsa.js.ast.AssignmentExpr
import com.muedsa.js.ast.BinaryOp
import com.muedsa.js.ast.BigIntLiteral
import com.muedsa.js.ast.BlockStatement
import com.muedsa.js.ast.BooleanLiteral
import com.muedsa.js.ast.BreakStatement
import com.muedsa.js.ast.CallExpr
import com.muedsa.js.ast.ClassDeclaration
import com.muedsa.js.ast.ClassExpression
import com.muedsa.js.ast.ClassMember
import com.muedsa.js.ast.ClassMemberKind
import com.muedsa.js.ast.CoalesceExpr
import com.muedsa.js.ast.ConditionalExpr
import com.muedsa.js.ast.ContinueStatement
import com.muedsa.js.ast.DebuggerStatement
import com.muedsa.js.ast.DeclarationKind
import com.muedsa.js.ast.DoWhileStatement
import com.muedsa.js.ast.EmptyStatement
import com.muedsa.js.ast.Expression
import com.muedsa.js.ast.ExpressionStatement
import com.muedsa.js.ast.ForInStatement
import com.muedsa.js.ast.ForOfStatement
import com.muedsa.js.ast.ForStatement
import com.muedsa.js.ast.FunctionDeclaration
import com.muedsa.js.ast.FunctionExpression
import com.muedsa.js.ast.Identifier
import com.muedsa.js.ast.IfStatement
import com.muedsa.js.ast.LabeledStatement
import com.muedsa.js.ast.MemberExpr
import com.muedsa.js.ast.NewExpr
import com.muedsa.js.ast.NewTargetExpr
import com.muedsa.js.ast.NullLiteral
import com.muedsa.js.ast.NumberLiteral
import com.muedsa.js.ast.ObjectLiteral
import com.muedsa.js.ast.ObjectProperty
import com.muedsa.js.ast.ObjectSpreadProperty
import com.muedsa.js.ast.OptionalChainCall
import com.muedsa.js.ast.OptionalChainExpr
import com.muedsa.js.ast.OptionalChainMember
import com.muedsa.js.ast.Parameter
import com.muedsa.js.ast.Program
import com.muedsa.js.ast.PropertyKind
import com.muedsa.js.ast.RegExpLiteral
import com.muedsa.js.ast.ReturnStatement
import com.muedsa.js.ast.SequenceExpr
import com.muedsa.js.ast.SpreadElement
import com.muedsa.js.ast.Statement
import com.muedsa.js.ast.StringLiteral
import com.muedsa.js.ast.SuperExpr
import com.muedsa.js.ast.SwitchStatement
import com.muedsa.js.ast.TaggedTemplate
import com.muedsa.js.ast.TemplateLiteral
import com.muedsa.js.ast.ThisExpr
import com.muedsa.js.ast.ThrowStatement
import com.muedsa.js.ast.TryStatement
import com.muedsa.js.ast.UnaryOp
import com.muedsa.js.ast.UpdateExpr
import com.muedsa.js.ast.VarDeclaration
import com.muedsa.js.ast.WhileStatement
import com.muedsa.js.ast.WithStatement
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.SourceRange
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.StackFrame
import com.muedsa.js.runtime.regex.JsRegex
import com.muedsa.js.runtime.regex.JsRegexSyntax
import com.muedsa.js.runtime.regex.JsRegexEngine
import com.muedsa.js.runtime.regex.KotlinJsRegex
import com.muedsa.js.runtime.value.InstanceField
import com.muedsa.js.runtime.value.JSArguments
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSRegExp
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.JSWeakMap
import com.muedsa.js.runtime.value.JSWeakSet
import com.muedsa.js.runtime.value.NumberFormatting
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey
import com.muedsa.js.runtime.value.ThisBindingSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * AST 解释器（Executor 的第一实现）。
 *
 * 执行模型：
 * - 语句返回 [Completion]（Normal/Return/Break/Continue），throw 使用 [JSException]；
 * - 所有执行方法都是 suspend，以便后续接入 async/await 与 Promise；
 * - 变量查找走 [Environment]，全局 var/function 委托到全局对象属性；
 * - 属性读写集中在本类（getV/putV），内建对象通过公共方法复用同一套语义。
 *
 * 后续性能优化可在不改语义的前提下：
 * - 用槽位索引替换 Environment 的 Map 查找；
 * - 用编译后的指令/闭包替换 AST 遍历（实现同一个 Executor 接口）。
 */
class Interpreter(
    internal val context: EngineContext,
) : Executor, PropertyAccess {

    /** 类型转换集中实现。 */
    val conversions = Conversions(this)

    /** 运行时内建原型集合。 */
    val intrinsics: Intrinsics get() = context.intrinsics

    /** 调用栈（用于错误信息）。 */
    private val callStack = mutableListOf<StackFrame>()

    /** NamedEvaluation 的名称提示栈：匿名 class 求值前压入，供静态字段/块读取类名。 */
    private val classNameHints = ArrayList<String>()

    /** 当前执行上下文；运行脚本前为 null。 */
    private var frame: ExecutionContext? = null

    /** 是否处于原生构造器调用中（供 Date 等区分 new Date() 与 Date()）。 */
    internal var nativeConstructCall: Boolean = false

    private val currentFrame: ExecutionContext
        get() = frame ?: throw IllegalStateException("Execution frame is not initialized")

    // ================================================================
    // 公共 API
    // ================================================================

    override suspend fun run(program: Program, environment: Environment): JSValue =
        runProgram(program, environment)

    /**
     * 执行整个脚本。
     *
     * @param program 程序根节点
     * @param environment 全局词法环境
     */
    suspend fun runProgram(program: Program, environment: Environment): JSValue {
        val previousFrame = frame
        frame = ExecutionContext(
            lexicalEnv = environment,
            thisValue = context.globalObject,
            strict = program.strict,
        )
        pushStackFrame("<global>")
        try {
            hoistDeclarations(environment, program.functionDeclarations, program.varDeclarations)
            predeclareLexical(program.body, environment)
            val completion = executeStatements(program.body)
            return when (completion) {
                is Completion.Normal -> completion.value
                is Completion.Return -> completion.value
                is Completion.Break, is Completion.Continue ->
                    throw syntaxError("Illegal break/continue statement")
            }
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /** ToBoolean。 */
    fun toBoolean(value: JSValue): Boolean = conversions.toBoolean(value)

    /** 当前时间（毫秒），来自宿主注入的 Clock。 */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun currentTimeMillis(): Double = context.host.clock.now().toEpochMilliseconds().toDouble()

    /**
     * 求值一个独立表达式（供 JSON.parse 等内建复用）。
     *
     * 如果当前没有执行上下文（极少见），会临时创建一个全局上下文。
     */
    suspend fun evaluateStandalone(expression: Expression): JSValue {
        if (frame == null) {
            frame = ExecutionContext(
                lexicalEnv = context.globalEnvironment,
                thisValue = context.globalObject,
                strict = false,
            )
        }
        return evaluate(expression)
    }

    /**
     * 执行 eval 源码。
     *
     * 当前实现按“直接 eval”处理：共享调用方的词法环境与 this，
     * 并继承调用方或 eval 源码自身的严格模式。
     */
    suspend fun evaluateEvalSource(source: String): JSValue {
        val previousFrame = frame
        val caller = previousFrame ?: ExecutionContext(
            lexicalEnv = context.globalEnvironment,
            thisValue = context.globalObject,
            strict = false,
        )
        val callerFunction = caller.function
        // 直接 eval 的代码继承调用方的严格模式与 super 语法上下文
        val program = try {
            Parser(
                Lexer(source).tokenize(),
                strictContext = caller.strict,
                superPropertyContext = callerFunction?.homeObject != null ||
                    callerFunction?.superBase != null,
                superCallContext = callerFunction?.let {
                    it.isClassConstructor && it.superConstructor != null
                } == true,
                newTargetContext = callerFunction != null && callerFunction.allowsNewTarget,
                // 字段初始化器/静态块（含其箭头函数）内的直接 eval 同样禁止 arguments
                argumentsForbiddenContext = callerFunction?.forbidsArguments == true,
            ).parseProgram()
        } catch (error: ParseException) {
            syntaxError(error.message ?: "Invalid eval code")
        } catch (error: LexerException) {
            syntaxError(error.message ?: "Invalid eval code")
        }

        val evalStrict = caller.strict || program.strict
        // 参数默认值中的直接 eval：var 声明与尚未初始化的参数绑定冲突属于 SyntaxError
        if (!evalStrict) {
            val callerDeclarative = caller.lexicalEnv as? DeclarativeEnvironment
            if (callerDeclarative != null) {
                val varNames = program.varDeclarations +
                    program.functionDeclarations.map { it.name }
                for (name in varNames) {
                    if (callerDeclarative.hasUninitializedLocal(name)) {
                        throw JSException(
                            JSError("SyntaxError", "Identifier '$name' has already been declared")
                        )
                    }
                }
                checkEvalVarNameConflicts(caller.lexicalEnv, varNames)
            }
        }
        // 直接 eval 始终拥有独立词法环境；sloppy 的 var/function 提升到调用方 var 作用域
        val lexicalEnvironment = DeclarativeEnvironment(parent = caller.lexicalEnv)
        val varEnvironment = if (evalStrict) {
            DeclarativeEnvironment(parent = lexicalEnvironment, isVarScope = true)
        } else {
            caller.lexicalEnv.findVarScope()
        }
        val executionEnvironment = if (evalStrict) varEnvironment else lexicalEnvironment
        frame = ExecutionContext(
            lexicalEnv = executionEnvironment,
            thisValue = caller.thisValue,
            strict = evalStrict,
            function = callerFunction,
            newTarget = caller.newTarget,
        )
        return try {
            hoistDeclarations(
                varEnvironment,
                program.functionDeclarations,
                program.varDeclarations,
            )
            predeclareLexical(program.body, executionEnvironment)
            when (val completion = executeStatements(program.body)) {
                is Completion.Normal -> completion.value
                is Completion.Return -> completion.value
                is Completion.Break, is Completion.Continue ->
                    throw syntaxError("Illegal break/continue statement in eval code")
            }
        } finally {
            frame = previousFrame
        }
    }

    /** var/function 声明与词法绑定冲突（EvalDeclarationInstantiation）。 */
    private fun checkEvalVarNameConflicts(lexicalEnvironment: Environment, names: List<String>) {
        for (name in names) {
            var environment: Environment? = lexicalEnvironment
            while (environment != null) {
                if (environment is DeclarativeEnvironment && environment.hasOwnLexicalBinding(name)) {
                    throw JSException(
                        JSError("SyntaxError", "Identifier '$name' has already been declared")
                    )
                }
                if (environment.isVarScope) break
                environment = environment.parent
            }
        }
    }

    /**
     * 间接 eval：代码在全局环境中求值，且不继承调用方严格模式。
     */
    suspend fun evaluateIndirectEval(source: String): JSValue {
        val program = try {
            Parser(Lexer(source).tokenize()).parseProgram()
        } catch (error: ParseException) {
            syntaxError(error.message ?: "Invalid eval code")
        } catch (error: LexerException) {
            syntaxError(error.message ?: "Invalid eval code")
        }
        val previousFrame = frame
        val evalStrict = program.strict
        if (!evalStrict) {
            val varNames = program.varDeclarations +
                program.functionDeclarations.map { it.name }
            checkEvalVarNameConflicts(context.globalEnvironment, varNames)
        }
        // 间接 eval 同样拥有独立词法环境；sloppy 的 var/function 写入全局 var 作用域
        val lexicalEnvironment = DeclarativeEnvironment(parent = context.globalEnvironment)
        val varEnvironment = if (evalStrict) {
            DeclarativeEnvironment(parent = lexicalEnvironment, isVarScope = true)
        } else {
            context.globalEnvironment.findVarScope()
        }
        val executionEnvironment = if (evalStrict) varEnvironment else lexicalEnvironment
        frame = ExecutionContext(
            lexicalEnv = executionEnvironment,
            thisValue = context.globalObject,
            strict = evalStrict,
        )
        pushStackFrame("<eval>")
        return try {
            hoistDeclarations(
                varEnvironment,
                program.functionDeclarations,
                program.varDeclarations,
            )
            predeclareLexical(program.body, executionEnvironment)
            when (val completion = executeStatements(program.body)) {
                is Completion.Normal -> completion.value
                is Completion.Return -> completion.value
                is Completion.Break, is Completion.Continue ->
                    throw syntaxError("Illegal break/continue statement in eval code")
            }
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /** ToNumber。 */
    suspend fun toNumber(value: JSValue): Double = conversions.toNumber(value)

    /** ToString（避免与 Any.toString 混淆，命名为 toStringValue）。 */
    suspend fun toStringValue(value: JSValue): String = conversions.toString(value)

    /** ToPropertyKey。 */
    suspend fun toPropertyKey(value: JSValue): PropertyKey = conversions.toPropertyKey(value)

    /** ToObject。 */
    suspend fun toObject(value: JSValue): JSObject = conversions.toObject(value)

    /** ToInt32。 */
    suspend fun toInt32(value: JSValue): Int = conversions.toInt32(value)

    /** ToUint32。 */
    suspend fun toUint32(value: JSValue): Long = conversions.toUint32(value)

    /** 严格相等。 */
    fun strictEquals(a: JSValue, b: JSValue): Boolean = conversions.strictEquals(a, b)

    /** 抽象相等。 */
    suspend fun abstractEquals(a: JSValue, b: JSValue): Boolean = conversions.abstractEquals(a, b)

    /** 是否为可调用对象。 */
    fun isCallable(value: JSValue): Boolean = value is JSFunction || value is JSNativeFunction

    /**
     * SpeciesConstructor(O, defaultConstructor)。
     *
     * 读取 O.constructor，再取其 @@species；undefined/null 时回退默认构造器。
     */
    suspend fun speciesConstructor(objectValue: JSObject, defaultConstructor: JSValue): JSValue {
        val constructor = getV(objectValue, PropertyKey.CONSTRUCTOR)
        if (constructor is JSUndefined) return defaultConstructor
        if (constructor !is JSObject) {
            throw typeError("Species constructor is not an object")
        }
        val species = getV(constructor, PropertyKey.Sym(intrinsics.symbolSpecies))
        if (species is JSUndefined || species is JSNull) return defaultConstructor
        if (!isConstructor(species)) {
            throw typeError("Species is not a constructor")
        }
        return species
    }

    /** IsRegExp：优先读取 @@match，未定义时按内建正则对象判断。 */
    suspend fun isRegExp(value: JSValue): Boolean {
        if (value !is JSObject) return false
        val matcher = getV(value, PropertyKey.Sym(intrinsics.symbolMatch))
        if (matcher !is JSUndefined) return toBoolean(matcher)
        return value is JSRegExp
    }

    /** typeof 结果。 */
    fun typeOf(value: JSValue): String = when (value) {
        is JSUndefined -> "undefined"
        is JSNull -> "object"
        is JSBoolean -> "boolean"
        is JSNumber -> "number"
        is JSString -> "string"
        is JSSymbol -> "symbol"
        is JSFunction, is JSNativeFunction -> "function"
        is JSObject -> "object"
    }

    // ================================================================
    // 属性访问
    // ================================================================

    /**
     * 读取属性（支持对象、原始值包装与原型链）。
     */
    suspend fun getV(base: JSValue, key: PropertyKey): JSValue = when (base) {
        is JSObject -> getPropertyFrom(base, key, base)

        is JSString -> {
            if (key == PropertyKey.LENGTH) {
                JSNumber(base.value.length.toDouble())
            } else {
                val index = canonicalIndex(key)
                if (index != null && index >= 0 && index < base.value.length.toLong()) {
                    JSString(base.value[index.toInt()].toString())
                } else {
                    getPropertyFrom(intrinsics.stringPrototype, key, base)
                }
            }
        }

        is JSNumber -> getPropertyFrom(intrinsics.numberPrototype, key, base)
        is JSBoolean -> getPropertyFrom(intrinsics.booleanPrototype, key, base)
        is JSSymbol -> getPropertyFrom(intrinsics.symbolPrototype, key, base)
        is JSNull -> throw typeError("Cannot read properties of null (reading '${key}')")
        is JSUndefined -> throw typeError("Cannot read properties of undefined (reading '${key}')")
    }

    /**
     * 写入属性（支持 setter、只读属性与原型链）。
     */
    suspend fun putV(base: JSValue, key: PropertyKey, value: JSValue, strict: Boolean) {
        if (base !is JSObject) {
            if (strict) {
                throw typeError("Cannot create property '${key}' on ${typeOf(base)}")
            }
            return
        }
        putProperty(base, key, value, strict)
    }

    /**
     * [[DefineOwnProperty]] 的挂起版本。
     *
     * 数组 length 的赋值需要 ToNumber/ToUint32（可能调用 valueOf/toString），
     * 因此不能放在非挂起的 JSArray.defineOwnProperty 中完成。
     */
    suspend fun defineOwnPropertyOn(
        objectValue: JSObject,
        key: PropertyKey,
        descriptor: PropertyDescriptor,
    ): Boolean {
        if (objectValue is JSArguments) {
            // ES5 10.6：映射索引定义数据属性时同步形参；不可写或改为访问器时解除映射
            val mapping = objectValue.parameterMapping(key)
            if (mapping != null) {
                if (descriptor.isAccessor) {
                    objectValue.unmapParameter(key)
                } else {
                    descriptor.value?.let { mapping.write(it) }
                    if (!descriptor.writable) objectValue.unmapParameter(key)
                }
            }
        }
        if (objectValue is JSArray && key == PropertyKey.LENGTH) {
            if (descriptor.isAccessor) return false
            // length 恒为不可枚举、不可配置
            if (descriptor.enumerable || descriptor.configurable) return false
            val value = descriptor.value
            val newLength = if (value == null) {
                objectValue.lengthLong
            } else {
                val number = toNumber(value)
                if (number.isNaN() || number < 0.0 || number > JSArray.MAX_LENGTH.toDouble() ||
                    number != kotlin.math.floor(number)
                ) {
                    throw rangeError("Invalid array length")
                }
                number.toLong()
            }
            return objectValue.defineLength(newLength, descriptor.writable)
        }
        return objectValue.defineOwnProperty(key, descriptor)
    }

    /** [PropertyAccess] 实现：对象属性读取。 */
    override suspend fun getProperty(base: JSObject, key: PropertyKey): JSValue =
        getPropertyFrom(base, key, base)

    /** [PropertyAccess] 实现：对象属性写入。 */
    override suspend fun setProperty(base: JSObject, key: PropertyKey, value: JSValue, strict: Boolean) {
        putProperty(base, key, value, strict)
    }

    /** [PropertyAccess] 实现：属性存在性判断。 */
    override fun hasProperty(base: JSObject, key: PropertyKey): Boolean = base.hasProperty(key)

    override fun isStrictContext(): Boolean = frame?.strict ?: false

    /** 沿原型链读取属性，访问器使用 receiver 作为 this。 */
    private suspend fun getPropertyFrom(
        objectValue: JSObject?,
        key: PropertyKey,
        receiver: JSValue,
    ): JSValue {
        var current: JSObject? = objectValue
        while (current != null) {
            val descriptor = current.getOwnProperty(key)
            if (descriptor != null) {
                if (descriptor.isAccessor) {
                    val getter = descriptor.get
                    if (getter == null || !isCallable(getter)) return JSUndefined
                    return callFunction(getter, receiver, emptyList())
                }
                return descriptor.value ?: JSUndefined
            }
            current = current.prototype
        }
        return JSUndefined
    }

    /** 写入属性，遵循 setter / 只读 / 原型链规则。 */
    private suspend fun putProperty(
        objectValue: JSObject,
        key: PropertyKey,
        value: JSValue,
        strict: Boolean,
    ) {
        var current: JSObject? = objectValue
        while (current != null) {
            val descriptor = current.getOwnProperty(key)
            if (descriptor != null) {
                if (descriptor.isAccessor) {
                    val setter = descriptor.set
                    if (setter == null || !isCallable(setter)) {
                        if (strict) throw typeError("Cannot set property '${key}' which has only a getter")
                        return
                    }
                    callFunction(setter, objectValue, listOf(value))
                    return
                }
                if (!descriptor.writable) {
                    if (strict) throw typeError("Cannot assign to read only property '${key}'")
                    return
                }
                if (current === objectValue) {
                    val updated = defineOwnPropertyOn(current, key,
                        PropertyDescriptor.data(
                            value = value,
                            writable = true,
                            enumerable = descriptor.enumerable,
                            configurable = descriptor.configurable,
                        )
                    )
                    if (!updated && strict) {
                        throw typeError("Cannot assign to read only property '${key}'")
                    }
                    return
                }
                break
            }
            current = current.prototype
        }
        val created = defineOwnPropertyOn(
            objectValue,
            key,
            PropertyDescriptor.data(value, writable = true, enumerable = true, configurable = true),
        )
        if (!created && strict) {
            throw typeError("Cannot add property '${key}', object is not extensible")
        }
    }

    // ================================================================
    // 函数调用与构造
    // ================================================================

    /**
     * 调用函数（用户函数或原生函数）。
     */
    suspend fun callFunction(
        callee: JSValue,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSValue {
        return when (callee) {
            is JSFunction -> callUserFunction(callee, thisValue, args, callRange)
            is JSNativeFunction -> {
                pushStackFrame(callee.name, callRange)
                try {
                    callee.function(this, thisValue, args)
                } finally {
                    popStackFrame()
                }
            }

            else -> throw typeError("${describeValue(callee)} is not a function")
        }
    }

    /**
     * 构造实例（new）。
     */
    suspend fun construct(constructor: JSValue, args: List<JSValue>): JSValue {
        return when (constructor) {
            is JSFunction -> constructUserFunction(constructor, args)
            is JSNativeFunction -> {
                val customConstruct = constructor.constructFunction
                if (customConstruct != null) {
                    pushStackFrame(constructor.name)
                    val previousConstructCall = nativeConstructCall
                    nativeConstructCall = true
                    try {
                        customConstruct(this, args)
                    } finally {
                        nativeConstructCall = previousConstructCall
                        popStackFrame()
                    }
                } else {
                    // 内建方法（无 [[Construct]] 原型对象）不能被 new
                    if (constructor.functionPrototypeObject == null) {
                        throw typeError("${constructor.name.ifEmpty { "anonymous" }} is not a constructor")
                    }
                    pushStackFrame(constructor.name)
                    val previousConstructCall = nativeConstructCall
                    nativeConstructCall = true
                    try {
                        val result = constructor.function(this, JSUndefined, args)
                        if (result is JSObject) {
                            result
                        } else {
                            wrapPrimitive(result)
                        }
                    } finally {
                        nativeConstructCall = previousConstructCall
                        popStackFrame()
                    }
                }
            }

            else -> throw typeError("${describeValue(constructor)} is not a constructor")
        }
    }

    /**
     * 把原始值包装为对象（new String/Number/Boolean/Symbol 与 Object(primitive)）。
     */
    fun wrapPrimitive(value: JSValue): JSObject {
        val prototype = when (value) {
            is JSString -> intrinsics.stringPrototype
            is JSNumber -> intrinsics.numberPrototype
            is JSBoolean -> intrinsics.booleanPrototype
            is JSSymbol -> intrinsics.symbolPrototype
            else -> intrinsics.objectPrototype
        }
        val wrapper = JSBoxedPrimitive(value, prototype)
        if (value is JSString) {
            wrapper.defineDataProperty(
                key = PropertyKey.LENGTH,
                value = JSNumber(value.value.length.toDouble()),
                writable = false,
                enumerable = false,
                configurable = false,
            )
            value.value.forEachIndexed { index, c ->
                wrapper.defineDataProperty(
                    key = PropertyKey.Str(index.toString()),
                    value = JSString(c.toString()),
                    writable = false,
                    enumerable = true,
                    configurable = false,
                )
            }
        }
        return wrapper
    }

    /** 调用用户函数。 */
    private suspend fun callUserFunction(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSValue {
        if (function.isClassConstructor) {
            throw typeError("Class constructor cannot be invoked without 'new'")
        }

        val effectiveThis = if (function.isArrow) {
            // this 绑定来源由词法环境提供（派生构造器中 super() 前访问会抛 ReferenceError）
            val source = function.lexicalThisSource
            source?.boundThis ?: function.lexicalThis ?: JSUndefined
        } else if (function.strict) {
            thisValue
        } else if (thisValue is JSUndefined || thisValue is JSNull) {
            context.globalObject
        } else if (thisValue is JSObject) {
            thisValue
        } else {
            // ES5 10.4.3：非严格函数收到原始值 thisArg 时先装箱
            wrapPrimitive(thisValue)
        }

        if (function.isAsync) {
            return callAsyncFunction(function, effectiveThis, args)
        }
        return executeUserFunction(function, effectiveThis, args, callRange)
    }

    /**
     * 调用 async 函数：立即返回 Promise，函数体在事件循环中执行。
     */
    private fun callAsyncFunction(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
    ): JSValue {
        val promise = JSPromise(intrinsics.promisePrototype)
        // 每次 async 调用使用独立解释器实例：
        // 协程挂起期间主解释器的 frame 会被外层脚本覆盖，独立实例可避免恢复时上下文错乱。
        val asyncInterpreter = Interpreter(context)
        context.eventLoop.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = asyncInterpreter.executeUserFunction(function, thisValue, args)
                PromiseSupport.resolve(this@Interpreter, promise, result)
            } catch (error: JSException) {
                PromiseSupport.reject(this@Interpreter, promise, error.value)
            } catch (error: Throwable) {
                PromiseSupport.reject(
                    this@Interpreter,
                    promise,
                    JSError(
                        "InternalError",
                        error.message ?: error::class.simpleName ?: "Internal error",
                        intrinsics.objectPrototype,
                    ),
                )
            }
        }
        return promise
    }

    /** 在给定 this 下执行函数体（普通函数 / 箭头函数共用）。 */
    private suspend fun executeUserFunction(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSValue {
        val callEnv = DeclarativeEnvironment(parent = function.closure, isVarScope = true)
        val previousFrame = frame
        frame = ExecutionContext(
            callEnv,
            thisValue,
            function.strict,
            function,
            newTarget = if (function.isArrow) function.lexicalNewTarget else null,
            // 箭头函数继承 this 绑定的初始化状态
            thisInitialized = function.lexicalThisSource?.thisBound ?: true,
        )
        pushStackFrame(function.name.ifEmpty { "anonymous" }, callRange)
        try {
            if (!function.isArrow) {
                // 规范 9.2.10：形参名重复时不建立 arguments 映射
                val parameterNames = function.params.mapNotNull { it.name }
                val mapped = !function.strict && function.params.all {
                    it.name != null && !it.rest && it.defaultValue == null && it.pattern == null
                } && parameterNames.distinct().size == function.params.size
                callEnv.createVarBinding(
                    "arguments",
                    createArgumentsObject(
                        args = args,
                        function = function,
                        mappedEnvironment = if (mapped) callEnv else null,
                        mappedParams = if (mapped) function.params else emptyList(),
                    ),
                )
            }
            bindParameters(function, args, callEnv)
            hoistDeclarations(callEnv, function.functionDeclarations, function.varDeclarations)

            val expressionBody = function.expressionBody
            if (expressionBody != null) {
                // 字段初始化器按字段名做 NamedEvaluation（匿名 class 需在静态初始化前带名）
                val hint = function.namedEvaluationHint
                return if (hint != null) evaluateNamed(expressionBody, hint) else evaluate(expressionBody)
            }

            val body = function.body ?: return JSUndefined
            predeclareLexical(body.statements, callEnv)
            val completion = executeStatements(body.statements)
            return when (completion) {
                is Completion.Normal -> JSUndefined
                is Completion.Return -> completion.value
                is Completion.Break, is Completion.Continue ->
                    throw syntaxError("Illegal break/continue statement")
            }
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /** 绑定函数参数（默认值 / rest）。 */
    private suspend fun bindParameters(
        function: JSFunction,
        args: List<JSValue>,
        environment: Environment,
    ) {
        // 参数 TDZ：先声明全部参数名为未初始化绑定，默认值只能引用已初始化的前置参数
        val declarative = environment as? DeclarativeEnvironment
        if (declarative != null) {
            val parameterNames = mutableListOf<String>()
            for (param in function.params) {
                if (param.name != null) {
                    parameterNames.add(param.name)
                } else {
                    param.pattern?.let { collectPatternNames(it, parameterNames) }
                }
            }
            for (name in parameterNames.toSet()) declarative.declareUninitialized(name)
        }
        var index = 0
        for (param in function.params) {
            if (param.rest) {
                val restValues = ArrayList<JSValue?>()
                while (index < args.size) {
                    restValues.add(args[index])
                    index++
                }
                val restArray = JSArray(restValues, prototype = intrinsics.arrayPrototype)
                val restName = param.name
                if (restName != null) {
                    environment.createVarBinding(restName, restArray)
                } else {
                    val pattern = param.pattern
                        ?: throw syntaxError("Invalid rest parameter")
                    bindPattern(pattern, restArray, DeclarationKind.LET, environment)
                }
            } else {
                var value = args.getOrNull(index) ?: JSUndefined
                index++
                if (value is JSUndefined && param.defaultValue != null) {
                    val defaultValue = param.defaultValue
                    value = evaluate(defaultValue)
                    param.name?.let { applyInferredFunctionName(defaultValue, value, it) }
                }
                val name = param.name
                if (name != null) {
                    environment.createVarBinding(name, value)
                } else {
                    param.pattern?.let {
                        bindPattern(it, value, DeclarationKind.LET, environment)
                    }
                }
            }
        }
    }

    /** 构造用户函数实例。 */
    private suspend fun constructUserFunction(function: JSFunction, args: List<JSValue>): JSValue {
        if (function.isArrow || function.isMethod || function.isAsync) {
            throw typeError("${function.name.ifEmpty { "anonymous" }} is not a constructor")
        }
        // 必须在构造时读取当前的 F.prototype，而不是创建函数时捕获的对象
        val prototypeValue = getV(function, PropertyKey.PROTOTYPE)
        val instancePrototype = prototypeValue as? JSObject ?: intrinsics.objectPrototype
        val instance = JSObject(prototype = instancePrototype)

        val derived = function.isClassConstructor && function.superConstructor != null
        val callEnv = DeclarativeEnvironment(parent = function.closure, isVarScope = true)
        val previousFrame = frame
        frame = ExecutionContext(
            callEnv,
            instance,
            function.strict,
            function,
            newTarget = function,
            thisInitialized = !derived,
        )
        pushStackFrame(function.name.ifEmpty { "anonymous" })
        val activeFrame = frame!!
        try {
            callEnv.createVarBinding("arguments", createArgumentsObject(args, function))
            bindParameters(function, args, callEnv)
            hoistDeclarations(callEnv, function.functionDeclarations, function.varDeclarations)

            // 基类构造器：this 已初始化，先按源码顺序初始化实例字段
            if (!derived) {
                initializeInstanceFields(function, activeFrame.thisValue)
            }

            // 默认派生类构造器：隐式调用父构造器（动态读取当前 [[Prototype]]）
            if (function.isDefaultDerivedConstructor) {
                val superConstructorValue: JSValue = function.prototype ?: intrinsics.functionPrototype
                if (!isConstructor(superConstructorValue)) {
                    throw typeError("Super constructor is not a constructor")
                }
                val result = callSuperConstructor(superConstructorValue, activeFrame.thisValue, args)
                activeFrame.thisValue = result
                activeFrame.thisInitialized = true
                // super() 返回后初始化本类的实例字段
                initializeInstanceFields(function, activeFrame.thisValue)
            }

            val body = function.body
            if (body == null) {
                if (!activeFrame.thisInitialized) {
                    throw referenceError("Must call super constructor before returning from derived constructor")
                }
                return activeFrame.thisValue
            }
            val completion = executeStatements(body.statements)
            if (completion is Completion.Return) {
                val returned = completion.value
                if (returned is JSObject) return returned
                if (derived && returned !is JSUndefined) {
                    throw typeError("Derived constructors may only return object or undefined")
                }
            }
            if (!activeFrame.thisInitialized) {
                throw referenceError("Must call super constructor before returning from derived constructor")
            }
            return activeFrame.thisValue
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /** 创建用户函数对象（自动创建 .prototype 对象；箭头函数没有 prototype）。 */
    fun createFunction(
        name: String,
        params: List<Parameter>,
        body: BlockStatement?,
        closure: Environment,
        strict: Boolean,
        varDeclarations: List<String> = emptyList(),
        functionDeclarations: List<FunctionDeclaration> = emptyList(),
        expressionBody: Expression? = null,
        isArrow: Boolean = false,
        isAsync: Boolean = false,
        isMethod: Boolean = false,
        lexicalThis: JSValue? = null,
        lexicalThisSource: ThisBindingSource? = null,
        lexicalFunction: JSFunction? = null,
        lexicalNewTarget: JSValue? = null,
        allowsNewTarget: Boolean = true,
        forbidsArguments: Boolean = false,
        namedEvaluationHint: String? = null,
        isClassConstructor: Boolean = false,
        isDefaultDerivedConstructor: Boolean = false,
        homeObject: JSObject? = null,
        superConstructor: JSValue? = null,
        superBase: JSObject? = null,
        functionPrototypeObject: JSObject? = null,
    ): JSFunction {
        // async 函数不是构造器，没有 own "prototype" 属性
        val prototypeObject = if (isArrow || isMethod || isAsync) {
            null
        } else {
            functionPrototypeObject ?: JSObject(prototype = intrinsics.objectPrototype)
        }
        val function = JSFunction(
            name = name,
            params = params,
            body = body,
            closure = closure,
            strict = strict,
            // async 函数的 [[Prototype]] 是 %AsyncFunction.prototype%
            prototype = if (isAsync) intrinsics.asyncFunctionPrototype else intrinsics.functionPrototype,
            functionPrototypeObject = prototypeObject,
            varDeclarations = varDeclarations,
            functionDeclarations = functionDeclarations,
            expressionBody = expressionBody,
            isArrow = isArrow,
            isAsync = isAsync,
            isMethod = isMethod,
            lexicalThis = lexicalThis,
            lexicalThisSource = lexicalThisSource,
            lexicalFunction = lexicalFunction,
            lexicalNewTarget = lexicalNewTarget,
            allowsNewTarget = allowsNewTarget,
            forbidsArguments = forbidsArguments,
            namedEvaluationHint = namedEvaluationHint,
            isClassConstructor = isClassConstructor,
            isDefaultDerivedConstructor = isDefaultDerivedConstructor,
            homeObject = homeObject,
            superConstructor = superConstructor,
            superBase = superBase,
        )
        // ES5 15.3.5.4：非严格普通函数的 caller/arguments 是值为 null 的自有数据属性；
        // 严格函数、箭头、方法与 async 函数不定义（读取落到 Function.prototype 拒绝访问器）
        if (!strict && !isArrow && !isMethod && !isAsync) {
            function.defineDataProperty(
                PropertyKey.Str("caller"),
                JSUndefined,
                writable = false,
                enumerable = false,
                configurable = false,
            )
            function.defineDataProperty(
                PropertyKey.Str("arguments"),
                JSNull,
                writable = false,
                enumerable = false,
                configurable = false,
            )
        }
        return function
    }

    /** 创建 arguments 对象（类数组，非 Array 实例）。 */
    private fun createArgumentsObject(
        args: List<JSValue>,
        function: JSFunction?,
        mappedEnvironment: Environment? = null,
        mappedParams: List<Parameter> = emptyList(),
    ): JSObject {
        val arguments = JSArguments(prototype = intrinsics.objectPrototype)
        arguments.defineDataProperty(
            key = PropertyKey.LENGTH,
            value = JSNumber(args.size.toDouble()),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        args.forEachIndexed { index, value ->
            val parameterName = mappedParams.getOrNull(index)?.name
            if (mappedEnvironment != null && parameterName != null) {
                // sloppy 简单参数列表：arguments[index] 与形参双向绑定
                val getter = JSNativeFunction(
                    name = "get",
                    length = 0,
                    prototype = intrinsics.functionPrototype,
                ) { _, _, _ -> mappedEnvironment.getBinding(parameterName) ?: JSUndefined }
                val setter = JSNativeFunction(
                    name = "set",
                    length = 1,
                    prototype = intrinsics.functionPrototype,
                ) { _, _, setterArgs ->
                    mappedEnvironment.setBinding(
                        parameterName,
                        setterArgs.getOrElse(0) { JSUndefined },
                        strict = false,
                    )
                    JSUndefined
                }
                arguments.defineOwnProperty(
                    PropertyKey.Str(index.toString()),
                    PropertyDescriptor.accessor(
                        get = getter,
                        set = setter,
                        enumerable = true,
                        configurable = true,
                    ),
                )
                // 记录参数映射：Object.defineProperty 在同步索引值时需要直接读写形参
                arguments.mapParameter(
                    index.toLong(),
                    JSArguments.ParameterMapping(
                        read = { mappedEnvironment.getBinding(parameterName) ?: JSUndefined },
                        write = { newValue -> mappedEnvironment.setBinding(parameterName, newValue, strict = false) },
                    ),
                )
            } else {
                arguments.defineDataProperty(
                    key = PropertyKey.Str(index.toString()),
                    value = value,
                    writable = true,
                    enumerable = true,
                    configurable = true,
                )
            }
        }
        if (function != null) {
            if (function.strict) {
                // 严格模式下访问 callee/caller 必须抛 TypeError
                val thrower = JSNativeFunction(
                    name = "thrower",
                    length = 0,
                    prototype = intrinsics.functionPrototype,
                ) { interpreter, _, _ ->
                    interpreter.typeError("'caller', 'callee' and 'arguments' properties may not be accessed on strict mode functions")
                }
                arguments.defineOwnProperty(
                    PropertyKey.Str("callee"),
                    PropertyDescriptor.accessor(
                        get = thrower,
                        set = thrower,
                        enumerable = false,
                        configurable = false,
                    ),
                )
            } else {
                arguments.defineDataProperty(
                    key = PropertyKey.Str("callee"),
                    value = function,
                    writable = true,
                    enumerable = false,
                    configurable = true,
                )
            }
        }
        // ES2015：arguments 对象自有 @@iterator，复用 Array.prototype.values
        intrinsics.arrayValuesFunction?.let { valuesFunction ->
            arguments.defineDataProperty(
                key = PropertyKey.Sym(intrinsics.symbolIterator),
                value = valuesFunction,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
        return arguments
    }

    /**
     * 动态创建函数（Function 构造器）。
     *
     * 使用全局环境作为闭包，参数与函数体通过 Parser 校验。
     */
    fun createDynamicFunction(paramStrings: List<String>, body: String): JSFunction {
        val parameters = paramStrings.joinToString(",")
        val source = "(function($parameters) {\n$body\n})"
        val function = parseDynamicFunctionSource(source)
        return createFunction(
            name = function.name ?: "anonymous",
            params = function.params,
            body = function.body,
            closure = context.globalEnvironment,
            strict = function.strict,
            varDeclarations = function.varDeclarations,
            functionDeclarations = function.functionDeclarations,
            isAsync = function.isAsync,
        )
    }

    /**
     * 动态创建 async 函数（AsyncFunction 构造器）。
     *
     * 参数文本可能以行注释结尾，因此在 ')' 前保留换行；源码文本同时用于
     * Function.prototype.toString（动态函数按规范返回构造函数体源码）。
     */
    fun createDynamicAsyncFunction(paramStrings: List<String>, body: String): JSFunction {
        val parameters = paramStrings.joinToString(",")
        val source = "(async function anonymous($parameters\n) {\n$body\n})"
        val function = parseDynamicFunctionSource(source)
        val created = createFunction(
            name = function.name ?: "anonymous",
            params = function.params,
            body = function.body,
            closure = context.globalEnvironment,
            strict = function.strict,
            varDeclarations = function.varDeclarations,
            functionDeclarations = function.functionDeclarations,
            isAsync = true,
        )
        created.sourceText = source.substring(1, source.length - 1)
        return created
    }

    /** 解析动态函数源码并返回函数表达式节点。 */
    private fun parseDynamicFunctionSource(source: String): FunctionExpression {
        val expression = try {
            val program = Parser(Lexer(source).tokenize()).parseProgram()
            val statement = program.body.singleOrNull()
            if (statement !is ExpressionStatement) {
                throw syntaxError("Invalid dynamic function arguments")
            }
            statement.expression
        } catch (error: LexerException) {
            throw syntaxError(error.message ?: "Invalid dynamic function arguments")
        } catch (error: ParseException) {
            throw syntaxError(error.message ?: "Invalid dynamic function arguments")
        }
        return expression as? FunctionExpression
            ?: throw syntaxError("Invalid dynamic function arguments")
    }

    /**
     * 变量提升：函数声明优先，其次 var。
     */
    private fun hoistDeclarations(
        environment: Environment,
        functionDeclarations: List<FunctionDeclaration>,
        varDeclarations: List<String>,
    ) {
        val varScope = environment.findVarScope()
        for (declaration in functionDeclarations) {
            val function = createFunction(
                name = declaration.name,
                params = declaration.params,
                body = declaration.body,
                closure = environment,
                strict = declaration.strict,
                varDeclarations = declaration.varDeclarations,
                functionDeclarations = declaration.functionDeclarations,
                isAsync = declaration.isAsync,
            )
            varScope.createVarBinding(declaration.name, function)
        }
        for (name in varDeclarations) {
            varScope.ensureVarBinding(name)
        }
    }

    // ================================================================
    // 语句执行
    // ================================================================

    private suspend fun executeStatements(statements: List<Statement>): Completion {
        var lastValue: JSValue = JSUndefined
        for (statement in statements) {
            when (val completion = executeStatement(statement)) {
                is Completion.Normal -> {
                    // 空语句与声明语句产生 empty completion，不覆盖 eval 完成值
                    if (statement !is EmptyStatement && statement !is ClassDeclaration &&
                        statement !is FunctionDeclaration && statement !is VarDeclaration
                    ) {
                        lastValue = completion.value
                    }
                }
                // 规范：break/continue 携带所在语句列表已累积的完成值
                is Completion.Break -> return Completion.Break(completion.label, lastValue)
                is Completion.Continue -> return Completion.Continue(completion.label, lastValue)
                else -> return completion
            }
        }
        return Completion.Normal(lastValue)
    }

    private suspend fun executeStatement(statement: Statement): Completion {
        return when (statement) {
            is EmptyStatement -> Completion.Normal(JSUndefined)
            is BlockStatement -> executeBlock(
                statement.statements,
                DeclarativeEnvironment(parent = currentFrame.lexicalEnv),
            )

            is ExpressionStatement -> Completion.Normal(evaluate(statement.expression))
            is ClassDeclaration -> {
                val classValue = evaluateClass(statement.name, statement.superClass, statement.members)
                if (!initializeLexicalIfDeclared(currentFrame.lexicalEnv, statement.name, classValue)) {
                    currentFrame.lexicalEnv.createConstBinding(statement.name, classValue)
                }
                Completion.Normal(JSUndefined)
            }

            is VarDeclaration -> executeVarDeclaration(statement)
            is FunctionDeclaration -> Completion.Normal(JSUndefined) // 已提升
            is IfStatement -> executeIf(statement)
            is WhileStatement -> executeWhile(statement)
            is DoWhileStatement -> executeDoWhile(statement)
            is ForStatement -> executeFor(statement)
            is ForInStatement -> executeForIn(statement)
            is ForOfStatement -> executeForOf(statement)
            is LabeledStatement -> executeLabeled(statement)
            is DebuggerStatement -> Completion.Normal(JSUndefined)
            is WithStatement -> executeWith(statement)
            is SwitchStatement -> executeSwitch(statement)
            is BreakStatement -> Completion.Break(statement.label)
            is ContinueStatement -> Completion.Continue(statement.label)
            is ReturnStatement -> Completion.Return(
                statement.value?.let { evaluate(it) } ?: JSUndefined
            )

            is ThrowStatement -> throw JSException(
                value = evaluate(statement.argument),
                stack = callStack.toList(),
            )

            is TryStatement -> executeTry(statement)
        }
    }

    /** 在指定环境中执行语句列表，返回后恢复原环境。 */
    private suspend fun executeBlock(statements: List<Statement>, environment: Environment): Completion {
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = environment
        return try {
            predeclareLexical(statements, environment)
            executeStatements(statements)
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /**
     * 在进入作用域时预声明 let/const/class 绑定，实现暂时性死区。
     *
     * 同时检查同一语句列表中词法声明与 var/function 声明的同名冲突，
     * 以及解构声明中的绑定名。
     */
    private fun predeclareLexical(statements: List<Statement>, environment: Environment) {
        val declarative = environment as? DeclarativeEnvironment ?: return
        val lexicalBindings = LinkedHashMap<String, Boolean>()
        for (statement in statements) {
            when (statement) {
                is VarDeclaration -> if (statement.kind != DeclarationKind.VAR) {
                    val mutable = statement.kind == DeclarationKind.LET
                    for (declaration in statement.declarations) {
                        val names = mutableListOf<String>()
                        val pattern = declaration.pattern
                        if (pattern != null) {
                            collectPatternNames(pattern, names)
                        } else {
                            declaration.name?.let { names.add(it) }
                        }
                        for (name in names) {
                            if (lexicalBindings.put(name, mutable) != null) {
                                throw JSException(
                                    JSError("SyntaxError", "Identifier '$name' has already been declared")
                                )
                            }
                        }
                    }
                }

                is ClassDeclaration -> {
                    if (lexicalBindings.put(statement.name, false) != null) {
                        throw JSException(
                            JSError(
                                "SyntaxError",
                                "Identifier '${statement.name}' has already been declared",
                            )
                        )
                    }
                }

                else -> Unit
            }
        }
        if (lexicalBindings.isEmpty()) return

        for (statement in statements) {
            val varNames = when (statement) {
                is VarDeclaration -> if (statement.kind == DeclarationKind.VAR) {
                    val names = mutableListOf<String>()
                    for (declaration in statement.declarations) {
                        val pattern = declaration.pattern
                        if (pattern != null) {
                            collectPatternNames(pattern, names)
                        } else {
                            declaration.name?.let { names.add(it) }
                        }
                    }
                    names
                } else {
                    emptyList()
                }

                is FunctionDeclaration -> listOf(statement.name)
                else -> emptyList()
            }
            for (name in varNames) {
                if (lexicalBindings.containsKey(name)) {
                    throw JSException(
                        JSError("SyntaxError", "Identifier '$name' has already been declared")
                    )
                }
            }
        }

        for ((name, mutable) in lexicalBindings) {
            declarative.declareLexical(name, mutable)
        }
    }

    /** 收集声明模式中的绑定名（解构、默认值、rest）。 */
    private fun collectPatternNames(pattern: Expression, names: MutableList<String>) {
        when (pattern) {
            is Identifier -> names.add(pattern.name)
            is AssignmentExpr -> collectPatternNames(pattern.target, names)
            is ArrayLiteral -> for (element in pattern.elements) {
                when (element) {
                    null -> Unit
                    is SpreadElement -> collectPatternNames(element.argument, names)
                    else -> collectPatternNames(element, names)
                }
            }

            is ObjectLiteral -> for (element in pattern.properties) {
                when (element) {
                    is ObjectProperty -> collectPatternNames(element.value, names)
                    is ObjectSpreadProperty -> collectPatternNames(element.argument, names)
                }
            }

            else -> Unit
        }
    }

    /**
     * 若当前作用域已预声明同名绑定，则完成初始化并返回 true。
     */
    private fun initializeLexicalIfDeclared(
        environment: Environment,
        name: String,
        value: JSValue,
    ): Boolean {
        val declarative = environment as? DeclarativeEnvironment ?: return false
        if (!declarative.hasUninitializedLocal(name)) return false
        declarative.initializeLexical(name, value)
        return true
    }

    /** 变量声明（支持解构）。 */
    private suspend fun executeVarDeclaration(statement: VarDeclaration): Completion {
        for (declaration in statement.declarations) {
            val pattern = declaration.pattern
            if (pattern != null) {
                if (declaration.initializer == null) {
                    throw syntaxError("Missing initializer in destructuring declaration")
                }
                val value = evaluate(declaration.initializer)
                bindPattern(pattern, value, statement.kind, currentFrame.lexicalEnv)
            } else {
                val name = declaration.name ?: continue
                when (statement.kind) {
                    DeclarationKind.VAR -> {
                        val initializer = declaration.initializer
                        if (initializer == null) {
                            // 重复的 var 声明不得把已有值重置为 undefined
                            currentFrame.lexicalEnv.findVarScope().ensureVarBinding(name)
                        } else {
                            // 初始化器是赋值语义：在 with 作用域内应优先写入 with 对象
                            val value = evaluateNamed(initializer, name)
                            writeIdentifierReference(findBindingOwner(name), name, value)
                        }
                    }

                    DeclarationKind.LET -> {
                        val initializer = declaration.initializer
                        val value = initializer?.let { evaluateNamed(it, name) } ?: JSUndefined
                        if (!initializeLexicalIfDeclared(currentFrame.lexicalEnv, name, value)) {
                            currentFrame.lexicalEnv.createLetBinding(name, value)
                        }
                    }

                    DeclarationKind.CONST -> {
                        val initializer = declaration.initializer
                            ?: throw syntaxError("Missing initializer in const declaration")
                        val value = evaluateNamed(initializer, name)
                        if (!initializeLexicalIfDeclared(currentFrame.lexicalEnv, name, value)) {
                            currentFrame.lexicalEnv.createConstBinding(name, value)
                        }
                    }
                }
            }
        }
        return Completion.Normal(JSUndefined)
    }

    /** if 语句。 */
    private suspend fun executeIf(statement: IfStatement): Completion {
        return if (toBoolean(evaluate(statement.condition))) {
            executeStatement(statement.consequent)
        } else {
            statement.alternate?.let { executeStatement(it) } ?: Completion.Normal(JSUndefined)
        }
    }

    /** while 语句。 */
    private suspend fun executeWhile(statement: WhileStatement, labels: List<String> = emptyList()): Completion {
        // V：最后一次正常完成的语句值（供 eval 完成值使用）
        var lastValue: JSValue = JSUndefined
        while (toBoolean(evaluate(statement.condition))) {
            when (val completion = executeStatement(statement.body)) {
                is Completion.Normal -> lastValue = completion.value
                is Completion.Return -> return completion
                is Completion.Break -> {
                    if (completion.label == null || completion.label in labels) {
                        return Completion.Normal(completion.value)
                    }
                    return completion
                }

                is Completion.Continue -> {
                    if (completion.label != null && completion.label !in labels) return completion
                    lastValue = completion.value
                }
            }
        }
        return Completion.Normal(lastValue)
    }

    /** do-while 语句。 */
    private suspend fun executeDoWhile(statement: DoWhileStatement, labels: List<String> = emptyList()): Completion {
        var lastValue: JSValue = JSUndefined
        do {
            when (val completion = executeStatement(statement.body)) {
                is Completion.Normal -> lastValue = completion.value
                is Completion.Return -> return completion
                is Completion.Break -> {
                    if (completion.label == null || completion.label in labels) {
                        return Completion.Normal(completion.value)
                    }
                    return completion
                }

                is Completion.Continue -> {
                    if (completion.label != null && completion.label !in labels) return completion
                    lastValue = completion.value
                }
            }
        } while (toBoolean(evaluate(statement.condition)))
        return Completion.Normal(lastValue)
    }

    /** for 语句（支持 let/const 每迭代作用域）。 */
    private suspend fun executeFor(statement: ForStatement, labels: List<String> = emptyList()): Completion {
        val forEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = forEnvironment
        try {
            // for (let/const ...) 的词法绑定名（支持解构）
            var loopVariableNames: List<String> = emptyList()
            var loopMutable = true
            val init = statement.init
            if (init is VarDeclaration && init.kind != DeclarationKind.VAR) {
                loopMutable = init.kind == DeclarationKind.LET
                val names = LinkedHashSet<String>()
                for (declaration in init.declarations) {
                    declaration.name?.let { names.add(it) }
                    declaration.pattern?.let { pattern ->
                        val patternNames = mutableListOf<String>()
                        collectPatternNames(pattern, patternNames)
                        names.addAll(patternNames)
                    }
                }
                loopVariableNames = names.toList()
            }
            if (init != null) {
                val initCompletion = executeStatement(init)
                if (initCompletion !is Completion.Normal) return initCompletion
            }

            // 每轮创建新的词法环境并复制上一轮的值；初始环境保留给初始化表达式的闭包
            suspend fun createIterationEnvironment(source: DeclarativeEnvironment): DeclarativeEnvironment {
                val environment = DeclarativeEnvironment(parent = previousEnvironment)
                for (name in loopVariableNames) {
                    val value = source.getBinding(name) ?: JSUndefined
                    if (loopMutable) {
                        environment.createLetBinding(name, value)
                    } else {
                        environment.createConstBinding(name, value)
                    }
                }
                return environment
            }

            var loopEnvironment = if (loopVariableNames.isEmpty()) {
                forEnvironment
            } else {
                createIterationEnvironment(forEnvironment)
            }
            currentFrame.lexicalEnv = loopEnvironment

            var lastValue: JSValue = JSUndefined
            while (true) {
                val condition = statement.condition?.let { toBoolean(evaluate(it)) } ?: true
                if (!condition) break

                var completion: Completion = Completion.Normal(JSUndefined)
                try {
                    completion = executeStatement(statement.body)
                } finally {
                    currentFrame.lexicalEnv = loopEnvironment
                }

                when (completion) {
                    is Completion.Normal -> lastValue = completion.value
                    is Completion.Return -> return completion
                    is Completion.Break -> {
                        if (completion.label == null || completion.label in labels) {
                            return Completion.Normal(completion.value)
                        }
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) return completion
                        lastValue = completion.value
                    }
                }

                if (loopVariableNames.isNotEmpty()) {
                    loopEnvironment = createIterationEnvironment(loopEnvironment)
                    currentFrame.lexicalEnv = loopEnvironment
                }
                statement.update?.let { evaluate(it) }
            }
            return Completion.Normal(lastValue)
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /**
     * for-in 语句。
     *
     * 遍历对象自身与原型链上的可枚举字符串键；let/const 声明为每轮迭代创建新绑定。
     */
    private suspend fun executeForIn(statement: ForInStatement, labels: List<String> = emptyList()): Completion {
        val previousEnvironment = currentFrame.lexicalEnv
        val lexical = statement.declarationKind == DeclarationKind.LET ||
            statement.declarationKind == DeclarationKind.CONST
        val declarationNames = mutableListOf<String>()
        if (lexical) {
            statement.variableName?.let { declarationNames.add(it) }
            statement.pattern?.let { collectPatternNames(it, declarationNames) }
        }
        var headEnvironment: DeclarativeEnvironment? = null
        var forEnvironment: DeclarativeEnvironment? = null
        try {
            if (lexical) {
                // RHS 在头部词法环境中求值；绑定名尚未初始化（TDZ）
                headEnvironment = DeclarativeEnvironment(parent = previousEnvironment)
                for (name in declarationNames) {
                    headEnvironment.declareLexical(
                        name,
                        mutable = statement.declarationKind != DeclarationKind.CONST,
                    )
                }
                currentFrame.lexicalEnv = headEnvironment
            }
            val rightValue = evaluate(statement.right)
            if (rightValue is JSNull || rightValue is JSUndefined) {
                return Completion.Normal(JSUndefined)
            }
            val objectValue = toObject(rightValue)
            val keys = enumerableStringKeysIncludingPrototype(objectValue)
            if (!lexical) {
                forEnvironment = DeclarativeEnvironment(parent = previousEnvironment)
                currentFrame.lexicalEnv = forEnvironment
            }

            var lastValue: JSValue = JSUndefined
            for (key in keys) {
                val propertyKey = PropertyKey.Str(key)
                if (!objectValue.hasProperty(propertyKey)) continue

                val iterationEnvironment = if (lexical) {
                    DeclarativeEnvironment(parent = headEnvironment!!)
                } else {
                    null
                }
                if (iterationEnvironment != null) {
                    currentFrame.lexicalEnv = iterationEnvironment
                }

                var completion: Completion = Completion.Normal(JSUndefined)
                try {
                    val pattern = statement.pattern
                    val variableName = statement.variableName
                    val kind = statement.declarationKind ?: DeclarationKind.VAR
                    val targetEnvironment = iterationEnvironment ?: forEnvironment!!
                    when {
                        pattern != null -> bindPattern(pattern, JSString(key), kind, targetEnvironment)
                        variableName != null -> when (kind) {
                            DeclarationKind.VAR -> forEnvironment!!.findVarScope()
                                .createVarBinding(variableName, JSString(key))

                            DeclarationKind.LET -> targetEnvironment
                                .createLetBinding(variableName, JSString(key))

                            DeclarationKind.CONST -> targetEnvironment
                                .createConstBinding(variableName, JSString(key))
                        }

                        else -> statement.target?.let { assignTarget(it, JSString(key)) }
                    }
                    completion = executeStatement(statement.body)
                } finally {
                    currentFrame.lexicalEnv = if (lexical) headEnvironment!! else forEnvironment!!
                }

                when (completion) {
                    is Completion.Normal -> lastValue = completion.value
                    is Completion.Return -> return completion
                    is Completion.Break -> {
                        if (completion.label == null || completion.label in labels) {
                            return Completion.Normal(completion.value)
                        }
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) return completion
                        lastValue = completion.value
                    }
                }
            }
            return Completion.Normal(lastValue)
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /**
     * for-of 语句：遍历可迭代值。
     */
    private suspend fun executeForOf(statement: ForOfStatement, labels: List<String> = emptyList()): Completion {
        val previousEnvironment = currentFrame.lexicalEnv
        val lexical = statement.declarationKind == DeclarationKind.LET ||
            statement.declarationKind == DeclarationKind.CONST
        val declarationNames = mutableListOf<String>()
        if (lexical) {
            statement.variableName?.let { declarationNames.add(it) }
            statement.pattern?.let { collectPatternNames(it, declarationNames) }
        }
        var headEnvironment: DeclarativeEnvironment? = null
        var forEnvironment: DeclarativeEnvironment? = null
        try {
            if (lexical) {
                // RHS 在头部词法环境中求值；绑定名尚未初始化（TDZ）
                headEnvironment = DeclarativeEnvironment(parent = previousEnvironment)
                for (name in declarationNames) {
                    headEnvironment.declareLexical(
                        name,
                        mutable = statement.declarationKind != DeclarationKind.CONST,
                    )
                }
                currentFrame.lexicalEnv = headEnvironment
            }
            val records = getIterator(evaluate(statement.right))
            if (!lexical) {
                forEnvironment = DeclarativeEnvironment(parent = previousEnvironment)
                currentFrame.lexicalEnv = forEnvironment
            }

            var lastValue: JSValue = JSUndefined
            while (true) {
                // IteratorStep 的异常直接传播，不调用 return
                val value = iteratorNext(records) ?: break

                val iterationEnvironment = if (lexical) {
                    DeclarativeEnvironment(parent = headEnvironment!!)
                } else {
                    null
                }
                if (iterationEnvironment != null) {
                    currentFrame.lexicalEnv = iterationEnvironment
                }

                var completion: Completion = Completion.Normal(JSUndefined)
                try {
                    val pattern = statement.pattern
                    val variableName = statement.variableName
                    val kind = statement.declarationKind ?: DeclarationKind.VAR
                    val targetEnvironment = iterationEnvironment ?: forEnvironment!!
                    when {
                        pattern != null -> bindPattern(pattern, value, kind, targetEnvironment)
                        variableName != null -> when (kind) {
                            DeclarationKind.VAR -> forEnvironment!!.findVarScope()
                                .createVarBinding(variableName, value)

                            DeclarationKind.LET -> targetEnvironment
                                .createLetBinding(variableName, value)

                            DeclarationKind.CONST -> targetEnvironment
                                .createConstBinding(variableName, value)
                        }

                        else -> statement.target?.let { assignTarget(it, value) }
                    }
                    completion = executeStatement(statement.body)
                } catch (error: JSException) {
                    iteratorClose(records, throwCompletion = true)
                    throw error
                } finally {
                    currentFrame.lexicalEnv = if (lexical) headEnvironment!! else forEnvironment!!
                }

                when (completion) {
                    is Completion.Normal -> lastValue = completion.value
                    is Completion.Return -> {
                        iteratorClose(records, throwCompletion = false)
                        return completion
                    }

                    is Completion.Break -> {
                        iteratorClose(records, throwCompletion = false)
                        if (completion.label == null || completion.label in labels) {
                            return Completion.Normal(completion.value)
                        }
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) {
                            iteratorClose(records, throwCompletion = false)
                            return completion
                        }
                        lastValue = completion.value
                    }
                }
            }
            return Completion.Normal(lastValue)
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /**
     * 标签语句。
     *
     * 支持多层标签（`a: b: for (...)`），循环会接收整条标签链。
     */
    private suspend fun executeLabeled(statement: LabeledStatement): Completion {
        val labels = mutableListOf(statement.label)
        var body: Statement = statement.body
        while (body is LabeledStatement) {
            labels.add(body.label)
            body = body.body
        }

        val completion = when (body) {
            is WhileStatement -> executeWhile(body, labels)
            is DoWhileStatement -> executeDoWhile(body, labels)
            is ForStatement -> executeFor(body, labels)
            is ForInStatement -> executeForIn(body, labels)
            is ForOfStatement -> executeForOf(body, labels)
            else -> executeStatement(body)
        }

        return if (completion is Completion.Break && completion.label in labels) {
            Completion.Normal(completion.value)
        } else {
            completion
        }
    }

    /**
     * with 语句：把对象属性注入当前作用域链。
     */
    private suspend fun executeWith(statement: WithStatement): Completion {
        val value = evaluate(statement.objectExpression)
        if (value is JSNull || value is JSUndefined) {
            throw typeError("Cannot convert undefined or null to object")
        }
        val objectValue = toObject(value)
        val withEnvironment = ObjectEnvironment(
            bindingObject = objectValue,
            parent = currentFrame.lexicalEnv,
            access = this,
            isVarScope = false,
            unscopablesKey = PropertyKey.Sym(intrinsics.symbolUnscopables),
        )
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = withEnvironment
        return try {
            executeStatement(statement.body)
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /**
     * switch 语句。
     *
     * 先确定匹配的 case（找不到则使用 default），再从该位置开始顺序执行（fall-through）。
     */
    private suspend fun executeSwitch(statement: SwitchStatement): Completion {
        val discriminant = evaluate(statement.discriminant)
        // switch 的 case 块共享一个词法作用域
        val switchEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = switchEnvironment
        return try {
            predeclareLexical(
                statement.cases.flatMap { it.consequent },
                switchEnvironment,
            )
            var startIndex = -1
            var defaultIndex = -1

            for ((index, switchCase) in statement.cases.withIndex()) {
                val test = switchCase.test
                if (test == null) {
                    defaultIndex = index
                } else if (startIndex < 0) {
                    if (strictEquals(discriminant, evaluate(test))) {
                        startIndex = index
                    }
                }
            }

            if (startIndex < 0) startIndex = defaultIndex
            if (startIndex < 0) {
                Completion.Normal(JSUndefined)
            } else {
                var result: Completion = Completion.Normal(JSUndefined)
                var lastValue: JSValue = JSUndefined
                execution@ for (index in startIndex until statement.cases.size) {
                    for (inner in statement.cases[index].consequent) {
                        when (val completion = executeStatement(inner)) {
                            is Completion.Normal -> {
                                lastValue = completion.value
                                result = completion
                            }

                            is Completion.Return -> {
                                result = completion
                                break@execution
                            }

                            is Completion.Break -> {
                                result = if (completion.label == null) {
                                    Completion.Normal(lastValue)
                                } else {
                                    completion
                                }
                                break@execution
                            }

                            is Completion.Continue -> {
                                result = completion
                                break@execution
                            }
                        }
                    }
                }
                result
            }
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /** try-catch-finally 语句。 */
    private suspend fun executeTry(statement: TryStatement): Completion {
        var pending: Completion = Completion.Normal(JSUndefined)
        var thrown: JSException? = null

        try {
            pending = executeBlock(
                statement.block.statements,
                DeclarativeEnvironment(parent = currentFrame.lexicalEnv),
            )
        } catch (exception: JSException) {
            normalizeErrorValue(exception.value)
            val handler = statement.handler
            if (handler != null) {
                val catchEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
                if (handler.param != null) {
                    catchEnvironment.createLetBinding(handler.param, exception.value)
                } else if (handler.pattern != null) {
                    bindPattern(
                        handler.pattern,
                        exception.value,
                        DeclarationKind.LET,
                        catchEnvironment,
                    )
                }
                try {
                    pending = executeBlock(handler.body.statements, catchEnvironment)
                } catch (catchException: JSException) {
                    thrown = catchException
                }
            } else {
                thrown = exception
            }
        }

        val finalizer = statement.finalizer
        if (finalizer != null) {
            try {
                val completion = executeBlock(
                    finalizer.statements,
                    DeclarativeEnvironment(parent = currentFrame.lexicalEnv),
                )
                if (completion !is Completion.Normal) {
                    // finally 的 abrupt 完成（break/continue/return）覆盖之前的 throw
                    pending = completion
                    thrown = null
                }
            } catch (finalizerException: JSException) {
                thrown = finalizerException
            }
        }

        val error = thrown
        if (error != null) throw error
        return pending
    }

    // ================================================================
    // 表达式求值
    // ================================================================

    private suspend fun evaluate(expression: Expression): JSValue {
        return when (expression) {
            is Identifier -> currentFrame.lexicalEnv.getBinding(expression.name)
                ?: throw referenceError("${expression.name} is not defined")

            is NumberLiteral -> JSNumber(expression.value)
            is BigIntLiteral -> throw typeError("BigInt literals are not supported")
            is StringLiteral -> JSString(expression.value)
            is BooleanLiteral -> JSBoolean.getJsBoolean(expression.value)
            is NullLiteral -> JSNull
            is ThisExpr -> {
                if (!currentFrame.thisInitialized) {
                    throw referenceError("Must call super constructor before accessing 'this'")
                }
                currentFrame.thisValue
            }

            is RegExpLiteral -> createRegExp(expression.pattern, expression.flags)
            is TemplateLiteral -> evaluateTemplateLiteral(expression)
            is TaggedTemplate -> evaluateTaggedTemplate(expression)
            is ClassExpression -> evaluateClass(expression.name, expression.superClass, expression.members)
            is SuperExpr -> throw syntaxError("'super' keyword unexpected here")
            is NewTargetExpr -> currentFrame.newTarget ?: JSUndefined

            is ArrayLiteral -> evaluateArrayLiteral(expression)
            is ObjectLiteral -> evaluateObjectLiteral(expression)

            is FunctionExpression -> {
                val name = expression.name
                if (name == null || expression.isMethodDefinition) {
                    // 方法语法没有 BindingIdentifier，不建立词法名字绑定
                    createFunction(
                        name = name ?: "",
                        params = expression.params,
                        body = expression.body,
                        closure = currentFrame.lexicalEnv,
                        strict = expression.strict,
                        varDeclarations = expression.varDeclarations,
                        functionDeclarations = expression.functionDeclarations,
                        isAsync = expression.isAsync,
                        isMethod = expression.isMethodDefinition,
                    )
                } else {
                    // ES5 13：具名函数表达式的名字只在其自身作用域内可见（用于递归），
                    // 不污染外层环境
                    val nameEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
                    val function = createFunction(
                        name = name,
                        params = expression.params,
                        body = expression.body,
                        closure = nameEnvironment,
                        strict = expression.strict,
                        varDeclarations = expression.varDeclarations,
                        functionDeclarations = expression.functionDeclarations,
                        isAsync = expression.isAsync,
                    )
                    nameEnvironment.createFunctionNameBinding(name, function)
                    function
                }
            }

            is ArrowFunction -> createFunction(
                name = "",
                params = expression.params,
                body = expression.body,
                closure = currentFrame.lexicalEnv,
                strict = expression.strict,
                varDeclarations = expression.varDeclarations,
                functionDeclarations = expression.functionDeclarations,
                expressionBody = expression.expressionBody,
                isArrow = true,
                isAsync = expression.isAsync,
                lexicalThis = currentFrame.thisValue,
                // 箭头函数继承最近的 this 绑定来源（跳过中间箭头，保留构造器 TDZ 状态）
                lexicalThisSource = (currentFrame.function as? JSFunction)
                    ?.takeIf { it.isArrow }?.lexicalThisSource ?: currentFrame,
                lexicalFunction = (currentFrame.function as? JSFunction)
                    ?.takeIf { it.isArrow }?.lexicalFunction ?: currentFrame.function,
                // arguments 语法限制同样穿过箭头函数
                forbidsArguments = currentFrame.function?.forbidsArguments == true,
                // 箭头函数继承 super 绑定与 new.target
                lexicalNewTarget = currentFrame.newTarget,
                allowsNewTarget = expression.allowsNewTarget,
                homeObject = currentFrame.function?.homeObject,
                superBase = currentFrame.function?.superBase,
            )

            is MemberExpr -> {
                if (expression.obj is SuperExpr) {
                    if (!currentFrame.thisInitialized) {
                        throw referenceError("Must call super constructor before accessing 'super'")
                    }
                    getV(superPropertyBase(), memberKey(expression))
                } else {
                    val base = evaluate(expression.obj)
                    getV(base, memberKey(expression))
                }
            }

            is OptionalChainExpr -> evaluateOptionalChain(expression)
            is CallExpr -> evaluateCall(expression)
            is NewExpr -> construct(
                constructor = evaluate(expression.constructor),
                args = evaluateArguments(expression.arguments),
            )

            is AssignmentExpr -> evaluateAssignment(expression)
            is UpdateExpr -> evaluateUpdate(expression)
            is UnaryOp -> evaluateUnary(expression)
            is BinaryOp -> evaluateBinary(expression)

            is ConditionalExpr -> if (toBoolean(evaluate(expression.condition))) {
                evaluate(expression.thenBranch)
            } else {
                evaluate(expression.elseBranch)
            }

            is CoalesceExpr -> {
                val left = evaluate(expression.left)
                if (left is JSNull || left is JSUndefined) evaluate(expression.right) else left
            }

            is SequenceExpr -> {
                var result: JSValue = JSUndefined
                for (item in expression.expressions) {
                    result = evaluate(item)
                }
                result
            }

            is SpreadElement -> throw syntaxError("Unexpected spread element")
        }
    }

    private suspend fun evaluateArrayLiteral(expression: ArrayLiteral): JSValue {
        val elements = ArrayList<JSValue?>(expression.elements.size)
        for (element in expression.elements) {
            when (element) {
                null -> elements.add(null)
                is SpreadElement -> elements.addAll(iterateValues(evaluate(element.argument)))
                else -> elements.add(evaluate(element))
            }
        }
        return JSArray(elements, prototype = intrinsics.arrayPrototype)
    }

    private suspend fun evaluateObjectLiteral(expression: ObjectLiteral): JSValue {
        val result = JSObject(prototype = intrinsics.objectPrototype)
        for (element in expression.properties) {
            when (element) {
                is ObjectSpreadProperty -> {
                    val spreadValue = evaluate(element.argument)
                    if (spreadValue is JSNull || spreadValue is JSUndefined) continue
                    val source = toObject(spreadValue)
                    for (key in source.ownPropertyKeys()) {
                        if (source.getOwnProperty(key)?.enumerable != true) continue
                        result.defineDataProperty(
                            key = key,
                            value = getV(source, key),
                            writable = true,
                            enumerable = true,
                            configurable = true,
                        )
                    }
                }

                is ObjectProperty -> {
                    val key = if (element.computed) {
                        toPropertyKey(evaluate(element.key))
                    } else {
                        PropertyKey.Str(propertyName(element.key))
                    }
                    val value = evaluate(element.value)
                    if (value is JSFunction && element.value is FunctionExpression) {
                        // 对象字面量方法/访问器的 HomeObject 是正在构建的对象
                        value.setMethodHome(result, result.prototype)
                        if (element.method) value.markAsMethod()
                    }
                    if (value is JSFunction) {
                        val source = element.value
                        // 匿名函数/类按属性名推断名称；访问器使用 get/set 前缀，
                        // 非标识符键的方法（字符串/数字/计算键）也在此补名
                        val shouldName = element.kind != PropertyKind.INIT ||
                            isAnonymousFunctionDefinition(source) ||
                            (source is FunctionExpression && source.name == "")
                        if (shouldName) {
                            value.setName(methodFunctionName(key, element.kind))
                        }
                    }
                    when (element.kind) {
                        PropertyKind.INIT -> {
                            val literalKeyName = if (!element.computed) {
                                when (val keyExpression = element.key) {
                                    is Identifier -> keyExpression.name
                                    is StringLiteral -> keyExpression.value
                                    else -> null
                                }
                            } else {
                                null
                            }
                            // 冒号形式的 "__proto__: value" 设置原型；非对象/ null 忽略
                            val isShorthand = element.value is Identifier &&
                                element.value.range == element.key.range
                            if (literalKeyName == "__proto__" && !element.method && !isShorthand) {
                                when (val prototypeValue = value) {
                                    is JSObject -> result.prototype = prototypeValue
                                    is JSNull -> result.prototype = null
                                    else -> Unit
                                }
                            } else {
                                result.defineDataProperty(
                                    key = key,
                                    value = value,
                                    writable = true,
                                    enumerable = true,
                                    configurable = true,
                                )
                            }
                        }

                        PropertyKind.GET, PropertyKind.SET -> {
                            val existing = result.getOwnProperty(key)
                            val getter = if (element.kind == PropertyKind.GET) value else existing?.get
                            val setter = if (element.kind == PropertyKind.SET) value else existing?.set
                            result.defineOwnProperty(
                                key,
                                PropertyDescriptor.accessor(
                                    get = getter,
                                    set = setter,
                                    enumerable = true,
                                    configurable = true,
                                ),
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    /** 对象字面量键的静态名称。 */
    private fun propertyName(key: Expression): String = when (key) {
        is Identifier -> key.name
        is StringLiteral -> key.value
        is NumberLiteral -> NumberFormatting.toString(key.value)
        else -> ""
    }

    /** 是否为语法上匿名的函数/类定义（需要 NamedEvaluation 推断名称）。 */
    private fun isAnonymousFunctionDefinition(expression: Expression): Boolean = when (expression) {
        is ArrowFunction -> true
        is FunctionExpression -> expression.name == null
        is ClassExpression -> expression.name == null
        else -> false
    }

    /** NamedEvaluation：先带入名称提示再求值，保证匿名 class 的静态初始化能看到名称。 */
    private suspend fun evaluateNamed(expression: Expression, name: String): JSValue {
        val hinted = expression is ClassExpression && expression.name == null
        if (hinted) classNameHints.add(name)
        try {
            val value = evaluate(expression)
            applyInferredFunctionName(expression, value, name)
            return value
        } finally {
            // 正常路径由 evaluateClass 在入口消费；异常路径在这里清理残留提示
            if (hinted && classNameHints.lastOrNull() == name) {
                classNameHints.removeAt(classNameHints.lastIndex)
            }
        }
    }

    /** DefineField：CreateDataPropertyOrThrow 语义，定义失败时抛 TypeError。 */
    private fun defineFieldOrThrow(receiver: JSObject, key: PropertyKey, value: JSValue) {
        val existing = receiver.getOwnProperty(key)
        if (existing != null && !existing.configurable) {
            throw typeError("Cannot define property '${key}'")
        }
        val defined = receiver.defineOwnProperty(
            key,
            PropertyDescriptor.data(
                value = value,
                writable = true,
                enumerable = true,
                configurable = true,
            ),
        )
        if (!defined) {
            throw typeError("Cannot define property '${key}'")
        }
    }

    /** 为匿名函数/类设置推断名称；具名函数表达式保持自己的名字。 */
    private fun applyInferredFunctionName(expression: Expression?, value: JSValue, name: String) {
        if (expression == null || !isAnonymousFunctionDefinition(expression)) return
        (value as? JSFunction)?.setName(name)
    }

    /** ES2015 SetFunctionName 的 PropertyKey → 名称转换（Symbol 使用 [description]）。 */
    private fun functionNameFromPropertyKey(key: PropertyKey): String = when (key) {
        is PropertyKey.Str -> key.value
        is PropertyKey.Sym -> key.symbol.description?.let { "[$it]" } ?: ""
    }

    /** 方法定义名称（访问器带 get/set 前缀）。 */
    private fun methodFunctionName(key: PropertyKey, kind: PropertyKind): String {
        val base = functionNameFromPropertyKey(key)
        return when (kind) {
            PropertyKind.GET -> "get $base"
            PropertyKind.SET -> "set $base"
            else -> base
        }
    }

    /** 类成员方法名称（访问器带 get/set 前缀）。 */
    private fun methodFunctionName(key: PropertyKey, kind: ClassMemberKind): String {
        val base = functionNameFromPropertyKey(key)
        return when (kind) {
            ClassMemberKind.GET -> "get $base"
            ClassMemberKind.SET -> "set $base"
            else -> base
        }
    }

    /** 模板字符串求值（普通模板）。 */
    private suspend fun evaluateTemplateLiteral(expression: TemplateLiteral): JSValue {
        val builder = StringBuilder()
        for (index in expression.expressions.indices) {
            builder.append(expression.quasis[index])
            builder.append(toStringValue(evaluate(expression.expressions[index])))
        }
        builder.append(expression.quasis.last())
        return JSString(builder.toString())
    }

    /** 标签模板求值。 */
    private suspend fun evaluateTaggedTemplate(expression: TaggedTemplate): JSValue {
        val cooked = expression.quasis.map { JSString(it) as JSValue? }.toMutableList()
        val raw = expression.rawQuasis.map { JSString(it) as JSValue? }.toMutableList()
        val templateObject = JSArray(cooked, prototype = intrinsics.arrayPrototype)
        val rawObject = JSArray(raw, prototype = intrinsics.arrayPrototype)
        rawObject.freezeArray()
        templateObject.defineDataProperty(
            key = PropertyKey.Str("raw"),
            value = rawObject,
            writable = false,
            enumerable = false,
            configurable = false,
        )
        templateObject.freezeArray()

        val arguments = ArrayList<JSValue>()
        arguments.add(templateObject)
        for (item in expression.expressions) {
            arguments.add(evaluate(item))
        }
        return callFunction(evaluate(expression.tag), JSUndefined, arguments)
    }

    /**
     * 创建 class 构造器与原型方法/字段。
     */
    private suspend fun evaluateClass(
        name: String?,
        superClassExpression: Expression?,
        members: List<ClassMember>,
    ): JSValue {
        // 匿名 class 表达式在 NamedEvaluation 场景（如 var C = class {...}）中
        // 需要在静态初始化之前就带上名称
        val inferredName = if (name == null) classNameHints.removeLastOrNull() else null
        val effectiveName = name ?: inferredName
        val superClassValue = superClassExpression?.let { evaluate(it) }
        if (superClassValue != null && superClassValue !is JSNull &&
            !isConstructor(superClassValue)
        ) {
            throw typeError("Class extends value is not a constructor or null")
        }

        val superPrototype = when (superClassValue) {
            is JSFunction -> superClassValue.functionPrototypeObject
            is JSNativeFunction -> superClassValue.functionPrototypeObject
            else -> null
        }
        // extends null 时类原型对象的 [[Prototype]] 为 null
        val prototype = JSObject(
            prototype = when {
                superClassValue is JSNull -> null
                else -> superPrototype ?: intrinsics.objectPrototype
            }
        )
        val classEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)

        val constructorMember = members.firstOrNull { it.kind == ClassMemberKind.CONSTRUCTOR }
        val constructor = if (constructorMember != null) {
            createFunction(
                name = effectiveName ?: "",
                params = constructorMember.value.params,
                body = constructorMember.value.body,
                closure = classEnvironment,
                strict = true,
                varDeclarations = constructorMember.value.varDeclarations,
                functionDeclarations = constructorMember.value.functionDeclarations,
                isClassConstructor = true,
                superConstructor = superClassValue,
                superBase = superPrototype,
                functionPrototypeObject = prototype,
            )
        } else {
            createFunction(
                name = effectiveName ?: "",
                params = emptyList(),
                body = BlockStatement(emptyList(), SourceRange.NONE),
                closure = classEnvironment,
                strict = true,
                isClassConstructor = true,
                isDefaultDerivedConstructor = superClassValue != null,
                superConstructor = superClassValue,
                superBase = superPrototype,
                functionPrototypeObject = prototype,
            )
        }

        if (name != null) {
            classEnvironment.createConstBinding(name, constructor)
        }
        if (superClassValue is JSObject) {
            constructor.prototype = superClassValue
        }

        // 静态字段与静态块按源码顺序执行；实例字段在构造时按源码顺序初始化
        val staticElements = ArrayList<StaticElement>()
        val instanceFields = ArrayList<InstanceField>()
        for (member in members) {
            if (member.kind == ClassMemberKind.CONSTRUCTOR) continue
            if (member.kind == ClassMemberKind.STATIC_BLOCK) {
                staticElements.add(StaticElement(member, null))
                continue
            }
            val key = if (member.computed) {
                toPropertyKey(evaluate(member.key))
            } else {
                PropertyKey.Str(propertyName(member.key))
            }
            val target: JSObject = if (member.isStatic) constructor else prototype
            val superBase = if (member.isStatic) {
                superClassValue as? JSObject
            } else {
                superPrototype
            }
            if (member.kind == ClassMemberKind.FIELD) {
                if (member.isStatic) {
                    staticElements.add(StaticElement(member, key))
                } else {
                    // 实例字段初始化器在类求值时创建一次，构造实例时复用
                    instanceFields.add(
                        InstanceField(
                            key,
                            createFunction(
                                name = "",
                                params = emptyList(),
                                body = null,
                                closure = classEnvironment,
                                strict = true,
                                expressionBody = member.value.fieldInitializer,
                                isMethod = true,
                                superBase = superBase,
                                homeObject = target,
                                forbidsArguments = true,
                                namedEvaluationHint = functionNameFromPropertyKey(key),
                            ),
                        )
                    )
                }
                continue
            }
            val function = createFunction(
                name = methodFunctionName(key, member.kind),
                params = member.value.params,
                body = member.value.body,
                closure = classEnvironment,
                strict = true,
                varDeclarations = member.value.varDeclarations,
                functionDeclarations = member.value.functionDeclarations,
                isAsync = member.value.isAsync,
                isMethod = true,
                superBase = superBase,
                homeObject = target,
            )
            when (member.kind) {
                ClassMemberKind.GET, ClassMemberKind.SET -> {
                    val existing = target.getOwnProperty(key)
                    val getter = if (member.kind == ClassMemberKind.GET) function else existing?.get
                    val setter = if (member.kind == ClassMemberKind.SET) function else existing?.set
                    target.defineOwnProperty(
                        key,
                        PropertyDescriptor.accessor(
                            get = getter,
                            set = setter,
                            enumerable = false,
                            configurable = true,
                        ),
                    )
                }

                ClassMemberKind.METHOD -> target.defineDataProperty(
                    key = key,
                    value = function,
                    writable = true,
                    enumerable = false,
                    configurable = true,
                )

                ClassMemberKind.CONSTRUCTOR,
                ClassMemberKind.STATIC_BLOCK,
                ClassMemberKind.FIELD -> Unit
            }
        }
        constructor.instanceFields = instanceFields

        // 静态字段与静态初始化块按源码顺序执行：this 为类构造器，super 基为父构造器
        for (element in staticElements) {
            val member = element.member
            val function = createFunction(
                name = "",
                params = emptyList(),
                body = if (member.kind == ClassMemberKind.STATIC_BLOCK) member.value.body else null,
                closure = classEnvironment,
                strict = true,
                varDeclarations = member.value.varDeclarations,
                functionDeclarations = member.value.functionDeclarations,
                expressionBody = member.value.fieldInitializer,
                isMethod = true,
                superBase = superClassValue as? JSObject,
                homeObject = constructor,
                forbidsArguments = true,
                namedEvaluationHint = if (member.kind == ClassMemberKind.FIELD) {
                    functionNameFromPropertyKey(element.key!!)
                } else {
                    null
                },
            )
            val value = callFunction(function, constructor, emptyList())
            if (member.kind == ClassMemberKind.FIELD) {
                // DefineField：CreateDataPropertyOrThrow 语义
                defineFieldOrThrow(constructor, element.key!!, value)
            }
        }

        return constructor
    }

    /** 类求值时的静态元素（静态字段或静态初始化块）；字段携带已求值的属性键。 */
    private class StaticElement(val member: ClassMember, val key: PropertyKey?)

    /** 在构造实例时按源码顺序执行 class 实例字段初始化器（DefineField 语义）。 */
    private suspend fun initializeInstanceFields(function: JSFunction, instance: JSValue) {
        if (function.instanceFields.isEmpty()) return
        val receiver = instance as? JSObject ?: return
        for (field in function.instanceFields) {
            val value = callFunction(field.initializer, receiver, emptyList())
            defineFieldOrThrow(receiver, field.key, value)
        }
    }

    /**
     * 调用父构造器（super(...)）。使用当前实例作为 this，不创建新对象。
     */
    private suspend fun callSuperConstructor(
        superConstructor: JSValue,
        thisValue: JSValue,
        args: List<JSValue>,
    ): JSValue {
        return when (superConstructor) {
            is JSFunction -> {
                if (superConstructor.isArrow || superConstructor.isMethod || superConstructor.isAsync) {
                    throw typeError("Super constructor is not a constructor")
                }
                val derived = superConstructor.isClassConstructor && superConstructor.superConstructor != null
                val newTarget = currentFrame.newTarget
                val callEnv = DeclarativeEnvironment(parent = superConstructor.closure, isVarScope = true)
                val previousFrame = frame
                val activeFrame = ExecutionContext(
                    lexicalEnv = callEnv,
                    thisValue = thisValue,
                    strict = true,
                    function = superConstructor,
                    newTarget = newTarget,
                    thisInitialized = !derived,
                )
                frame = activeFrame
                pushStackFrame(superConstructor.name.ifEmpty { "anonymous" })
                try {
                    callEnv.createVarBinding("arguments", createArgumentsObject(args, superConstructor))
                    bindParameters(superConstructor, args, callEnv)
                    hoistDeclarations(
                        callEnv,
                        superConstructor.functionDeclarations,
                        superConstructor.varDeclarations,
                    )
                    // 基类父构造器：先初始化其字段，再执行构造器体
                    if (!derived) {
                        initializeInstanceFields(superConstructor, activeFrame.thisValue)
                    }
                    if (superConstructor.isDefaultDerivedConstructor) {
                        val parentValue: JSValue =
                            superConstructor.prototype ?: intrinsics.functionPrototype
                        if (!isConstructor(parentValue)) {
                            throw typeError("Super constructor is not a constructor")
                        }
                        val result = callSuperConstructor(parentValue, activeFrame.thisValue, args)
                        activeFrame.thisValue = result
                        activeFrame.thisInitialized = true
                        // 默认派生父构造器的 super() 之后初始化其字段
                        initializeInstanceFields(superConstructor, activeFrame.thisValue)
                    }
                    val body = superConstructor.body
                    if (body != null) {
                        val completion = executeStatements(body.statements)
                        if (completion is Completion.Return) {
                            val returned = completion.value
                            if (returned is JSObject) return returned
                            if (derived && returned !is JSUndefined) {
                                throw typeError("Derived constructors may only return object or undefined")
                            }
                        }
                    }
                    if (!activeFrame.thisInitialized) {
                        throw referenceError("Must call super constructor before returning from derived constructor")
                    }
                    activeFrame.thisValue
                } finally {
                    popStackFrame()
                    frame = previousFrame
                }
            }

            is JSNativeFunction -> {
                // 原生父构造器（Map/Set/Promise/Error 等）创建实际实例；
                // 把实例原型改为 NewTarget.prototype（即派生构造器预创建实例的原型），
                // 使 instanceof 与原型方法生效。
                val previousConstructCall = nativeConstructCall
                nativeConstructCall = true
                val result = try {
                    callFunction(superConstructor, thisValue, args)
                } finally {
                    nativeConstructCall = previousConstructCall
                }
                if (result is JSObject) {
                    if (thisValue is JSObject) {
                        result.prototype = thisValue.prototype
                    }
                    result
                } else {
                    thisValue
                }
            }

            else -> throw typeError("Super constructor is not a constructor")
        }
    }

    private suspend fun evaluateCall(expression: CallExpr): JSValue {
        val calleeExpression = expression.callee

        // super(...)：调用父构造器（使用当前 this）
        if (calleeExpression is SuperExpr) {
            val activeFunction = currentFrame.function
                ?: throw syntaxError("'super' keyword unexpected here")
            // 箭头函数中的 super() 使用外层非箭头构造器的 [[Prototype]] 与 this 绑定
            val function = if (activeFunction.isArrow) {
                activeFunction.lexicalFunction
                    ?: throw syntaxError("'super' keyword unexpected here")
            } else {
                activeFunction
            }
            val thisBinding: ThisBindingSource = if (activeFunction.isArrow) {
                activeFunction.lexicalThisSource ?: currentFrame
            } else {
                currentFrame
            }
            // 规范顺序：先求值参数并调用父构造器，BindThisValue 发现 this 已初始化时才抛错
            val alreadyInitialized = thisBinding.thisBound
            val arguments = evaluateArguments(expression.arguments)
            val superConstructorValue: JSValue = function.prototype ?: intrinsics.functionPrototype
            if (!isConstructor(superConstructorValue)) {
                throw typeError("Super constructor is not a constructor")
            }
            val result = callSuperConstructor(superConstructorValue, thisBinding.boundThis, arguments)
            if (alreadyInitialized) {
                throw referenceError("Super constructor may only be called once")
            }
            thisBinding.boundThis = result
            thisBinding.thisBound = true
            // super() 返回后按源码顺序初始化派生类的实例字段
            initializeInstanceFields(function, thisBinding.boundThis)
            return result
        }

        // super.method(...)：在父类原型上查找方法，this 保持当前对象
        if (calleeExpression is MemberExpr && calleeExpression.obj is SuperExpr) {
            val superBase = currentFrame.function?.superBase
                ?: throw syntaxError("'super' keyword unexpected here")
            val key = memberKey(calleeExpression)
            val function = getV(superBase, key)
            val arguments = evaluateArguments(expression.arguments)
            return callFunction(function, currentFrame.thisValue, arguments, expression.range)
        }

        // 直接 eval：只有与全局 %eval% 是同一函数时才按直接求值处理
        if (calleeExpression is Identifier && calleeExpression.name == "eval") {
            val function = evaluate(calleeExpression)
            val arguments = evaluateArguments(expression.arguments)
            val globalEval = context.globalObject.getOwnDataValue(PropertyKey.Str("eval"))
            if (globalEval != null && function === globalEval) {
                val value = arguments.getOrElse(0) { JSUndefined }
                return if (value is JSString) evaluateEvalSource(value.value) else value
            }
            return callFunction(function, JSUndefined, arguments, expression.range)
        }

        if (calleeExpression is OptionalChainExpr) {
            // 括号包裹的可选链作为被调用者：保留最后成员访问的 this
            val chain = evaluateOptionalChainInternal(
                calleeExpression,
                calleeExpression.operations.size,
            )
            val arguments = evaluateArguments(expression.arguments)
            val thisValue = if (chain.hasReceiver) chain.receiver ?: JSUndefined else JSUndefined
            return callFunction(chain.value, thisValue, arguments, expression.range)
        }

        if (calleeExpression is MemberExpr) {
            // 方法调用：this 为成员访问的基对象
            val base = evaluate(calleeExpression.obj)
            val key = memberKey(calleeExpression)
            val function = getV(base, key)
            val arguments = evaluateArguments(expression.arguments)
            if (!isCallable(function)) {
                throw typeError(
                    "${describeValue(function)} is not a function (at ${calleeExpression.range})"
                )
            }
            return callFunction(function, base, arguments, expression.range)
        }

        val function = evaluate(calleeExpression)
        val arguments = evaluateArguments(expression.arguments)
        if (!isCallable(function)) {
            throw typeError(
                "${describeValue(function)} is not a function (at ${calleeExpression.range})"
            )
        }
        return callFunction(function, JSUndefined, arguments, expression.range)
    }

    /**
     * 可选链求值：任一 optional 段遇到 null/undefined 时整条链短路为 undefined，
     * 其后的成员键与调用参数都不再求值；调用保留成员访问的 this。
     */
    /** 可选链求值结果：保留最后成员访问的 this 与短路标记。 */
    private class OptionalChainResult(
        val value: JSValue,
        val receiver: JSValue?,
        val hasReceiver: Boolean,
        val shortCircuited: Boolean,
    )

    private suspend fun evaluateOptionalChain(expression: OptionalChainExpr): JSValue {
        val result = evaluateOptionalChainInternal(expression, expression.operations.size)
        return if (result.shortCircuited) JSUndefined else result.value
    }

    /**
     * 求值可选链前 [count] 个操作，并记录可作为调用 this 的接收者。
     *
     * 基表达式是成员访问时先按引用求值，保证 `a.b?.()`/`super.m?.()` 的 this 正确。
     */
    private suspend fun evaluateOptionalChainInternal(
        expression: OptionalChainExpr,
        count: Int,
    ): OptionalChainResult {
        var receiver: JSValue? = null
        var hasReceiver = false
        var value = when (val base = expression.base) {
            is OptionalChainExpr -> {
                // 括号包裹的可选链作为链基：保留其最后成员的接收者
                val inner = evaluateOptionalChainInternal(base, base.operations.size)
                if (inner.shortCircuited) {
                    return OptionalChainResult(JSUndefined, null, false, true)
                }
                receiver = inner.receiver
                hasReceiver = inner.hasReceiver
                inner.value
            }

            is MemberExpr -> {
                if (base.obj is SuperExpr) {
                    if (!currentFrame.thisInitialized) {
                        throw referenceError("Must call super constructor before accessing 'super'")
                    }
                    receiver = currentFrame.thisValue
                    hasReceiver = true
                    getV(superPropertyBase(), memberKey(base))
                } else {
                    val baseValue = evaluate(base.obj)
                    receiver = baseValue
                    hasReceiver = true
                    getV(baseValue, memberKey(base))
                }
            }

            else -> evaluate(base)
        }
        for (index in 0 until count) {
            when (val operation = expression.operations[index]) {
                is OptionalChainMember -> {
                    if (operation.optional && (value is JSNull || value is JSUndefined)) {
                        return OptionalChainResult(JSUndefined, null, false, true)
                    }
                    val base = value
                    val key = if (operation.computed) {
                        toPropertyKey(evaluate(operation.property))
                    } else {
                        PropertyKey.Str((operation.property as Identifier).name)
                    }
                    receiver = base
                    hasReceiver = true
                    value = getV(base, key)
                }

                is OptionalChainCall -> {
                    if (operation.optional && (value is JSNull || value is JSUndefined)) {
                        return OptionalChainResult(JSUndefined, null, false, true)
                    }
                    val callee = value
                    val arguments = evaluateArguments(operation.arguments)
                    val thisValue = if (hasReceiver) receiver ?: JSUndefined else JSUndefined
                    value = callFunction(callee, thisValue, arguments)
                    hasReceiver = false
                }
            }
        }
        return OptionalChainResult(value, receiver, hasReceiver, false)
    }

    private suspend fun evaluateArguments(expressions: List<Expression>): List<JSValue> {
        val values = ArrayList<JSValue>(expressions.size)
        for (expression in expressions) {
            if (expression is SpreadElement) {
                values.addAll(iterateValues(evaluate(expression.argument)))
            } else {
                values.add(evaluate(expression))
            }
        }
        return values
    }

    /** super 属性访问的基对象：方法用 HomeObject 的动态原型，构造器用 superBase。 */
    private fun superPropertyBase(): JSObject {
        val function = currentFrame.function
        val base = if (function?.homeObject != null) {
            function.homeObject?.prototype
        } else {
            function?.superBase
        }
        return base ?: throw typeError("Cannot read properties of null (reading 'super')")
    }

    /** super 属性赋值：setter 以实际 this 为接收者，否则在接收者上创建自有属性。 */
    private suspend fun putSuperProperty(
        base: JSObject,
        receiver: JSValue,
        key: PropertyKey,
        value: JSValue,
        strict: Boolean,
    ) {
        var current: JSObject? = base
        while (current != null) {
            val descriptor = current.getOwnProperty(key)
            if (descriptor != null) {
                if (descriptor.isAccessor) {
                    val setter = descriptor.set
                    if (setter == null || setter is JSUndefined) {
                        if (strict) throw typeError("Cannot set property '$key' which has only a getter")
                        return
                    }
                    callFunction(setter, receiver, listOf(value))
                    return
                }
                if (!descriptor.writable) {
                    if (strict) throw typeError("Cannot assign to read only property '$key'")
                    return
                }
                break
            }
            current = current.prototype
        }
        val receiverObject = receiver as? JSObject
            ?: if (strict) {
                throw typeError("Cannot create property '$key' on primitive value")
            } else {
                return
            }
        val defined = receiverObject.defineOwnProperty(
            key,
            PropertyDescriptor.data(value, writable = true, enumerable = true, configurable = true),
        )
        if (!defined && strict) throw typeError("Cannot create property '$key'")
    }

    private suspend fun evaluateAssignment(expression: AssignmentExpr): JSValue {
        val operator = expression.operator
        return when (val target = expression.target) {
            is Identifier -> {
                // 先解析 Reference 的基环境，避免 RHS/访问器删除绑定后写错位置
                val owner = findBindingOwner(target.name)
                when {
                    operator == "=" -> {
                        val value = evaluateNamed(expression.value, target.name)
                        writeIdentifierReference(owner, target.name, value)
                        value
                    }

                    isLogicalAssignment(operator) -> {
                        val current = readIdentifierReference(owner, target.name)
                        if (!shouldAssignLogical(operator, current)) {
                            current
                        } else {
                            val value = evaluateNamed(expression.value, target.name)
                            writeIdentifierReference(owner, target.name, value)
                            value
                        }
                    }

                    else -> {
                        val current = readIdentifierReference(owner, target.name)
                        val right = evaluate(expression.value)
                        val result = applyBinary(operator.dropLast(1), current, right)
                        writeIdentifierReference(owner, target.name, result)
                        result
                    }
                }
            }

            is MemberExpr -> {
                if (target.obj is SuperExpr) {
                    if (!currentFrame.thisInitialized) {
                        throw referenceError("Must call super constructor before accessing 'super'")
                    }
                    val base = superPropertyBase()
                    val key = memberKey(target)
                    return when {
                        operator == "=" -> {
                            val value = evaluate(expression.value)
                            putSuperProperty(base, currentFrame.thisValue, key, value, currentFrame.strict)
                            value
                        }

                        isLogicalAssignment(operator) -> {
                            val current = getV(base, key)
                            if (!shouldAssignLogical(operator, current)) {
                                current
                            } else {
                                val value = evaluate(expression.value)
                                putSuperProperty(base, currentFrame.thisValue, key, value, currentFrame.strict)
                                value
                            }
                        }

                        else -> {
                            val current = getV(base, key)
                            val right = evaluate(expression.value)
                            val result = applyBinary(operator.dropLast(1), current, right)
                            putSuperProperty(base, currentFrame.thisValue, key, result, currentFrame.strict)
                            result
                        }
                    }
                }
                // 引用求值：base 与计算属性表达式先求值，ToPropertyKey 延迟到读写时；
                // null/undefined 基对象在键转换之前抛 TypeError（规范 PutValue/GetValue 顺序）
                val reference = preEvaluateMemberReference(target)
                    ?: throw syntaxError("Invalid assignment target")
                val base = reference.base
                suspend fun key(): PropertyKey =
                    reference.staticKey ?: toPropertyKey(reference.keyValue ?: JSUndefined)
                when {
                    operator == "=" -> {
                        val value = evaluate(expression.value)
                        if (base is JSNull || base is JSUndefined) {
                            throw typeError("Cannot set properties of null or undefined")
                        }
                        putV(base, key(), value, currentFrame.strict)
                        value
                    }

                    isLogicalAssignment(operator) -> {
                        if (base is JSNull || base is JSUndefined) {
                            throw typeError("Cannot read properties of null or undefined")
                        }
                        val current = getV(base, key())
                        if (!shouldAssignLogical(operator, current)) {
                            current
                        } else {
                            val value = evaluate(expression.value)
                            putV(base, key(), value, currentFrame.strict)
                            value
                        }
                    }

                    else -> {
                        if (base is JSNull || base is JSUndefined) {
                            throw typeError("Cannot read properties of null or undefined")
                        }
                        val current = getV(base, key())
                        val right = evaluate(expression.value)
                        val result = applyBinary(operator.dropLast(1), current, right)
                        putV(base, key(), result, currentFrame.strict)
                        result
                    }
                }
            }

            is ArrayLiteral, is ObjectLiteral -> {
                if (operator != "=") throw syntaxError("Invalid assignment target")
                val value = evaluate(expression.value)
                bindPattern(target, value, kind = null, environment = null)
                value
            }

            else -> throw syntaxError("Invalid assignment target")
        }
    }

    /**
     * 解构绑定/赋值。
     *
     * @param kind 声明种类；null 表示赋值模式
     * @param environment 声明目标环境（kind 为 null 时忽略）
     */
    /**
     * 解构赋值中预先求值的成员引用（引用求值必须早于 IteratorStep）。
     * 计算键的属性名表达式会先求值，但 ToPropertyKey 延迟到真正写入时。
     */
    private class MemberReference(
        val base: JSValue,
        val staticKey: PropertyKey?,
        val keyValue: JSValue?,
    )

    private suspend fun preEvaluateMemberReference(target: Expression): MemberReference? = when (target) {
        is MemberExpr -> {
            val base = evaluate(target.obj)
            if (target.computed) {
                MemberReference(base, null, evaluate(target.property))
            } else {
                MemberReference(base, memberKey(target), null)
            }
        }
        is AssignmentExpr -> preEvaluateMemberReference(target.target)
        else -> null
    }

    private suspend fun bindPattern(
        pattern: Expression,
        value: JSValue,
        kind: DeclarationKind?,
        environment: Environment?,
        reference: MemberReference? = null,
    ) {
        when (pattern) {
            is Identifier -> {
                if (kind == null) {
                    assignIdentifier(pattern.name, value)
                } else {
                    val targetEnvironment = environment
                        ?: throw syntaxError("Invalid destructuring environment")
                    when (kind) {
                        DeclarationKind.VAR ->
                            targetEnvironment.findVarScope().createVarBinding(pattern.name, value)

                        DeclarationKind.LET -> {
                            if (!initializeLexicalIfDeclared(targetEnvironment, pattern.name, value)) {
                                targetEnvironment.createLetBinding(pattern.name, value)
                            }
                        }

                        DeclarationKind.CONST -> {
                            if (!initializeLexicalIfDeclared(targetEnvironment, pattern.name, value)) {
                                targetEnvironment.createConstBinding(pattern.name, value)
                            }
                        }
                    }
                }
            }

            is AssignmentExpr -> {
                // 模式中的默认值：仅当值为 undefined 时求值；
                // 匿名函数/类默认值按绑定标识符推断名称
                val actualValue = if (value is JSUndefined) {
                    val evaluated = evaluate(pattern.value)
                    (pattern.target as? Identifier)?.let { target ->
                        applyInferredFunctionName(pattern.value, evaluated, target.name)
                    }
                    evaluated
                } else {
                    value
                }
                bindPattern(pattern.target, actualValue, kind, environment, reference)
            }

            is ArrayLiteral -> {
                // 数组解构按迭代器协议逐项消费；迭代器已 done 时不再 close，
                // next() 自身的异常直接传播，绑定异常在未 done 时 close 后重抛
                val record = getIterator(value)
                var done = false
                for (element in pattern.elements) {
                    if (element == null) {
                        if (!done) {
                            val next = iteratorNext(record)
                            if (next == null) done = true
                        }
                        continue
                    }
                    if (element is SpreadElement) {
                        // 赋值模式下 rest 目标引用先于迭代求值
                        val reference = if (kind == null) {
                            try {
                                preEvaluateMemberReference(element.argument)
                            } catch (error: JSException) {
                                if (!done) iteratorClose(record, throwCompletion = true)
                                throw error
                            }
                        } else {
                            null
                        }
                        val restValues = ArrayList<JSValue?>()
                        while (!done) {
                            val next = iteratorNext(record)
                            if (next == null) {
                                done = true
                            } else {
                                restValues.add(next)
                            }
                        }
                        try {
                            bindPattern(
                                element.argument,
                                JSArray(restValues, prototype = intrinsics.arrayPrototype),
                                kind,
                                environment,
                                reference,
                            )
                        } catch (error: JSException) {
                            if (!done) iteratorClose(record, throwCompletion = true)
                            throw error
                        }
                    } else {
                        // 赋值模式下目标引用先于 IteratorStep
                        val reference = if (kind == null) {
                            try {
                                preEvaluateMemberReference(element)
                            } catch (error: JSException) {
                                if (!done) iteratorClose(record, throwCompletion = true)
                                throw error
                            }
                        } else {
                            null
                        }
                        val elementValue = if (done) {
                            JSUndefined
                        } else {
                            val next = iteratorNext(record)
                            if (next == null) {
                                done = true
                                JSUndefined
                            } else {
                                next
                            }
                        }
                        try {
                            bindPattern(element, elementValue, kind, environment, reference)
                        } catch (error: JSException) {
                            if (!done) iteratorClose(record, throwCompletion = true)
                            throw error
                        }
                    }
                }
                if (!done) iteratorClose(record, throwCompletion = false)
            }

            is ObjectLiteral -> {
                if (value is JSNull || value is JSUndefined) {
                    throw typeError("Cannot destructure '${typeOf(value)}' as an object")
                }
                val source = toObject(value)
                val usedKeys = mutableSetOf<PropertyKey>()
                for (element in pattern.properties) {
                    when (element) {
                        is ObjectProperty -> {
                            val key = if (element.computed) {
                                toPropertyKey(evaluate(element.key))
                            } else {
                                PropertyKey.Str(propertyName(element.key))
                            }
                            usedKeys.add(key)
                            bindPattern(element.value, getV(source, key), kind, environment)
                        }

                        is ObjectSpreadProperty -> {
                            val restObject = JSObject(prototype = intrinsics.objectPrototype)
                            for (key in source.ownPropertyKeys()) {
                                if (key in usedKeys) continue
                                if (source.getOwnProperty(key)?.enumerable != true) continue
                                restObject.defineDataProperty(
                                    key = key,
                                    value = getV(source, key),
                                    writable = true,
                                    enumerable = true,
                                    configurable = true,
                                )
                            }
                            bindPattern(element.argument, restObject, kind, environment)
                        }
                    }
                }
            }

            is MemberExpr -> {
                if (kind != null) throw syntaxError("Invalid destructuring declaration target")
                val base = reference?.base ?: evaluate(pattern.obj)
                val key = reference?.let { ref ->
                    ref.staticKey ?: toPropertyKey(ref.keyValue ?: JSUndefined)
                } ?: memberKey(pattern)
                putV(base, key, value, currentFrame.strict)
            }

            else -> throw syntaxError("Invalid destructuring target")
        }
    }

    /**
     * 把可迭代值展开为列表（数组、字符串、类数组对象）。
     *
     * Map/Set 与自定义迭代器在后续批次接入。
     */
    /** 迭代器记录：[[Iterator]] 与 [[NextMethod]]。 */
    private class IteratorRecord(val iterator: JSObject, val nextMethod: JSValue)

    /** GetIterator：要求对象具有可调用的 @@iterator。 */
    private suspend fun getIterator(value: JSValue): IteratorRecord {
        if (value is JSNull || value is JSUndefined) {
            throw typeError("Cannot convert undefined or null to object")
        }
        val objectValue = if (value is JSObject) value else toObject(value)
        val iteratorMethod = getV(objectValue, PropertyKey.Sym(intrinsics.symbolIterator))
        if (!isCallable(iteratorMethod)) {
            throw typeError("${describeValue(value)} is not iterable")
        }
        val iteratorValue = callFunction(iteratorMethod, objectValue, emptyList())
        if (iteratorValue !is JSObject) {
            throw typeError("Result of the Symbol.iterator method is not an object")
        }
        val nextMethod = getV(iteratorValue, PropertyKey.Str("next"))
        if (!isCallable(nextMethod)) {
            throw typeError("Iterator does not have a next method")
        }
        return IteratorRecord(iteratorValue, nextMethod)
    }

    /** 取下一个迭代值；返回 null 表示迭代完成，否则返回 value（非空）。 */
    private suspend fun iteratorNext(record: IteratorRecord): JSValue? {
        val result = callFunction(record.nextMethod, record.iterator, emptyList())
        if (result !is JSObject) {
            throw typeError("Iterator result is not an object")
        }
        if (toBoolean(getV(result, PropertyKey.Str("done")))) return null
        return getV(result, PropertyKey.Str("value"))
    }

    /**
     * IteratorClose。
     *
     * @param throwCompletion 因异常关闭时，return 方法自身的错误不覆盖原异常；
     *   正常完成（for-of break/return、解构正常结束）时要求 return() 返回对象。
     */
    private suspend fun iteratorClose(record: IteratorRecord, throwCompletion: Boolean) {
        val returnMethod = try {
            getV(record.iterator, PropertyKey.Str("return"))
        } catch (error: JSException) {
            if (throwCompletion) return
            throw error
        }
        if (returnMethod is JSUndefined || returnMethod is JSNull) return
        if (!isCallable(returnMethod)) {
            if (throwCompletion) return
            throw typeError("Iterator return is not a function")
        }
        val result = try {
            callFunction(returnMethod, record.iterator, emptyList())
        } catch (error: JSException) {
            if (throwCompletion) return
            throw error
        }
        if (!throwCompletion && result !is JSObject) {
            throw typeError("Iterator result is not an object")
        }
    }

    /** 是否具有可调用的 @@iterator（Array.from 的数组式回退判断）。 */
    suspend fun isIterable(value: JSValue): Boolean {
        if (value is JSNull || value is JSUndefined) return false
        val objectValue = if (value is JSObject) value else toObject(value)
        return isCallable(getV(objectValue, PropertyKey.Sym(intrinsics.symbolIterator)))
    }

    /** 按迭代器协议逐项消费；consumer 抛错时关闭迭代器并保留原异常。 */
    suspend fun consumeIterator(value: JSValue, consumer: suspend (JSValue) -> Unit) {
        val record = getIterator(value)
        while (true) {
            val next = iteratorNext(record) ?: break
            try {
                consumer(next)
            } catch (error: JSException) {
                iteratorClose(record, throwCompletion = true)
                throw error
            }
        }
    }

    /** 按迭代器协议收集全部值（abrupt 时关闭迭代器并保留原异常）。 */
    suspend fun iterateValues(value: JSValue): List<JSValue> {
        val record = getIterator(value)
        val results = ArrayList<JSValue>()
        try {
            var guard = 0
            while (guard < 1_000_000) {
                guard++
                val next = iteratorNext(record) ?: break
                results.add(next)
            }
        } catch (error: JSException) {
            iteratorClose(record, throwCompletion = true)
            throw error
        }
        return results
    }

    private fun isLogicalAssignment(operator: String): Boolean =
        operator == "&&=" || operator == "||=" || operator == "??="

    private fun shouldAssignLogical(operator: String, current: JSValue): Boolean = when (operator) {
        "&&=" -> toBoolean(current)
        "||=" -> !toBoolean(current)
        "??=" -> current is JSNull || current is JSUndefined
        else -> false
    }

    private suspend fun evaluateUpdate(expression: UpdateExpr): JSValue {
        val delta = if (expression.operator == "++") 1.0 else -1.0
        return when (val target = expression.target) {
            is Identifier -> {
                val owner = findBindingOwner(target.name)
                val oldValue = readIdentifierReference(owner, target.name)
                val oldNumber = toNumber(oldValue)
                val newValue = JSNumber(oldNumber + delta)
                writeIdentifierReference(owner, target.name, newValue)
                if (expression.prefix) newValue else JSNumber(oldNumber)
            }

            is MemberExpr -> {
                val base = evaluate(target.obj)
                val key = memberKey(target)
                val oldValue = getV(base, key)
                val oldNumber = toNumber(oldValue)
                val newValue = JSNumber(oldNumber + delta)
                putV(base, key, newValue, currentFrame.strict)
                if (expression.prefix) newValue else JSNumber(oldNumber)
            }

            else -> throw syntaxError("Invalid update target")
        }
    }

    private suspend fun evaluateUnary(expression: UnaryOp): JSValue {
        val operator = expression.operator

        // typeof 对未声明标识符返回 "undefined"，而不是抛 ReferenceError
        if (operator == "typeof" && expression.operand is Identifier) {
            val value = currentFrame.lexicalEnv.getBinding(expression.operand.name)
            return JSString(if (value == null) "undefined" else typeOf(value))
        }

        // delete 不能先读取成员值，否则会触发 getter（delete obj.x 应只删除属性）
        if (operator == "delete") {
            return evaluateDelete(expression.operand)
        }

        val operand = evaluate(expression.operand)
        return when (operator) {
            "typeof" -> JSString(typeOf(operand))
            "+" -> JSNumber(toNumber(operand))
            "-" -> JSNumber(-toNumber(operand))
            "!" -> JSBoolean.getJsBoolean(!toBoolean(operand))
            "~" -> JSNumber(toInt32(operand).inv().toDouble())
            "void" -> JSUndefined
            "await" -> {
                // 规范 Await：非 Promise 值先经 PromiseResolve 采纳（thenable 通过微任务调用 then）
                val awaited = if (operand is JSPromise) {
                    operand
                } else {
                    val promise = JSPromise(intrinsics.promisePrototype)
                    PromiseSupport.resolve(this, promise, operand)
                    promise
                }
                val value = try {
                    awaited.awaitResult()
                } catch (error: JSException) {
                    // 拒绝同样要经过一个微任务周期后再抛给 JS 层
                    context.eventLoop.awaitMicrotask()
                    throw error
                }
                // await 至少让出一个微任务周期，即使等待值已就绪
                context.eventLoop.awaitMicrotask()
                value
            }
            else -> throw syntaxError("Unsupported unary operator '$operator'")
        }
    }

    /** delete 运算符。 */
    private suspend fun evaluateDelete(operand: Expression): JSValue {
        return when (operand) {
            is Identifier -> {
                if (currentFrame.strict) {
                    throw syntaxError("Delete of an unqualified identifier in strict mode")
                }
                // with 环境中的标识符删除的是绑定对象的属性；普通声明绑定不可删除
                val owner = findBindingOwner(operand.name)
                when {
                    owner == null -> JSBoolean.TRUE // 无法解析的引用删除返回 true
                    owner is ObjectEnvironment -> JSBoolean.getJsBoolean(
                        owner.bindingObject.deleteOwnProperty(PropertyKey.Str(operand.name))
                    )
                    else -> JSBoolean.FALSE
                }
            }

            is MemberExpr -> {
                // delete super.prop 在运行时抛 ReferenceError
                if (operand.obj is SuperExpr) {
                    throw referenceError("Unsupported reference to 'super'")
                }
                val base = evaluate(operand.obj)
                val key = memberKey(operand)
                if (base is JSObject) {
                    val deleted = base.deleteOwnProperty(key)
                    if (!deleted && currentFrame.strict) {
                        throw typeError("Cannot delete property '${key}'")
                    }
                    JSBoolean.getJsBoolean(deleted)
                } else {
                    JSBoolean.TRUE
                }
            }

            is OptionalChainExpr -> {
                val lastIndex = operand.operations.lastIndex
                val prefix = evaluateOptionalChainInternal(operand, lastIndex)
                val value = prefix.value
                if (prefix.shortCircuited) {
                    JSBoolean.TRUE
                } else {
                    when (val last = operand.operations[lastIndex]) {
                        is OptionalChainMember -> {
                            if (last.optional && (value is JSNull || value is JSUndefined)) {
                                return JSBoolean.TRUE
                            }
                            val key = if (last.computed) {
                                toPropertyKey(evaluate(last.property))
                            } else {
                                PropertyKey.Str((last.property as Identifier).name)
                            }
                            if (value is JSObject) {
                                val deleted = value.deleteOwnProperty(key)
                                if (!deleted && currentFrame.strict) {
                                    throw typeError("Cannot delete property '$key'")
                                }
                                JSBoolean.getJsBoolean(deleted)
                            } else {
                                JSBoolean.TRUE
                            }
                        }

                        is OptionalChainCall -> {
                            // delete a?.b()：调用后按值删除，结果恒为 true
                            evaluateOptionalChain(operand)
                            JSBoolean.TRUE
                        }
                    }
                }
            }

            else -> {
                evaluate(operand)
                JSBoolean.TRUE
            }
        }
    }

    private suspend fun evaluateBinary(expression: BinaryOp): JSValue {
        // 逻辑运算短路
        if (expression.operator == "&&") {
            val left = evaluate(expression.left)
            return if (!toBoolean(left)) left else evaluate(expression.right)
        }
        if (expression.operator == "||") {
            val left = evaluate(expression.left)
            return if (toBoolean(left)) left else evaluate(expression.right)
        }

        val left = evaluate(expression.left)
        val right = evaluate(expression.right)
        return applyBinary(expression.operator, left, right)
    }

    /**
     * 二元运算统一入口（也供复合赋值使用）。
     */
    suspend fun applyBinary(operator: String, left: JSValue, right: JSValue): JSValue {
        return when (operator) {
            "+" -> {
                val leftPrimitive = conversions.toPrimitive(left)
                val rightPrimitive = conversions.toPrimitive(right)
                if (leftPrimitive is JSString || rightPrimitive is JSString) {
                    JSString(
                        conversions.toString(leftPrimitive) +
                            conversions.toString(rightPrimitive)
                    )
                } else {
                    JSNumber(conversions.toNumber(leftPrimitive) + conversions.toNumber(rightPrimitive))
                }
            }

            "-" -> JSNumber(toNumber(left) - toNumber(right))
            "*" -> JSNumber(toNumber(left) * toNumber(right))
            "/" -> JSNumber(toNumber(left) / toNumber(right))
            "%" -> JSNumber(toNumber(left) % toNumber(right))
            "**" -> JSNumber(toNumber(left).pow(toNumber(right)))

            "<" -> JSBoolean.getJsBoolean(compareRelational(left, right) == -1)
            "<=" -> {
                val comparison = compareRelational(left, right)
                JSBoolean.getJsBoolean(comparison == -1 || comparison == 0)
            }

            ">" -> JSBoolean.getJsBoolean(compareRelational(left, right) == 1)
            ">=" -> {
                val comparison = compareRelational(left, right)
                JSBoolean.getJsBoolean(comparison == 1 || comparison == 0)
            }

            "==" -> JSBoolean.getJsBoolean(abstractEquals(left, right))
            "!=" -> JSBoolean.getJsBoolean(!abstractEquals(left, right))
            "===" -> JSBoolean.getJsBoolean(strictEquals(left, right))
            "!==" -> JSBoolean.getJsBoolean(!strictEquals(left, right))

            "&" -> JSNumber((toInt32(left) and toInt32(right)).toDouble())
            "|" -> JSNumber((toInt32(left) or toInt32(right)).toDouble())
            "^" -> JSNumber((toInt32(left) xor toInt32(right)).toDouble())
            "<<" -> {
                // 规范顺序：先 ToInt32(left)，再 ToUint32(right)
                val leftInt = toInt32(left)
                val shift = (toUint32(right) and 31L).toInt()
                JSNumber((leftInt shl shift).toDouble())
            }

            ">>" -> {
                val leftInt = toInt32(left)
                val shift = (toUint32(right) and 31L).toInt()
                JSNumber((leftInt shr shift).toDouble())
            }

            ">>>" -> {
                val leftInt = toUint32(left)
                val shift = (toUint32(right) and 31L).toInt()
                JSNumber((leftInt shr shift).toDouble())
            }

            "in" -> {
                if (right !is JSObject) {
                    throw typeError("Cannot use 'in' operator to search for '${describeValue(left)}' in ${typeOf(right)}")
                }
                JSBoolean.getJsBoolean(right.hasProperty(toPropertyKey(left)))
            }

            "instanceof" -> JSBoolean.getJsBoolean(instanceOf(left, right))

            else -> throw syntaxError("Unsupported binary operator '$operator'")
        }
    }

    /** instanceof：优先使用 Symbol.hasInstance，否则沿原型链查找构造器的 .prototype。 */
    private suspend fun instanceOf(value: JSValue, constructor: JSValue): Boolean {
        if (constructor is JSObject) {
            val hasInstance = getV(
                constructor,
                PropertyKey.Sym(intrinsics.symbolHasInstance),
            )
            if (isCallable(hasInstance)) {
                return toBoolean(
                    callFunction(hasInstance, constructor, listOf(value))
                )
            }
        }

        if (!isCallable(constructor)) {
            throw typeError("Right-hand side of 'instanceof' is not callable")
        }
        // 使用构造器当前的 prototype（可能已被重新赋值）
        val prototypeValue = getV(constructor as JSObject, PropertyKey.PROTOTYPE)
        val prototypeObject = prototypeValue as? JSObject
            ?: throw typeError("Function has non-object prototype in instanceof check")
        if (value !is JSObject) return false

        if (value !is JSObject) return false
        var prototype = value.prototype
        while (prototype != null) {
            if (prototype === prototypeObject) return true
            prototype = prototype.prototype
        }
        return false
    }

    /** 关系比较：返回 -1/0/1；NaN 参与时返回 null（所有关系比较均为 false）。 */
    private suspend fun compareRelational(left: JSValue, right: JSValue): Int? {
        val leftPrimitive = conversions.toPrimitive(left, PreferredType.NUMBER)
        val rightPrimitive = conversions.toPrimitive(right, PreferredType.NUMBER)

        if (leftPrimitive is JSString && rightPrimitive is JSString) {
            val comparison = leftPrimitive.value.compareTo(rightPrimitive.value)
            return if (comparison < 0) -1 else if (comparison > 0) 1 else 0
        }

        val leftNumber = conversions.toNumber(leftPrimitive)
        val rightNumber = conversions.toNumber(rightPrimitive)
        if (leftNumber.isNaN() || rightNumber.isNaN()) return null
        return if (leftNumber < rightNumber) -1 else if (leftNumber > rightNumber) 1 else 0
    }

    /** 计算成员访问的属性键。 */
    private suspend fun memberKey(expression: MemberExpr): PropertyKey {
        return if (expression.computed) {
            toPropertyKey(evaluate(expression.property))
        } else {
            PropertyKey.Str((expression.property as Identifier).name)
        }
    }

    /** 查找当前作用域链中拥有该绑定/属性的环境（Reference 的基环境）。 */
    private suspend fun findBindingOwner(name: String): Environment? {
        var environment: Environment? = currentFrame.lexicalEnv
        while (environment != null) {
            if (environment.hasBindingForReference(name)) return environment
            environment = environment.parent
        }
        return null
    }

    /** 读取标识符 Reference；不存在时抛 ReferenceError。 */
    private suspend fun readIdentifierReference(owner: Environment?, name: String): JSValue {
        val value = when (owner) {
            is ObjectEnvironment -> {
                val key = PropertyKey.Str(name)
                // GetBindingValue：HasBinding 之后绑定可能被删除
                if (!owner.bindingObject.hasProperty(key) && currentFrame.strict) {
                    throw referenceError("$name is not defined")
                }
                getV(owner.bindingObject, key)
            }

            null -> null
            else -> owner.getBinding(name)
        }
        return value ?: throw referenceError("$name is not defined")
    }

    /** 写回标识符 Reference；对象环境即使属性已被删除也写回基对象。 */
    private suspend fun writeIdentifierReference(
        owner: Environment?,
        name: String,
        value: JSValue,
    ) {
        when (owner) {
            is ObjectEnvironment -> {
                val key = PropertyKey.Str(name)
                // SetMutableBinding：严格模式下绑定已被删除需抛 ReferenceError
                if (!owner.bindingObject.hasProperty(key) && currentFrame.strict) {
                    throw referenceError("$name is not defined")
                }
                putV(owner.bindingObject, key, value, currentFrame.strict)
            }

            null -> assignIdentifier(name, value)
            else -> {
                if (!owner.setBinding(name, value, currentFrame.strict)) {
                    assignIdentifier(name, value)
                }
            }
        }
    }

    /** 对标识符赋值；未声明时按严格模式处理。 */
    private suspend fun assignIdentifier(name: String, value: JSValue) {
        val handled = currentFrame.lexicalEnv.setBinding(name, value, currentFrame.strict)
        if (!handled) {
            if (currentFrame.strict) throw referenceError("$name is not defined")
            context.globalObject.defineDataProperty(
                key = PropertyKey.Str(name),
                value = value,
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /** 解析规范数组索引（"0" 或非前导零的十进制非负整数）。 */
    private fun canonicalIndex(key: PropertyKey): Long? {
        val text = (key as? PropertyKey.Str)?.value ?: return null
        if (text.isEmpty() || text.length > 10) return null
        if (text != "0" && text[0] == '0') return null
        var value = 0L
        for (c in text) {
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        // 2^32-1 及以上不是字符串索引
        return if (value >= 4294967295L) null else value
    }

    /** 给 for-in/of 的赋值目标写值。 */
    private suspend fun assignTarget(target: Expression, value: JSValue) {
        when (target) {
            is Identifier -> assignIdentifier(target.name, value)
            is MemberExpr -> {
                val base = evaluate(target.obj)
                val key = memberKey(target)
                putV(base, key, value, currentFrame.strict)
            }

            is ArrayLiteral, is ObjectLiteral ->
                bindPattern(target, value, kind = null, environment = null)

            else -> throw syntaxError("Invalid assignment target")
        }
    }

    /** 收集对象自身与原型链上的可枚举字符串键（for-in）。 */
    private fun enumerableStringKeysIncludingPrototype(objectValue: JSObject): List<String> {
        val keys = LinkedHashSet<String>()
        // 非枚举的自有属性同样会遮蔽原型上的同名属性，因此单独记录已访问键
        val visited = HashSet<String>()
        var current: JSObject? = objectValue
        while (current != null) {
            for (key in current.ownPropertyKeys()) {
                if (key !is PropertyKey.Str) continue
                if (!visited.add(key.value)) continue
                if (current.getOwnProperty(key)?.enumerable == true) {
                    keys.add(key.value)
                }
            }
            current = current.prototype
        }
        return keys.toList()
    }

    /**
     * 创建正则对象。
     *
     * Kotlin Regex 与 JS RegExp 存在少量差异（如命名组语法），
     * 这里覆盖 ES5 常用的匹配、捕获与全局搜索行为。
     */
    /**
     * s 标志：把字符类外的 `.` 展开为 `[\s\S]`。
     * 公共 RegexOption 不提供 DOT_MATCHES_ALL，回退引擎则自行处理 s 标志。
     */
    private fun expandDotAll(pattern: String): String {
        val builder = StringBuilder(pattern.length)
        var inClass = false
        var escaped = false
        for (c in pattern) {
            if (escaped) {
                builder.append(c)
                escaped = false
                continue
            }
            when (c) {
                '\\' -> {
                    builder.append(c)
                    escaped = true
                }

                '[' -> {
                    inClass = true
                    builder.append(c)
                }

                ']' -> {
                    inClass = false
                    builder.append(c)
                }

                '.' -> builder.append(if (inClass) "." else "[\\s\\S]")
                else -> builder.append(c)
            }
        }
        return builder.toString()
    }

    fun createRegExp(pattern: String, flags: String): JSValue {
        validateJsRegexSyntax(pattern)
        if ('u' in flags) {
            validateUnicodeRegexSyntax(pattern)
        }
        val seen = mutableSetOf<Char>()
        val options = mutableSetOf<RegexOption>()
        for (flag in flags) {
            if (!seen.add(flag)) throw syntaxError("Invalid regular expression flags")
            when (flag) {
                'g', 'y' -> Unit
                'i' -> options.add(RegexOption.IGNORE_CASE)
                'm' -> options.add(RegexOption.MULTILINE)
                // Kotlin/JS 等平台的公共 RegexOption 不提供 DOT_MATCHES_ALL，'s' 暂按普通模式处理
                's' -> Unit
                'u' -> Unit
                // d 标志只影响匹配结果（indices 数组），不影响匹配语义
                'd' -> Unit
                else -> throw syntaxError("Invalid regular expression flags")
            }
        }
        fun buildFallback(): JsRegex = try {
            JsRegexEngine(pattern, flags)
        } catch (fallbackError: Throwable) {
            val detail = fallbackError.message?.lineSequence()?.firstOrNull()?.trim()
            throw syntaxError(
                "Invalid regular expression: /$pattern/$flags" +
                    if (detail.isNullOrEmpty()) "" else " ($detail)"
            )
        }

        // 已知 JS 专属语法（空字符类、\0、前向反向引用等）直接使用回退引擎，
        // 因为 Kotlin/Java 的语义与 JS 不同或无法编译；
        // d 标志需要每个捕获组的精确区间，Kotlin 公共 MatchGroup 不暴露位置，
        // 因此同样统一使用回退引擎
        val matcher = if ('d' in flags || requiresJsRegexEngine(pattern)) {
            buildFallback()
        } else {
            val kotlinRegex = try {
                Regex(if ('s' in flags) expandDotAll(pattern) else pattern, options)
            } catch (_: Throwable) {
                null
            }
            if (kotlinRegex != null) {
                KotlinJsRegex(kotlinRegex)
            } else {
                // 仅在 Kotlin Regex 明确不支持、且是已知 JS-only 构造时启用回退，
                // 避免错误接受非法正则。
                if (!hasLookaheadBackReference(pattern) && !requiresJsRegexEngine(pattern)) {
                    throw syntaxError("Invalid regular expression: /$pattern/$flags")
                }
                buildFallback()
            }
        }
        return JSRegExp(
            pattern = pattern,
            flags = flags,
            matcher = matcher,
            prototype = intrinsics.regexpPrototype,
        )
    }

    /** lookahead 中包含数字反向引用的模式（Java 对反向引用语义与 JS 不同）。 */
    private fun hasLookaheadBackReference(pattern: String): Boolean {
        if (!pattern.contains("(?=") && !pattern.contains("(?!")) return false
        return Regex("""\\[1-9]""").containsMatchIn(pattern)
    }

    /**
     * 判断是否必须使用纯 Kotlin 正则引擎：
     * - Kotlin/Java 不支持的空字符类 `[]`、`[^]`、字符类内 `\b`、`\0`；
     * - 前向反向引用（引用尚未参与匹配的捕获组）。
     */
    private fun requiresJsRegexEngine(pattern: String): Boolean {
        val hasJsClassSyntax = pattern.contains("[]") || pattern.contains("[^]") ||
            pattern.contains("\\0") || pattern.contains("\\c") || pattern.contains("\\u{") ||
            pattern.contains("(?:)") ||
            hasBackspaceInsideClass(pattern) || hasBracketInsideClass(pattern)
        if (hasJsClassSyntax) return true
        if (hasLookaheadBackReference(pattern)) return true
        if (hasForwardBackReference(pattern)) return true
        // Java 在量词组内保留上一轮捕获值，JS 要求每轮重置
        return hasQuantifiedCapturingGroup(pattern)
    }

    /** 是否存在被量词的捕获组（其内部捕获需要在每轮迭代前重置）。 */
    private fun hasQuantifiedCapturingGroup(pattern: String): Boolean {
        val stack = mutableListOf<Boolean>()
        var inClass = false
        var index = 0
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> index++
                inClass -> Unit
                c == '[' -> inClass = true
                c == ']' -> inClass = false
                c == '(' -> {
                    val capturing = index + 1 >= pattern.length || pattern[index + 1] != '?'
                    stack.add(capturing)
                }
                c == ')' -> {
                    val hasCapture = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else false
                    if (hasCapture && stack.isNotEmpty()) {
                        stack[stack.size - 1] = true
                    }
                    if (hasCapture && isQuantifierStart(pattern, index + 1)) return true
                }
            }
            index++
        }
        return false
    }

    private fun isQuantifierStart(pattern: String, index: Int): Boolean {
        if (index >= pattern.length) return false
        return when (pattern[index]) {
            '*', '+', '?' -> true
            '{' -> validBraceQuantifierEnd(pattern, index) != null
            else -> false
        }
    }

    /**
     * JS 正则基础语法校验：Kotlin/Java 会接受一些 JS 非法的量词组合
     * （例如 a++、x{1}{1,}），这里在编译前拒绝。
     */
    private fun validateJsRegexSyntax(pattern: String) {
        var index = 0
        var inClass = false
        var lastQuantifier = false
        var lastLazy = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> {
                    index += 2
                    lastQuantifier = false
                    lastLazy = false
                    continue
                }
                c == '[' && !inClass -> {
                    inClass = true
                    lastQuantifier = false
                    lastLazy = false
                }
                c == ']' && inClass -> {
                    inClass = false
                    lastQuantifier = false
                    lastLazy = false
                }
                inClass -> Unit
                c == '*' || c == '+' || c == '?' -> {
                    if (lastQuantifier) {
                        if (c == '?' && !lastLazy) {
                            lastLazy = true
                            lastQuantifier = false
                        } else {
                            throw syntaxError("Invalid regular expression: /$pattern/")
                        }
                    } else if (lastLazy) {
                        throw syntaxError("Invalid regular expression: /$pattern/")
                    } else {
                        lastQuantifier = true
                    }
                }
                c == '{' -> {
                    val end = validBraceQuantifierEnd(pattern, index)
                    if (end != null) {
                        if (lastQuantifier || lastLazy) {
                            throw syntaxError("Invalid regular expression: /$pattern/")
                        }
                        lastQuantifier = true
                        lastLazy = false
                        index = end
                        continue
                    }
                    lastQuantifier = false
                    lastLazy = false
                }
                c == ')' -> {
                    lastQuantifier = false
                    lastLazy = false
                }
                else -> {
                    lastQuantifier = false
                    lastLazy = false
                }
            }
            index++
        }
    }

    /** Unicode 模式（u 标志）的额外早期错误，复用词法器共享校验。 */
    private fun validateUnicodeRegexSyntax(pattern: String) {
        val error = JsRegexSyntax.unicodeError(pattern)
        if (error != null) throw syntaxError(error)
    }

    /** 若 index 处是合法的 {m} / {m,} / {m,n}，返回 '}' 之后的索引。 */
    private fun validBraceQuantifierEnd(pattern: String, index: Int): Int? {
        var cursor = index + 1
        val minStart = cursor
        while (cursor < pattern.length && pattern[cursor] in '0'..'9') cursor++
        if (cursor == minStart) return null
        if (cursor < pattern.length && pattern[cursor] == ',') {
            cursor++
            while (cursor < pattern.length && pattern[cursor] in '0'..'9') cursor++
        }
        if (cursor < pattern.length && pattern[cursor] == '}') return cursor + 1
        return null
    }

    /** JS 允许字符类内部出现未转义的 '['，Kotlin/Java 会拒绝。 */
    private fun hasBracketInsideClass(pattern: String): Boolean {
        var inClass = false
        var index = 0
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> index++
                c == '[' && inClass -> return true
                c == '[' -> inClass = true
                c == ']' && inClass -> inClass = false
            }
            index++
        }
        return false
    }

    private fun hasBackspaceInsideClass(pattern: String): Boolean {
        var inClass = false
        var index = 0
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> {
                    if (inClass && index + 1 < pattern.length && pattern[index + 1] == 'b') return true
                    index++
                }
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
            }
            index++
        }
        return false
    }

    private fun hasForwardBackReference(pattern: String): Boolean {
        var totalGroups = 0
        var index = 0
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> index++
                c == '[' -> {
                    index++
                    while (index < pattern.length && pattern[index] != ']') {
                        if (pattern[index] == '\\') index++
                        index++
                    }
                }
                c == '(' && (index + 1 >= pattern.length || pattern[index + 1] != '?') -> totalGroups++
            }
            index++
        }
        if (totalGroups == 0) return false

        var groupsSeen = 0
        index = 0
        var inClass = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' && index + 1 < pattern.length -> {
                    val next = pattern[index + 1]
                    if (!inClass && next in '1'..'9') {
                        var reference = next - '0'
                        var lookahead = index + 2
                        while (lookahead < pattern.length && pattern[lookahead] in '0'..'9' &&
                            reference * 10 + (pattern[lookahead] - '0') <= totalGroups
                        ) {
                            reference = reference * 10 + (pattern[lookahead] - '0')
                            lookahead++
                        }
                        if (reference > groupsSeen) return true
                    }
                    index++
                }
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
                c == '(' && !inClass && (index + 1 >= pattern.length || pattern[index + 1] != '?') ->
                    groupsSeen++
            }
            index++
        }
        return false
    }

    /** 判断值是否可作为构造器（[[Construct]] 是否存在）。 */
    fun isConstructor(value: JSValue): Boolean = when (value) {
        is JSFunction -> !value.isArrow && !value.isAsync && !value.isMethod
        is JSNativeFunction -> value.functionPrototypeObject != null || value.constructFunction != null
        else -> false
    }

    /** 错误值的可读描述（用于 "xxx is not a function" 之类消息）。 */
    private fun describeValue(value: JSValue): String = try {
        value.toPrimitiveString()
    } catch (_: Throwable) {
        typeOf(value)
    }

    private fun pushStackFrame(name: String, range: SourceRange? = null) {
        callStack.add(StackFrame(name, range))
    }

    private fun popStackFrame() {
        if (callStack.isNotEmpty()) {
            callStack.removeAt(callStack.lastIndex)
        }
    }

    /** 为内部构造的 JSError 补齐内建错误原型，保证 catch 后 constructor 身份正确。 */
    fun normalizeErrorValue(value: JSValue): JSValue {
        if (value !is JSError) return value
        val current = value.prototype
        if (current != null && current !== intrinsics.errorPrototype) return value
        value.prototype = when (value.errorName) {
            "EvalError" -> intrinsics.evalErrorPrototype
            "RangeError" -> intrinsics.rangeErrorPrototype
            "ReferenceError" -> intrinsics.referenceErrorPrototype
            "SyntaxError" -> intrinsics.syntaxErrorPrototype
            "TypeError" -> intrinsics.typeErrorPrototype
            "URIError" -> intrinsics.uriErrorPrototype
            else -> intrinsics.errorPrototype
        }
        return value
    }

    /** 抛出 TypeError。 */
    fun typeError(message: String): Nothing = throw JSException(
        value = JSError("TypeError", message, intrinsics.typeErrorPrototype),
        stack = callStack.toList(),
    )

    /** 抛出 ReferenceError。 */
    fun referenceError(message: String): Nothing = throw JSException(
        value = JSError("ReferenceError", message, intrinsics.referenceErrorPrototype),
        stack = callStack.toList(),
    )

    /** 抛出 SyntaxError。 */
    fun syntaxError(message: String): Nothing = throw JSException(
        value = JSError("SyntaxError", message, intrinsics.syntaxErrorPrototype),
        stack = callStack.toList(),
    )

    /** 抛出 RangeError。 */
    fun rangeError(message: String): Nothing = throw JSException(
        value = JSError("RangeError", message, intrinsics.rangeErrorPrototype),
        stack = callStack.toList(),
    )
}
