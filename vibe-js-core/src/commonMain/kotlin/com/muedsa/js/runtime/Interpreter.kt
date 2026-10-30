package com.muedsa.js.runtime

import com.muedsa.js.ast.ArrayLiteral
import com.muedsa.js.ast.ArrowFunction
import com.muedsa.js.ast.AssignmentExpr
import com.muedsa.js.ast.BinaryOp
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
                if (index != null && index < base.value.length) {
                    JSString(base.value[index].toString())
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

    /** [PropertyAccess] 实现：对象属性读取。 */
    override suspend fun getProperty(base: JSObject, key: PropertyKey): JSValue =
        getPropertyFrom(base, key, base)

    /** [PropertyAccess] 实现：对象属性写入。 */
    override suspend fun setProperty(base: JSObject, key: PropertyKey, value: JSValue, strict: Boolean) {
        putProperty(base, key, value, strict)
    }

    /** [PropertyAccess] 实现：属性存在性判断。 */
    override fun hasProperty(base: JSObject, key: PropertyKey): Boolean = base.hasProperty(key)

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
                    val updated = current.defineOwnProperty(
                        key,
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
        val created = objectValue.defineOwnProperty(
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
    suspend fun callFunction(callee: JSValue, thisValue: JSValue, args: List<JSValue>): JSValue {
        return when (callee) {
            is JSFunction -> callUserFunction(callee, thisValue, args)
            is JSNativeFunction -> {
                pushStackFrame(callee.name)
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
    ): JSValue {
        if (function.isClassConstructor) {
            throw typeError("Class constructor cannot be invoked without 'new'")
        }

        val effectiveThis = if (function.isArrow) {
            function.lexicalThis ?: JSUndefined
        } else if (function.strict) {
            thisValue
        } else if (thisValue is JSUndefined || thisValue is JSNull) {
            context.globalObject
        } else {
            thisValue
        }

        if (function.isAsync) {
            return callAsyncFunction(function, effectiveThis, args)
        }
        return executeUserFunction(function, effectiveThis, args)
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
    ): JSValue {
        val callEnv = DeclarativeEnvironment(parent = function.closure, isVarScope = true)
        val previousFrame = frame
        frame = ExecutionContext(callEnv, thisValue, function.strict, function)
        pushStackFrame(function.name.ifEmpty { "anonymous" })
        try {
            if (!function.isArrow) {
                callEnv.createVarBinding("arguments", createArgumentsObject(args, function))
            }
            bindParameters(function, args, callEnv)
            hoistDeclarations(callEnv, function.functionDeclarations, function.varDeclarations)

            val expressionBody = function.expressionBody
            if (expressionBody != null) {
                return evaluate(expressionBody)
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
        var index = 0
        for (param in function.params) {
            if (param.rest) {
                val restValues = ArrayList<JSValue?>()
                while (index < args.size) {
                    restValues.add(args[index])
                    index++
                }
                environment.createVarBinding(
                    param.name ?: throw syntaxError("Invalid rest parameter"),
                    JSArray(restValues, prototype = intrinsics.arrayPrototype),
                )
            } else {
                var value = args.getOrNull(index) ?: JSUndefined
                index++
                if (value is JSUndefined && param.defaultValue != null) {
                    value = evaluate(param.defaultValue)
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
        if (function.isArrow) {
            throw typeError("${function.name.ifEmpty { "anonymous" }} is not a constructor")
        }
        val instancePrototype = function.functionPrototypeObject ?: intrinsics.objectPrototype
        val instance = JSObject(prototype = instancePrototype)

        val callEnv = DeclarativeEnvironment(parent = function.closure, isVarScope = true)
        val previousFrame = frame
        frame = ExecutionContext(callEnv, instance, function.strict, function, newTarget = function)
        pushStackFrame(function.name.ifEmpty { "anonymous" })
        try {
            callEnv.createVarBinding("arguments", createArgumentsObject(args, function))
            bindParameters(function, args, callEnv)
            hoistDeclarations(callEnv, function.functionDeclarations, function.varDeclarations)

            // 默认派生类构造器：隐式调用父构造器
            if (function.isDefaultDerivedConstructor) {
                val superConstructor = function.superConstructor
                    ?: throw typeError("Super constructor is not a constructor")
                callSuperConstructor(superConstructor, instance, args)
            }

            val body = function.body ?: return instance
            val completion = executeStatements(body.statements)
            return when (completion) {
                is Completion.Return -> if (completion.value is JSObject) completion.value else instance
                else -> instance
            }
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
        lexicalThis: JSValue? = null,
        isClassConstructor: Boolean = false,
        isDefaultDerivedConstructor: Boolean = false,
        homeObject: JSObject? = null,
        superConstructor: JSValue? = null,
        superBase: JSObject? = null,
        functionPrototypeObject: JSObject? = null,
    ): JSFunction {
        val prototypeObject = if (isArrow) {
            null
        } else {
            functionPrototypeObject ?: JSObject(prototype = intrinsics.objectPrototype)
        }
        return JSFunction(
            name = name,
            params = params,
            body = body,
            closure = closure,
            strict = strict,
            prototype = intrinsics.functionPrototype,
            functionPrototypeObject = prototypeObject,
            varDeclarations = varDeclarations,
            functionDeclarations = functionDeclarations,
            expressionBody = expressionBody,
            isArrow = isArrow,
            isAsync = isAsync,
            lexicalThis = lexicalThis,
            isClassConstructor = isClassConstructor,
            isDefaultDerivedConstructor = isDefaultDerivedConstructor,
            homeObject = homeObject,
            superConstructor = superConstructor,
            superBase = superBase,
        )
    }

    /** 创建 arguments 对象（类数组，非 Array 实例）。 */
    private fun createArgumentsObject(args: List<JSValue>, function: JSFunction?): JSObject {
        val arguments = JSObject(prototype = intrinsics.objectPrototype)
        arguments.defineDataProperty(
            key = PropertyKey.LENGTH,
            value = JSNumber(args.size.toDouble()),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        args.forEachIndexed { index, value ->
            arguments.defineDataProperty(
                key = PropertyKey.Str(index.toString()),
                value = value,
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }
        if (function != null) {
            arguments.defineDataProperty(
                key = PropertyKey.Str("callee"),
                value = function,
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
        val expression = try {
            val program = Parser(Lexer(source).tokenize()).parseProgram()
            val statement = program.body.singleOrNull()
            if (statement !is ExpressionStatement) {
                throw syntaxError("Invalid Function constructor arguments")
            }
            statement.expression
        } catch (error: LexerException) {
            throw syntaxError(error.message ?: "Invalid Function constructor arguments")
        } catch (error: ParseException) {
            throw syntaxError(error.message ?: "Invalid Function constructor arguments")
        }

        val function = expression as? FunctionExpression
            ?: throw syntaxError("Invalid Function constructor arguments")
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
            val completion = executeStatement(statement)
            if (completion !is Completion.Normal) return completion
            lastValue = completion.value
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
                val value = declaration.initializer?.let { evaluate(it) } ?: JSUndefined
                when (statement.kind) {
                    DeclarationKind.VAR ->
                        currentFrame.lexicalEnv.findVarScope().createVarBinding(name, value)

                    DeclarationKind.LET -> {
                        if (!initializeLexicalIfDeclared(currentFrame.lexicalEnv, name, value)) {
                            currentFrame.lexicalEnv.createLetBinding(name, value)
                        }
                    }

                    DeclarationKind.CONST -> {
                        if (declaration.initializer == null) {
                            throw syntaxError("Missing initializer in const declaration")
                        }
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
        while (toBoolean(evaluate(statement.condition))) {
            when (val completion = executeStatement(statement.body)) {
                is Completion.Normal -> Unit
                is Completion.Return -> return completion
                is Completion.Break -> {
                    if (completion.label == null || completion.label in labels) break
                    return completion
                }

                is Completion.Continue -> {
                    if (completion.label != null && completion.label !in labels) return completion
                }
            }
        }
        return Completion.Normal(JSUndefined)
    }

    /** do-while 语句。 */
    private suspend fun executeDoWhile(statement: DoWhileStatement, labels: List<String> = emptyList()): Completion {
        do {
            when (val completion = executeStatement(statement.body)) {
                is Completion.Normal -> Unit
                is Completion.Return -> return completion
                is Completion.Break -> {
                    if (completion.label == null || completion.label in labels) {
                        return Completion.Normal(JSUndefined)
                    }
                    return completion
                }

                is Completion.Continue -> {
                    if (completion.label != null && completion.label !in labels) return completion
                }
            }
        } while (toBoolean(evaluate(statement.condition)))
        return Completion.Normal(JSUndefined)
    }

    /** for 语句（支持 let/const 每迭代作用域）。 */
    private suspend fun executeFor(statement: ForStatement, labels: List<String> = emptyList()): Completion {
        val forEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = forEnvironment
        try {
            var loopVariableNames: List<String> = emptyList()
            val init = statement.init
            if (init != null) {
                val initCompletion = executeStatement(init)
                if (initCompletion !is Completion.Normal) return initCompletion
                if (init is VarDeclaration && init.kind != DeclarationKind.VAR) {
                    loopVariableNames = init.declarations.mapNotNull { it.name }
                }
            }

            while (true) {
                val condition = statement.condition?.let { toBoolean(evaluate(it)) } ?: true
                if (!condition) break

                val iterationEnvironment = if (loopVariableNames.isEmpty()) {
                    null
                } else {
                    val env = DeclarativeEnvironment(parent = forEnvironment)
                    for (name in loopVariableNames) {
                        env.createLetBinding(name, forEnvironment.getBinding(name) ?: JSUndefined)
                    }
                    env
                }

                if (iterationEnvironment != null) {
                    currentFrame.lexicalEnv = iterationEnvironment
                }

                var completion: Completion = Completion.Normal(JSUndefined)
                try {
                    completion = executeStatement(statement.body)
                } finally {
                    if (iterationEnvironment != null) {
                        // 把本轮循环变量的值同步回 for 环境，供 update 使用
                        for (name in loopVariableNames) {
                            forEnvironment.createVarBinding(
                                name,
                                iterationEnvironment.getBinding(name) ?: JSUndefined,
                            )
                        }
                        currentFrame.lexicalEnv = forEnvironment
                    }
                }

                when (completion) {
                    is Completion.Normal -> Unit
                    is Completion.Return -> return completion
                    is Completion.Break -> {
                        if (completion.label == null || completion.label in labels) break
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) return completion
                    }
                }

                statement.update?.let { evaluate(it) }
            }
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
        return Completion.Normal(JSUndefined)
    }

    /**
     * for-in 语句。
     *
     * 遍历对象自身与原型链上的可枚举字符串键；let/const 声明为每轮迭代创建新绑定。
     */
    private suspend fun executeForIn(statement: ForInStatement, labels: List<String> = emptyList()): Completion {
        val rightValue = evaluate(statement.right)
        if (rightValue is JSNull || rightValue is JSUndefined) {
            return Completion.Normal(JSUndefined)
        }
        val objectValue = toObject(rightValue)
        val keys = enumerableStringKeysIncludingPrototype(objectValue)

        val forEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = forEnvironment
        try {
            for (key in keys) {
                val propertyKey = PropertyKey.Str(key)
                if (!objectValue.hasProperty(propertyKey)) continue

                val iterationEnvironment = when (statement.declarationKind) {
                    DeclarationKind.LET, DeclarationKind.CONST ->
                        DeclarativeEnvironment(parent = forEnvironment)

                    else -> null
                }
                if (iterationEnvironment != null) {
                    currentFrame.lexicalEnv = iterationEnvironment
                }

                var completion: Completion = Completion.Normal(JSUndefined)
                try {
                    val pattern = statement.pattern
                    val variableName = statement.variableName
                    val kind = statement.declarationKind ?: DeclarationKind.VAR
                    val targetEnvironment = iterationEnvironment ?: forEnvironment
                    when {
                        pattern != null -> bindPattern(pattern, JSString(key), kind, targetEnvironment)
                        variableName != null -> when (kind) {
                            DeclarationKind.VAR -> forEnvironment.findVarScope()
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
                    if (iterationEnvironment != null) {
                        currentFrame.lexicalEnv = forEnvironment
                    }
                }

                when (completion) {
                    is Completion.Normal -> Unit
                    is Completion.Return -> return completion
                    is Completion.Break -> {
                        if (completion.label == null || completion.label in labels) break
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) return completion
                    }
                }
            }
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
        return Completion.Normal(JSUndefined)
    }

    /**
     * for-of 语句：遍历可迭代值。
     */
    private suspend fun executeForOf(statement: ForOfStatement, labels: List<String> = emptyList()): Completion {
        val values = iterateValues(evaluate(statement.right))

        val forEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = forEnvironment
        try {
            for (value in values) {
                val iterationEnvironment = when (statement.declarationKind) {
                    DeclarationKind.LET, DeclarationKind.CONST ->
                        DeclarativeEnvironment(parent = forEnvironment)

                    else -> null
                }
                if (iterationEnvironment != null) {
                    currentFrame.lexicalEnv = iterationEnvironment
                }

                var completion: Completion = Completion.Normal(JSUndefined)
                try {
                    val pattern = statement.pattern
                    val variableName = statement.variableName
                    val kind = statement.declarationKind ?: DeclarationKind.VAR
                    val targetEnvironment = iterationEnvironment ?: forEnvironment
                    when {
                        pattern != null -> bindPattern(pattern, value, kind, targetEnvironment)
                        variableName != null -> when (kind) {
                            DeclarationKind.VAR -> forEnvironment.findVarScope()
                                .createVarBinding(variableName, value)

                            DeclarationKind.LET -> targetEnvironment
                                .createLetBinding(variableName, value)

                            DeclarationKind.CONST -> targetEnvironment
                                .createConstBinding(variableName, value)
                        }

                        else -> statement.target?.let { assignTarget(it, value) }
                    }
                    completion = executeStatement(statement.body)
                } finally {
                    if (iterationEnvironment != null) {
                        currentFrame.lexicalEnv = forEnvironment
                    }
                }

                when (completion) {
                    is Completion.Normal -> Unit
                    is Completion.Return -> return completion
                    is Completion.Break -> {
                        if (completion.label == null || completion.label in labels) break
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) return completion
                    }
                }
            }
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
        return Completion.Normal(JSUndefined)
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
            Completion.Normal(JSUndefined)
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
                execution@ for (index in startIndex until statement.cases.size) {
                    for (inner in statement.cases[index].consequent) {
                        when (val completion = executeStatement(inner)) {
                            is Completion.Normal -> Unit
                            is Completion.Return -> {
                                result = completion
                                break@execution
                            }

                            is Completion.Break -> {
                                result = if (completion.label == null) {
                                    Completion.Normal(JSUndefined)
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
                if (completion !is Completion.Normal) pending = completion
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
            is StringLiteral -> JSString(expression.value)
            is BooleanLiteral -> JSBoolean.getJsBoolean(expression.value)
            is NullLiteral -> JSNull
            is ThisExpr -> currentFrame.thisValue

            is RegExpLiteral -> createRegExp(expression.pattern, expression.flags)
            is TemplateLiteral -> evaluateTemplateLiteral(expression)
            is TaggedTemplate -> evaluateTaggedTemplate(expression)
            is ClassExpression -> evaluateClass(expression.name, expression.superClass, expression.members)
            is SuperExpr -> throw syntaxError("'super' keyword unexpected here")
            is NewTargetExpr -> currentFrame.newTarget ?: JSUndefined

            is ArrayLiteral -> evaluateArrayLiteral(expression)
            is ObjectLiteral -> evaluateObjectLiteral(expression)

            is FunctionExpression -> createFunction(
                name = expression.name ?: "",
                params = expression.params,
                body = expression.body,
                closure = currentFrame.lexicalEnv,
                strict = expression.strict,
                varDeclarations = expression.varDeclarations,
                functionDeclarations = expression.functionDeclarations,
                isAsync = expression.isAsync,
            )

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
            )

            is MemberExpr -> {
                if (expression.obj is SuperExpr) {
                    val superBase = currentFrame.function?.superBase
                        ?: throw syntaxError("'super' keyword unexpected here")
                    getV(superBase, memberKey(expression))
                } else {
                    val base = evaluate(expression.obj)
                    getV(base, memberKey(expression))
                }
            }

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
                    when (element.kind) {
                        PropertyKind.INIT -> result.defineDataProperty(
                            key = key,
                            value = value,
                            writable = true,
                            enumerable = true,
                            configurable = true,
                        )

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
     * 创建 class 构造器与原型方法。
     */
    private suspend fun evaluateClass(
        name: String?,
        superClassExpression: Expression?,
        members: List<ClassMember>,
    ): JSValue {
        val superClassValue = superClassExpression?.let { evaluate(it) }
        if (superClassValue != null && superClassValue !is JSNull &&
            superClassValue !is JSFunction && superClassValue !is JSNativeFunction
        ) {
            throw typeError("Class extends value is not a constructor or null")
        }

        val superPrototype = when (superClassValue) {
            is JSFunction -> superClassValue.functionPrototypeObject
            is JSNativeFunction -> superClassValue.functionPrototypeObject
            else -> null
        }
        val prototype = JSObject(prototype = superPrototype ?: intrinsics.objectPrototype)
        val classEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)

        val constructorMember = members.firstOrNull { it.kind == ClassMemberKind.CONSTRUCTOR }
        val constructor = if (constructorMember != null) {
            createFunction(
                name = name ?: "",
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
                name = name ?: "",
                params = emptyList(),
                body = BlockStatement(emptyList(), SourceRange.NONE),
                closure = classEnvironment,
                strict = true,
                isClassConstructor = true,
                isDefaultDerivedConstructor = superClassValue != null && superClassValue !is JSNull,
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

        for (member in members) {
            if (member.kind == ClassMemberKind.CONSTRUCTOR) continue
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
            val function = createFunction(
                name = if (member.key is Identifier) member.key.name else "",
                params = member.value.params,
                body = member.value.body,
                closure = classEnvironment,
                strict = true,
                varDeclarations = member.value.varDeclarations,
                functionDeclarations = member.value.functionDeclarations,
                isAsync = member.value.isAsync,
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

                ClassMemberKind.CONSTRUCTOR -> Unit
            }
        }

        return constructor
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
                if (superConstructor.isArrow) {
                    throw typeError("Super constructor is not a constructor")
                }
                val newTarget = currentFrame.newTarget
                val callEnv = DeclarativeEnvironment(parent = superConstructor.closure, isVarScope = true)
                val previousFrame = frame
                frame = ExecutionContext(
                    lexicalEnv = callEnv,
                    thisValue = thisValue,
                    strict = true,
                    function = superConstructor,
                    newTarget = newTarget,
                )
                pushStackFrame(superConstructor.name.ifEmpty { "anonymous" })
                try {
                    callEnv.createVarBinding("arguments", createArgumentsObject(args, superConstructor))
                    bindParameters(superConstructor, args, callEnv)
                    hoistDeclarations(
                        callEnv,
                        superConstructor.functionDeclarations,
                        superConstructor.varDeclarations,
                    )
                    if (superConstructor.isDefaultDerivedConstructor) {
                        val parent = superConstructor.superConstructor
                            ?: throw typeError("Super constructor is not a constructor")
                        callSuperConstructor(parent, thisValue, args)
                    }
                    val body = superConstructor.body
                    if (body != null) {
                        val completion = executeStatements(body.statements)
                        if (completion is Completion.Return && completion.value is JSObject) {
                            return completion.value
                        }
                    }
                    thisValue
                } finally {
                    popStackFrame()
                    frame = previousFrame
                }
            }

            is JSNativeFunction -> {
                val result = callFunction(superConstructor, thisValue, args)
                // 原生父构造器（如 Error）会返回新对象；把其自有属性合并到当前实例，
                // 保证 class X extends Error 中 super(message) 后的 name/message 可用。
                if (result is JSObject && thisValue is JSObject && result !== thisValue) {
                    for (key in result.ownPropertyKeys()) {
                        val descriptor = result.getOwnProperty(key) ?: continue
                        thisValue.defineOwnProperty(key, descriptor)
                    }
                }
                thisValue
            }

            else -> throw typeError("Super constructor is not a constructor")
        }
    }

    private suspend fun evaluateCall(expression: CallExpr): JSValue {
        val calleeExpression = expression.callee

        // super(...)：调用父构造器（使用当前 this）
        if (calleeExpression is SuperExpr) {
            val function = currentFrame.function
                ?: throw syntaxError("'super' keyword unexpected here")
            val superConstructor = function.superConstructor
                ?: throw syntaxError("'super' keyword unexpected here")
            val arguments = evaluateArguments(expression.arguments)
            callSuperConstructor(superConstructor, currentFrame.thisValue, arguments)
            return currentFrame.thisValue
        }

        // super.method(...)：在父类原型上查找方法，this 保持当前对象
        if (calleeExpression is MemberExpr && calleeExpression.obj is SuperExpr) {
            val superBase = currentFrame.function?.superBase
                ?: throw syntaxError("'super' keyword unexpected here")
            val key = memberKey(calleeExpression)
            val function = getV(superBase, key)
            val arguments = evaluateArguments(expression.arguments)
            return callFunction(function, currentFrame.thisValue, arguments)
        }

        if (calleeExpression is MemberExpr) {
            // 方法调用：this 为成员访问的基对象
            val base = evaluate(calleeExpression.obj)
            val key = memberKey(calleeExpression)
            val function = getV(base, key)
            val arguments = evaluateArguments(expression.arguments)
            return callFunction(function, base, arguments)
        }

        val function = evaluate(calleeExpression)
        val arguments = evaluateArguments(expression.arguments)
        return callFunction(function, JSUndefined, arguments)
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

    private suspend fun evaluateAssignment(expression: AssignmentExpr): JSValue {
        val operator = expression.operator
        return when (val target = expression.target) {
            is Identifier -> {
                when {
                    operator == "=" -> {
                        val value = evaluate(expression.value)
                        assignIdentifier(target.name, value)
                        value
                    }

                    isLogicalAssignment(operator) -> {
                        val current = currentFrame.lexicalEnv.getBinding(target.name)
                            ?: throw referenceError("${target.name} is not defined")
                        if (!shouldAssignLogical(operator, current)) {
                            current
                        } else {
                            val value = evaluate(expression.value)
                            assignIdentifier(target.name, value)
                            value
                        }
                    }

                    else -> {
                        val current = currentFrame.lexicalEnv.getBinding(target.name)
                            ?: throw referenceError("${target.name} is not defined")
                        val right = evaluate(expression.value)
                        val result = applyBinary(operator.dropLast(1), current, right)
                        assignIdentifier(target.name, result)
                        result
                    }
                }
            }

            is MemberExpr -> {
                val base = evaluate(target.obj)
                val key = memberKey(target)
                when {
                    operator == "=" -> {
                        val value = evaluate(expression.value)
                        putV(base, key, value, currentFrame.strict)
                        value
                    }

                    isLogicalAssignment(operator) -> {
                        val current = getV(base, key)
                        if (!shouldAssignLogical(operator, current)) {
                            current
                        } else {
                            val value = evaluate(expression.value)
                            putV(base, key, value, currentFrame.strict)
                            value
                        }
                    }

                    else -> {
                        val current = getV(base, key)
                        val right = evaluate(expression.value)
                        val result = applyBinary(operator.dropLast(1), current, right)
                        putV(base, key, result, currentFrame.strict)
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
    private suspend fun bindPattern(
        pattern: Expression,
        value: JSValue,
        kind: DeclarationKind?,
        environment: Environment?,
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
                // 模式中的默认值：仅当值为 undefined 时求值
                val actualValue = if (value is JSUndefined) evaluate(pattern.value) else value
                bindPattern(pattern.target, actualValue, kind, environment)
            }

            is ArrayLiteral -> {
                val values = iterateValues(value)
                var index = 0
                for (element in pattern.elements) {
                    if (element == null) {
                        index++
                        continue
                    }
                    if (element is SpreadElement) {
                        val restValues = ArrayList<JSValue?>()
                        while (index < values.size) {
                            restValues.add(values[index])
                            index++
                        }
                        bindPattern(
                            element.argument,
                            JSArray(restValues, prototype = intrinsics.arrayPrototype),
                            kind,
                            environment,
                        )
                    } else {
                        bindPattern(
                            element,
                            values.getOrElse(index) { JSUndefined },
                            kind,
                            environment,
                        )
                        index++
                    }
                }
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
                val base = evaluate(pattern.obj)
                val key = memberKey(pattern)
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
    suspend fun iterateValues(value: JSValue): List<JSValue> {
        return when (value) {
            is JSArray -> {
                val values = ArrayList<JSValue>(value.length)
                for (index in 0 until value.length) {
                    values.add(value.getElement(index) ?: JSUndefined)
                }
                values
            }

            is JSString -> value.value.map { JSString(it.toString()) }

            is JSWeakMap, is JSWeakSet ->
                throw typeError("${describeValue(value)} is not iterable")

            is JSMap -> value.entries.map { (key, entryValue) ->
                JSArray(
                    mutableListOf<JSValue?>(key, entryValue),
                    prototype = intrinsics.arrayPrototype,
                )
            }

            is JSSet -> value.values.toList()

            is JSObject -> {
                val iteratorMethod = getV(value, PropertyKey.Sym(intrinsics.symbolIterator))
                if (isCallable(iteratorMethod)) {
                    val iterator = toObject(callFunction(iteratorMethod, value, emptyList()))
                    val results = ArrayList<JSValue>()
                    var guard = 0
                    while (guard < 1_000_000) {
                        guard++
                        val nextMethod = getV(iterator, PropertyKey.Str("next"))
                        if (!isCallable(nextMethod)) {
                            throw typeError("Iterator does not have a next method")
                        }
                        val nextResult = toObject(callFunction(nextMethod, iterator, emptyList()))
                        if (toBoolean(getV(nextResult, PropertyKey.Str("done")))) break
                        results.add(getV(nextResult, PropertyKey.Str("value")))
                    }
                    results
                } else {
                    val lengthValue = getV(value, PropertyKey.LENGTH)
                    if (lengthValue is JSNumber && lengthValue.value.isFinite()) {
                        val length = lengthValue.value.toInt().coerceAtLeast(0)
                        val values = ArrayList<JSValue>(length)
                        for (index in 0 until length) {
                            values.add(getV(value, PropertyKey.Str(index.toString())))
                        }
                        values
                    } else {
                        throw typeError("${describeValue(value)} is not iterable")
                    }
                }
            }

            else -> throw typeError("${typeOf(value)} is not iterable")
        }
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
                val oldValue = currentFrame.lexicalEnv.getBinding(target.name)
                    ?: throw referenceError("${target.name} is not defined")
                val oldNumber = toNumber(oldValue)
                val newValue = JSNumber(oldNumber + delta)
                assignIdentifier(target.name, newValue)
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

        val operand = evaluate(expression.operand)
        return when (operator) {
            "typeof" -> JSString(typeOf(operand))
            "+" -> JSNumber(toNumber(operand))
            "-" -> JSNumber(-toNumber(operand))
            "!" -> JSBoolean.getJsBoolean(!toBoolean(operand))
            "~" -> JSNumber(toInt32(operand).inv().toDouble())
            "void" -> JSUndefined
            "delete" -> evaluateDelete(expression.operand)
            "await" -> {
                val value = try {
                    if (operand is JSPromise) operand.awaitResult() else operand
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
                JSBoolean.FALSE
            }

            is MemberExpr -> {
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
                val shift = (toUint32(right) and 31L).toInt()
                JSNumber((toInt32(left) shl shift).toDouble())
            }

            ">>" -> {
                val shift = (toUint32(right) and 31L).toInt()
                JSNumber((toInt32(left) shr shift).toDouble())
            }

            ">>>" -> {
                val shift = (toUint32(right) and 31L).toInt()
                JSNumber((toUint32(left) shr shift).toDouble())
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

        val prototypeObject = when (constructor) {
            is JSFunction -> constructor.functionPrototypeObject
            is JSNativeFunction -> constructor.functionPrototypeObject
            else -> throw typeError("Right-hand side of 'instanceof' is not callable")
        } ?: return false

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
    private fun canonicalIndex(key: PropertyKey): Int? {
        val text = (key as? PropertyKey.Str)?.value ?: return null
        if (text.isEmpty() || text.length > 10) return null
        if (text != "0" && text[0] == '0') return null
        var value = 0
        for (c in text) {
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
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
        var current: JSObject? = objectValue
        while (current != null) {
            for (key in current.ownPropertyKeys()) {
                if (key is PropertyKey.Str && current.getOwnProperty(key)?.enumerable == true) {
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
    fun createRegExp(pattern: String, flags: String): JSValue {
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
                else -> throw syntaxError("Invalid regular expression flags")
            }
        }
        val regex = try {
            Regex(pattern, options)
        } catch (_: Throwable) {
            throw syntaxError("Invalid regular expression: /$pattern/$flags")
        }
        return JSRegExp(
            pattern = pattern,
            flags = flags,
            regex = regex,
            prototype = intrinsics.regexpPrototype,
        )
    }

    /** 错误值的可读描述（用于 "xxx is not a function" 之类消息）。 */
    private fun describeValue(value: JSValue): String = try {
        value.toPrimitiveString()
    } catch (_: Throwable) {
        typeOf(value)
    }

    private fun pushStackFrame(name: String) {
        callStack.add(StackFrame(name))
    }

    private fun popStackFrame() {
        if (callStack.isNotEmpty()) {
            callStack.removeAt(callStack.lastIndex)
        }
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
