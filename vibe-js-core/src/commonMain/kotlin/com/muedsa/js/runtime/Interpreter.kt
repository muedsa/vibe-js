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
import com.muedsa.js.ast.ExportAllDeclaration
import com.muedsa.js.ast.ExportDefaultDeclaration
import com.muedsa.js.ast.ExportNamedDeclaration
import com.muedsa.js.ast.Expression
import com.muedsa.js.ast.ExpressionStatement
import com.muedsa.js.ast.ForInStatement
import com.muedsa.js.ast.ForOfStatement
import com.muedsa.js.ast.ForStatement
import com.muedsa.js.ast.FunctionDeclaration
import com.muedsa.js.ast.FunctionExpression
import com.muedsa.js.ast.Identifier
import com.muedsa.js.ast.IfStatement
import com.muedsa.js.ast.ImportCallExpr
import com.muedsa.js.ast.ImportDeclaration
import com.muedsa.js.ast.ImportMetaExpr
import com.muedsa.js.ast.LabeledStatement
import com.muedsa.js.ast.MemberExpr
import com.muedsa.js.ast.ModuleItem
import com.muedsa.js.ast.StatementItem
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
import com.muedsa.js.ast.PrivateIdentifier
import com.muedsa.js.ast.PrivateInExpr
import com.muedsa.js.ast.Program
import com.muedsa.js.ast.PropertyKind
import com.muedsa.js.ast.RegExpLiteral
import com.muedsa.js.ast.ReturnStatement
import com.muedsa.js.ast.SequenceExpr
import com.muedsa.js.ast.SpreadElement
import com.muedsa.js.ast.Statement
import com.muedsa.js.ast.UsingDeclaration
import com.muedsa.js.ast.UsingMode
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
import com.muedsa.js.ast.YieldExpression
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.SourceRange
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.exception.GeneratorReturn
import com.muedsa.js.runtime.exception.StackFrame
import com.muedsa.js.runtime.exception.StackFrameLink
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.internal.JSModuleNamespace
import com.muedsa.js.runtime.module.internal.ModuleRecord
import com.muedsa.js.runtime.module.internal.ModuleRuntime
import com.muedsa.js.runtime.regex.JsRegex
import com.muedsa.js.runtime.regex.JsRegexSyntax
import com.muedsa.js.runtime.regex.JsRegexEngine
import com.muedsa.js.runtime.regex.KotlinJsRegex
import com.muedsa.js.runtime.regex.RegexNamedGroups
import com.muedsa.js.runtime.regex.RegexSyntaxError
import com.muedsa.js.runtime.value.GeneratorChannels
import com.muedsa.js.runtime.value.GeneratorRequest
import com.muedsa.js.runtime.value.GeneratorStep
import com.muedsa.js.runtime.value.InstanceField
import com.muedsa.js.runtime.value.JSArguments
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBigInt
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSGenerator
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSProxy
import com.muedsa.js.runtime.value.JSRegExp
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSTypedArray
import com.muedsa.js.runtime.value.JSTypedArrayType
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.JSWeakMap
import com.muedsa.js.runtime.value.JSWeakSet
import com.muedsa.js.runtime.value.NumberFormatting
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PrivateElementKind
import com.muedsa.js.runtime.value.PrivateEnvironment
import com.muedsa.js.runtime.value.PrivateName
import com.muedsa.js.runtime.value.PrivateSlot
import com.muedsa.js.runtime.value.PropertyKey
import com.muedsa.js.runtime.value.ThisBindingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.coroutines.ContinuationInterceptor

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

    /** 延迟执行的宿主回调共享全局状态，但不得复用仍挂起的词法帧和调用栈。 */
    fun forkForCallback(): Interpreter = Interpreter(context)

    /** 异步内建与 async 函数共用冻结的创建链，避免挂起后复用调用方解释器状态。 */
    internal fun forkForAsync(callRange: SourceRange? = null): Interpreter = Interpreter(context).also { child ->
        child.inheritedCallDepth = inheritedCallDepth + callDepth
        val parent = snapshotStackLink(callRange)
        child.stackHead = parent?.let { StackFrameLink(it.frame.copy(isAsync = true), it.parent) }
        child.currentRange = child.stackHead?.frame?.range
    }

    /** 不可变祖先链与当前执行位置；async 只共享冻结后的祖先，不共享可变解释器状态。 */
    private var stackHead: StackFrameLink? = null
    private var currentRange: SourceRange? = null
    private val currentSourceInfo: SourceInfo get() = stackHead?.frame?.sourceInfo ?: SourceInfo()

    /** 未转换的宿主异常在退栈前留下的位置，供 Engine / async 的包装边界使用。 */
    internal var hostFailureStack: List<StackFrame>? = null
        private set
    private var hostFailure: Throwable? = null

    /** 仅在调用指定的 Promise 拒绝函数期间传递原异常，不给原始 JS 值附加属性。 */
    internal var pendingPromiseRejection: PendingPromiseRejection? = null

    /** 当前解释器的调用深度，不计全局帧；async 另继承创建时的逻辑祖先深度。 */
    private var callDepth = 0
    private var inheritedCallDepth = 0

    /** NamedEvaluation 的名称提示栈：匿名 class 求值前压入，供静态字段/块读取类名。 */
    private val classNameHints = ArrayList<String>()

    /** 类求值期间生效的私有名环境（计算属性名求值等场景）。 */
    private val privateEnvironmentOverrides = ArrayList<PrivateEnvironment>()

    /** using 资源释放作用域栈（语句列表/循环作用域）。 */
    private val disposalScopes = ArrayList<DisposalScope>()

    /** 仍待执行的 catch/finally 数量：大于 0 时返回不参与尾调用优化。 */
    private var tailCallBlockers = 0

    /** 当前执行上下文；运行脚本前为 null。 */
    private var frame: ExecutionContext? = null

    /** 当前模块求值记录；脚本/null 时 import.meta 与 import() 不可用。 */
    internal var currentModuleRecord: ModuleRecord? = null

    /** 本次模块运行的 Provider/加载缓存；脚本运行为 null。 */
    internal var moduleRuntime: ModuleRuntime? = null

    /**
     * 当前生效的模块记录：模块内函数调用优先使用函数所属模块
     * [ExecutionContext.sourceModule]，模块体求值期间回退到 [currentModuleRecord]。
     */
    internal fun resolveModuleRecord(): ModuleRecord? =
        frame?.sourceModule ?: currentModuleRecord

    /**
     * 生成器函数体执行期间绑定的暂停/恢复通道。
     *
     * 只在生成器体所在的独立解释器实例上非空；普通函数调用为 null，
     * 因此 yield 出现在生成器体之外时会明确报错。
     */
    private var generatorChannels: GeneratorChannels? = null

    /**
     * 生成器函数体是否为 async generator。
     *
     * 与 [generatorChannels] 一样只在生成器体所在的独立解释器实例上为 true：
     * 决定 `yield` 是否先 await 产出值、`yield*` 是否按异步迭代协议委托。
     */
    private var generatorIsAsync = false

    /**
     * 当前生成器体对应的函数对象。
     *
     * 生成器体内部调用普通函数时复用同一解释器实例，[generatorIsAsync]
     * 仍为 true；return 语句必须据此判断自己属于生成器体还是嵌套函数，
     * 否则嵌套函数的 `return expr` 会被错误地按 async generator 规则 Await。
     */
    private var generatorFunction: JSFunction? = null

    /**
     * 语句、表达式和循环共用检查计数器，每 1024 个执行检查点观察一次取消。
     *
     * 避免为每个 AST 节点读取协程上下文；检查不挂起协程、不运行微任务，
     * 因此不会改变 JS 的同步执行顺序。它只响应已经发出的取消，不提供时钟
     * 或执行配额；单线程宿主仍须确保取消信号有机会被调度。
     */
    private var stepsUntilCancellationCheck = 1024

    /** 内建长循环也使用同一检查点，统一消费预算并保持协作式取消语义。 */
    internal suspend fun checkExecution() {
        if (--stepsUntilCancellationCheck == 0) {
            // 先复位再检查：取消退出后同一个解释器仍可以执行后续脚本。
            stepsUntilCancellationCheck = 1024
            currentCoroutineContext().ensureActive()
        }
        // 配额需要逐检查点计数，不能按取消检查的间隔批量扣除，否则小预算会失效。
        context.eventLoop.executionBudget?.step()
    }

    /** Annex B RegExp 遗留静态属性状态（input/lastMatch/...）。 */
    var legacyRegExpInput: String = ""
    var legacyRegExpLastMatch: String = ""
    var legacyRegExpLastParen: String = ""
    var legacyRegExpLeftContext: String = ""
    var legacyRegExpRightContext: String = ""

    /** $1..$9 捕获值（未匹配为 null）。 */
    val legacyRegExpCaptures: Array<String?> = arrayOfNulls(9)

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
        if (currentCoroutineContext()[ContinuationInterceptor] !is ExecutionTrampoline) {
            return withExecutionTrampoline { runProgram(program, environment) }
        }
        // 入口立即检查，避免短脚本或空脚本跳过周期性检查后仍创建全局绑定。
        currentCoroutineContext().ensureActive()
        hostFailureStack = null
        hostFailure = null
        val previousFrame = frame
        val programFrame = ExecutionContext(
            lexicalEnv = environment,
            thisValue = context.globalObject,
            strict = program.strict,
        )
        pushStackFrame("<global>", program.range, countsTowardsDepth = false, sourceInfo = program.sourceInfo)
        frame = programFrame
        try {
            // GlobalDeclarationInstantiation：词法绑定先于 var/function 实例化，
            // 冲突与可定义性检查失败时不创建任何 var/function 绑定
            checkGlobalDeclarationInstantiation(environment, program)
            predeclareLexical(program.body, environment)
            hoistDeclarations(environment, program.functionDeclarations, program.varDeclarations)
            val completion = executeStatements(program.body, program.hasUsingDeclaration)
            return when (completion) {
                is Completion.Normal -> completion.value
                is Completion.Return -> completion.value
                is Completion.Break, is Completion.Continue ->
                    throw syntaxError("Illegal break/continue statement")
            }
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
        } finally {
            popStackFrame(countsTowardsDepth = false)
            frame = previousFrame
        }
    }

    /**
     * 执行一个已实例化的模块体。
     *
     * 与 [runProgram] 不同：没有全局声明实例化（已由模块阶段完成），
     * `this` 为 undefined、强制严格模式，并按 ModuleItem 源码顺序跳过 import/export 外壳。
     */
    @OptIn(ExperimentalModuleApi::class)
    internal suspend fun runModule(record: ModuleRecord): JSValue {
        if (currentCoroutineContext()[ContinuationInterceptor] !is ExecutionTrampoline) {
            return withExecutionTrampoline { runModule(record) }
        }
        currentCoroutineContext().ensureActive()
        hostFailureStack = null
        hostFailure = null
        val environment = record.environment
            ?: throw IllegalStateException("Module is not instantiated: ${record.id}")
        val previousFrame = frame
        val previousModule = currentModuleRecord
        val moduleFrame = ExecutionContext(
            lexicalEnv = environment,
            thisValue = JSUndefined,
            strict = true,
        )
        pushStackFrame(
            "<module:${record.id}>",
            record.program.range,
            countsTowardsDepth = false,
            sourceInfo = SourceInfo(record.source.sourceName),
        )
        frame = moduleFrame
        currentModuleRecord = record
        try {
            // 模块顶层 using/await using 的释放作用域覆盖整个模块体，
            // TLA 完成后在 runModule 返回前释放资源。
            withDisposalScope {
                for (item in record.program.items) {
                    executeModuleItem(item)
                }
            }
            return JSUndefined
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
        } finally {
            currentModuleRecord = previousModule
            popStackFrame(countsTowardsDepth = false)
            frame = previousFrame
        }
    }

    private suspend fun executeModuleItem(item: ModuleItem) {
        when (item) {
            is ImportDeclaration, is ExportAllDeclaration -> Unit
            is ExportNamedDeclaration -> item.declaration?.let { executeStatement(it) }
            is ExportDefaultDeclaration -> {
                val declaration = item.declaration
                val expression = item.expression
                if (declaration != null) {
                    executeStatement(declaration)
                } else if (expression != null) {
                    val value = if (isAnonymousFunctionDefinition(expression)) {
                        evaluateNamed(expression, "default")
                    } else {
                        evaluate(expression)
                    }
                    (currentFrame.lexicalEnv as? DeclarativeEnvironment)
                        ?.initializeLexical("*default*", value)
                }
            }

            is StatementItem -> executeStatement(item.statement)
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
            // eval 源码同样受解析期资源限制约束，避免绕过 Engine.parse 的检查。
            context.limits.checkSourceLength(source.length)
            Parser(
                Lexer(source, maxNestingDepth = context.limits.maxNestingDepth).tokenize(),
                strictContext = caller.strict,
                superPropertyContext = callerFunction?.homeObject != null ||
                    callerFunction?.superBase != null,
                superCallContext = callerFunction?.let {
                    it.isClassConstructor && it.superConstructor != null
                } == true,
                newTargetContext = callerFunction != null && callerFunction.allowsNewTarget,
                // 字段初始化器/静态块（含其箭头函数）内的直接 eval 同样禁止 arguments
                argumentsForbiddenContext = callerFunction?.forbidsArguments == true,
                // 直接 eval 可引用调用方词法作用域内的私有名
                evalPrivateNames = callerFunction?.privateEnvironment?.let { environment ->
                    val names = mutableSetOf<String>()
                    environment.collectNames(names)
                    names
                } ?: emptySet(),
                source = source,
                maxNestingDepth = context.limits.maxNestingDepth,
                maxAstNodes = context.limits.maxAstNodes,
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
                // 参数初始化期间（含其内部嵌套的 eval）：从当前词法环境向上到
                // VariableEnvironment 之间，参数环境的同名绑定（含隐式 arguments）
                // 与 var/function 声明冲突（规范 EvalDeclarationInstantiation；
                // Node 对隐式 arguments 有偏离，此处以 Test262 期望为准）。
                if (caller.inParameterInitialization) {
                    for (name in varNames) {
                        var environment: Environment? = caller.lexicalEnv
                        while (environment != null && environment !== caller.variableEnv) {
                            if (environment is DeclarativeEnvironment &&
                                environment.hasOwnBinding(name)
                            ) {
                                throw JSException(
                                    JSError(
                                        "SyntaxError",
                                        "Identifier '$name' has already been declared",
                                    )
                                )
                            }
                            if (environment.isVarScope) break
                            environment = environment.parent
                        }
                    }
                }
                checkEvalVarNameConflicts(caller.lexicalEnv, varNames)
            }
            // 全局作用域下的直接 eval：函数/var 声明先做可定义性检查
            val callerVarEnv = caller.variableEnv ?: caller.lexicalEnv.findVarScope()
            if (callerVarEnv === context.globalEnvironment.findVarScope()) {
                checkEvalGlobalDeclarations(program)
            }
        }
        // 直接 eval 始终拥有独立词法环境；sloppy 的 var/function 提升到调用方 var 作用域
        val lexicalEnvironment = DeclarativeEnvironment(parent = caller.lexicalEnv)
        val varEnvironment = if (evalStrict) {
            DeclarativeEnvironment(parent = lexicalEnvironment, isVarScope = true)
        } else {
            caller.variableEnv ?: caller.lexicalEnv.findVarScope()
        }
        val executionEnvironment = if (evalStrict) varEnvironment else lexicalEnvironment
        val evalFrame = ExecutionContext(
            lexicalEnv = executionEnvironment,
            thisValue = caller.thisValue,
            strict = evalStrict,
            function = callerFunction,
            newTarget = caller.newTarget,
            variableEnv = varEnvironment,
            // 嵌套 eval 仍处于外层函数的参数初始化阶段
            inParameterInitialization = caller.inParameterInitialization,
        ).also { it.sourceModule = caller.sourceModule }
        // 直接 eval 不经过 callFunction，也必须计入深度。先检查再切换执行帧，
        // 避免超限抛错发生在 try/finally 之外时破坏调用方的词法环境。
        pushStackFrame("<eval>", program.range, sourceInfo = SourceInfo("<eval>"))
        frame = evalFrame
        return try {
            hoistDeclarations(
                varEnvironment,
                program.functionDeclarations,
                program.varDeclarations,
                globalDeletable = true,
                deletable = true,
            )
            predeclareLexical(program.body, executionEnvironment)
            when (val completion = executeStatements(program.body, program.hasUsingDeclaration)) {
                is Completion.Normal -> completion.value
                is Completion.Return -> completion.value
                is Completion.Break, is Completion.Continue ->
                    throw syntaxError("Illegal break/continue statement in eval code")
            }
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /** var/function 声明与词法绑定冲突（EvalDeclarationInstantiation）。 */
    private fun checkEvalVarNameConflicts(lexicalEnvironment: Environment, names: List<String>) {
        for (name in names) {
            var environment: Environment? = lexicalEnvironment
            while (environment != null) {
                if (environment is DeclarativeEnvironment && environment.hasOwnLexicalBinding(name)) {
                    // Annex B.3.5：catch 参数环境中的同名绑定不构成早期错误，
                    // 直接 eval 的 var/function 声明按 var 作用域正常提升。
                    if (!environment.isCatchEnvironment) {
                        throw JSException(
                            JSError("SyntaxError", "Identifier '$name' has already been declared")
                        )
                    }
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
            context.limits.checkSourceLength(source.length)
            Parser(
                Lexer(source, maxNestingDepth = context.limits.maxNestingDepth).tokenize(),
                source = source,
                maxNestingDepth = context.limits.maxNestingDepth,
                maxAstNodes = context.limits.maxAstNodes,
            ).parseProgram()
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
            // 全局作用域的间接 eval：函数/var 声明先做可定义性检查
            checkEvalGlobalDeclarations(program)
        }
        // 间接 eval 同样拥有独立词法环境；sloppy 的 var/function 写入全局 var 作用域
        val lexicalEnvironment = DeclarativeEnvironment(parent = context.globalEnvironment)
        val varEnvironment = if (evalStrict) {
            DeclarativeEnvironment(parent = lexicalEnvironment, isVarScope = true)
        } else {
            context.globalEnvironment.findVarScope()
        }
        val executionEnvironment = if (evalStrict) varEnvironment else lexicalEnvironment
        val evalFrame = ExecutionContext(
            lexicalEnv = executionEnvironment,
            thisValue = context.globalObject,
            strict = evalStrict,
            variableEnv = varEnvironment,
        )
        pushStackFrame("<eval>", program.range, sourceInfo = SourceInfo("<eval>"))
        frame = evalFrame
        return try {
            hoistDeclarations(
                varEnvironment,
                program.functionDeclarations,
                program.varDeclarations,
                globalDeletable = true,
            )
            predeclareLexical(program.body, executionEnvironment)
            when (val completion = executeStatements(program.body, program.hasUsingDeclaration)) {
                is Completion.Normal -> completion.value
                is Completion.Return -> completion.value
                is Completion.Break, is Completion.Continue ->
                    throw syntaxError("Illegal break/continue statement in eval code")
            }
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
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

    /**
     * 设置对象原型（OrdinarySetPrototypeOf 的包装）。
     *
     * 环检测已由 [internalSetPrototypeOf] 按规范处理（Proxy 处停止），
     * 这里只做委托，避免对 Proxy 触发意外的 [[GetPrototypeOf]] trap。
     */
    suspend fun setPrototypeOf(objectValue: JSObject, newPrototype: JSObject?): Boolean {
        // `%Object.prototype%` 是不可变原型对象：只有 null 是合法原型。
        if (objectValue === intrinsics.objectPrototype && newPrototype != null) return false
        return internalSetPrototypeOf(objectValue, newPrototype)
    }

    /** ToInt32。 */
    suspend fun toInt32(value: JSValue): Int = conversions.toInt32(value)

    /** ToUint32。 */
    suspend fun toUint32(value: JSValue): Long = conversions.toUint32(value)

    /** ToIndex（0..2^53-1，越界抛 RangeError）。 */
    suspend fun toIndex(value: JSValue): Long = conversions.toIndex(value)

    /** 严格相等。 */
    fun strictEquals(a: JSValue, b: JSValue): Boolean = conversions.strictEquals(a, b)

    /** SameValueZero：NaN 视为相等，+0 与 -0 视为相同（includes / Map 键使用）。 */
    fun sameValueZero(a: JSValue, b: JSValue): Boolean = conversions.sameValueZero(a, b)

    /** SameValue：NaN 视为相等，+0 与 -0 视为不同（Proxy 不变量与描述符比较使用）。 */
    fun sameValue(left: JSValue, right: JSValue): Boolean {
        if (left is JSNumber && right is JSNumber) {
            if (left.value.isNaN() && right.value.isNaN()) return true
            if (left.value == 0.0 && right.value == 0.0) {
                return 1.0 / left.value == 1.0 / right.value
            }
        }
        return strictEquals(left, right)
    }

    /** 抽象相等。 */
    suspend fun abstractEquals(a: JSValue, b: JSValue): Boolean = conversions.abstractEquals(a, b)

    /** 是否为可调用对象。 */
    fun isCallable(value: JSValue): Boolean =
        value is JSFunction || value is JSNativeFunction ||
            (value is JSProxy && isCallable(value.target))

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
        is JSBigInt -> "bigint"
        is JSFunction, is JSNativeFunction -> "function"
        is JSProxy -> if (isCallable(value)) "function" else "object"
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
                JSNumber.of(base.value.length.toDouble())
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
        is JSBigInt -> getPropertyFrom(intrinsics.bigIntPrototype, key, base)
        is JSNull -> throw typeError("Cannot read properties of null (reading '${key}')")
        is JSUndefined -> throw typeError("Cannot read properties of undefined (reading '${key}')")
    }

    /**
     * 写入属性（支持 setter、只读属性与原型链）。
     */
    suspend fun putV(base: JSValue, key: PropertyKey, value: JSValue, strict: Boolean) {
        if (base !is JSObject) {
            // 规范 PutValue：原始值 base 先 ToObject，再以其包装对象执行 [[Set]]，
            // 访问器 setter / 原型链上的 Proxy set trap 的 this 与 receiver 仍是原始值
            if (base is JSNull || base is JSUndefined) {
                if (strict) {
                    throw typeError("Cannot create property '${key}' on ${typeOf(base)}")
                }
                return
            }
            putProperty(wrapPrimitive(base), key, value, strict, receiver = base)
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
            // ES5 10.6：映射索引定义数据属性时同步形参；改为访问器或不可写时解除映射。
            // 映射仍生效时保留底层 getter/setter，只更新描述符标志，避免映射被数据属性截断。
            val mapping = objectValue.parameterMapping(key)
            if (mapping != null) {
                if (descriptor.isAccessor) {
                    objectValue.unmapParameter(key)
                } else {
                    descriptor.value?.let { mapping.write(it) }
                    if (descriptor.writable == false) {
                        objectValue.unmapParameter(key)
                    } else {
                        val current = objectValue.getOwnProperty(key)
                        if (current != null && current.isAccessor) {
                            return objectValue.defineOwnProperty(
                                key,
                                PropertyDescriptor.accessor(
                                    get = current.get,
                                    set = current.set,
                                    enumerable = descriptor.enumerable,
                                    configurable = descriptor.configurable,
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (objectValue is JSArray && key == PropertyKey.LENGTH) {
            if (descriptor.hasAccessorFields) return false
            // ArraySetLength：先 ToUint32（可能触发 valueOf），再 ToNumber（再次触发），
            // 非法长度必须在该步抛 RangeError，早于不可配置等不变量检查
            val newLength = if (!descriptor.hasValue) {
                objectValue.lengthLong
            } else {
                val value = descriptor.value ?: JSUndefined
                toUint32(value)
                val number = toNumber(value)
                if (number.isNaN() || number < 0.0 || number > JSArray.MAX_LENGTH.toDouble() ||
                    number != kotlin.math.floor(number)
                ) {
                    throw rangeError("Invalid array length")
                }
                number.toLong()
            }
            // length 恒为不可枚举、不可配置：只拒绝显式请求 true 的描述符
            if (descriptor.hasEnumerable && descriptor.enumerable) return false
            if (descriptor.hasConfigurable && descriptor.configurable) return false
            // ValidateAndApplyPropertyDescriptor：当前 length 不可写时不能请求 writable = true
            val currentLength = objectValue.getOwnProperty(PropertyKey.LENGTH)
            if (currentLength != null && !currentLength.writable &&
                descriptor.hasWritable && descriptor.writable
            ) {
                return false
            }
            val writable = if (descriptor.hasWritable) descriptor.writable else currentLength?.writable ?: true
            return objectValue.defineLength(newLength, writable)
        }
        if (objectValue is JSTypedArray) {
            when (val classified = objectValue.classifyIndex(key)) {
                // 非规范数字索引字符串：按普通自有属性定义（可以放置自定义属性）
                JSTypedArray.IndexKey.NotIndex -> Unit
                // 规范：非法数字索引的定义一律失败，且不执行值转换
                JSTypedArray.IndexKey.Invalid -> return false
                is JSTypedArray.IndexKey.Valid -> {
                    // 规范：索引不允许改成访问器，也不允许显式请求不可配置/不可枚举/不可写
                    if (descriptor.hasAccessorFields) return false
                    if (descriptor.hasConfigurable && !descriptor.configurable) return false
                    if (descriptor.hasEnumerable && !descriptor.enumerable) return false
                    if (descriptor.hasWritable && !descriptor.writable) return false
                    // 规范只使用 Desc.[[Value]]；描述符没有 value 字段时保持原值
                    if (!descriptor.hasValue) return true
                    // ToNumber 可能触发 valueOf/toString，必须在挂起上下文完成
                    setTypedArrayElement(objectValue, classified.index, descriptor.value ?: JSUndefined)
                    return true
                }
            }
        }
        return objectValue.defineOwnProperty(key, descriptor)
    }

    /**
     * IntegerIndexedElementSet：写入 TypedArray 的元素。
     *
     * 调用方必须已完成下标合法性检查；规范要求越界时静默失败且不做 ToNumber，
     * 因此越界路径不应调用本方法。元素类型转换规则：
     * - 整数类型使用 ToInt32/ToUint32（Uint8Clamped 使用 ToUint8Clamp）；
     * - 浮点类型使用 ToNumber 后按单/双精度存储；
     * - BigInt64/BigUint64 使用 ToBigInt64/ToBigUint64 后写入低 64 位位模式。
     */
    suspend fun setTypedArrayElement(array: JSTypedArray, index: Int, value: JSValue) {
        when (array.type) {
            JSTypedArrayType.INT8, JSTypedArrayType.UINT8,
            JSTypedArrayType.INT16, JSTypedArrayType.UINT16,
            JSTypedArrayType.INT32,
            -> array.setIntElement(index, toInt32(value))

            JSTypedArrayType.UINT32 -> array.setUint32Element(index, toUint32(value))
            JSTypedArrayType.UINT8_CLAMPED -> array.setUint8ClampedElement(index, toNumber(value))
            JSTypedArrayType.FLOAT16 -> array.setFloat16Element(index, toNumber(value))
            JSTypedArrayType.FLOAT32 -> array.setFloat32Element(index, toNumber(value))
            JSTypedArrayType.FLOAT64 -> array.setFloat64Element(index, toNumber(value))
            // BigInt 视图：Number 值会在 ToBigInt 抛 TypeError（规范要求的隐式转换限制）
            JSTypedArrayType.BIGINT64 ->
                array.setBigInt64Element(index, conversions.toBigInt64(value).toInt64Bits())

            JSTypedArrayType.BIGUINT64 ->
                array.setBigUint64Element(index, conversions.toBigUint64(value).toInt64Bits())
        }
    }

    /** [PropertyAccess] 实现：对象属性读取。 */
    override suspend fun getProperty(base: JSObject, key: PropertyKey): JSValue =
        getPropertyFrom(base, key, base)

    /** [PropertyAccess] 实现：对象属性写入。 */
    override suspend fun setProperty(base: JSObject, key: PropertyKey, value: JSValue, strict: Boolean) {
        putProperty(base, key, value, strict)
    }

    /** [PropertyAccess] 实现：属性存在性判断。 */
    override suspend fun hasProperty(base: JSObject, key: PropertyKey): Boolean = internalHasProperty(base, key)

    override fun isStrictContext(): Boolean = frame?.strict ?: false

    // ================================================================
    // 对象内部方法（Ordinary Object Internal Methods）
    //
    // 全部声明为 suspend：Proxy 的 trap 可以执行任意 JS（包括 await），
    // 因此所有 JS 可见的属性操作都必须经过这一层，而不是直接调用
    // JSObject 的非挂起存储方法。Proxy 分支由 proxy* 方法实现。
    // ================================================================

    /** [[GetOwnProperty]]：读取自有属性描述符。 */
    suspend fun internalGetOwnProperty(objectValue: JSObject, key: PropertyKey): PropertyDescriptor? =
        if (objectValue is JSProxy) proxyGetOwnProperty(objectValue, key)
        else objectValue.getOwnProperty(key)

    /** [[DefineOwnProperty]]：定义/覆盖自有属性。 */
    suspend fun internalDefineOwnProperty(
        objectValue: JSObject,
        key: PropertyKey,
        descriptor: PropertyDescriptor,
    ): Boolean =
        if (objectValue is JSProxy) proxyDefineOwnProperty(objectValue, key, descriptor)
        else validateAndApplyOwnProperty(objectValue, key, descriptor)

    /**
     * 普通对象的 ValidateAndApplyPropertyDescriptor + 写入。
     *
     * 这是 [[DefineOwnProperty]] 的规范入口：先按当前描述符校验兼容性，
     * 再把调用方给出的部分描述符合并成完整描述符后写入存储。
     * 数组 length、TypedArray 索引与 arguments 映射的专属规则仍由
     * [defineOwnPropertyOn] 处理。
     */
    private suspend fun validateAndApplyOwnProperty(
        objectValue: JSObject,
        key: PropertyKey,
        descriptor: PropertyDescriptor,
    ): Boolean {
        // 数组 length 的 ArraySetLength 自带转换顺序与不变量（RangeError 必须先于
        // configurable 冲突），因此不进入通用 ValidateAndApply
        if (objectValue is JSArray && key == PropertyKey.LENGTH) {
            return defineOwnPropertyOn(objectValue, key, descriptor)
        }
        var current = objectValue.getOwnProperty(key)
        // ES5 10.6：映射的 arguments 索引在描述符层面表现为可写数据属性，
        // 否则访问器/数据属性的类型不变量会误拒绝合法的形参映射定义
        if (objectValue is JSArguments && current != null && current.isAccessor) {
            val mapping = objectValue.parameterMapping(key)
            if (mapping != null) {
                current = PropertyDescriptor.data(
                    value = mapping.read(),
                    writable = true,
                    enumerable = current.enumerable,
                    configurable = current.configurable,
                )
            }
        }
        val merged = validateAndApplyPropertyDescriptor(
            objectValue = objectValue,
            key = key,
            extensible = objectValue.isExtensible(),
            descriptor = descriptor,
            current = current,
        ) ?: return false
        // 空描述符不改变属性：无需进入存储层，也避免触发 arguments 映射等副作用
        if (descriptor.isEmpty) return true
        return defineOwnPropertyOn(objectValue, key, merged)
    }

    /** [[Delete]]：删除自有属性。 */
    suspend fun internalDeleteProperty(objectValue: JSObject, key: PropertyKey): Boolean =
        if (objectValue is JSProxy) proxyDeleteProperty(objectValue, key)
        else objectValue.deleteOwnProperty(key)

    /** [[OwnPropertyKeys]]：自有属性键（数组索引升序、字符串插入序、Symbol 最后）。 */
    suspend fun internalOwnPropertyKeys(objectValue: JSObject): List<PropertyKey> =
        if (objectValue is JSProxy) proxyOwnPropertyKeys(objectValue)
        else objectValue.ownPropertyKeys()

    /** [[GetPrototypeOf]]：读取原型。 */
    suspend fun internalGetPrototypeOf(objectValue: JSObject): JSObject? =
        if (objectValue is JSProxy) proxyGetPrototypeOf(objectValue)
        else objectValue.prototype

    /**
     * [[SetPrototypeOf]]：设置原型（不可扩展且原型不同则失败）。
     *
     * 普通对象按 OrdinarySetPrototypeOf 检查原型链环：V 沿 [[GetPrototypeOf]]
     * 前进时若回到 O 则返回 false（`Object.setPrototypeOf` 据此抛 TypeError）。
     */
    suspend fun internalSetPrototypeOf(objectValue: JSObject, proto: JSObject?): Boolean {
        if (objectValue is JSProxy) return proxySetPrototypeOf(objectValue, proto)
        if (objectValue.prototype === proto) return true
        if (!objectValue.isExtensible()) return false
        // 按规范遍历候选原型链；Proxy 处停止（其 [[GetPrototypeOf]] 非普通内部方法）。
        // 宿主低层写入可能已经制造环，用 visited 集合保证检测在有限步内结束。
        val visited = HashSet<JSObject>()
        var candidate: JSObject? = proto
        while (candidate != null) {
            if (candidate === objectValue) return false
            if (candidate is JSProxy) break
            if (!visited.add(candidate)) return false
            candidate = candidate.prototype
        }
        objectValue.prototype = proto
        return true
    }

    /** [[IsExtensible]]：是否可扩展。 */
    suspend fun internalIsExtensible(objectValue: JSObject): Boolean =
        if (objectValue is JSProxy) proxyIsExtensible(objectValue)
        else objectValue.isExtensible()

    /** [[PreventExtensions]]：禁止新增属性。 */
    suspend fun internalPreventExtensions(objectValue: JSObject): Boolean {
        if (objectValue is JSProxy) return proxyPreventExtensions(objectValue)
        objectValue.preventExtensions()
        return true
    }

    /**
     * IsArray（ECMAScript 7.2.2）。
     *
     * Proxy 递归检查 target；已撤销的 Proxy 必须抛 TypeError
     * （JSON.stringify / Array.isArray 等调用方依赖该行为）。
     */
    fun isArray(value: JSValue?): Boolean = when (value) {
        is JSArray -> true
        is JSProxy -> {
            if (value.revoked) {
                typeError("Cannot perform 'IsArray' on a proxy that has been revoked")
            }
            isArray(value.target)
        }

        else -> false
    }

    /** LengthOfArrayLike：读取 length 并按 ToLength 截断。 */
    suspend fun lengthOfArrayLike(objectValue: JSObject): Long {
        val number = toNumber(getV(objectValue, PropertyKey.LENGTH))
        if (number.isNaN() || number <= 0.0) return 0L
        if (number >= 9007199254740991.0) return 9007199254740991L
        return kotlin.math.floor(number).toLong()
    }

    /**
     * SetIntegrityLevel：冻结/密封的共享算法。
     *
     * 顺序必须与规范一致：先 [[PreventExtensions]]（失败返回 false，调用方抛
     * TypeError），再取自有属性键，最后逐个 [[GetOwnProperty]] 后定义部分描述符。
     * 部分描述符只带 configurable（冻结时数据属性再带 writable=false），
     * Proxy 的 defineProperty trap 可以观察到字段缺失。
     */
    suspend fun setIntegrityLevel(objectValue: JSObject, frozen: Boolean): Boolean {
        if (!internalPreventExtensions(objectValue)) return false
        for (key in internalOwnPropertyKeys(objectValue)) {
            val current = internalGetOwnProperty(objectValue, key) ?: continue
            val descriptor = PropertyDescriptor.partial()
            descriptor.hasConfigurable = true
            descriptor.configurable = false
            if (frozen && current.isData) {
                descriptor.hasWritable = true
                descriptor.writable = false
            }
            if (!internalDefineOwnProperty(objectValue, key, descriptor)) return false
        }
        return true
    }

    /** [[HasProperty]]：沿原型链判断属性是否存在（不触发 getter）。 */
    suspend fun internalHasProperty(objectValue: JSObject, key: PropertyKey): Boolean {
        if (objectValue is JSProxy) return proxyHasProperty(objectValue, key)
        var current: JSObject? = objectValue
        while (current != null) {
            if (current is JSProxy) return proxyHasProperty(current, key)
            // 整数索引异质对象的 [[HasProperty]]：规范数字索引不进入普通原型链查询
            if (current is JSTypedArray) {
                when (current.classifyIndex(key)) {
                    JSTypedArray.IndexKey.NotIndex -> Unit
                    JSTypedArray.IndexKey.Invalid -> return false
                    is JSTypedArray.IndexKey.Valid -> return true
                }
            }
            // Module namespace 的 [[HasProperty]] 不能读取绑定值；
            // 其他对象仍走 [[GetOwnProperty]]，保持数组/TypedArray 等存储语义。
            if (current is JSModuleNamespace) {
                if (current.hasOwnProperty(key)) return true
            } else if (current.getOwnProperty(key) != null) {
                return true
            }
            current = current.prototype
        }
        return false
    }

    /** [[Get]]，显式指定 receiver（Reflect.get 使用）。 */
    suspend fun getWithReceiver(target: JSObject, key: PropertyKey, receiver: JSValue): JSValue =
        getPropertyFrom(target, key, receiver)

    /**
     * [[Set]]，显式指定 receiver（Reflect.set 使用）。
     *
     * 返回是否成功；不因 strict 抛 TypeError（由调用方决定）。
     */
    suspend fun setWithReceiver(
        target: JSObject,
        key: PropertyKey,
        value: JSValue,
        receiver: JSValue,
    ): Boolean {
        if (target is JSProxy) {
            val trap = proxyTrap(target, "set")
                ?: return setWithReceiver(target.target, key, value, receiver)
            val result = toBoolean(
                callProxyTrap(
                    target,
                    trap,
                    listOf<JSValue>(target.target, propertyKeyToValue(key), value, receiver),
                )
            )
            if (result) {
                val targetDescriptor = internalGetOwnProperty(target.target, key)
                if (targetDescriptor != null && !targetDescriptor.configurable) {
                    if (targetDescriptor.isData && !targetDescriptor.writable &&
                        !sameValue(value, targetDescriptor.value ?: JSUndefined)
                    ) {
                        throw typeError("'set' trap returned true for a non-configurable, non-writable property")
                    }
                    if (targetDescriptor.isAccessor &&
                        (targetDescriptor.set == null || targetDescriptor.set is JSUndefined)
                    ) {
                        throw typeError("'set' trap returned true for a non-configurable accessor without setter")
                    }
                }
            }
            return result
        }
        // 整数索引异质对象的 [[Set]]：规范数字索引由异质逻辑直接处理
        if (target is JSTypedArray) {
            when (val classified = target.classifyIndex(key)) {
                JSTypedArray.IndexKey.NotIndex -> Unit
                // 规范：分离缓冲区的 Integer-Indexed [[Set]] 静默成功（返回 true）；
                // 普通越界索引在接收者为自身时失败（保持既有严格模式行为）
                JSTypedArray.IndexKey.Invalid ->
                    return if (target.isDetached) true else receiver !== target
                is JSTypedArray.IndexKey.Valid -> {
                    if (receiver === target) {
                        setTypedArrayElement(target, classified.index, value)
                        return true
                    }
                    // 接收者不同：OrdinarySet 把值定义到接收者上
                    return ordinarySetOnReceiver(receiver, key, value)
                }
            }
        }
        var current: JSObject? = target
        while (current != null) {
            // 原型链遇到 Proxy：按 OrdinarySetWithOwnDescriptor 步骤 2.b 转发其 [[Set]]
            if (current is JSProxy) {
                return setWithReceiver(current, key, value, receiver)
            }
            // 原型链中的 TypedArray：按 TypedArray [[Set]] 处理；Receiver 与 O
            // 相同时即使索引越界也要完成一次数值转换。
            if (current is JSTypedArray) {
                when (val classified = current.classifyIndex(key)) {
                    JSTypedArray.IndexKey.NotIndex -> Unit
                    JSTypedArray.IndexKey.Invalid -> {
                        if (current === receiver) conversions.toNumeric(value)
                        return true
                    }

                    is JSTypedArray.IndexKey.Valid -> {
                        if (current === receiver) {
                            setTypedArrayElement(current, classified.index, value)
                            return true
                        }
                    }
                }
            }
            val descriptor = internalGetOwnProperty(current, key)
            if (descriptor != null) {
                if (descriptor.isAccessor) {
                    val setter = descriptor.set
                    if (setter == null || !isCallable(setter)) return false
                    callFunction(setter, receiver, listOf(value))
                    return true
                }
                if (!descriptor.writable) return false
                if (current === target) {
                    return ordinarySetOnReceiver(receiver, key, value)
                }
                break
            }
            current = internalGetPrototypeOf(current)
        }
        return ordinarySetOnReceiver(receiver, key, value)
    }

    /**
     * OrdinarySetWithOwnDescriptor 的接收者定义步骤。
     *
     * 接收者已有属性时只更新值并保留原属性标志（Descriptor 只带 [[Value]]，
     * Proxy 的 defineProperty trap 因此能看到部分描述符）；访问器或不可写属性失败。
     * 接收者没有该属性时按 CreateDataProperty 创建可写/可枚举/可配置数据属性。
     */
    private suspend fun ordinarySetOnReceiver(
        receiver: JSValue,
        key: PropertyKey,
        value: JSValue,
    ): Boolean {
        val receiverObject = receiver as? JSObject ?: return false
        val existing = internalGetOwnProperty(receiverObject, key)
        // Module namespace 的 [[Set]] 恒为 false；不能在 OrdinarySet 的
        // “值未变化”路径里误成功。
        if (receiverObject is JSModuleNamespace) {
            return false
        }
        if (existing != null) {
            if (existing.isAccessor || !existing.writable) return false
            val valueDescriptor = PropertyDescriptor.partial()
            valueDescriptor.hasValue = true
            valueDescriptor.value = value
            return internalDefineOwnProperty(receiverObject, key, valueDescriptor)
        }
        return internalDefineOwnProperty(
            receiverObject,
            key,
            PropertyDescriptor.data(value, writable = true, enumerable = true, configurable = true),
        )
    }

    /** CreateListFromArrayLike：按 length 与索引读取类数组（Reflect.apply/construct 使用）。 */
    suspend fun createListFromArrayLike(value: JSValue): List<JSValue> {
        if (value !is JSObject) throw typeError("CreateListFromArrayLike called on non-object")
        val lengthValue = toNumber(getV(value, PropertyKey.LENGTH))
        val length = when {
            lengthValue.isNaN() || lengthValue <= 0.0 -> 0L
            lengthValue >= 9007199254740991.0 -> 9007199254740991L
            else -> kotlin.math.floor(lengthValue).toLong()
        }
        val list = ArrayList<JSValue>(length.toInt().coerceAtMost(1024))
        var index = 0L
        while (index < length) {
            list.add(getV(value, PropertyKey.Str(index.toString())))
            index++
        }
        return list
    }

    // ================================================================
    // Proxy 内部方法（ECMAScript 9.5）
    //
    // 每个方法：取 trap -> 无 trap 时回退 target 的普通行为 -> 调用 trap ->
    // 校验规范不变量。撤销后一律抛 TypeError。
    // ================================================================

    /** 撤销检查。 */
    private fun ensureProxyActive(proxy: JSProxy) {
        if (proxy.revoked) {
            throw typeError("Cannot perform operation on a proxy that has been revoked")
        }
    }

    /** 读取并校验 trap；缺失返回 null。 */
    private suspend fun proxyTrap(proxy: JSProxy, name: String): JSValue? {
        ensureProxyActive(proxy)
        val trap = getV(proxy.handler, PropertyKey.Str(name))
        if (trap is JSUndefined || trap is JSNull) return null
        if (!isCallable(trap)) throw typeError("'$name' trap must be a function")
        return trap
    }

    /** 调用 trap，this 为 handler。 */
    private suspend fun callProxyTrap(
        proxy: JSProxy,
        trap: JSValue,
        args: List<JSValue>,
    ): JSValue = callFunction(trap, proxy.handler, args)

    /** PropertyKey 转为 JS 值（trap 参数使用）。 */
    internal fun propertyKeyToValue(key: PropertyKey): JSValue = when (key) {
        is PropertyKey.Str -> JSString(key.value)
        is PropertyKey.Sym -> key.symbol
    }

    /** ToPropertyDescriptor：读取描述符对象（包括继承字段）。 */
    internal suspend fun toPropertyDescriptor(value: JSValue): PropertyDescriptor? {
        if (value is JSUndefined) return null
        if (value !is JSObject) throw typeError("Property description must be an object")
        val descriptor = PropertyDescriptor()
        val hasValue = internalHasProperty(value, PropertyKey.Str("value"))
        val hasWritable = internalHasProperty(value, PropertyKey.Str("writable"))
        val hasGet = internalHasProperty(value, PropertyKey.Str("get"))
        val hasSet = internalHasProperty(value, PropertyKey.Str("set"))
        val hasEnumerable = internalHasProperty(value, PropertyKey.Str("enumerable"))
        val hasConfigurable = internalHasProperty(value, PropertyKey.Str("configurable"))
        // 记录字段是否存在：Proxy trap 的 Desc 参数与 FromPropertyDescriptor 依赖该信息
        descriptor.hasValue = hasValue
        descriptor.hasWritable = hasWritable
        descriptor.hasGet = hasGet
        descriptor.hasSet = hasSet
        descriptor.hasEnumerable = hasEnumerable
        descriptor.hasConfigurable = hasConfigurable
        if (hasValue) descriptor.value = getV(value, PropertyKey.Str("value"))
        if (hasWritable) descriptor.writable = toBoolean(getV(value, PropertyKey.Str("writable")))
        if (hasGet) {
            val getter = getV(value, PropertyKey.Str("get"))
            if (getter !is JSUndefined && !isCallable(getter)) {
                throw typeError("Getter must be a function")
            }
            descriptor.get = if (getter is JSUndefined) null else getter
        }
        if (hasSet) {
            val setter = getV(value, PropertyKey.Str("set"))
            if (setter !is JSUndefined && !isCallable(setter)) {
                throw typeError("Setter must be a function")
            }
            descriptor.set = if (setter is JSUndefined) null else setter
        }
        if (hasEnumerable) descriptor.enumerable = toBoolean(getV(value, PropertyKey.Str("enumerable")))
        if (hasConfigurable) descriptor.configurable = toBoolean(getV(value, PropertyKey.Str("configurable")))
        if ((hasGet || hasSet) && (hasValue || hasWritable)) {
            throw typeError("Invalid property descriptor: cannot be both accessor and data")
        }
        descriptor.accessor = hasGet || hasSet
        return descriptor
    }

    /** FromPropertyDescriptor：把描述符转换为普通对象（只包含显式存在的字段）。 */
    internal fun fromPropertyDescriptor(descriptor: PropertyDescriptor): JSObject {
        val result = JSObject(prototype = intrinsics.objectPrototype)
        if (descriptor.hasValue) {
            result.defineDataProperty(
                PropertyKey.Str("value"),
                descriptor.value ?: JSUndefined,
                true, true, true,
            )
        }
        if (descriptor.hasWritable) {
            result.defineDataProperty(
                PropertyKey.Str("writable"),
                JSBoolean.getJsBoolean(descriptor.writable),
                true, true, true,
            )
        }
        if (descriptor.hasGet) {
            result.defineDataProperty(PropertyKey.Str("get"), descriptor.get ?: JSUndefined, true, true, true)
        }
        if (descriptor.hasSet) {
            result.defineDataProperty(PropertyKey.Str("set"), descriptor.set ?: JSUndefined, true, true, true)
        }
        if (descriptor.hasEnumerable) {
            result.defineDataProperty(
                PropertyKey.Str("enumerable"),
                JSBoolean.getJsBoolean(descriptor.enumerable),
                true, true, true,
            )
        }
        if (descriptor.hasConfigurable) {
            result.defineDataProperty(
                PropertyKey.Str("configurable"),
                JSBoolean.getJsBoolean(descriptor.configurable),
                true, true, true,
            )
        }
        return result
    }

    /**
     * CompletePropertyDescriptor：为缺失字段补默认值。
     *
     * [[GetOwnProperty]] 的 trap 结果必须先补全，再与 target 描述符比较；
     * 补全规则按描述符类型区分（访问器补 get/set，数据属性补 value/writable）。
     */
    internal fun completePropertyDescriptor(descriptor: PropertyDescriptor): PropertyDescriptor {
        val result = descriptor.copy()
        val isAccessorDescriptor = result.hasAccessorFields
        if (isAccessorDescriptor) {
            if (!result.hasGet) result.get = null
            if (!result.hasSet) result.set = null
            result.hasGet = true
            result.hasSet = true
            result.accessor = true
        } else {
            if (!result.hasValue) result.value = JSUndefined
            if (!result.hasWritable) result.writable = false
            result.hasValue = true
            result.hasWritable = true
            result.accessor = false
        }
        if (!result.hasEnumerable) result.enumerable = false
        if (!result.hasConfigurable) result.configurable = false
        result.hasEnumerable = true
        result.hasConfigurable = true
        return result
    }

    /**
     * IsCompatiblePropertyDescriptor(Extensible, Desc, Current)。
     *
     * 即 ValidateAndApplyPropertyDescriptor 只校验、不写入的分支；
     * 返回 false 表示 Proxy 不变量被破坏（调用方抛 TypeError）。
     */
    internal fun isCompatiblePropertyDescriptor(
        extensible: Boolean,
        descriptor: PropertyDescriptor,
        current: PropertyDescriptor?,
    ): Boolean = validateAndApplyPropertyDescriptor(
        objectValue = null,
        key = null,
        extensible = extensible,
        descriptor = descriptor,
        current = current,
    ) != null

    /**
     * ValidateAndApplyPropertyDescriptor（不含异质对象的专属步骤）。
     *
     * @param objectValue null 表示只校验；否则返回合并后的完整描述符，由调用方写入存储
     * @return 校验失败返回 null；成功返回应用后的完整描述符（`objectValue` 为 null 时
     *   返回值仅表示可应用，不保证字段完整）
     */
    internal fun validateAndApplyPropertyDescriptor(
        objectValue: JSObject?,
        key: PropertyKey?,
        extensible: Boolean,
        descriptor: PropertyDescriptor,
        current: PropertyDescriptor?,
    ): PropertyDescriptor? {
        if (current == null) {
            if (!extensible) return null
            if (objectValue == null) return descriptor
            // 新属性：未提供的字段取默认值，类型由描述符是否包含访问器字段决定
            val created = PropertyDescriptor()
            created.accessor = descriptor.hasAccessorFields
            if (created.accessor) {
                created.get = if (descriptor.hasGet) descriptor.get else null
                created.set = if (descriptor.hasSet) descriptor.set else null
                created.hasGet = true
                created.hasSet = true
            } else {
                created.value = if (descriptor.hasValue) descriptor.value else JSUndefined
                created.writable = descriptor.hasWritable && descriptor.writable
                created.hasValue = true
                created.hasWritable = true
            }
            created.enumerable = descriptor.enumerable
            created.configurable = descriptor.configurable
            created.hasEnumerable = true
            created.hasConfigurable = true
            return created
        }
        if (descriptor.isEmpty) return current
        if (!current.configurable) {
            if (descriptor.hasConfigurable && descriptor.configurable) return null
            if (descriptor.hasEnumerable && descriptor.enumerable != current.enumerable) return null
            if (descriptor.hasAccessorFields && !current.isAccessor) return null
            if (descriptor.hasDataFields && current.isAccessor) return null
            if (current.isData) {
                if (!current.writable) {
                    if (descriptor.hasWritable && descriptor.writable) return null
                    if (descriptor.hasValue &&
                        !sameValue(descriptor.value ?: JSUndefined, current.value ?: JSUndefined)
                    ) {
                        return null
                    }
                }
            } else {
                if (descriptor.hasGet &&
                    !sameValue(descriptor.get ?: JSUndefined, current.get ?: JSUndefined)
                ) {
                    return null
                }
                if (descriptor.hasSet &&
                    !sameValue(descriptor.set ?: JSUndefined, current.set ?: JSUndefined)
                ) {
                    return null
                }
            }
        }
        if (objectValue == null) return descriptor
        val merged = PropertyDescriptor()
        val mergedAccessor = when {
            descriptor.hasAccessorFields -> true
            descriptor.hasDataFields -> false
            else -> current.isAccessor
        }
        merged.accessor = mergedAccessor
        if (mergedAccessor) {
            // 转换为访问器时未提供的字段取 undefined；访问器之间保留旧值
            if (!current.isAccessor) {
                merged.get = if (descriptor.hasGet) descriptor.get else null
                merged.set = if (descriptor.hasSet) descriptor.set else null
            } else {
                merged.get = if (descriptor.hasGet) descriptor.get else current.get
                merged.set = if (descriptor.hasSet) descriptor.set else current.set
            }
            merged.hasGet = true
            merged.hasSet = true
        } else {
            if (current.isAccessor) {
                merged.value = if (descriptor.hasValue) descriptor.value else JSUndefined
                merged.writable = descriptor.hasWritable && descriptor.writable
            } else {
                merged.value = if (descriptor.hasValue) descriptor.value else current.value
                merged.writable = if (descriptor.hasWritable) descriptor.writable else current.writable
            }
            merged.hasValue = true
            merged.hasWritable = true
        }
        merged.enumerable = if (descriptor.hasEnumerable) descriptor.enumerable else current.enumerable
        merged.configurable = if (descriptor.hasConfigurable) descriptor.configurable else current.configurable
        merged.hasEnumerable = true
        merged.hasConfigurable = true
        return merged
    }

    /** [[GetOwnProperty]] 的 Proxy 分支（ECMAScript 10.5.5）。 */
    private suspend fun proxyGetOwnProperty(proxy: JSProxy, key: PropertyKey): PropertyDescriptor? {
        val trap = proxyTrap(proxy, "getOwnPropertyDescriptor")
            ?: return internalGetOwnProperty(proxy.target, key)
        val result = callProxyTrap(proxy, trap, listOf<JSValue>(proxy.target, propertyKeyToValue(key)))
        // 规范步骤顺序：先校验 trap 结果类型，再读取 target 描述符
        if (result !is JSUndefined && result !is JSObject) {
            throw typeError("'getOwnPropertyDescriptor' trap must return an object or undefined")
        }
        val targetDescriptor = internalGetOwnProperty(proxy.target, key)
        if (result is JSUndefined) {
            if (targetDescriptor == null) return null
            if (!targetDescriptor.configurable) {
                throw typeError("'getOwnPropertyDescriptor' trap reported a non-configurable property as non-existent")
            }
            if (!internalIsExtensible(proxy.target)) {
                throw typeError("'getOwnPropertyDescriptor' trap reported a property of a non-extensible target as non-existent")
            }
            return null
        }
        val extensibleTarget = internalIsExtensible(proxy.target)
        val resultDescriptor = completePropertyDescriptor(
            toPropertyDescriptor(result)
                ?: throw typeError("'getOwnPropertyDescriptor' trap must return an object or undefined")
        )
        if (!isCompatiblePropertyDescriptor(extensibleTarget, resultDescriptor, targetDescriptor)) {
            throw typeError("'getOwnPropertyDescriptor' trap returned an incompatible descriptor")
        }
        if (!resultDescriptor.configurable) {
            // 代理不能凭空报告一个不可配置属性：target 必须已有同名的不可配置属性
            if (targetDescriptor == null || targetDescriptor.configurable) {
                throw typeError("'getOwnPropertyDescriptor' trap reported a non-configurable property not present on the target")
            }
            // 规范 10.5.5 步骤 17.b：target 可写而 trap 报告不可写同样违反不变量
            if (resultDescriptor.hasWritable && !resultDescriptor.writable &&
                targetDescriptor.isData && targetDescriptor.writable
            ) {
                throw typeError("'getOwnPropertyDescriptor' trap reported a writable property as non-writable")
            }
        }
        return resultDescriptor
    }

    /** [[DefineOwnProperty]] 的 Proxy 分支（ECMAScript 10.5.6）。 */
    private suspend fun proxyDefineOwnProperty(
        proxy: JSProxy,
        key: PropertyKey,
        descriptor: PropertyDescriptor,
    ): Boolean {
        val trap = proxyTrap(proxy, "defineProperty")
            ?: return internalDefineOwnProperty(proxy.target, key, descriptor)
        // FromPropertyDescriptor 只携带调用方显式给出的字段
        val descriptorObject = fromPropertyDescriptor(descriptor)
        val result = toBoolean(
            callProxyTrap(proxy, trap, listOf<JSValue>(proxy.target, propertyKeyToValue(key), descriptorObject))
        )
        val targetDescriptor = internalGetOwnProperty(proxy.target, key)
        val extensibleTarget = internalIsExtensible(proxy.target)
        val settingConfigFalse = descriptor.hasConfigurable && !descriptor.configurable
        if (!result) {
            // trap 返回 false 时仍要拒绝与 target 不兼容的请求，但自身的 false 合法
            if (targetDescriptor != null) {
                if (!targetDescriptor.configurable) {
                    throw typeError("'defineProperty' trap returned false for a non-configurable property")
                }
                if (!isCompatiblePropertyDescriptor(extensibleTarget, descriptor, targetDescriptor)) {
                    throw typeError("'defineProperty' trap returned false with an incompatible descriptor")
                }
            }
            return false
        }
        if (targetDescriptor == null) {
            if (!extensibleTarget) {
                throw typeError("'defineProperty' trap reported a new property on a non-extensible target")
            }
            if (settingConfigFalse) {
                throw typeError("'defineProperty' trap cannot define a non-configurable property on a target without it")
            }
        } else {
            if (!isCompatiblePropertyDescriptor(extensibleTarget, descriptor, targetDescriptor)) {
                throw typeError("'defineProperty' trap returned true for an incompatible descriptor")
            }
            if (settingConfigFalse && targetDescriptor.configurable) {
                throw typeError("'defineProperty' trap reported a configurable property as non-configurable")
            }
            if (targetDescriptor.isData && !targetDescriptor.configurable &&
                targetDescriptor.writable && descriptor.hasWritable && !descriptor.writable
            ) {
                throw typeError("'defineProperty' trap reported a writable property as non-writable")
            }
        }
        return true
    }

    /** [[Delete]] 的 Proxy 分支（ECMAScript 10.5.10）。 */
    private suspend fun proxyDeleteProperty(proxy: JSProxy, key: PropertyKey): Boolean {
        val trap = proxyTrap(proxy, "deleteProperty")
            ?: return internalDeleteProperty(proxy.target, key)
        val result = toBoolean(callProxyTrap(proxy, trap, listOf<JSValue>(proxy.target, propertyKeyToValue(key))))
        // trap 返回 false 时直接失败；只有声称删除成功才需要校验 target
        if (!result) return false
        val targetDescriptor = internalGetOwnProperty(proxy.target, key)
            ?: return true
        if (!targetDescriptor.configurable) {
            throw typeError("'deleteProperty' trap reported a non-configurable property as deleted")
        }
        if (!internalIsExtensible(proxy.target)) {
            throw typeError("'deleteProperty' trap reported a property of a non-extensible target as deleted")
        }
        return true
    }

    /** [[OwnPropertyKeys]] 的 Proxy 分支（ECMAScript 10.5.11）。 */
    private suspend fun proxyOwnPropertyKeys(proxy: JSProxy): List<PropertyKey> {
        val trap = proxyTrap(proxy, "ownKeys")
            ?: return internalOwnPropertyKeys(proxy.target)
        val result = callProxyTrap(proxy, trap, listOf(proxy.target))
        if (result !is JSObject) throw typeError("'ownKeys' trap must return an object")
        val length = lengthOfArrayLike(result)
        val keys = ArrayList<PropertyKey>(length.toInt().coerceAtMost(1024))
        val seen = HashSet<PropertyKey>()
        for (index in 0 until length) {
            // CreateListFromArrayLike 只接受 String/Symbol：其他类型必须抛 TypeError，
            // 不能像 ToPropertyKey 那样把数字/布尔值静默转成字符串
            val element = getV(result, PropertyKey.Str(index.toString()))
            val key = when (element) {
                is JSString -> PropertyKey.Str(element.value)
                is JSSymbol -> PropertyKey.Sym(element)
                else -> throw typeError("'ownKeys' trap result must contain only strings and symbols")
            }
            if (!seen.add(key)) throw typeError("'ownKeys' trap returned duplicate keys")
            keys.add(key)
        }
        val targetKeys = internalOwnPropertyKeys(proxy.target)
        for (key in targetKeys) {
            val descriptor = internalGetOwnProperty(proxy.target, key)
            if (descriptor != null && !descriptor.configurable && key !in seen) {
                throw typeError("'ownKeys' trap omitted a non-configurable property")
            }
        }
        if (!internalIsExtensible(proxy.target)) {
            if (targetKeys.size != keys.size || !targetKeys.all { it in seen }) {
                throw typeError("'ownKeys' trap returned keys inconsistent with a non-extensible target")
            }
        }
        return keys
    }

    /** [[GetPrototypeOf]] 的 Proxy 分支。 */
    private suspend fun proxyGetPrototypeOf(proxy: JSProxy): JSObject? {
        val trap = proxyTrap(proxy, "getPrototypeOf")
            ?: return internalGetPrototypeOf(proxy.target)
        val result = callProxyTrap(proxy, trap, listOf(proxy.target))
        if (result !is JSObject && result !is JSNull) {
            throw typeError("'getPrototypeOf' trap must return an object or null")
        }
        val proto = result as? JSObject
        if (!internalIsExtensible(proxy.target)) {
            if (internalGetPrototypeOf(proxy.target) !== proto) {
                throw typeError("'getPrototypeOf' trap returned a different prototype for a non-extensible target")
            }
        }
        return proto
    }

    /** [[SetPrototypeOf]] 的 Proxy 分支。 */
    private suspend fun proxySetPrototypeOf(proxy: JSProxy, proto: JSObject?): Boolean {
        val trap = proxyTrap(proxy, "setPrototypeOf")
            ?: return internalSetPrototypeOf(proxy.target, proto)
        val result = toBoolean(
            callProxyTrap(proxy, trap, listOf<JSValue>(proxy.target, proto ?: JSNull))
        )
        if (result && !internalIsExtensible(proxy.target)) {
            if (internalGetPrototypeOf(proxy.target) !== proto) {
                throw typeError("'setPrototypeOf' trap changed the prototype of a non-extensible target")
            }
        }
        return result
    }

    /** [[IsExtensible]] 的 Proxy 分支。 */
    private suspend fun proxyIsExtensible(proxy: JSProxy): Boolean {
        val trap = proxyTrap(proxy, "isExtensible")
            ?: return internalIsExtensible(proxy.target)
        val result = toBoolean(callProxyTrap(proxy, trap, listOf(proxy.target)))
        if (result != internalIsExtensible(proxy.target)) {
            throw typeError("'isExtensible' trap result does not match the target")
        }
        return result
    }

    /** [[PreventExtensions]] 的 Proxy 分支。 */
    private suspend fun proxyPreventExtensions(proxy: JSProxy): Boolean {
        val trap = proxyTrap(proxy, "preventExtensions")
            ?: return internalPreventExtensions(proxy.target)
        val result = toBoolean(callProxyTrap(proxy, trap, listOf(proxy.target)))
        if (result && internalIsExtensible(proxy.target)) {
            throw typeError("'preventExtensions' trap returned true but the target is still extensible")
        }
        return result
    }

    /** [[HasProperty]] 的 Proxy 分支。 */
    private suspend fun proxyHasProperty(proxy: JSProxy, key: PropertyKey): Boolean {
        val trap = proxyTrap(proxy, "has")
            ?: return internalHasProperty(proxy.target, key)
        val result = toBoolean(callProxyTrap(proxy, trap, listOf<JSValue>(proxy.target, propertyKeyToValue(key))))
        if (!result) {
            val targetDescriptor = internalGetOwnProperty(proxy.target, key)
            if (targetDescriptor != null && !targetDescriptor.configurable) {
                throw typeError("'has' trap returned false for a non-configurable property")
            }
            if (!internalIsExtensible(proxy.target)) {
                throw typeError("'has' trap returned false for a non-extensible target")
            }
        }
        return result
    }

    /** [[Call]] 的 Proxy 分支。 */
    private suspend fun proxyApply(
        proxy: JSProxy,
        thisValue: JSValue,
        args: List<JSValue>,
    ): JSValue {
        val trap = proxyTrap(proxy, "apply")
        if (trap == null) {
            if (!isCallable(proxy.target)) throw typeError("Proxy target is not callable")
            return callFunction(proxy.target, thisValue, args)
        }
        if (!isCallable(proxy.target)) throw typeError("'apply' trap requires a callable target")
        val argsArray = JSArray(args.toMutableList(), prototype = intrinsics.arrayPrototype)
        return callProxyTrap(proxy, trap, listOf<JSValue>(proxy.target, thisValue, argsArray))
    }

    /** [[Construct]] 的 Proxy 分支。 */
    private suspend fun proxyConstruct(
        proxy: JSProxy,
        args: List<JSValue>,
        newTarget: JSValue = proxy,
    ): JSValue {
        val trap = proxyTrap(proxy, "construct")
        if (trap == null) {
            if (!isConstructor(proxy.target)) throw typeError("Proxy target is not a constructor")
            return construct(proxy.target, args, newTarget)
        }
        if (!isConstructor(proxy.target)) throw typeError("'construct' trap requires a constructor target")
        val argsArray = JSArray(args.toMutableList(), prototype = intrinsics.arrayPrototype)
        val result = callProxyTrap(proxy, trap, listOf<JSValue>(proxy.target, argsArray, newTarget))
        if (result !is JSObject) throw typeError("'construct' trap must return an object")
        return result
    }

    /** 沿原型链读取属性，访问器使用 receiver 作为 this。 */
    private suspend fun getPropertyFrom(
        objectValue: JSObject?,
        key: PropertyKey,
        receiver: JSValue,
    ): JSValue {
        var current: JSObject? = objectValue
        while (current != null) {
            if (current is JSProxy) {
                val trap = proxyTrap(current, "get")
                if (trap != null) {
                    val result = callProxyTrap(current, trap, listOf<JSValue>(current.target, propertyKeyToValue(key), receiver))
                    val targetDescriptor = internalGetOwnProperty(current.target, key)
                    if (targetDescriptor != null && !targetDescriptor.configurable) {
                        if (targetDescriptor.isData && !targetDescriptor.writable &&
                            !sameValue(result, targetDescriptor.value ?: JSUndefined)
                        ) {
                            throw typeError("'get' trap returned a different value for a non-configurable, non-writable property")
                        }
                        if (targetDescriptor.isAccessor && targetDescriptor.get == null &&
                            !sameValue(result, JSUndefined)
                        ) {
                            throw typeError("'get' trap returned a value for a non-configurable accessor without getter")
                        }
                    }
                    return result
                }
                current = current.target
                continue
            }
            // 整数索引异质对象的 [[Get]]：规范数字索引完全由异质逻辑处理，
            // 非法索引直接返回 undefined，不继续沿原型链查找（也不触发原型 getter）
            if (current is JSTypedArray) {
                when (val classified = current.classifyIndex(key)) {
                    JSTypedArray.IndexKey.NotIndex -> Unit
                    JSTypedArray.IndexKey.Invalid -> return JSUndefined
                    is JSTypedArray.IndexKey.Valid -> return current.getElement(classified.index)
                }
            }
            val descriptor = internalGetOwnProperty(current, key)
            if (descriptor != null) {
                if (descriptor.isAccessor) {
                    val getter = descriptor.get
                    if (getter == null || !isCallable(getter)) return JSUndefined
                    return callFunction(getter, receiver, emptyList())
                }
                return descriptor.value ?: JSUndefined
            }
            current = internalGetPrototypeOf(current)
        }
        return JSUndefined
    }

    /** 写入属性，遵循 setter / 只读 / 原型链规则。 */
    private suspend fun putProperty(
        objectValue: JSObject,
        key: PropertyKey,
        value: JSValue,
        strict: Boolean,
        receiver: JSValue = objectValue,
    ) {
        if (objectValue is JSProxy) {
            val trap = proxyTrap(objectValue, "set")
            if (trap != null) {
                val result = toBoolean(
                    callProxyTrap(objectValue, trap, listOf<JSValue>(objectValue.target, propertyKeyToValue(key), value, receiver))
                )
                if (!result) {
                    if (strict) throw typeError("'set' trap returned falsish")
                    return
                }
                val targetDescriptor = internalGetOwnProperty(objectValue.target, key)
                if (targetDescriptor != null && !targetDescriptor.configurable) {
                    if (targetDescriptor.isData && !targetDescriptor.writable &&
                        !sameValue(value, targetDescriptor.value ?: JSUndefined)
                    ) {
                        throw typeError("'set' trap returned true for a non-configurable, non-writable property")
                    }
                    if (targetDescriptor.isAccessor &&
                        (targetDescriptor.set == null || targetDescriptor.set is JSUndefined)
                    ) {
                        throw typeError("'set' trap returned true for a non-configurable accessor without setter")
                    }
                }
                return
            }
            // 无 trap：OrdinarySet(target, P, V, Receiver = proxy)
            val success = setWithReceiver(objectValue.target, key, value, receiver)
            if (!success && strict) {
                throw typeError("Cannot assign to property '$key'")
            }
            return
        }
        // 整数索引异质对象的 [[Set]]：规范数字索引直接写入元素或失败，不查原型链
        if (objectValue is JSTypedArray) {
            when (val classified = objectValue.classifyIndex(key)) {
                JSTypedArray.IndexKey.NotIndex -> Unit
                JSTypedArray.IndexKey.Invalid -> {
                    // 规范：分离缓冲区的 [[Set]] 返回 true 且不写任何元素，严格模式也不抛错；
                    // 普通越界/非法索引保持既有语义（严格模式由赋值语义抛 TypeError）
                    if (objectValue.isDetached) return
                    if (strict) throw typeError("Cannot assign to read only property '$key'")
                    return
                }
                is JSTypedArray.IndexKey.Valid -> {
                    // ToNumber 在 [[Set]] 内完成，可能调用 valueOf/toString
                    setTypedArrayElement(objectValue, classified.index, value)
                    return
                }
            }
        }
        var current: JSObject? = objectValue
        while (current != null) {
            // 原型链遇到 Proxy：按 OrdinarySetWithOwnDescriptor 步骤 2.b 转发其 [[Set]]，
            // Receiver 仍是最初被赋值的对象/原始值
            if (current is JSProxy) {
                val success = setWithReceiver(current, key, value, receiver)
                if (!success && strict) {
                    throw typeError("Cannot assign to property '$key'")
                }
                return
            }
            // 原型链中的 TypedArray：非法数字索引不再继续查找（等价于 [[Set]] 返回 true）
            if (current is JSTypedArray) {
                when (current.classifyIndex(key)) {
                    JSTypedArray.IndexKey.NotIndex -> Unit
                    JSTypedArray.IndexKey.Invalid -> return
                    is JSTypedArray.IndexKey.Valid -> Unit
                }
            }
            val descriptor = internalGetOwnProperty(current, key)
            if (descriptor != null) {
                if (descriptor.isAccessor) {
                    val setter = descriptor.set
                    if (setter == null || !isCallable(setter)) {
                        if (strict) throw typeError("Cannot set property '${key}' which has only a getter")
                        return
                    }
                    callFunction(setter, receiver, listOf(value))
                    return
                }
                if (!descriptor.writable) {
                    if (strict) throw typeError("Cannot assign to read only property '${key}'")
                    return
                }
                if (current === objectValue) {
                    val updated = ordinarySetOnReceiver(receiver, key, value)
                    if (!updated && strict) {
                        throw typeError("Cannot assign to read only property '${key}'")
                    }
                    return
                }
                break
            }
            current = internalGetPrototypeOf(current)
        }
        val created = ordinarySetOnReceiver(receiver, key, value)
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
        if (callDepth == 0 && currentCoroutineContext()[ContinuationInterceptor] !is ExecutionTrampoline) {
            return withExecutionTrampoline { callFunction(callee, thisValue, args, callRange) }
        }
        checkExecution()
        return when (callee) {
            is JSFunction -> callUserFunction(callee, thisValue, args, callRange)
            is JSNativeFunction -> {
                pushStackFrame(callee.name, callRange)
                try {
                    callee.function(this, thisValue, args).also { drainExecutionStack() }
                } catch (error: Throwable) {
                    captureFailure(error)
                    throw error
                } finally {
                    popStackFrame()
                }
            }

            is JSProxy -> proxyApply(callee, thisValue, args)

            else -> throw typeError("${describeValue(callee)} is not a function")
        }
    }

    /**
     * GetPrototypeFromConstructor(NewTarget, fallback)。
     *
     * newTarget 为 undefined（直接调用）或 prototype 不是对象时回退 fallback。
     */
    suspend fun prototypeFromNewTarget(newTarget: JSValue, fallback: JSObject): JSObject {
        if (newTarget is JSUndefined) return fallback
        val proto = getV(newTarget, PropertyKey.PROTOTYPE)
        if (proto is JSObject) return proto
        // GetPrototypeFromConstructor 步骤 4：prototype 不是对象时 GetFunctionRealm(newTarget)，
        // 已撤销的 Proxy 必须在此抛 TypeError
        if (newTarget is JSProxy && newTarget.revoked) {
            throw typeError("Cannot get function realm from a revoked proxy")
        }
        return fallback
    }

    /**
     * 构造实例（new）。
     *
     * @param newTarget 规范中的 NewTarget；默认等于 constructor，Reflect.construct 可显式指定。
     */
    suspend fun construct(
        constructor: JSValue,
        args: List<JSValue>,
        newTarget: JSValue = constructor,
    ): JSValue {
        if (callDepth == 0 && currentCoroutineContext()[ContinuationInterceptor] !is ExecutionTrampoline) {
            return withExecutionTrampoline { construct(constructor, args, newTarget) }
        }
        checkExecution()
        return when (constructor) {
            is JSFunction -> constructUserFunction(constructor, args, newTarget)
            is JSNativeFunction -> {
                val customConstruct = constructor.constructFunction
                if (customConstruct != null) {
                    pushStackFrame(constructor.name)
                    val previousConstructCall = nativeConstructCall
                    nativeConstructCall = true
                    try {
                        val result = customConstruct(this, args, newTarget)
                        if (result is JSObject) {
                            result
                        } else {
                            // 包装器构造器（Boolean/Number/String）返回原始值：
                            // 用 NewTarget.prototype 包装，而不是默认原型
                            wrapPrimitive(
                                result,
                                prototypeFromNewTarget(
                                    newTarget,
                                    constructor.functionPrototypeObject ?: intrinsics.objectPrototype,
                                ),
                            )
                        }
                    } catch (error: Throwable) {
                        captureFailure(error)
                        throw error
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
                    } catch (error: Throwable) {
                        captureFailure(error)
                        throw error
                    } finally {
                        nativeConstructCall = previousConstructCall
                        popStackFrame()
                    }
                }
            }

            is JSProxy -> proxyConstruct(constructor, args, newTarget)

            else -> throw typeError("${describeValue(constructor)} is not a constructor")
        }
    }

    /**
     * 把原始值包装为对象（new String/Number/Boolean/Symbol 与 Object(primitive)）。
     */
    fun wrapPrimitive(value: JSValue, prototypeOverride: JSObject? = null): JSObject {
        val prototype = prototypeOverride ?: when (value) {
            is JSString -> intrinsics.stringPrototype
            is JSNumber -> intrinsics.numberPrototype
            is JSBoolean -> intrinsics.booleanPrototype
            is JSSymbol -> intrinsics.symbolPrototype
            is JSBigInt -> intrinsics.bigIntPrototype
            else -> intrinsics.objectPrototype
        }
        val wrapper = JSBoxedPrimitive(value, prototype)
        if (value is JSString) {
            wrapper.defineDataProperty(
                key = PropertyKey.LENGTH,
                value = JSNumber.of(value.value.length.toDouble()),
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

    /**
     * 调用用户函数；尾调用通过 [TailCallSignal] 回到此循环，避免持续增长调用栈。
     */
    private suspend fun callUserFunction(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSValue {
        var target = function
        var targetThis = thisValue
        var targetArgs = args
        var targetRange = callRange
        while (true) {
            checkExecution()
            try {
                return callUserFunctionOnce(target, targetThis, targetArgs, targetRange)
            } catch (signal: TailCallSignal) {
                target = signal.callee ?: throw signal
                targetThis = signal.thisValue
                targetArgs = signal.args
                // 尾调用已移除旧帧，父帧仍应停在最初调用点，不能借用被省略函数的源码范围。
                targetRange = null
            }
        }
    }

    /** 尾调用信号：复用同一实例，避免深递归中反复创建异常与采集调用栈。 */
    private class TailCallSignal : RuntimeException() {
        var callee: JSFunction? = null
        var thisValue: JSValue = JSUndefined
        var args: List<JSValue> = emptyList()
        var callRange: SourceRange? = null
    }

    private val tailCallSignal = TailCallSignal()

    private fun throwTailCall(
        callee: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange?,
    ): Nothing {
        tailCallSignal.callee = callee
        tailCallSignal.thisValue = thisValue
        tailCallSignal.args = args
        tailCallSignal.callRange = callRange
        throw tailCallSignal
    }

    /** 当前返回表达式是否处于可优化的尾位置。 */
    private fun canUseTailCall(): Boolean {
        if (tailCallBlockers > 0) return false
        val active = frame ?: return false
        if (!active.strict || active.isConstructor) return false
        return active.function?.isAsync != true
    }

    /** 非尾调用路径直接执行；尾位置且被调用者是用户函数时抛出信号。 */
    private suspend fun invokeOrTail(
        callee: JSValue,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange?,
        tail: Boolean,
    ): JSValue {
        if (tail && callee is JSFunction && !callee.isAsync && !callee.isClassConstructor) {
            throwTailCall(callee, thisValue, args, callRange)
        }
        return callFunction(callee, thisValue, args, callRange)
    }

    /** 调用用户函数（单次执行）。 */
    private suspend fun callUserFunctionOnce(
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

        if (function.isGenerator) {
            // async generator（L1.4）：调用同样只创建对象，next/return/throw 返回 Promise
            return if (function.isAsync) {
                createAsyncGeneratorObject(function, effectiveThis, args, callRange)
            } else {
                createGeneratorObject(function, effectiveThis, args, callRange)
            }
        }
        if (function.isAsync) {
            return callAsyncFunction(function, effectiveThis, args, callRange)
        }
        return executeUserFunction(function, effectiveThis, args, callRange)
    }

    /**
     * 创建生成器对象（GeneratorStart 的创建阶段）。
     *
     * 规范要求生成器函数被调用时先完成 FunctionDeclarationInstantiation
     * （参数默认值、this、arguments 与参数 TDZ），再创建 suspendedStart 的生成器。
     * 实例的 [[Prototype]] 取生成器函数当前的 `.prototype` 属性（脚本可改写），
     * 该属性不是对象时回退 `%GeneratorPrototype%`。
     */
    private suspend fun createGeneratorObject(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSGenerator {
        // 参数绑定可能执行用户代码（默认值）并抛出异常，必须在调用栈上同步完成
        val scope = prepareGeneratorFunctionScope(function, thisValue, args, callRange)
        return JSGenerator(
            interpreter = this,
            function = function,
            thisValue = thisValue,
            scope = scope,
            // 规范顺序：参数初始化之后才读取函数的 .prototype
            prototype = prototypeFromNewTarget(function, intrinsics.generatorPrototype),
        )
    }

    /**
     * 创建 async generator 对象（AsyncGeneratorStart 的创建阶段）。
     *
     * 参数绑定时机与同步生成器一致：调用时求值默认值并创建参数环境，
     * 参数默认值中的异常同步抛给调用方，而不是变成 next() 的 Promise 拒绝。
     * 实例原型同样取函数当前的 `.prototype` 属性，不是对象时回退
     * `%AsyncGeneratorPrototype%`；`next`/`return`/`throw` 由该原型提供 Promise 化实现。
     */
    private suspend fun createAsyncGeneratorObject(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSGenerator {
        val scope = prepareGeneratorFunctionScope(function, thisValue, args, callRange)
        return JSGenerator(
            interpreter = this,
            function = function,
            thisValue = thisValue,
            scope = scope,
            prototype = prototypeFromNewTarget(function, intrinsics.asyncGeneratorPrototype),
            isAsync = true,
        )
    }

    /**
     * 生成器调用阶段的 FunctionDeclarationInstantiation。
     *
     * 在调用方解释器的调用栈上创建参数环境、arguments 并绑定形参；默认值求值、
     * 参数 TDZ 与 this/arguments 引用都发生在生成器对象返回之前。返回的
     * [FunctionScope] 交给首次 next 的函数体协程继续使用，避免重复绑定。
     */
    private suspend fun prepareGeneratorFunctionScope(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange?,
    ): FunctionScope {
        val scope = createFunctionScope(function)
        val previousFrame = frame
        val functionFrame = createFunctionExecutionContext(
            function,
            thisValue,
            scope,
            scope.calleeEnvironment,
        )
        if (callRange != null) currentRange = callRange
        pushStackFrame(
            function.name.ifEmpty { "anonymous" },
            function.body?.range ?: function.expressionBody?.range,
            sourceInfo = function.sourceInfo,
        )
        frame = functionFrame
        try {
            bindFunctionArgumentsAndParameters(function, args, scope.parameterEnvironment)
            return scope
        } catch (error: Throwable) {
            // 参数初始化失败时异常沿普通调用路径传播（生成器对象尚未创建）
            captureFailure(error)
            throw error
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /**
     * 启动生成器函数体协程（首次 next 时调用）。
     *
     * 协程挂在事件循环作用域上而不是当前 run：生成器必须在多次脚本执行之间
     * 保持暂停状态，仍能在后续任务中恢复；引擎关闭时随事件循环一起取消。
     * UNDISPATCHED 让函数体同步执行到第一个 yield，保证 next() 同步返回结果。
     */
    internal fun launchGeneratorBody(generator: JSGenerator) {
        context.eventLoop.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                executeGeneratorBody(generator)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExecutionLimitException) {
                // 配额耗尽：交给等待中的 next()/return()/throw() 原样抛出
                generator.channels.steps.trySend(GeneratorStep.LimitReached(error))
            } catch (error: JSException) {
                // 边界兜底：正常情况下函数体已把异常转换为 Threw 步骤
                generator.channels.steps.trySend(GeneratorStep.Threw(error))
            } catch (error: Throwable) {
                generator.channels.steps.trySend(
                    GeneratorStep.Threw(
                        JSException(
                            JSError(
                                errorName = "InternalError",
                                messageText = error.message
                                    ?: error::class.simpleName
                                    ?: "Internal error",
                                prototype = intrinsics.errorPrototype,
                            )
                        )
                    )
                )
            }
        }
    }

    /**
     * 启动 async generator 的驱动协程（首次请求入队时调用）。
     *
     * 与 async 函数共用事件循环任务模型：驱动协程同步执行到函数体首个
     * yield/await 后交还调用方，之后由微任务队列推进。
     */
    internal suspend fun launchAsyncGeneratorDriver(generator: JSGenerator) {
        context.eventLoop.launchAsyncTask {
            generator.driveAsyncQueue()
        }
    }

    /**
     * 执行生成器函数体（在生成器协程中调用）。
     *
     * 使用独立解释器实例：函数体挂起期间调用方会继续执行其他脚本，不能复用
     * 调用方的词法帧与调用栈。函数体的所有完成（正常返回、return 请求、未捕获异常）
     * 都转换为 [GeneratorStep] 步骤交还给恢复方；只有宿主控制流（协程取消、
     * 步数耗尽）继续向上传播。
     *
     * 参数环境与 arguments 已由调用阶段的 [prepareGeneratorFunctionScope] 建立，
     * 这里只恢复执行上下文并执行函数体，不会重新求值默认值。
     */
    internal suspend fun executeGeneratorBody(generator: JSGenerator) {
        val bodyInterpreter = forkForCallback()
        bodyInterpreter.generatorChannels = generator.channels
        bodyInterpreter.generatorIsAsync = generator.isAsync
        bodyInterpreter.generatorFunction = generator.function
        try {
            val result = bodyInterpreter.executeSuspendedGeneratorBody(
                function = generator.function,
                thisValue = generator.thisValue,
                scope = generator.scope,
            )
            generator.channels.steps.send(GeneratorStep.Returned(result))
        } catch (signal: GeneratorReturn) {
            // 生成器边界：return() 的控制流信号转换为 { done: true } 的返回步骤。
            // async generator 的恢复值已在挂起点按 AsyncGeneratorUnwrapYieldResumption 等待过
            generator.channels.steps.send(GeneratorStep.Returned(signal.value))
        } catch (error: JSException) {
            generator.channels.steps.send(GeneratorStep.Threw(error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ExecutionLimitException) {
            throw error
        } catch (error: Throwable) {
            generator.channels.steps.send(
                GeneratorStep.Threw(
                    JSException(
                        JSError(
                            errorName = "InternalError",
                            messageText = error.message ?: error::class.simpleName ?: "Internal error",
                            prototype = intrinsics.errorPrototype,
                        ),
                        stack = bodyInterpreter.hostFailureStack ?: emptyList(),
                    )
                )
            )
        }
    }

    /**
     * 在生成器协程中恢复函数体执行。
     *
     * 与 [executeUserFunction] 的差别：不再创建参数环境、不重新绑定 arguments、
     * 不重新求值默认值；只建立函数体执行上下文并完成声明提升。
     */
    private suspend fun executeSuspendedGeneratorBody(
        function: JSFunction,
        thisValue: JSValue,
        scope: FunctionScope,
    ): JSValue {
        val previousFrame = frame
        val functionFrame = createFunctionExecutionContext(
            function,
            thisValue,
            scope,
            scope.variableEnvironment,
        )
        pushStackFrame(
            function.name.ifEmpty { "anonymous" },
            function.body?.range ?: function.expressionBody?.range,
            sourceInfo = function.sourceInfo,
        )
        frame = functionFrame
        try {
            return executeFunctionBodyWithScope(function, scope)
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /**
     * 调用 async 函数：立即返回 Promise，函数体在事件循环中执行。
     */
    private suspend fun callAsyncFunction(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSValue {
        val promise = JSPromise(intrinsics.promisePrototype)
        // 每次 async 调用使用独立解释器实例：
        // 协程挂起期间主解释器的 frame 会被外层脚本覆盖，独立实例可避免恢复时上下文错乱。
        val asyncInterpreter = forkForAsync(callRange)
        // 新解释器不能使同步递归的 async 函数绕过深度限制。保留逻辑祖先深度，
        // 不把多个并列挂起的 async 调用累加到主解释器的活动调用栈中。
        // 冻结创建点的调用方帧。只复制链首并标记异步边界，万层 async 递归仍是
        // 线性内存；等待期间其他 run 改变主解释器的位置不会污染这条祖先链。
        context.eventLoop.launchAsyncTask {
            try {
                val result = asyncInterpreter.executeUserFunction(function, thisValue, args)
                PromiseSupport.resolve(asyncInterpreter, promise, result)
            } catch (error: CancellationException) {
                // async 协程取消属于宿主控制流，不应变成 Promise 的 InternalError 拒绝。
                throw error
            } catch (error: ExecutionLimitException) {
                // 交给事件循环的配额终止边界，不生成 Promise 的 InternalError 拒绝。
                throw error
            } catch (error: JSException) {
                PromiseSupport.reject(asyncInterpreter, promise, error.value, error.stack)
            } catch (error: Throwable) {
                PromiseSupport.reject(
                    asyncInterpreter,
                    promise,
                    JSError(
                        "InternalError",
                        error.message ?: error::class.simpleName ?: "Internal error",
                        intrinsics.errorPrototype,
                    ),
                    asyncInterpreter.hostFailureStack ?: asyncInterpreter.captureStack(),
                )
            }
        }
        return promise
    }

    /**
     * 创建函数调用的环境层级（FunctionDeclarationInstantiation 的环境部分）。
     *
     * 参数环境与函数体 var 环境：含参数表达式时分离（参数闭包看不到函数体 var）。
     * 规范 10.2.11：参数初始化期间 VariableEnvironment 仍是函数的最外层环境，
     * 其中 eval 声明的 var/function 对参数闭包与函数体均可见；参数绑定完成后才切到 var 环境。
     */
    private fun createFunctionScope(function: JSFunction): FunctionScope {
        val hasParameterExpressions = function.params.any {
            it.rest || it.defaultValue != null || it.pattern != null
        }
        val calleeEnv = DeclarativeEnvironment(parent = function.closure, isVarScope = true)
        val parameterEnv: DeclarativeEnvironment
        val varEnv: DeclarativeEnvironment
        if (hasParameterExpressions) {
            parameterEnv = DeclarativeEnvironment(parent = calleeEnv)
            varEnv = DeclarativeEnvironment(parent = parameterEnv, isVarScope = true)
        } else {
            parameterEnv = calleeEnv
            varEnv = calleeEnv
        }
        return FunctionScope(calleeEnv, parameterEnv, varEnv, hasParameterExpressions)
    }

    /**
     * 为一次函数调用创建执行上下文。
     *
     * @param variableEnvironment 参数初始化阶段为最外层环境，函数体执行阶段为 var 环境
     */
    private fun createFunctionExecutionContext(
        function: JSFunction,
        thisValue: JSValue,
        scope: FunctionScope,
        variableEnvironment: Environment,
    ): ExecutionContext = ExecutionContext(
        scope.parameterEnvironment,
        thisValue,
        function.strict,
        function,
        newTarget = if (function.isArrow) function.lexicalNewTarget else null,
        // 箭头函数继承 this 绑定的初始化状态
        thisInitialized = function.lexicalThisSource?.thisBound ?: true,
        variableEnv = variableEnvironment,
        inParameterInitialization = scope.hasParameterExpressions,
    ).also { it.sourceModule = function.sourceModule }

    /**
     * 创建 arguments 对象并绑定形参（默认值/解构/rest 在参数环境中求值）。
     *
     * 生成器函数在调用时执行本方法，因此默认值中的副作用与异常都发生在
     * 生成器对象返回之前；首次 next 不会再次调用。
     */
    private suspend fun bindFunctionArgumentsAndParameters(
        function: JSFunction,
        args: List<JSValue>,
        parameterEnvironment: DeclarativeEnvironment,
    ) {
        if (!function.isArrow) {
            // 规范 9.2.10：形参名重复时不建立 arguments 映射
            val parameterNames = function.params.mapNotNull { it.name }
            val mapped = !function.strict && function.params.all {
                it.name != null && !it.rest && it.defaultValue == null && it.pattern == null
            } && parameterNames.distinct().size == function.params.size
            parameterEnvironment.createVarBinding(
                "arguments",
                createArgumentsObject(
                    args = args,
                    function = function,
                    mappedEnvironment = if (mapped) parameterEnvironment else null,
                    mappedParams = if (mapped) function.params else emptyList(),
                ),
            )
        }
        bindParameters(function, args, parameterEnvironment)
    }

    /**
     * 执行已绑定参数的函数体：创建函数体词法环境并完成声明提升。
     *
     * 调用前必须已经建立执行上下文；本方法供普通调用与生成器首次 next 共用。
     */
    private suspend fun executeFunctionBodyWithScope(
        function: JSFunction,
        scope: FunctionScope,
    ): JSValue {
        // 函数体词法环境位于 var 环境之上；无词法声明（含箭头表达式体）时
        // 复用 var 环境，避免每次调用分配一个空环境
        val body = function.body
        val bodyNeedsEnvironment = when {
            body != null -> body.requiresEnvironment
            function.expressionBody != null -> false
            else -> true
        }
        val bodyEnv = if (bodyNeedsEnvironment) {
            DeclarativeEnvironment(parent = scope.variableEnvironment)
        } else {
            scope.variableEnvironment
        }
        return executeFunctionBody(
            function,
            bodyEnv,
            scope.variableEnvironment,
            scope.parameterEnvironment,
            scope.hasParameterExpressions,
        )
    }

    /** 在给定 this 下执行函数体（普通函数 / 箭头函数共用）。 */
    private suspend fun executeUserFunction(
        function: JSFunction,
        thisValue: JSValue,
        args: List<JSValue>,
        callRange: SourceRange? = null,
    ): JSValue {
        val scope = createFunctionScope(function)
        val previousFrame = frame
        val functionFrame = createFunctionExecutionContext(
            function,
            thisValue,
            scope,
            scope.calleeEnvironment,
        )
        if (callRange != null) currentRange = callRange
        pushStackFrame(
            function.name.ifEmpty { "anonymous" }, function.body?.range ?: function.expressionBody?.range,
            sourceInfo = function.sourceInfo,
        )
        frame = functionFrame
        try {
            bindFunctionArgumentsAndParameters(function, args, scope.parameterEnvironment)
            return executeFunctionBodyWithScope(function, scope)
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
        } finally {
            popStackFrame()
            frame = previousFrame
        }
    }

    /**
     * 执行已绑定参数的函数体（普通函数、箭头函数与生成器函数共用）。
     *
     * 调用前必须已经建立执行上下文、绑定 arguments 与形参；本方法负责
     * 函数体环境的词法声明/var 提升，并返回函数体的完成值。
     *
     * @param bodyEnv 函数体词法环境
     * @param varEnv 函数体 var 环境
     * @param parameterEnv 参数环境（含参数表达式时与 var 环境分离）
     * @param hasParameterExpressions 形参列表是否含默认值/rest/解构
     */
    private suspend fun executeFunctionBody(
        function: JSFunction,
        bodyEnv: DeclarativeEnvironment,
        varEnv: DeclarativeEnvironment,
        parameterEnv: DeclarativeEnvironment,
        hasParameterExpressions: Boolean,
    ): JSValue {
        currentFrame.lexicalEnv = bodyEnv
        currentFrame.variableEnv = varEnv
        currentFrame.inParameterInitialization = false
        hoistDeclarations(bodyEnv, function.functionDeclarations, function.varDeclarations)
        if (hasParameterExpressions) {
            initializeParameterVarBindings(function, parameterEnv, varEnv)
        }

        val expressionBody = function.expressionBody
        if (expressionBody != null) {
            // 字段初始化器按字段名做 NamedEvaluation（匿名 class 需在静态初始化前带名）
            val hint = function.namedEvaluationHint
            return if (hint != null) evaluateNamed(expressionBody, hint) else evaluate(expressionBody)
        }

        val bodyBlock = function.body ?: return JSUndefined
        if (bodyBlock.requiresEnvironment) {
            predeclareLexical(bodyBlock.statements, bodyEnv)
        }
        val completion = executeStatements(bodyBlock.statements, bodyBlock.hasUsingDeclaration)
        return when (completion) {
            is Completion.Normal -> JSUndefined
            is Completion.Return -> completion.value
            is Completion.Break, is Completion.Continue ->
                throw syntaxError("Illegal break/continue statement")
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

    /**
     * 规范 10.2.11：函数体 var 与形参同名时，var 绑定以形参当前值初始化。
     *
     * 仅在参数环境与 var 环境分离时调用；同名函数声明优先于形参值。
     */
    private suspend fun initializeParameterVarBindings(
        function: JSFunction,
        parameterEnvironment: DeclarativeEnvironment,
        varEnvironment: DeclarativeEnvironment,
    ) {
        val functionNames = function.functionDeclarations.mapTo(mutableSetOf()) { it.name }
        val parameterNames = mutableListOf<String>()
        for (param in function.params) {
            val name = param.name
            if (name != null) {
                parameterNames.add(name)
            } else {
                param.pattern?.let { collectPatternNames(it, parameterNames) }
            }
        }
        // 普通函数的 arguments 也属于参数绑定；箭头函数没有自己的 arguments
        if (!function.isArrow) {
            parameterNames.add("arguments")
        }
        for (name in function.varDeclarations) {
            if (name in functionNames || name !in parameterNames) continue
            val value = parameterEnvironment.getBinding(name) ?: continue
            varEnvironment.createVarBinding(name, value)
        }
    }

    /** 构造用户函数实例。 */
    private suspend fun constructUserFunction(
        function: JSFunction,
        args: List<JSValue>,
        newTarget: JSValue = function,
    ): JSValue {
        if (function.isArrow || function.isMethod || function.isAsync || function.isGenerator) {
            throw typeError("${function.name.ifEmpty { "anonymous" }} is not a constructor")
        }
        // 必须在构造时读取当前的 F.prototype，而不是创建函数时捕获的对象；
        // Reflect.construct 允许 newTarget 与 constructor 不同。
        // 同时处理 GetPrototypeFromConstructor 的 GetFunctionRealm 分支（撤销 Proxy）。
        val instancePrototype = prototypeFromNewTarget(newTarget, intrinsics.objectPrototype)
        val instance = JSObject(prototype = instancePrototype)

        val derived = function.isClassConstructor && function.superConstructor != null
        val hasParameterExpressions = function.params.any {
            it.rest || it.defaultValue != null || it.pattern != null
        }
        // 与普通调用一致：参数初始化期间 VariableEnvironment 指向最外层环境
        val calleeEnv = DeclarativeEnvironment(parent = function.closure, isVarScope = true)
        val parameterEnv: DeclarativeEnvironment
        val varEnv: DeclarativeEnvironment
        if (hasParameterExpressions) {
            parameterEnv = DeclarativeEnvironment(parent = calleeEnv)
            varEnv = DeclarativeEnvironment(parent = parameterEnv, isVarScope = true)
        } else {
            parameterEnv = calleeEnv
            varEnv = calleeEnv
        }
        val previousFrame = frame
        val functionFrame = ExecutionContext(
            parameterEnv,
            instance,
            function.strict,
            function,
            newTarget = newTarget,
            thisInitialized = !derived,
            isConstructor = true,
            variableEnv = calleeEnv,
            inParameterInitialization = hasParameterExpressions,
        ).also { it.sourceModule = function.sourceModule }
        pushStackFrame(function.name.ifEmpty { "anonymous" }, function.body?.range, sourceInfo = function.sourceInfo)
        frame = functionFrame
        val activeFrame = functionFrame
        try {
            parameterEnv.createVarBinding("arguments", createArgumentsObject(args, function))
            bindParameters(function, args, parameterEnv)
            // 参数绑定完成，VariableEnvironment 切换到独立 var 环境；函数体词法环境位于其上方。
            // 构造器体无词法声明时复用 var 环境（默认派生构造器等合成体保守保留独立环境）。
            val body = function.body
            val bodyEnv = if (body == null || body.requiresEnvironment) {
                DeclarativeEnvironment(parent = varEnv)
            } else {
                varEnv
            }
            activeFrame.lexicalEnv = bodyEnv
            activeFrame.variableEnv = varEnv
            activeFrame.inParameterInitialization = false
            hoistDeclarations(bodyEnv, function.functionDeclarations, function.varDeclarations)
            if (hasParameterExpressions) {
                initializeParameterVarBindings(function, parameterEnv, varEnv)
            }

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

            val bodyBlock = body
            if (bodyBlock == null) {
                if (!activeFrame.thisInitialized) {
                    throw referenceError("Must call super constructor before returning from derived constructor")
                }
                return activeFrame.thisValue
            }
            val completion = executeStatements(bodyBlock.statements, bodyBlock.hasUsingDeclaration)
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
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
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
        isGenerator: Boolean = false,
        isMethod: Boolean = false,
        lexicalThis: JSValue? = null,
        lexicalThisSource: ThisBindingSource? = null,
        lexicalFunction: JSFunction? = null,
        lexicalNewTarget: JSValue? = null,
        allowsNewTarget: Boolean = true,
        forbidsArguments: Boolean = false,
        namedEvaluationHint: String? = null,
        privateEnvironment: PrivateEnvironment? = null,
        isClassConstructor: Boolean = false,
        isDefaultDerivedConstructor: Boolean = false,
        homeObject: JSObject? = null,
        superConstructor: JSValue? = null,
        superBase: JSObject? = null,
        functionPrototypeObject: JSObject? = null,
        /** 函数对象自身的 [[Prototype]]；null 时按 kind 使用默认（Function/AsyncFunction）。 */
        functionObjectPrototype: JSObject? = null,
        sourceText: String? = null,
        sourceInfo: SourceInfo = currentSourceInfo,
    ): JSFunction {
        // async 函数与普通方法不是构造器，没有 own "prototype" 属性；
        // 生成器（含 async generator）方法与函数按规范仍然创建 .prototype
        // （其 [[Prototype]] 是 %GeneratorPrototype% / %AsyncGeneratorPrototype%），
        // 与生成器函数声明/表达式一致（规范 9.2.3）
        val prototypeObject = if (isArrow || (isAsync && !isGenerator) || (isMethod && !isGenerator)) {
            null
        } else {
            functionPrototypeObject
                ?: JSObject(
                    prototype = when {
                        !isGenerator -> intrinsics.objectPrototype
                        isAsync -> intrinsics.asyncGeneratorPrototype
                        else -> intrinsics.generatorPrototype
                    },
                )
        }
        val function = JSFunction(
            name = name,
            params = params,
            body = body,
            closure = closure,
            strict = strict,
            // async 函数的 [[Prototype]] 是 %AsyncFunction.prototype%；
            // 生成器函数是 %GeneratorFunction.prototype%；
            // async generator 函数是 %AsyncGeneratorFunction.prototype%
            prototype = functionObjectPrototype
                ?: when {
                    isGenerator && isAsync -> intrinsics.asyncGeneratorFunctionPrototype
                    isAsync -> intrinsics.asyncFunctionPrototype
                    isGenerator -> intrinsics.generatorFunctionPrototype
                    else -> intrinsics.functionPrototype
                },
            functionPrototypeObject = prototypeObject,
            varDeclarations = varDeclarations,
            functionDeclarations = functionDeclarations,
            expressionBody = expressionBody,
            isArrow = isArrow,
            isAsync = isAsync,
            isGenerator = isGenerator,
            isMethod = isMethod,
            lexicalThis = lexicalThis,
            lexicalThisSource = lexicalThisSource,
            lexicalFunction = lexicalFunction,
            lexicalNewTarget = lexicalNewTarget,
            allowsNewTarget = allowsNewTarget,
            forbidsArguments = forbidsArguments,
            namedEvaluationHint = namedEvaluationHint,
            privateEnvironment = privateEnvironment,
            isClassConstructor = isClassConstructor,
            isDefaultDerivedConstructor = isDefaultDerivedConstructor,
            homeObject = homeObject,
            superConstructor = superConstructor,
            superBase = superBase,
            sourceInfo = sourceInfo,
        )
        function.sourceText = sourceText
        // 模块内嵌套函数在延迟回调中创建时 currentModuleRecord 可能已清空，
        // 回退到当前执行帧所属模块，保证后续 import.meta / import() 仍可定位。
        function.sourceModule = currentModuleRecord ?: frame?.sourceModule
        // ES5 15.3.5.4：非严格普通函数的 caller/arguments 是值为 null 的自有数据属性；
        // 严格函数、箭头、方法、async 与生成器函数不定义（读取落到 Function.prototype 拒绝访问器）
        if (!strict && !isArrow && !isMethod && !isAsync && !isGenerator) {
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
            value = JSNumber.of(args.size.toDouble()),
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
            // 非简单参数列表创建 unmapped arguments 对象，其 callee 也是 %ThrowTypeError%
            val unmappedArguments = function.params.any {
                it.rest || it.defaultValue != null || it.pattern != null
            }
            if (function.strict || unmappedArguments) {
                // 严格/非简单参数下访问 callee/caller 必须抛 TypeError；与 Function.prototype 共用 %ThrowTypeError%
                val thrower = intrinsics.throwTypeErrorFunction ?: JSNativeFunction(
                    name = "",
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
    fun createDynamicFunction(
        paramStrings: List<String>,
        body: String,
        functionObjectPrototype: JSObject? = null,
    ): JSFunction {
        val parameters = paramStrings.joinToString(",")
        // 规范：')' 前与函数体前后保留换行，参数末尾的行注释不会吞掉右括号
        val sourceText = "function anonymous($parameters\n) {\n$body\n}"
        val function = parseDynamicFunctionSource("($sourceText)")
        val created = createFunction(
            name = function.name ?: "anonymous",
            params = function.params,
            body = function.body,
            closure = context.globalEnvironment,
            strict = function.strict,
            varDeclarations = function.varDeclarations,
            functionDeclarations = function.functionDeclarations,
            isAsync = function.isAsync,
            functionObjectPrototype = functionObjectPrototype,
            sourceInfo = SourceInfo("<Function>"),
        )
        created.sourceText = sourceText
        return created
    }

    /**
     * 动态创建 async 函数（AsyncFunction 构造器）。
     *
     * 参数文本可能以行注释结尾，因此在 ')' 前保留换行；源码文本同时用于
     * Function.prototype.toString（动态函数按规范返回构造函数体源码）。
     */
    fun createDynamicAsyncFunction(
        paramStrings: List<String>,
        body: String,
        functionObjectPrototype: JSObject? = null,
    ): JSFunction {
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
            functionObjectPrototype = functionObjectPrototype,
            sourceInfo = SourceInfo("<AsyncFunction>"),
        )
        created.sourceText = source.substring(1, source.length - 1)
        return created
    }

    /**
     * 动态创建生成器函数（GeneratorFunction 构造器）。
     *
     * 与 AsyncFunction 一样以全局环境作为闭包，并通过 Parser 校验参数与函数体；
     * 生成器关键字写在源码文本中，由解析器产生 isGenerator 标记。
     */
    fun createDynamicGeneratorFunction(
        paramStrings: List<String>,
        body: String,
        functionObjectPrototype: JSObject? = null,
    ): JSFunction {
        val parameters = paramStrings.joinToString(",")
        val source = "(function* anonymous($parameters\n) {\n$body\n})"
        val function = parseDynamicFunctionSource(source)
        val created = createFunction(
            name = function.name ?: "anonymous",
            params = function.params,
            body = function.body,
            closure = context.globalEnvironment,
            strict = function.strict,
            varDeclarations = function.varDeclarations,
            functionDeclarations = function.functionDeclarations,
            isGenerator = true,
            functionObjectPrototype = functionObjectPrototype,
            sourceInfo = SourceInfo("<GeneratorFunction>"),
        )
        created.sourceText = source.substring(1, source.length - 1)
        return created
    }

    /**
     * 动态创建 async generator 函数（AsyncGeneratorFunction 构造器）。
     *
     * 与 AsyncFunction/GeneratorFunction 一致：以全局环境作为闭包，
     * 参数与函数体交给 Parser 校验；`async function*` 源码同时产生
     * isAsync 与 isGenerator 标记（L1.4）。
     */
    fun createDynamicAsyncGeneratorFunction(
        paramStrings: List<String>,
        body: String,
        functionObjectPrototype: JSObject? = null,
    ): JSFunction {
        val parameters = paramStrings.joinToString(",")
        val source = "(async function* anonymous($parameters\n) {\n$body\n})"
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
            isGenerator = true,
            functionObjectPrototype = functionObjectPrototype,
            sourceInfo = SourceInfo("<AsyncGeneratorFunction>"),
        )
        created.sourceText = source.substring(1, source.length - 1)
        return created
    }

    /** 解析动态函数源码并返回函数表达式节点。 */
    private fun parseDynamicFunctionSource(source: String): FunctionExpression {
        val expression = try {
            context.limits.checkSourceLength(source.length)
            val program = Parser(
                Lexer(source, maxNestingDepth = context.limits.maxNestingDepth).tokenize(),
                source = source,
                maxNestingDepth = context.limits.maxNestingDepth,
                maxAstNodes = context.limits.maxAstNodes,
            ).parseProgram()
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
     * GlobalDeclarationInstantiation 的检查阶段：
     * 1. 顶层词法名不得遮蔽不可配置的全局属性（含先前脚本的 var/function 声明）；
     * 2. var/function 名不得与既有全局词法绑定冲突；
     * 3. 函数/var 名称需通过 CanDeclareGlobalFunction / CanDeclareGlobalVar。
     *
     * 全部检查先于任何绑定创建，失败时全局对象不会残留新属性。
     */
    private fun checkGlobalDeclarationInstantiation(environment: Environment, program: Program) {
        val varScope = environment.findVarScope() as? ObjectEnvironment ?: return
        val globalObject = varScope.bindingObject
        val globalLexical = environment as? DeclarativeEnvironment
        val lexicalNames = mutableListOf<String>()
        for (statement in program.body) {
            when (statement) {
                is VarDeclaration -> if (statement.kind != DeclarationKind.VAR) {
                    for (declaration in statement.declarations) {
                        declaration.name?.let { lexicalNames.add(it) }
                        declaration.pattern?.let { collectPatternNames(it, lexicalNames) }
                    }
                }

                is ClassDeclaration -> lexicalNames.add(statement.name)
                is UsingDeclaration -> statement.declarations.forEach { lexicalNames.add(it.name) }
                else -> Unit
            }
        }
        // 1. HasRestrictedGlobalProperty：先前脚本的 var/function 属性不可配置
        for (name in lexicalNames) {
            val own = globalObject.getOwnProperty(PropertyKey.Str(name)) ?: continue
            if (!own.configurable) {
                throw syntaxError("Identifier '$name' has already been declared")
            }
        }
        val existingLexical = globalLexical?.ownLexicalNames() ?: emptySet()
        val functionNames = LinkedHashSet<String>()
        for (declaration in program.functionDeclarations.asReversed()) {
            functionNames.add(declaration.name)
        }
        // 2. var/function 与既有全局词法绑定冲突
        for (name in program.varDeclarations) {
            if (name in existingLexical) {
                throw syntaxError("Identifier '$name' has already been declared")
            }
        }
        for (name in functionNames) {
            if (name in existingLexical) {
                throw syntaxError("Identifier '$name' has already been declared")
            }
        }
        // 3. 可定义性检查（失败时抛 TypeError，且不创建任何绑定）
        for (name in functionNames) {
            if (!canDeclareGlobalFunction(globalObject, name)) {
                throw typeError("Cannot declare global function '$name'")
            }
        }
        for (name in program.varDeclarations) {
            if (name in functionNames) continue
            if (!canDeclareGlobalVar(globalObject, name)) {
                throw typeError("Cannot declare global variable '$name'")
            }
        }
    }

    /** CanDeclareGlobalFunction：可配置属性，或可写且可枚举的不可配置数据属性。 */
    private fun canDeclareGlobalFunction(globalObject: JSObject, name: String): Boolean {
        val descriptor = globalObject.getOwnProperty(PropertyKey.Str(name))
            ?: return globalObject.extensible
        if (descriptor.isAccessor) return false
        if (descriptor.configurable) return true
        return descriptor.writable && descriptor.enumerable
    }

    /** CanDeclareGlobalVar：已有自有属性或全局对象可扩展时允许。 */
    private fun canDeclareGlobalVar(globalObject: JSObject, name: String): Boolean =
        globalObject.getOwnProperty(PropertyKey.Str(name)) != null || globalObject.extensible

    /**
     * EvalDeclarationInstantiation 的全局声明检查：
     * 函数声明与 var 声明在创建任何绑定前先验证可定义性，失败抛 TypeError。
     */
    private fun checkEvalGlobalDeclarations(program: Program) {
        val globalObject = (context.globalEnvironment.findVarScope() as? ObjectEnvironment)
            ?.bindingObject ?: return
        val functionNames = LinkedHashSet<String>()
        for (declaration in program.functionDeclarations.asReversed()) {
            functionNames.add(declaration.name)
        }
        for (name in functionNames) {
            if (!canDeclareGlobalFunction(globalObject, name)) {
                throw typeError("Cannot declare global function '$name'")
            }
        }
        for (name in program.varDeclarations) {
            if (name in functionNames) continue
            if (!canDeclareGlobalVar(globalObject, name)) {
                throw typeError("Cannot declare global variable '$name'")
            }
        }
    }

    /**
     * 变量提升：函数声明优先，其次 var。
     *
     * 全局对象环境使用 CreateGlobalFunctionBinding/CreateGlobalVarBinding；
     * eval 代码传入 [globalDeletable] = true（规范 D 参数）。
     */
    private fun hoistDeclarations(
        environment: Environment,
        functionDeclarations: List<FunctionDeclaration>,
        varDeclarations: List<String>,
        globalDeletable: Boolean = false,
        /** sloppy eval：在函数 var 环境新建的绑定可删除（规范 CreateMutableBinding(..., true)） */
        deletable: Boolean = false,
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
                isGenerator = declaration.isGenerator,
                // 函数声明同样闭包捕获当前上下文的私有名环境
                privateEnvironment = frame?.function?.privateEnvironment,
                sourceText = declaration.sourceText,
            )
            if (varScope is ObjectEnvironment) {
                varScope.createGlobalFunctionBinding(
                    declaration.name,
                    function,
                    deletable = globalDeletable,
                )
            } else if (deletable && varScope is DeclarativeEnvironment) {
                varScope.createDeletableVarBinding(declaration.name, function)
            } else {
                varScope.createVarBinding(declaration.name, function)
            }
        }
        for (name in varDeclarations) {
            if (varScope is ObjectEnvironment) {
                varScope.createGlobalVarBinding(name, deletable = globalDeletable)
            } else if (deletable && varScope is DeclarativeEnvironment) {
                varScope.ensureDeletableVarBinding(name)
            } else {
                varScope.ensureVarBinding(name)
            }
        }
    }

    // ================================================================
    // 语句执行
    // ================================================================

    /**
     * 执行语句列表。
     *
     * 只有直接包含 using 声明的列表才需要资源释放作用域；
     * [hasUsingDeclaration] 由 Parser 预计算，缺少该信息时保守地创建作用域。
     */
    private suspend fun executeStatements(
        statements: List<Statement>,
        hasUsingDeclaration: Boolean = true,
    ): Completion = if (hasUsingDeclaration) {
        withDisposalScope { executeStatementsInternal(statements) }
    } else {
        executeStatementsInternal(statements)
    }

    private suspend fun executeStatementsInternal(statements: List<Statement>): Completion {
        var lastValue: JSValue = JSUndefined
        var hasValue = false
        for (statement in statements) {
            when (val completion = executeStatement(statement)) {
                is Completion.Normal -> if (completion.hasValue) {
                    lastValue = completion.value
                    hasValue = true
                }
                // 规范 UpdateEmpty：break/continue 的空完成值由语句列表的 V 填充
                is Completion.Break -> return Completion.Break(
                    completion.label,
                    if (completion.hasValue) completion.value else lastValue,
                    hasValue = true,
                )
                is Completion.Continue -> return Completion.Continue(
                    completion.label,
                    if (completion.hasValue) completion.value else lastValue,
                    hasValue = true,
                )
                else -> return completion
            }
        }
        return Completion.Normal(lastValue, hasValue)
    }

    private suspend fun executeStatement(statement: Statement): Completion =
        withSourceRange(statement.range) { executeStatementInternal(statement) }

    private suspend fun executeStatementInternal(statement: Statement): Completion {
        checkExecution()
        return when (statement) {
            is EmptyStatement -> Completion.Normal(JSUndefined, hasValue = false)
            is BlockStatement -> executeBlock(
                statement.statements,
                if (statement.requiresEnvironment) {
                    DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
                } else {
                    currentFrame.lexicalEnv
                },
                statement.hasUsingDeclaration,
            )

            is ExpressionStatement -> Completion.Normal(evaluate(statement.expression))
            is ClassDeclaration -> {
                val classValue = evaluateClass(statement.name, statement.superClass, statement.members, statement.sourceText)
                if (!initializeLexicalIfDeclared(currentFrame.lexicalEnv, statement.name, classValue)) {
                    // class 声明的绑定是可变词法绑定（可重新赋值）
                    currentFrame.lexicalEnv.createLetBinding(statement.name, classValue)
                }
                Completion.Normal(JSUndefined, hasValue = false)
            }

            is VarDeclaration -> executeVarDeclaration(statement)
            is UsingDeclaration -> {
                executeUsingDeclaration(statement)
                Completion.Normal(JSUndefined, hasValue = false)
            }
            is FunctionDeclaration -> {
                if (statement.annexBLegacy) {
                    // Annex B B.3.3：单语句 if/else 分支函数在求值点创建，
                    // 词法绑定位于隐式块环境，并同步包围 var 作用域的同名绑定
                    val environment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
                    val function = createFunction(
                        name = statement.name,
                        params = statement.params,
                        body = statement.body,
                        closure = environment,
                        strict = statement.strict,
                        varDeclarations = statement.varDeclarations,
                        functionDeclarations = statement.functionDeclarations,
                        isAsync = statement.isAsync,
                        isGenerator = statement.isGenerator,
                        privateEnvironment = frame?.function?.privateEnvironment,
                        sourceText = statement.sourceText,
                    )
                    environment.initializeBinding(statement.name, function)
                    if (statement.annexBVarEligible) {
                        val varScope = currentFrame.lexicalEnv.findVarScope()
                        if (!varScope.setBinding(statement.name, function, strict = false)) {
                            varScope.createVarBinding(statement.name, function)
                        }
                    }
                }
                // 普通函数声明已提升，运行时无操作
                Completion.Normal(JSUndefined, hasValue = false)
            }
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
            is ReturnStatement -> {
                val value = statement.value?.let {
                    evaluate(it, tail = canUseTailCall())
                } ?: JSUndefined
                // 规范 13.10.1：async generator 自身的 return 表达式值先经过 Await。
                // 必须是当前生成器体这一层：嵌套普通函数复用同一解释器实例，
                // 它们的 return 不受 async generator 规则影响。
                val asyncGeneratorReturn = generatorIsAsync && statement.value != null &&
                    generatorFunction != null && currentFrame.function === generatorFunction
                val result = if (asyncGeneratorReturn) {
                    awaitPromiseValue(value)
                } else {
                    value
                }
                Completion.Return(result)
            }

            is ThrowStatement -> {
                val value = evaluate(statement.argument)
                // 已有 Error 保留创建位置；throw 普通值则由当前执行边界记录抛出点。
                captureErrorStack(value)
                throw JSException(value).also {
                    if (it.stack.isEmpty()) it.attachStackIfAbsent(captureStack())
                }
            }

            is TryStatement -> executeTry(statement)
        }
    }

    /** 在指定环境中执行语句列表，返回后恢复原环境。 */
    private suspend fun executeBlock(
        statements: List<Statement>,
        environment: Environment,
        hasUsingDeclaration: Boolean = true,
    ): Completion {
        val previousEnvironment = currentFrame.lexicalEnv
        currentFrame.lexicalEnv = environment
        return try {
            predeclareLexical(statements, environment)
            instantiateBlockFunctions(statements, environment)
            executeStatements(statements, hasUsingDeclaration)
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /**
     * 块级函数声明在块入口实例化（BlockDeclarationInstantiation）。
     *
     * sloppy 模式下按 Annex B.3.3 同步包围 var 作用域的同名绑定，
     * 使块外也能看到最近一次执行的块级函数。
     */
    private fun instantiateBlockFunctions(statements: List<Statement>, environment: Environment) {
        val declarative = environment as? DeclarativeEnvironment ?: return
        for (statement in statements) {
            if (statement !is FunctionDeclaration) continue
            val function = createFunction(
                name = statement.name,
                params = statement.params,
                body = statement.body,
                closure = environment,
                strict = statement.strict,
                varDeclarations = statement.varDeclarations,
                functionDeclarations = statement.functionDeclarations,
                isAsync = statement.isAsync,
                isGenerator = statement.isGenerator,
                privateEnvironment = frame?.function?.privateEnvironment,
                sourceText = statement.sourceText,
            )
            declarative.initializeBinding(statement.name, function)
            // Annex B：仅当无词法冲突且为 sloppy 普通函数时同步包围 var 绑定
            if (statement.annexBVarEligible) {
                environment.findVarScope().createVarBinding(statement.name, function)
            }
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

                is UsingDeclaration -> {
                    for (declarator in statement.declarations) {
                        if (lexicalBindings.put(declarator.name, false) != null) {
                            throw JSException(
                                JSError(
                                    "SyntaxError",
                                    "Identifier '${declarator.name}' has already been declared",
                                )
                            )
                        }
                    }
                }

                is ClassDeclaration -> {
                    // class 绑定可变（let 语义），只做 TDZ 预声明
                    if (lexicalBindings.put(statement.name, true) != null) {
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
                            // 无初始化器：绑定已在声明实例化阶段创建，运行时不做任何事
                            // （包括不得重建已通过 delete 移除的 sloppy eval 绑定）
                        } else {
                            // 规范 13.3.2.4：先 ResolveBinding 捕获引用基环境，再求值初始化器；
                            // with 作用域内删除同名属性后仍写入原对象
                            val owner = findBindingOwner(name)
                            val value = evaluateNamed(initializer, name)
                            writeIdentifierReference(owner, name, value)
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
        return Completion.Normal(JSUndefined, hasValue = false)
    }

    /** if 语句。 */
    private suspend fun executeIf(statement: IfStatement): Completion {
        val completion = if (toBoolean(evaluate(statement.condition))) {
            executeStatement(statement.consequent)
        } else {
            statement.alternate?.let { executeStatement(it) } ?: Completion.Normal(JSUndefined)
        }
        // 规范：分支的正常空完成值转为 undefined，覆盖语句列表的累积值
        return if (completion is Completion.Normal && !completion.hasValue) {
            Completion.Normal(JSUndefined)
        } else {
            completion
        }
    }

    /** while 语句。 */
    private suspend fun executeWhile(statement: WhileStatement, labels: List<String> = emptyList()): Completion {
        // V：最后一次正常完成的语句值（供 eval 完成值使用）
        var lastValue: JSValue = JSUndefined
        while (toBoolean(evaluate(statement.condition))) {
            checkExecution()
            when (val completion = executeStatement(statement.body)) {
                is Completion.Normal -> if (completion.hasValue) lastValue = completion.value
                is Completion.Return -> return completion
                is Completion.Break -> {
                    if (completion.label == null || completion.label in labels) {
                        return Completion.Normal(if (completion.hasValue) completion.value else lastValue)
                    }
                    return completion
                }

                is Completion.Continue -> {
                    if (completion.label != null && completion.label !in labels) return completion
                    if (completion.hasValue) lastValue = completion.value
                }
            }
        }
        return Completion.Normal(lastValue)
    }

    /** do-while 语句。 */
    private suspend fun executeDoWhile(statement: DoWhileStatement, labels: List<String> = emptyList()): Completion {
        var lastValue: JSValue = JSUndefined
        do {
            checkExecution()
            when (val completion = executeStatement(statement.body)) {
                is Completion.Normal -> if (completion.hasValue) lastValue = completion.value
                is Completion.Return -> return completion
                is Completion.Break -> {
                    if (completion.label == null || completion.label in labels) {
                        return Completion.Normal(if (completion.hasValue) completion.value else lastValue)
                    }
                    return completion
                }

                is Completion.Continue -> {
                    if (completion.label != null && completion.label !in labels) return completion
                    if (completion.hasValue) lastValue = completion.value
                }
            }
        } while (toBoolean(evaluate(statement.condition)))
        return Completion.Normal(lastValue)
    }

    /** for 语句（支持 let/const 每迭代作用域）。 */
    private suspend fun executeFor(
        statement: ForStatement,
        labels: List<String> = emptyList(),
    ): Completion = if (statement.init is UsingDeclaration) {
        // for (using …)：循环头资源在循环结束时释放，需要独立释放作用域
        withDisposalScope { executeForInternal(statement, labels) }
    } else {
        executeForInternal(statement, labels)
    }

    private suspend fun executeForInternal(
        statement: ForStatement,
        labels: List<String>,
    ): Completion {
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

        val previousEnvironment = currentFrame.lexicalEnv
        // 循环头为词法声明（let/const/using/await using）时必须创建独立环境；
        // 表达式或 var 初始化时环境为空，直接复用外层环境避免多余分配
        val requiresLexicalHead = when (init) {
            is VarDeclaration -> init.kind != DeclarationKind.VAR
            is UsingDeclaration -> true
            else -> false
        }
        val headEnvironment = if (requiresLexicalHead) {
            DeclarativeEnvironment(parent = previousEnvironment)
        } else {
            null
        }
        val loopBaseEnvironment: Environment = headEnvironment ?: previousEnvironment
        currentFrame.lexicalEnv = loopBaseEnvironment
        try {
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

            var iterationEnvironment: DeclarativeEnvironment? = if (loopVariableNames.isEmpty()) {
                null
            } else {
                headEnvironment?.let { createIterationEnvironment(it) }
            }
            var loopEnvironment: Environment = iterationEnvironment ?: loopBaseEnvironment
            currentFrame.lexicalEnv = loopEnvironment

            var lastValue: JSValue = JSUndefined
            while (true) {
                checkExecution()
                val condition = statement.condition?.let { toBoolean(evaluate(it)) } ?: true
                if (!condition) break

                var completion: Completion = Completion.Normal(JSUndefined)
                try {
                    completion = executeStatement(statement.body)
                } finally {
                    currentFrame.lexicalEnv = loopEnvironment
                }

                when (completion) {
                    is Completion.Normal -> if (completion.hasValue) lastValue = completion.value
                    is Completion.Return -> return completion
                    is Completion.Break -> {
                        if (completion.label == null || completion.label in labels) {
                            return Completion.Normal(if (completion.hasValue) completion.value else lastValue)
                        }
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) return completion
                        if (completion.hasValue) lastValue = completion.value
                    }
                }

                val currentIteration = iterationEnvironment
                if (currentIteration != null) {
                    val next = createIterationEnvironment(currentIteration)
                    iterationEnvironment = next
                    loopEnvironment = next
                    currentFrame.lexicalEnv = next
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
            // Annex B 旧式初始化器：在 RHS 之前求值一次并写入循环变量
            statement.initializer?.let { initializer ->
                val value = evaluate(initializer)
                statement.variableName?.let { name ->
                    writeIdentifierReference(findBindingOwner(name), name, value)
                }
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
                checkExecution()
                val propertyKey = PropertyKey.Str(key)
                if (!internalHasProperty(objectValue, propertyKey)) continue

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
                            // var 绑定按引用解析写入：catch 参数同名时更新 catch 绑定
                            DeclarationKind.VAR -> writeIdentifierReference(
                                findBindingOwner(variableName),
                                variableName,
                                JSString(key),
                            )

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
                    is Completion.Normal -> if (completion.hasValue) lastValue = completion.value
                    is Completion.Return -> return completion
                    is Completion.Break -> {
                        if (completion.label == null || completion.label in labels) {
                            return Completion.Normal(if (completion.hasValue) completion.value else lastValue)
                        }
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) return completion
                        if (completion.hasValue) lastValue = completion.value
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
            val iterable = evaluate(statement.right)
            val records = if (statement.isAwait) getAsyncIterator(iterable) else getIterator(iterable)
            if (!lexical) {
                forEnvironment = DeclarativeEnvironment(parent = previousEnvironment)
                currentFrame.lexicalEnv = forEnvironment
            }

            var lastValue: JSValue = JSUndefined
            while (true) {
                checkExecution()
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

                // for (using x of ...)：每次迭代拥有独立的释放作用域
                val usingMode = statement.usingMode
                val usingScope = if (usingMode != null) DisposalScope() else null
                if (usingScope != null) {
                    usingScope.hasAsyncResource = usingMode == UsingMode.ASYNC
                    disposalScopes.add(usingScope)
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
                            // var 绑定按引用解析写入（catch 参数同名时更新 catch 绑定）
                            DeclarationKind.VAR -> writeIdentifierReference(
                                findBindingOwner(variableName),
                                variableName,
                                value,
                            )

                            DeclarationKind.LET -> targetEnvironment
                                .createLetBinding(variableName, value)

                            DeclarationKind.CONST -> targetEnvironment
                                .createConstBinding(variableName, value)
                        }

                        else -> statement.target?.let { assignTarget(it, value) }
                    }
                    if (usingScope != null && value !is JSNull && value !is JSUndefined) {
                        val method = resolveDisposeMethod(value, usingMode == UsingMode.ASYNC)
                        usingScope.resources.add(
                            DisposalResource(value, method, usingMode == UsingMode.ASYNC)
                        )
                    }
                    completion = executeStatement(statement.body)
                } catch (signal: GeneratorReturn) {
                    // 生成器 return() 在循环体内 yield 处提前完成：按 return 完成
                    // 先释放本次迭代的 using 资源，再关闭外层迭代器后传播信号
                    val failure = if (usingScope != null) disposeScope(usingScope, null) else null
                    iteratorClose(records, throwCompletion = failure != null)
                    if (failure != null) throw JSException(failure)
                    throw signal
                } catch (error: JSException) {
                    val failure = if (usingScope != null) {
                        disposeScope(usingScope, error.value)
                    } else {
                        null
                    }
                    iteratorClose(records, throwCompletion = true)
                    if (usingScope != null && failure != null) throw JSException(failure)
                    throw error
                } finally {
                    if (usingScope != null) {
                        // 异常路径已释放；正常/控制流路径在下方释放
                        if (disposalScopes.lastOrNull() === usingScope) {
                            disposalScopes.removeAt(disposalScopes.lastIndex)
                        }
                    }
                    currentFrame.lexicalEnv = if (lexical) headEnvironment!! else forEnvironment!!
                }

                if (usingScope != null) {
                    val failure = disposeScope(usingScope, null)
                    if (failure != null) {
                        iteratorClose(records, throwCompletion = true)
                        throw JSException(failure)
                    }
                    if (usingScope.hasAsyncResource) awaitPromiseValue(JSUndefined)
                }

                when (completion) {
                    is Completion.Normal -> if (completion.hasValue) lastValue = completion.value
                    is Completion.Return -> {
                        iteratorClose(records, throwCompletion = false)
                        return completion
                    }

                    is Completion.Break -> {
                        iteratorClose(records, throwCompletion = false)
                        if (completion.label == null || completion.label in labels) {
                            return Completion.Normal(if (completion.hasValue) completion.value else lastValue)
                        }
                        return completion
                    }

                    is Completion.Continue -> {
                        if (completion.label != null && completion.label !in labels) {
                            iteratorClose(records, throwCompletion = false)
                            return completion
                        }
                        if (completion.hasValue) lastValue = completion.value
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
            val completion = executeStatement(statement.body)
            if (completion is Completion.Normal && !completion.hasValue) {
                Completion.Normal(JSUndefined)
            } else {
                completion
            }
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
            // case 子句中的函数声明在 switch 块入口实例化
            instantiateBlockFunctions(
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
                var lastValue: JSValue = JSUndefined
                var result: Completion? = null
                execution@ for (index in startIndex until statement.cases.size) {
                    for (inner in statement.cases[index].consequent) {
                        when (val completion = executeStatement(inner)) {
                            is Completion.Normal -> {
                                if (completion.hasValue) lastValue = completion.value
                            }

                            is Completion.Return -> {
                                result = completion
                                break@execution
                            }

                            is Completion.Break -> {
                                if (completion.hasValue) lastValue = completion.value
                                result = if (completion.label == null) {
                                    Completion.Normal(lastValue)
                                } else {
                                    Completion.Break(completion.label, lastValue, hasValue = true)
                                }
                                break@execution
                            }

                            is Completion.Continue -> {
                                result = Completion.Continue(
                                    completion.label,
                                    if (completion.hasValue) completion.value else lastValue,
                                    hasValue = true,
                                )
                                break@execution
                            }
                        }
                    }
                }
                result ?: Completion.Normal(lastValue)
            }
        } finally {
            currentFrame.lexicalEnv = previousEnvironment
        }
    }

    /** try-catch-finally 语句。 */
    private suspend fun executeTry(statement: TryStatement): Completion =
        executeTryBody(statement)

    /**
     * try/catch/finally 执行。
     *
     * [tailCallBlockers] 记录返回时仍需执行的 handler/finalizer：
     * try 块内存在 handler/finalizer、catch 块内存在 finalizer 时禁止尾调用；
     * finally 块本身（以及无 finalizer 的 catch）允许尾调用。
     */
    private suspend fun executeTryBody(statement: TryStatement): Completion {
        var pending: Completion = Completion.Normal(JSUndefined)
        // 除 JSException 外还要承载生成器 return 的控制流信号（不进入 catch 子句）
        var thrown: Throwable? = null
        val outerBlockers = tailCallBlockers

        tailCallBlockers = if (statement.handler != null || statement.finalizer != null) {
            outerBlockers + 1
        } else {
            outerBlockers
        }
        try {
            pending = executeBlock(
                statement.block.statements,
                if (statement.block.requiresEnvironment) {
                    DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
                } else {
                    currentFrame.lexicalEnv
                },
                statement.block.hasUsingDeclaration,
            )
        } catch (signal: GeneratorReturn) {
            // 生成器 return() 不是 JS 异常：catch 子句过滤它，finally 仍然执行
            thrown = signal
        } catch (exception: JSException) {
            normalizeErrorValue(exception.value)
            val handler = statement.handler
            if (handler != null) {
                val catchEnvironment = DeclarativeEnvironment(
                    parent = currentFrame.lexicalEnv,
                    isCatchEnvironment = true,
                )
                if (handler.param != null) {
                    catchEnvironment.createLetBinding(handler.param, exception.value)
                } else if (handler.pattern != null) {
                    // 解构参数的默认值在 catch 参数环境中求值，
                    // 闭包需能捕获已绑定的前置参数（与函数参数初始化一致）
                    val previousEnvironment = currentFrame.lexicalEnv
                    currentFrame.lexicalEnv = catchEnvironment
                    try {
                        bindPattern(
                            handler.pattern,
                            exception.value,
                            DeclarationKind.LET,
                            catchEnvironment,
                        )
                    } finally {
                        currentFrame.lexicalEnv = previousEnvironment
                    }
                }
                tailCallBlockers = if (statement.finalizer != null) {
                    outerBlockers + 1
                } else {
                    outerBlockers
                }
                try {
                    // catch 块拥有独立于 catch 参数环境的词法环境：
                    // 参数默认值中的闭包看不到块内的 let/const 声明
                    pending = executeBlock(
                        handler.body.statements,
                        if (handler.body.requiresEnvironment) {
                            DeclarativeEnvironment(parent = catchEnvironment)
                        } else {
                            catchEnvironment
                        },
                        handler.body.hasUsingDeclaration,
                    )
                } catch (signal: GeneratorReturn) {
                    // catch 块内的 yield 收到 return 请求：先记下控制流信号，finally 照常执行
                    thrown = signal
                } catch (catchException: JSException) {
                    thrown = catchException
                }
            } else {
                thrown = exception
            }
        } finally {
            tailCallBlockers = outerBlockers
        }

        val finalizer = statement.finalizer
        if (finalizer != null) {
            try {
                val completion = executeBlock(
                    finalizer.statements,
                    if (finalizer.requiresEnvironment) {
                        DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
                    } else {
                        currentFrame.lexicalEnv
                    },
                    finalizer.hasUsingDeclaration,
                )
                if (completion !is Completion.Normal) {
                    // finally 的 abrupt 完成（break/continue/return）覆盖之前的 throw
                    pending = completion
                    thrown = null
                }
            } catch (signal: GeneratorReturn) {
                // finally 中的 yield 收到 return 请求：以新的完成记录覆盖之前的 throw
                thrown = signal
            } catch (finalizerException: JSException) {
                thrown = finalizerException
            }
        }

        val error = thrown
        if (error != null) throw error
        // 规范：try 语句把空完成值归一为 undefined
        return when (val completion = pending) {
            is Completion.Normal ->
                if (completion.hasValue) completion else Completion.Normal(JSUndefined)
            is Completion.Break ->
                if (completion.hasValue) completion
                else Completion.Break(completion.label, JSUndefined, hasValue = true)
            is Completion.Continue ->
                if (completion.hasValue) completion
                else Completion.Continue(completion.label, JSUndefined, hasValue = true)
            else -> completion
        }
    }

    // ================================================================
    // 表达式求值
    // ================================================================

    private suspend fun evaluate(expression: Expression, tail: Boolean = false): JSValue =
        withSourceRange(expression.range) { evaluateInternal(expression, tail) }

    private suspend fun evaluateInternal(expression: Expression, tail: Boolean): JSValue {
        checkExecution()
        return when (expression) {
            is Identifier -> currentFrame.lexicalEnv.getBinding(expression.name)
                ?: throw referenceError("${expression.name} is not defined")

            is NumberLiteral -> JSNumber.of(expression.value)
            is BigIntLiteral -> JSBigInt.parseLiteral(expression.raw)
                ?: throw syntaxError("Invalid BigInt literal ${expression.raw}")
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
            is TaggedTemplate -> evaluateTaggedTemplate(expression, tail)
            is ClassExpression -> evaluateClass(expression.name, expression.superClass, expression.members, expression.sourceText)
            is SuperExpr -> throw syntaxError("'super' keyword unexpected here")
            is NewTargetExpr -> currentFrame.newTarget ?: JSUndefined
            // 模块表达式节点只由 parseModule 产生；脚本目标访问 import.meta 或 import() 时报 SyntaxError。
            is ImportMetaExpr -> resolveModuleRecord()?.importMeta
                ?: throw syntaxError("import.meta is only available in module code")
            is ImportCallExpr -> evaluateDynamicImport(expression)

            is YieldExpression -> evaluateYield(expression)

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
                        isGenerator = expression.isGenerator,
                        isMethod = expression.isMethodDefinition,
                        privateEnvironment = currentFrame.function?.privateEnvironment,
                        sourceText = expression.sourceText,
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
                        isGenerator = expression.isGenerator,
                        privateEnvironment = currentFrame.function?.privateEnvironment,
                        sourceText = expression.sourceText,
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
                lexicalThisSource = currentFrame.function
                    ?.takeIf { it.isArrow }?.lexicalThisSource ?: currentFrame,
                lexicalFunction = currentFrame.function
                    ?.takeIf { it.isArrow }?.lexicalFunction ?: currentFrame.function,
                // arguments 语法限制同样穿过箭头函数
                forbidsArguments = currentFrame.function?.forbidsArguments == true,
                // 箭头函数继承 super 绑定与 new.target
                lexicalNewTarget = currentFrame.newTarget,
                allowsNewTarget = expression.allowsNewTarget,
                homeObject = currentFrame.function?.homeObject,
                superBase = currentFrame.function?.superBase,
                privateEnvironment = currentFrame.function?.privateEnvironment,
                sourceText = expression.sourceText,
            )

            is MemberExpr -> {
                val privateName = (expression.property as? PrivateIdentifier)?.name
                if (privateName != null) {
                    val base = evaluate(expression.obj)
                    getPrivateMember(base, resolvePrivateName(privateName))
                } else if (expression.obj is SuperExpr) {
                    if (!currentFrame.thisInitialized) {
                        throw referenceError("Must call super constructor before accessing 'super'")
                    }
                    // 访问器以当前 this 为接收者（super getter 的 this 是实例/类）
                    getPropertyFrom(
                        superPropertyBase(),
                        memberKey(expression),
                        currentFrame.thisValue,
                    )
                } else {
                    val base = evaluate(expression.obj)
                    // 计算键：规范先 ToObject(base) 再 ToPropertyKey(key)
                    if (expression.computed && (base is JSNull || base is JSUndefined)) {
                        typeError(
                            "Cannot read properties of " +
                                if (base is JSNull) "null" else "undefined"
                        )
                    }
                    getV(base, memberKey(expression))
                }
            }

            is PrivateInExpr -> {
                val target = evaluate(expression.objectExpression)
                val obj = target as? JSObject
                    ?: throw typeError("Right-hand side of 'in' must be an object")
                val name = resolvePrivateName(expression.name)
                JSBoolean.getJsBoolean(obj.findPrivateSlot(name) != null)
            }

            is PrivateIdentifier ->
                throw syntaxError("Unexpected private name")

            is OptionalChainExpr -> evaluateOptionalChain(expression)
            is CallExpr -> evaluateCall(expression, tail)
            is NewExpr -> construct(
                constructor = evaluate(expression.constructor),
                args = evaluateArguments(expression.arguments),
            )

            is AssignmentExpr -> evaluateAssignment(expression)
            is UpdateExpr -> evaluateUpdate(expression)
            is UnaryOp -> evaluateUnary(expression)
            is BinaryOp -> evaluateBinary(expression, tail)

            is ConditionalExpr -> if (toBoolean(evaluate(expression.condition))) {
                evaluate(expression.thenBranch, tail)
            } else {
                evaluate(expression.elseBranch, tail)
            }

            is CoalesceExpr -> {
                val left = evaluate(expression.left)
                if (left is JSNull || left is JSUndefined) evaluate(expression.right, tail) else left
            }

            is SequenceExpr -> {
                var result: JSValue = JSUndefined
                val lastIndex = expression.expressions.lastIndex
                for (index in expression.expressions.indices) {
                    result = evaluate(expression.expressions[index], tail && index == lastIndex)
                }
                result
            }

            is SpreadElement -> throw syntaxError("Unexpected spread element")
        }
    }

    /**
     * 动态 `import()`：同步求值说明符与选项，加载/链接/求值在微任务中完成。
     *
     * 说明符/选项求值错误同步抛出；模块加载、链接与求值错误以 Promise 拒绝上报。
     */
    @OptIn(ExperimentalModuleApi::class)
    private suspend fun evaluateDynamicImport(expression: ImportCallExpr): JSValue {
        val record = resolveModuleRecord()
            ?: throw syntaxError("import() is only available in module code")
        val runtime = record.runtime ?: moduleRuntime
            ?: throw syntaxError("import() is only available in module code")
        val specifierExpression = expression.specifier
            ?: throw typeError("import() requires a module specifier")
        val promise = JSPromise(intrinsics.promisePrototype)
        val specifier = try {
            toStringValue(evaluate(specifierExpression))
        } catch (error: JSException) {
            // ImportCall 的 ToString(specifier) 失败必须拒绝返回的 Promise，
            // 不能把 TypeError 同步抛给模块体。
            PromiseSupport.reject(this, promise, error.value, error.stack)
            return promise
        }
        val attributes = expression.options?.let { readImportAttributes(evaluate(it)) } ?: emptyMap()

        context.eventLoop.enqueueMicrotask {
            context.eventLoop.launchAsyncTask {
                val job = forkForCallback()
                job.currentModuleRecord = record
                job.moduleRuntime = runtime
                try {
                    val namespace = runtime.importModule(job, specifier, record, attributes)
                    PromiseSupport.resolve(job, promise, namespace)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ExecutionLimitException) {
                    throw error
                } catch (error: ResourceLimitException) {
                    throw error
                } catch (error: JSException) {
                    PromiseSupport.reject(job, promise, error.value, error.stack)
                } catch (error: ModuleLinkException) {
                    PromiseSupport.reject(
                        job,
                        promise,
                        JSError(
                            errorName = "Error",
                            messageText = error.message ?: "Module loading failed",
                            prototype = job.intrinsics.errorPrototype,
                        ),
                        job.captureStack(),
                    )
                } catch (error: ParseException) {
                    // 动态 import 的目标按 Module Record 解析；目标文件即使原本是脚本，
                    // 其早期语法错误也必须以 SyntaxError 拒绝 import()。
                    PromiseSupport.reject(
                        job,
                        promise,
                        JSError(
                            errorName = "SyntaxError",
                            messageText = error.message ?: "Invalid module syntax",
                            prototype = job.intrinsics.syntaxErrorPrototype,
                        ),
                        job.captureStack(),
                    )
                } catch (error: LexerException) {
                    PromiseSupport.reject(
                        job,
                        promise,
                        JSError(
                            errorName = "SyntaxError",
                            messageText = error.message ?: "Invalid module syntax",
                            prototype = job.intrinsics.syntaxErrorPrototype,
                        ),
                        job.captureStack(),
                    )
                } catch (error: Throwable) {
                    PromiseSupport.reject(
                        job,
                        promise,
                        JSError(
                            errorName = "InternalError",
                            messageText = error.message ?: error::class.simpleName ?: "Module loading failed",
                            prototype = job.intrinsics.errorPrototype,
                        ),
                        job.captureStack(),
                    )
                }
            }
        }
        return promise
    }

    /** 读取 `import(..., options)` 的 `with` 属性表；只接受字符串值。 */
    private suspend fun readImportAttributes(options: JSValue): Map<String, String> {
        if (options is JSUndefined) return emptyMap()
        if (options !is JSObject) {
            throw typeError("import() options must be an object")
        }
        val withValue = getV(options, PropertyKey.Str("with"))
        if (withValue is JSUndefined) return emptyMap()
        if (withValue !is JSObject) {
            throw typeError("Import attributes must be an object")
        }
        val attributes = LinkedHashMap<String, String>()
        for (key in internalOwnPropertyKeys(withValue)) {
            if (key !is PropertyKey.Str) continue
            val descriptor = internalGetOwnProperty(withValue, key) ?: continue
            if (!descriptor.enumerable) continue
            val value = getV(withValue, key)
            if (value !is JSString) {
                throw typeError("Import attribute value must be a string")
            }
            attributes[key.value] = value.value
        }
        return attributes
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
                    for (key in internalOwnPropertyKeys(source)) {
                        if (internalGetOwnProperty(source, key)?.enumerable != true) continue
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
                    val literalKeyName = if (!element.computed) {
                        when (val keyExpression = element.key) {
                            is Identifier -> keyExpression.name
                            is StringLiteral -> keyExpression.value
                            else -> null
                        }
                    } else {
                        null
                    }
                    // 冒号形式的 "__proto__: value" 是原型设置器，不参与 NamedEvaluation
                    val isShorthand = element.value is Identifier &&
                        element.value.range == element.key.range
                    val isProtoSetter = literalKeyName == "__proto__" &&
                        !element.method && !isShorthand && element.kind == PropertyKind.INIT
                    if (value is JSFunction && element.value is FunctionExpression) {
                        // 对象字面量方法/访问器的 HomeObject 是正在构建的对象
                        value.setMethodHome(result, result.prototype)
                        if (element.method) value.markAsMethod()
                    }
                    if (value is JSFunction && !isProtoSetter) {
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
                            if (isProtoSetter) {
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
                            val existing = internalGetOwnProperty(result, key)
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
        // BigInt 字面量属性名使用十进制字符串（如 `{ 1n: true }` 的键是 "1"）
        is BigIntLiteral -> JSBigInt.parseLiteral(key.raw)?.toString(10) ?: ""
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
    /** DefineField：通过 [[DefineOwnProperty]] 定义公有字段（Proxy trap 可观察）。 */
    private suspend fun defineFieldOrThrow(receiver: JSObject, key: PropertyKey, value: JSValue) {
        val defined = internalDefineOwnProperty(
            receiver,
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

    /** 在指定私有名环境下执行块（计算属性名等类定义阶段求值）。 */
    private suspend fun <T> withPrivateNameEnvironment(
        environment: PrivateEnvironment,
        block: suspend () -> T,
    ): T {
        privateEnvironmentOverrides.add(environment)
        return try {
            block()
        } finally {
            privateEnvironmentOverrides.removeAt(privateEnvironmentOverrides.lastIndex)
        }
    }

    /** 解析私有名引用（Parser 已在静态层面保证声明存在）。 */
    private fun resolvePrivateName(name: String): PrivateName =
        privateEnvironmentOverrides.lastOrNull()?.resolve(name)
            ?: currentFrame.function?.privateEnvironment?.resolve(name)
            ?: throw referenceError("Private field '#$name' must be declared in an enclosing class")

    /** 品牌检查：接收者必须拥有该私有名对应的槽位。 */
    private fun requirePrivateSlot(receiver: JSValue, name: PrivateName): PrivateSlot {
        val missing = {
            typeError(
                "Cannot read private member #${name.description} from an object " +
                    "whose class did not declare it"
            )
        }
        val obj = receiver as? JSObject ?: throw missing()
        return obj.findPrivateSlot(name) ?: throw missing()
    }

    /** PrivateFieldAdd：不可重复初始化，且接收者必须可扩展。 */
    private fun addPrivateFieldOrThrow(receiver: JSObject, name: PrivateName, value: JSValue) {
        if (receiver.findPrivateSlot(name) != null) {
            throw typeError("Cannot initialize #${name.description} twice on the same object")
        }
        if (!receiver.extensible) {
            throw typeError("Cannot add private field to a non-extensible object")
        }
        receiver.definePrivateField(name, value)
    }

    /** PrivateMethodOrAccessorAdd：接收者必须可扩展，且不可重复安装。 */
    private fun addPrivateMethodOrThrow(receiver: JSObject, name: PrivateName, slot: PrivateSlot) {
        if (!receiver.extensible) {
            throw typeError("Cannot add private method to a non-extensible object")
        }
        if (receiver.findPrivateSlot(name) != null) {
            throw typeError("Cannot initialize #${name.description} twice on the same object")
        }
        receiver.putPrivateSlot(name, slot)
    }

    /** 读取私有成员（字段/方法返回槽位值，访问器调用 getter）。 */
    private suspend fun getPrivateMember(receiver: JSValue, name: PrivateName): JSValue {
        val slot = requirePrivateSlot(receiver, name)
        return when (slot.kind) {
            PrivateElementKind.FIELD, PrivateElementKind.METHOD -> slot.value ?: JSUndefined
            PrivateElementKind.ACCESSOR -> {
                val getter = slot.getter
                    ?: throw typeError("'#${name.description}' was defined without a getter")
                callFunction(getter, receiver, emptyList())
            }
        }
    }

    /** 写入私有成员（字段可写；方法只读；访问器调用 setter）。 */
    private suspend fun setPrivateMember(receiver: JSValue, name: PrivateName, value: JSValue) {
        val slot = requirePrivateSlot(receiver, name)
        when (slot.kind) {
            PrivateElementKind.FIELD -> slot.value = value
            PrivateElementKind.METHOD ->
                throw typeError("Cannot assign to read only property '#${name.description}'")
            PrivateElementKind.ACCESSOR -> {
                val setter = slot.setter
                    ?: throw typeError("'#${name.description}' was defined without a setter")
                callFunction(setter, receiver, listOf(value))
            }
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

    /** 私有方法/访问器的函数名（带 # 前缀）。 */
    private fun privateMemberFunctionName(name: String, kind: ClassMemberKind): String = when (kind) {
        ClassMemberKind.GET -> "get #$name"
        ClassMemberKind.SET -> "set #$name"
        else -> "#$name"
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
            builder.append(expression.quasis[index] ?: "")
            builder.append(toStringValue(evaluate(expression.expressions[index])))
        }
        builder.append(expression.quasis.last() ?: "")
        return JSString(builder.toString())
    }

    /** 创建并冻结标签模板对象（含 raw 数组）。 */
    private fun createTemplateObject(expression: TaggedTemplate): JSArray {
        val cooked = expression.quasis.map { quasi ->
            quasi?.let { JSString(it) as JSValue } ?: JSUndefined
        }.toMutableList()
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
        return templateObject
    }

    /** 标签模板求值。 */
    private suspend fun evaluateTaggedTemplate(expression: TaggedTemplate, tail: Boolean = false): JSValue {
        // 先求值标签引用：成员访问形式保留接收者作为 this（obj.fn`...` 的 this 为 obj）
        val tagExpression = expression.tag
        val tagValue: JSValue
        var thisValue: JSValue = JSUndefined
        when {
            tagExpression is MemberExpr && tagExpression.obj is SuperExpr -> {
                if (!currentFrame.thisInitialized) {
                    throw referenceError("Must call super constructor before accessing 'super'")
                }
                thisValue = currentFrame.thisValue
                tagValue = getPropertyFrom(
                    superPropertyBase(),
                    memberKey(tagExpression),
                    currentFrame.thisValue,
                )
            }

            tagExpression is MemberExpr -> {
                val base = evaluate(tagExpression.obj)
                thisValue = base
                val privateName = (tagExpression.property as? PrivateIdentifier)?.name
                tagValue = if (privateName != null) {
                    getPrivateMember(base, resolvePrivateName(privateName))
                } else {
                    getV(base, memberKey(tagExpression))
                }
            }

            // 标识符标签：this 取 with 绑定对象（与普通调用一致）
            tagExpression is Identifier && tagExpression.name != "eval" -> {
                val owner = findBindingOwner(tagExpression.name)
                tagValue = readIdentifierReference(owner, tagExpression.name)
                thisValue = (owner as? ObjectEnvironment)?.withBaseObject() ?: JSUndefined
            }

            else -> tagValue = evaluate(tagExpression)
        }

        // GetTemplateObject：同一 AST 站点重复求值复用同一冻结模板对象
        val templateObject = intrinsics.templateCache
            .firstOrNull { it.first === expression }
            ?.second
            ?: createTemplateObject(expression).also {
                intrinsics.templateCache.add(expression to it)
            }

        val arguments = ArrayList<JSValue>()
        arguments.add(templateObject)
        for (item in expression.expressions) {
            arguments.add(evaluate(item))
        }
        return invokeOrTail(tagValue, thisValue, arguments, expression.range, tail)
    }

    /**
     * 创建 class 构造器与原型方法/字段。
     */
    private suspend fun evaluateClass(
        name: String?,
        superClassExpression: Expression?,
        members: List<ClassMember>,
        sourceText: String? = null,
    ): JSValue {
        // 匿名 class 表达式在 NamedEvaluation 场景（如 var C = class {...}）中
        // 需要在静态初始化之前就带上名称
        val inferredName = if (name == null) classNameHints.removeLastOrNull() else null
        val effectiveName = name ?: inferredName
        // class 环境先于 heritage 求值建立：类名在 heritage 中处于 TDZ
        // （class C extends C {} 必须抛 ReferenceError）
        val classEnvironment = DeclarativeEnvironment(parent = currentFrame.lexicalEnv)
        if (name != null) classEnvironment.declareLexical(name, mutable = false)
        val outerEnv = currentFrame.lexicalEnv
        val superClassValue = if (superClassExpression != null) {
            currentFrame.lexicalEnv = classEnvironment
            try {
                evaluate(superClassExpression)
            } finally {
                currentFrame.lexicalEnv = outerEnv
            }
        } else {
            null
        }
        if (superClassValue != null && superClassValue !is JSNull &&
            !isConstructor(superClassValue)
        ) {
            throw typeError("Class extends value is not a constructor or null")
        }

        // Get(superclass, "prototype")：可能是访问器，且必须为对象或 null，
        // 否则 GetPrototypeFromConstructor 语义下抛 TypeError（如 bound function）
        val protoParent: JSObject? = when (superClassValue) {
            null -> intrinsics.objectPrototype
            is JSNull -> null
            else -> when (val parentValue = getV(superClassValue, PropertyKey.PROTOTYPE)) {
                is JSObject -> parentValue
                is JSNull -> null
                else -> throw typeError(
                    "Class extends value does not have valid prototype property"
                )
            }
        }
        // class 的 [[Prototype]]：非 null 父类时为父构造器，否则为 %FunctionPrototype%
        val constructorParent: JSObject = when (superClassValue) {
            is JSObject -> superClassValue
            else -> intrinsics.functionPrototype
        }
        // extends null 时类原型对象的 [[Prototype]] 为 null
        val prototype = JSObject(prototype = protoParent)
        // class 私有名环境：成员函数与嵌套 class 通过闭包捕获
        val privateEnvironment = PrivateEnvironment(parent = currentFrame.function?.privateEnvironment)

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
                superBase = protoParent,
                homeObject = prototype,
                functionPrototypeObject = prototype,
                privateEnvironment = privateEnvironment,
                sourceText = sourceText ?: constructorMember.value.sourceText,
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
                superBase = protoParent,
                homeObject = prototype,
                functionPrototypeObject = prototype,
                privateEnvironment = privateEnvironment,
                sourceText = sourceText,
            )
        }

        if (name != null) {
            classEnvironment.initializeLexical(name, constructor)
        }
        // 构造器的内部 [[Prototype]] 为父构造器（extends null / 无 heritage 为 %FunctionPrototype%）
        constructor.prototype = constructorParent
        // class 构造器的 .prototype 是不可写、不可枚举、不可配置的数据属性
        constructor.defineOwnProperty(
            PropertyKey.PROTOTYPE,
            PropertyDescriptor.data(
                prototype,
                writable = false,
                enumerable = false,
                configurable = false,
            ),
        )

        // 静态字段与静态块按源码顺序执行；实例字段在构造时按源码顺序初始化
        val staticElements = ArrayList<StaticElement>()
        val instanceFields = ArrayList<InstanceField>()
        val instancePrivateMethods = LinkedHashMap<PrivateName, PrivateSlot>()
        val staticPrivateSlots = LinkedHashMap<PrivateName, PrivateSlot>()
        val privateAccessorSlots = LinkedHashMap<PrivateName, PrivateSlot>()
        // 私有名在整个 class 体内可见：先声明全部名字，再求值计算属性名
        for (member in members) {
            val key = member.key as? PrivateIdentifier ?: continue
            privateEnvironment.declare(key.name)
        }
        for (member in members) {
            if (member.kind == ClassMemberKind.CONSTRUCTOR) continue
            if (member.kind == ClassMemberKind.STATIC_BLOCK) {
                staticElements.add(StaticElement(member, null, null))
                continue
            }
            val privateKey = member.key as? PrivateIdentifier
            val privateName = privateKey?.let { privateEnvironment.declare(it.name) }
            val key = if (privateName != null) {
                // 仅作为函数名与记录键使用，不作为公开属性安装
                PropertyKey.Str("#${privateName.description}")
            } else if (member.computed) {
                // 计算属性名求值时，私有名引用解析到当前 class 的环境
                withPrivateNameEnvironment(privateEnvironment) {
                    toPropertyKey(evaluate(member.key))
                }
            } else {
                PropertyKey.Str(propertyName(member.key))
            }
            val target: JSObject = if (member.isStatic) constructor else prototype
            // 静态成员不得命名为 prototype（ClassElementEvaluation 的早期错误）
            if (member.isStatic && privateName == null && key == PropertyKey.PROTOTYPE) {
                throw typeError("Classes may not have a static property named 'prototype'")
            }
            val superBase = if (member.isStatic) {
                superClassValue as? JSObject
            } else {
                protoParent
            }
            val nameHint = if (privateName != null) {
                "#${privateName.description}"
            } else {
                functionNameFromPropertyKey(key)
            }
            if (member.kind == ClassMemberKind.FIELD) {
                if (member.isStatic) {
                    staticElements.add(
                        StaticElement(member, if (privateName == null) key else null, privateName)
                    )
                } else {
                    // 实例字段初始化器在类求值时创建一次，构造实例时复用
                    instanceFields.add(
                        InstanceField(
                            key = if (privateName == null) key else null,
                            privateName = privateName,
                            initializer = createFunction(
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
                                namedEvaluationHint = nameHint,
                                privateEnvironment = privateEnvironment,
                            ),
                        )
                    )
                }
                continue
            }
            val function = createFunction(
                name = if (privateName != null) {
                    privateMemberFunctionName(privateName.description, member.kind)
                } else {
                    methodFunctionName(key, member.kind)
                },
                params = member.value.params,
                body = member.value.body,
                closure = classEnvironment,
                strict = true,
                varDeclarations = member.value.varDeclarations,
                functionDeclarations = member.value.functionDeclarations,
                isAsync = member.value.isAsync,
                // 生成器方法：调用返回生成器对象，函数体延迟到首次 next()
                isGenerator = member.value.isGenerator,
                isMethod = true,
                superBase = superBase,
                homeObject = target,
                privateEnvironment = privateEnvironment,
                sourceText = member.value.sourceText,
            )
            if (privateName != null) {
                // 私有方法/访问器不安装为公开属性，而是安装到实例/构造器的私有槽位
                val slot = if (member.kind == ClassMemberKind.METHOD) {
                    PrivateSlot(PrivateElementKind.METHOD).also { it.value = function }
                } else {
                    val existing = privateAccessorSlots.getOrPut(privateName) {
                        PrivateSlot(PrivateElementKind.ACCESSOR)
                    }
                    if (member.kind == ClassMemberKind.GET) {
                        existing.getter = function
                    } else {
                        existing.setter = function
                    }
                    existing
                }
                if (member.isStatic) {
                    // getter/setter 共享同一槽位，按名字去重后再安装
                    staticPrivateSlots[privateName] = slot
                } else {
                    instancePrivateMethods[privateName] = slot
                }
                continue
            }
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
        for ((name, slot) in staticPrivateSlots) {
            addPrivateMethodOrThrow(constructor, name, slot)
        }
        constructor.instanceFields = instanceFields
        constructor.privateMethods = instancePrivateMethods.map { it.key to it.value }

        // 静态字段与静态初始化块按源码顺序执行：this 为类构造器，super 基为父构造器
        for (element in staticElements) {
            val member = element.member
            val privateName = element.privateName
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
                namedEvaluationHint = when {
                    privateName != null -> "#${privateName.description}"
                    member.kind == ClassMemberKind.FIELD ->
                        functionNameFromPropertyKey(element.key!!)

                    else -> null
                },
                privateEnvironment = privateEnvironment,
            )
            val value = callFunction(function, constructor, emptyList())
            if (member.kind == ClassMemberKind.FIELD) {
                if (privateName != null) {
                    addPrivateFieldOrThrow(constructor, privateName, value)
                } else {
                    // DefineField：CreateDataPropertyOrThrow 语义
                    defineFieldOrThrow(constructor, element.key!!, value)
                }
            }
        }

        return constructor
    }

    /** 一个 using 资源：接收者、初始化时读取的释放方法与是否异步释放。 */
    private class DisposalResource(
        val value: JSValue,
        val method: JSValue,
        val isAsync: Boolean,
    )

    /** 一个释放作用域内的资源列表（按声明顺序保存，释放时逆序）。 */
    private class DisposalScope {
        val resources = ArrayList<DisposalResource>()

        /** 是否存在 await using 声明（即使资源为空也需要让出微任务）。 */
        var hasAsyncResource: Boolean = false
    }

    /** 类求值时的静态元素（静态字段或静态初始化块）；字段携带属性键或私有名。 */
    private class StaticElement(
        val member: ClassMember,
        val key: PropertyKey?,
        val privateName: PrivateName?,
    )

    /** 在构造实例时安装私有方法/访问器，并按源码顺序执行字段初始化器。 */
    private suspend fun initializeInstanceFields(function: JSFunction, instance: JSValue) {
        val receiver = instance as? JSObject ?: return
        // 私有方法/访问器先于字段安装，字段初始化器内即可通过品牌检查访问
        for ((name, slot) in function.privateMethods) {
            addPrivateMethodOrThrow(receiver, name, slot)
        }
        for (field in function.instanceFields) {
            val value = callFunction(field.initializer, receiver, emptyList())
            val key = field.key
            if (key != null) {
                defineFieldOrThrow(receiver, key, value)
            } else {
                addPrivateFieldOrThrow(receiver, field.privateName!!, value)
            }
        }
    }

    /**
     * 在释放作用域内执行语句列表：正常/控制流完成与异常完成都先逆序释放资源。
     */
    private suspend fun <T> withDisposalScope(block: suspend () -> T): T {
        val scope = DisposalScope()
        disposalScopes.add(scope)
        try {
            val result = try {
                block()
            } catch (signal: GeneratorReturn) {
                // 生成器 return() 触发的提前完成等价于 return 完成：
                // using 资源仍然要释放，释放失败时由释放错误覆盖该完成
                disposeScope(scope, null)?.let { throw JSException(it) }
                if (scope.hasAsyncResource) awaitPromiseValue(JSUndefined)
                throw signal
            } catch (error: JSException) {
                disposeScope(scope, error.value)?.let { throw JSException(it) }
                throw error
            }
            disposeScope(scope, null)?.let { throw JSException(it) }
            if (scope.hasAsyncResource) awaitPromiseValue(JSUndefined)
            return result
        } finally {
            disposalScopes.removeAt(disposalScopes.lastIndex)
        }
    }

    /** 执行 using 声明：求值初始化器、读取释放方法并初始化不可变绑定。 */
    private suspend fun executeUsingDeclaration(statement: UsingDeclaration) {
        val scope = disposalScopes.lastOrNull()
            ?: throw syntaxError("using declaration is not allowed here")
        if (statement.isAwait) scope.hasAsyncResource = true
        for (declarator in statement.declarations) {
            val value = evaluateNamed(declarator.initializer, declarator.name)
            if (value !is JSNull && value !is JSUndefined) {
                val method = resolveDisposeMethod(value, statement.isAwait)
                scope.resources.add(DisposalResource(value, method, statement.isAwait))
            }
            if (!initializeLexicalIfDeclared(currentFrame.lexicalEnv, declarator.name, value)) {
                currentFrame.lexicalEnv.createConstBinding(declarator.name, value)
            }
        }
    }

    /** GetDisposeMethod：同步使用 @@dispose；异步优先 @@asyncDispose 再回退。 */
    private suspend fun resolveDisposeMethod(value: JSValue, isAsync: Boolean): JSValue {
        val method = if (isAsync) {
            val asyncMethod = getV(value, PropertyKey.Sym(intrinsics.symbolAsyncDispose))
            if (asyncMethod is JSUndefined || asyncMethod is JSNull) {
                getV(value, PropertyKey.Sym(intrinsics.symbolDispose))
            } else {
                asyncMethod
            }
        } else {
            getV(value, PropertyKey.Sym(intrinsics.symbolDispose))
        }
        if (method is JSUndefined || method is JSNull) {
            throw typeError("Object is not disposable")
        }
        if (!isCallable(method)) {
            throw typeError("Dispose method is not a function")
        }
        return method
    }

    /** DisposeResources：逆序调用释放方法，多个错误按 SuppressedError 嵌套。 */
    private suspend fun disposeScope(scope: DisposalScope, pending: JSValue?): JSValue? {
        var failure = pending
        for (index in scope.resources.indices.reversed()) {
            val resource = scope.resources[index]
            try {
                val result = callFunction(resource.method, resource.value, emptyList())
                if (resource.isAsync) awaitPromiseValue(result)
            } catch (error: JSException) {
                failure = if (failure == null) {
                    error.value
                } else {
                    createSuppressedErrorValue(error.value, failure)
                }
            }
        }
        scope.resources.clear()
        return failure
    }

    /**
     * Await(value)：PromiseResolve(%Promise%, value) 后等待落定。
     *
     * value 已是 %Promise% 的 Promise 时直接等待（PromiseResolve 的快速路径会读取
     * constructor，被篡改的 getter 使 Await 走 abrupt 分支），其他值先创建 Promise
     * 再采纳；无论是否需要等待，都至少让出一个微任务周期。
     */
    private suspend fun awaitPromiseValue(value: JSValue): JSValue {
        val promise = PromiseSupport.promiseResolve(this, value)
        val result = try {
            promise.awaitResult()
        } catch (error: JSException) {
            context.eventLoop.awaitMicrotask()
            throw error
        }
        context.eventLoop.awaitMicrotask()
        return result
    }

    /**
     * AsyncGeneratorAwaitReturn：async generator 的 return 完成值先经过 Await。
     *
     * 使用完整的 Await 语义：即使 return 值是普通值也要经过 PromiseResolve
     * （读取 constructor 等可观察副作用），thenable 的拒绝成为本次请求 Promise 的拒绝原因。
     */
    internal suspend fun awaitAsyncGeneratorReturnValue(value: JSValue): JSValue =
        awaitPromiseValue(value)

    /** 构造 SuppressedError 值（error 为新的主错误，suppressed 为先前累积错误）。 */
    private fun createSuppressedErrorValue(error: JSValue, suppressed: JSValue): JSValue {
        val objectValue = JSObject(prototype = intrinsics.suppressedErrorPrototype)
        // 自动资源释放生成的错误同样具有 ErrorData 和首次创建位置。
        objectValue.isErrorData = true
        captureErrorStack(objectValue)
        objectValue.defineDataProperty(
            key = PropertyKey.Str("error"),
            value = error,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        objectValue.defineDataProperty(
            key = PropertyKey.Str("suppressed"),
            value = suppressed,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return objectValue
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
                val hasParameterExpressions = superConstructor.params.any {
                    it.rest || it.defaultValue != null || it.pattern != null
                }
                // 与普通调用一致：参数初始化期间 VariableEnvironment 指向最外层环境
                val calleeEnv = DeclarativeEnvironment(
                    parent = superConstructor.closure,
                    isVarScope = true,
                )
                val parameterEnv: DeclarativeEnvironment
                val varEnv: DeclarativeEnvironment
                if (hasParameterExpressions) {
                    parameterEnv = DeclarativeEnvironment(parent = calleeEnv)
                    varEnv = DeclarativeEnvironment(parent = parameterEnv, isVarScope = true)
                } else {
                    parameterEnv = calleeEnv
                    varEnv = calleeEnv
                }
                val previousFrame = frame
                val activeFrame = ExecutionContext(
                    lexicalEnv = parameterEnv,
                    thisValue = thisValue,
                    strict = true,
                    function = superConstructor,
                    newTarget = newTarget,
                    thisInitialized = !derived,
                    variableEnv = calleeEnv,
                    inParameterInitialization = hasParameterExpressions,
                ).also { it.sourceModule = superConstructor.sourceModule }
                pushStackFrame(
                    superConstructor.name.ifEmpty { "anonymous" }, superConstructor.body?.range,
                    sourceInfo = superConstructor.sourceInfo,
                )
                frame = activeFrame
                try {
                    parameterEnv.createVarBinding("arguments", createArgumentsObject(args, superConstructor))
                    bindParameters(superConstructor, args, parameterEnv)
                    // 参数绑定完成，VariableEnvironment 切换到独立 var 环境；函数体词法环境位于其上方
                    val bodyEnv = DeclarativeEnvironment(parent = varEnv)
                    activeFrame.lexicalEnv = bodyEnv
                    activeFrame.variableEnv = varEnv
                    activeFrame.inParameterInitialization = false
                    hoistDeclarations(
                        bodyEnv,
                        superConstructor.functionDeclarations,
                        superConstructor.varDeclarations,
                    )
                    if (hasParameterExpressions) {
                        initializeParameterVarBindings(superConstructor, parameterEnv, varEnv)
                    }
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
                        val completion = executeStatements(body.statements, body.hasUsingDeclaration)
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
                } catch (error: Throwable) {
                    captureFailure(error)
                    throw error
                } finally {
                    popStackFrame()
                    frame = previousFrame
                }
            }

            is JSNativeFunction -> {
                // 原生父构造器：按 construct 语义创建实例，NewTarget 透传，Derived 采用返回值作为 this
                construct(superConstructor, args, currentFrame.newTarget ?: superConstructor)
            }

            else -> throw typeError("Super constructor is not a constructor")
        }
    }

    // ================================================================
    // 生成器（yield / yield*）
    // ================================================================

    /**
     * 求值 `yield` / `yield*`。
     *
     * yield 是生成器函数体与恢复方之间的握手点：先交出产出值，再等待下一个恢复请求。
     * Next 请求的值成为 yield 表达式的值；Return 请求转换为内部控制流信号
     * [GeneratorReturn]（因此 finally 会正常执行）；Throw 请求转换为 JS 异常。
     *
     * async generator 的 yield 操作数先经过 Await（thenable 会被解包，
     * 拒绝在函数体内传播，可被 try/catch 捕获）。
     *
     * 恢复值为 return 时同样先经过 Await（AsyncGeneratorUnwrapYieldResumption）：
     * 等待失败在该 yield 处以异常形式抛出，因此可以被函数体的 try/catch 捕获，
     * 而不是让请求 Promise 直接拒绝。yield* 的委托 return 路径复用同一规则。
     */
    private suspend fun evaluateYield(expression: YieldExpression): JSValue {
        val channels = generatorChannels
            ?: throw syntaxError("yield is only valid inside a generator function")
        val argument = expression.argument
        if (expression.delegate) {
            if (argument == null) throw syntaxError("yield* requires an argument")
            return evaluateYieldDelegate(argument, channels)
        }
        val value = if (argument != null) evaluate(argument) else JSUndefined
        // 规范 AsyncGeneratorYield step 5：async generator 在交出产出值前执行 Await(value)。
        // 即使 value 不是 thenable，Await 也会让出一个微任务周期（PromiseResolve），
        // 这决定了 return()/next() 与外部 Promise 链的交错顺序。
        val yieldedValue = if (generatorIsAsync) awaitPromiseValue(value) else value
        return when (val request = yieldAndReceive(channels, yieldedValue)) {
            is GeneratorRequest.Next -> request.value
            // 恢复值已在 yieldAndReceive 中按 AsyncGeneratorUnwrapYieldResumption 等待
            is GeneratorRequest.Return -> throw GeneratorReturn(request.value)
            is GeneratorRequest.Throw -> throw JSException(request.value)
        }
    }

    /**
     * 交出产出值并等待下一个恢复请求（不解释请求语义，供 yield* 复用）。
     *
     * async generator 的 return 恢复值先经过 Await（对应规范的
     * AsyncGeneratorUnwrapYieldResumption）：因此在 yield 与 yield* 的委托点，
     * 上层拿到的都是已解包的值，thenable 的 then 不会被重复读取。
     * 等待失败时该异常从 yield 表达式抛出，可被函数体的 try/catch 捕获。
     */
    private suspend fun yieldAndReceive(
        channels: GeneratorChannels,
        value: JSValue,
        resultObject: JSObject? = null,
    ): GeneratorRequest {
        channels.steps.send(GeneratorStep.Yielded(value, resultObject))
        val request = channels.requests.receive()
        return if (generatorIsAsync && request is GeneratorRequest.Return) {
            GeneratorRequest.Return(awaitPromiseValue(request.value))
        } else {
            request
        }
    }

    /**
     * 求值 `yield*`：按迭代协议转发 next/throw/return。
     *
     * 同步生成器使用同步迭代协议；async generator 使用异步迭代协议
     * （GetIterator(async)：优先 `@@asyncIterator`，缺失时包装同步迭代器），
     * 内部调用结果与产出值都按 Await 语义解包。
     *
     * 委托结束（内部迭代器 done）时 yield* 的值是内部迭代器的返回值；
     * 内部迭代器没有 return 方法、或 return 请求使内部迭代器结束时，
     * 按 return 完成结束外层生成器（仍会执行 finally）；
     * 没有 throw 方法时先关闭迭代器再抛 TypeError。
     */
    private suspend fun evaluateYieldDelegate(
        argument: Expression,
        channels: GeneratorChannels,
    ): JSValue {
        val isAsync = generatorIsAsync
        val record = if (isAsync) {
            getAsyncIterator(evaluate(argument))
        } else {
            getIterator(evaluate(argument))
        }
        val iterator = record.iterator
        // 规范：received 初值是 NormalCompletion(undefined)，因此首次 next 也带一个 undefined 参数；
        // null 表示首次调用（与后续 Next 请求共用 next 方法，不再重复读取）
        var request: GeneratorRequest? = null
        while (true) {
            val rawInnerResult = when (val current = request) {
                null -> callFunction(record.nextMethod, iterator, listOf(JSUndefined))
                is GeneratorRequest.Next ->
                    callFunction(record.nextMethod, iterator, listOf(current.value))

                is GeneratorRequest.Throw -> {
                    val throwMethod = getV(iterator, PropertyKey.Str("throw"))
                    if (throwMethod is JSUndefined || throwMethod is JSNull) {
                        // 没有 throw 方法：按正常完成关闭迭代器后再抛 TypeError
                        iteratorClose(record, throwCompletion = false)
                        throw typeError("The iterator does not provide a 'throw' method")
                    }
                    if (!isCallable(throwMethod)) {
                        throw typeError("Iterator throw is not a function")
                    }
                    callFunction(throwMethod, iterator, listOf(current.value))
                }

                is GeneratorRequest.Return -> {
                    val returnMethod = getV(iterator, PropertyKey.Str("return"))
                    if (returnMethod is JSUndefined || returnMethod is JSNull) {
                        // 委托的 return 完成直接结束外层生成器（async 下恢复值先经过 Await）
                        throw GeneratorReturn(
                            if (isAsync) awaitPromiseValue(current.value) else current.value
                        )
                    }
                    if (!isCallable(returnMethod)) {
                        throw typeError("Iterator return is not a function")
                    }
                    callFunction(returnMethod, iterator, listOf(current.value))
                }
            }
            // 异步委托：next/throw/return 的返回先经过 Await，再读取 done/value
            val innerResult = if (isAsync) awaitPromiseValue(rawInnerResult) else rawInnerResult
            if (innerResult !is JSObject) {
                throw typeError("Iterator result is not an object")
            }
            // 规范的读取顺序：先 IteratorComplete(done)，未完成时同步委托直接把
            // 内层 IteratorResult 交给调用方（Yield(innerResult)），不读取 value
            val done = toBoolean(getV(innerResult, PropertyKey.Str("done")))
            if (done) {
                val value = getV(innerResult, PropertyKey.Str("value"))
                // return 请求让内部迭代器结束：内部返回值成为外层生成器的 return 完成值
                if (request is GeneratorRequest.Return) {
                    throw GeneratorReturn(if (isAsync) awaitPromiseValue(value) else value)
                }
                return value
            }
            if (!isAsync) {
                // 同步 yield*：交出内层结果对象本身，value getter 的访问次数与内层一致
                request = yieldAndReceive(
                    channels,
                    JSUndefined,
                    resultObject = innerResult,
                )
                continue
            }
            // 异步委托不再对值做额外等待：AsyncFromSync 包装器的 next() 已按
            // AsyncFromSyncIteratorContinuation 解包 value；手动实现的异步迭代器
            // 返回的 Promise 按规范保持原样（yield* 只 Await 内部结果）。
            val value = getV(innerResult, PropertyKey.Str("value"))
            request = yieldAndReceive(channels, value)
        }
    }

    private suspend fun evaluateCall(expression: CallExpr, tail: Boolean = false): JSValue {
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
            // （super(super()) 中内层调用先绑定 this，外层再绑定应抛 ReferenceError）
            val arguments = evaluateArguments(expression.arguments)
            val superConstructorValue: JSValue = function.prototype ?: intrinsics.functionPrototype
            if (!isConstructor(superConstructorValue)) {
                throw typeError("Super constructor is not a constructor")
            }
            val result = callSuperConstructor(superConstructorValue, thisBinding.boundThis, arguments)
            if (thisBinding.thisBound) {
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
            if (!currentFrame.thisInitialized) {
                throw referenceError("Must call super constructor before accessing 'super'")
            }
            // 方法使用 HomeObject 的动态原型（对象可被 setPrototypeOf 修改）
            val superBase = superPropertyBase()
            val key = memberKey(calleeExpression)
            // 访问器以当前 this 为接收者
            val function = getPropertyFrom(superBase, key, currentFrame.thisValue)
            val arguments = evaluateArguments(expression.arguments)
            return invokeOrTail(function, currentFrame.thisValue, arguments, expression.range, tail)
        }

        // 直接 eval：只有与全局 %eval% 是同一函数时才按直接求值处理
        if (calleeExpression is Identifier && calleeExpression.name == "eval") {
            val function = evaluate(calleeExpression)
            val arguments = evaluateArguments(expression.arguments)
            // 规范：与 %eval% 内建函数同一才按直接 eval 处理（全局 eval 可被覆盖）
            if (function === intrinsics.evalFunction) {
                val value = arguments.getOrElse(0) { JSUndefined }
                return if (value is JSString) evaluateEvalSource(value.value) else value
            }
            return invokeOrTail(function, JSUndefined, arguments, expression.range, tail)
        }

        // 标识符调用：this 取引用基环境的 WithBaseObject（with 绑定对象），否则 undefined
        if (calleeExpression is Identifier) {
            val owner = findBindingOwner(calleeExpression.name)
            val function = readIdentifierReference(owner, calleeExpression.name)
            val thisValue = (owner as? ObjectEnvironment)?.withBaseObject() ?: JSUndefined
            val arguments = evaluateArguments(expression.arguments)
            if (!isCallable(function)) {
                throw typeError(
                    "${describeValue(function)} is not a function (at ${expression.range})"
                )
            }
            return invokeOrTail(function, thisValue, arguments, expression.range, tail)
        }

        if (calleeExpression is OptionalChainExpr) {
            // 括号包裹的可选链作为被调用者：保留最后成员访问的 this
            val chain = evaluateOptionalChainInternal(
                calleeExpression,
                calleeExpression.operations.size,
            )
            val arguments = evaluateArguments(expression.arguments)
            val thisValue = if (chain.hasReceiver) chain.receiver ?: JSUndefined else JSUndefined
            return invokeOrTail(chain.value, thisValue, arguments, expression.range, tail)
        }

        if (calleeExpression is MemberExpr) {
            // 方法调用：this 为成员访问的基对象
            val base = evaluate(calleeExpression.obj)
            val privateName = (calleeExpression.property as? PrivateIdentifier)?.name
            val function = if (privateName != null) {
                getPrivateMember(base, resolvePrivateName(privateName))
            } else {
                getV(base, memberKey(calleeExpression))
            }
            val arguments = evaluateArguments(expression.arguments)
            if (!isCallable(function)) {
                throw typeError(
                    "${describeValue(function)} is not a function (at ${calleeExpression.range})"
                )
            }
            return invokeOrTail(function, base, arguments, expression.range, tail)
        }

        val function = evaluate(calleeExpression)
        val arguments = evaluateArguments(expression.arguments)
        if (!isCallable(function)) {
            throw typeError(
                "${describeValue(function)} is not a function (at ${calleeExpression.range})"
            )
        }
        return invokeOrTail(function, JSUndefined, arguments, expression.range, tail)
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
                    getPropertyFrom(superPropertyBase(), memberKey(base), currentFrame.thisValue)
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
                    val privateName = (operation.property as? PrivateIdentifier)?.name
                    receiver = base
                    hasReceiver = true
                    value = if (privateName != null) {
                        getPrivateMember(base, resolvePrivateName(privateName))
                    } else {
                        val key = if (operation.computed) {
                            toPropertyKey(evaluate(operation.property))
                        } else {
                            PropertyKey.Str((operation.property as Identifier).name)
                        }
                        getV(base, key)
                    }
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
        val base = superPropertyBaseOrNull()
        return base ?: throw typeError("Cannot read properties of null (reading 'super')")
    }

    /** super 的 [[HomeObject]]/[[SuperBase]] 原型；可能为 null（PutValue 时才抛 TypeError）。 */
    private fun superPropertyBaseOrNull(): JSObject? {
        val function = currentFrame.function
        return if (function?.homeObject != null) {
            function.homeObject?.prototype
        } else {
            function?.superBase
        }
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
            val descriptor = internalGetOwnProperty(current, key)
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
        if (receiver !is JSObject) {
            if (strict) throw typeError("Cannot create property '$key' on primitive value")
            return
        }
        val defined = ordinarySetOnReceiver(receiver, key, value)
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
                        // 括号包裹的标识符目标不是语法上的 IdentifierRef，不推断函数名
                        val value = if (expression.lhsParenthesized) {
                            evaluate(expression.value)
                        } else {
                            evaluateNamed(expression.value, target.name)
                        }
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
                    // GetSuperBase 在属性表达式之后、RHS 之前；base 为 null 时 TypeError
                    // 延迟到 PutValue/GetValue（简单赋值会在 RHS 求值后抛出）
                    val base = superPropertyBaseOrNull()
                    val keyValue = if (target.computed) evaluate(target.property) else null
                    var resolvedKey: PropertyKey? = null
                    suspend fun key(): PropertyKey {
                        resolvedKey?.let { return it }
                        val result = keyValue?.let { toPropertyKey(it) }
                            ?: PropertyKey.Str((target.property as Identifier).name)
                        resolvedKey = result
                        return result
                    }
                    return when {
                        operator == "=" -> {
                            val value = evaluate(expression.value)
                            if (base == null) {
                                throw typeError("Cannot convert undefined or null to object")
                            }
                            putSuperProperty(
                                base, currentFrame.thisValue, key(), value, currentFrame.strict,
                            )
                            value
                        }

                        isLogicalAssignment(operator) -> {
                            if (base == null) throw typeError("Cannot read properties of null")
                            val current = getV(base, key())
                            if (!shouldAssignLogical(operator, current)) {
                                current
                            } else {
                                val value = evaluate(expression.value)
                                putSuperProperty(
                                    base, currentFrame.thisValue, key(), value, currentFrame.strict,
                                )
                                value
                            }
                        }

                        else -> {
                            if (base == null) throw typeError("Cannot read properties of null")
                            val current = getV(base, key())
                            val right = evaluate(expression.value)
                            val result = applyBinary(operator.dropLast(1), current, right)
                            putSuperProperty(
                                base, currentFrame.thisValue, key(), result, currentFrame.strict,
                            )
                            result
                        }
                    }
                }
                val privateMember = (target.property as? PrivateIdentifier)?.name
                if (privateMember != null) {
                    val base = evaluate(target.obj)
                    val name = resolvePrivateName(privateMember)
                    return when {
                        operator == "=" -> {
                            val value = evaluate(expression.value)
                            setPrivateMember(base, name, value)
                            value
                        }

                        isLogicalAssignment(operator) -> {
                            val current = getPrivateMember(base, name)
                            if (!shouldAssignLogical(operator, current)) {
                                current
                            } else {
                                val value = evaluate(expression.value)
                                setPrivateMember(base, name, value)
                                value
                            }
                        }

                        else -> {
                            val current = getPrivateMember(base, name)
                            val right = evaluate(expression.value)
                            val result = applyBinary(operator.dropLast(1), current, right)
                            setPrivateMember(base, name, result)
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

            is CallExpr -> {
                // Annex B web-compat：先求值调用，再抛 ReferenceError（不执行 RHS）
                evaluate(target)
                throw referenceError("Invalid left-hand side in assignment")
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
        val privateName: String? = null,
    )

    private suspend fun preEvaluateMemberReference(target: Expression): MemberReference? = when (target) {
        is MemberExpr -> {
            val base = evaluate(target.obj)
            val privateMember = (target.property as? PrivateIdentifier)?.name
            if (privateMember != null) {
                MemberReference(base, null, null, privateMember)
            } else if (target.computed) {
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
        /** 键控解构绑定在 GetV 之前解析出的 var 绑定环境（InitializeReferencedBinding） */
        bindingOwnerOverride: Environment? = null,
    ) {
        when (pattern) {
            is Identifier -> {
                if (kind == null) {
                    assignIdentifier(pattern.name, value)
                } else {
                    val targetEnvironment = environment
                        ?: throw syntaxError("Invalid destructuring environment")
                    when (kind) {
                        DeclarationKind.VAR -> {
                            // 键控解构已在 GetV 之前完成 ResolveBinding（HasBinding 可观察），
                            // 若解析到绑定则用该环境初始化
                            val owner = bindingOwnerOverride ?: targetEnvironment.findVarScope()
                            owner.createVarBinding(pattern.name, value)
                        }

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
                try {
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
                } catch (signal: GeneratorReturn) {
                    // 生成器 return() 在目标引用/默认值求值中触发：规范把该完成记录
                    // 传给 IteratorClose；return 完成不是 throw 完成，因此 return()
                    // 自身的异常或非对象返回值必须继续向上传播（覆盖原信号）。
                    if (!done) iteratorClose(record, throwCompletion = false)
                    throw signal
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
                            // 规范顺序：先求值源属性键（含 ToPropertyKey），
                            // 再求值目标引用；目标键的 ToPropertyKey 延迟到 PutValue
                            val key = if (element.computed) {
                                toPropertyKey(evaluate(element.key))
                            } else {
                                PropertyKey.Str(propertyName(element.key))
                            }
                            usedKeys.add(key)
                            val targetReference = if (kind == null) {
                                preEvaluateMemberReference(element.value)
                            } else {
                                null
                            }
                            // KeyedBindingInitialization：ResolveBinding 先于 GetV，
                            // with/Proxy 环境的 HasBinding 因此可以观察到顺序
                            val bindingOwner = if (kind == DeclarationKind.VAR) {
                                destructuringBindingTargetIdentifier(element.value)
                                    ?.let { findBindingOwner(it.name) }
                            } else {
                                null
                            }
                            bindPattern(
                                element.value,
                                getV(source, key),
                                kind,
                                environment,
                                targetReference,
                                bindingOwner,
                            )
                        }

                        is ObjectSpreadProperty -> {
                            val restObject = JSObject(prototype = intrinsics.objectPrototype)
                            for (key in internalOwnPropertyKeys(source)) {
                                if (key in usedKeys) continue
                                if (internalGetOwnProperty(source, key)?.enumerable != true) continue
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
                val privateMember = reference?.privateName
                    ?: (pattern.property as? PrivateIdentifier)?.name
                val base = reference?.base ?: evaluate(pattern.obj)
                if (privateMember != null) {
                    setPrivateMember(base, resolvePrivateName(privateMember), value)
                } else {
                    val key = reference?.let { ref ->
                        ref.staticKey ?: toPropertyKey(ref.keyValue ?: JSUndefined)
                    } ?: memberKey(pattern)
                    putV(base, key, value, currentFrame.strict)
                }
            }

            else -> throw syntaxError("Invalid destructuring target")
        }
    }

    /**
     * 解构绑定目标中的直接标识符：
     * - `{a}` → a
     * - `{a = 1}` → a
     * 其他模式（成员表达式、嵌套模式）返回 null。
     */
    private fun destructuringBindingTargetIdentifier(pattern: Expression): Identifier? = when (pattern) {
        is Identifier -> pattern
        is AssignmentExpr -> destructuringBindingTargetIdentifier(pattern.target)
        else -> null
    }

    /**
     * 把可迭代值展开为列表（数组、字符串、类数组对象）。
     *
     * Map/Set 与自定义迭代器在后续批次接入。
     */
    /** 迭代器记录：[[Iterator]] 与 [[NextMethod]]。 */
    private class IteratorRecord(
        val iterator: JSObject,
        val nextMethod: JSValue,
        /** 异步迭代器：next()/return() 的返回值需要 await。 */
        val isAsync: Boolean = false,
    )

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
        // GetIterator 只读取 next 方法，不在此校验可调用性：
        // 规范把 TypeError 推迟到 IteratorNext 真正调用 next 时。
        // 这样“目标引用求值中挂起”的赋值解构可以先取得迭代器再挂起，
        // 之后 return() 触发 IteratorClose 时仍能按规范关闭（见 dstr 用例）。
        val nextMethod = getV(iteratorValue, PropertyKey.Str("next"))
        return IteratorRecord(iteratorValue, nextMethod)
    }

    /** 取下一个迭代值；返回 null 表示迭代完成，否则返回 value（非空）。 */
    private suspend fun iteratorNext(record: IteratorRecord): JSValue? {
        val raw = callFunction(record.nextMethod, record.iterator, emptyList())
        val result = if (record.isAsync) awaitPromiseValue(raw) else raw
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
        val raw = try {
            callFunction(returnMethod, record.iterator, emptyList())
        } catch (error: JSException) {
            if (throwCompletion) return
            throw error
        }
        val result = if (record.isAsync) {
            try {
                awaitPromiseValue(raw)
            } catch (error: JSException) {
                if (throwCompletion) return
                throw error
            }
        } else {
            raw
        }
        if (!throwCompletion && result !is JSObject) {
            throw typeError("Iterator result is not an object")
        }
    }

    /**
     * GetIterator(obj, async)：优先 @@asyncIterator；缺失时回退到同步迭代器。
     *
     * 回退路径按 AsyncFromSyncIterator 语义处理：next() 的结果与 value 都需要 await。
     */
    private suspend fun getAsyncIterator(value: JSValue): IteratorRecord {
        if (value is JSNull || value is JSUndefined) {
            throw typeError("Cannot convert undefined or null to object")
        }
        val objectValue = if (value is JSObject) value else toObject(value)
        val asyncMethod = getV(objectValue, PropertyKey.Sym(intrinsics.symbolAsyncIterator))
        if (asyncMethod !is JSUndefined && asyncMethod !is JSNull) {
            if (!isCallable(asyncMethod)) {
                throw typeError("Symbol.asyncIterator is not a function")
            }
            val iteratorValue = callFunction(asyncMethod, objectValue, emptyList())
            if (iteratorValue !is JSObject) {
                throw typeError("Result of the Symbol.asyncIterator method is not an object")
            }
            // 与同步 GetIterator 一致：next 的可调用性推迟到真正调用时校验
            val nextMethod = getV(iteratorValue, PropertyKey.Str("next"))
            return IteratorRecord(iteratorValue, nextMethod, isAsync = true)
        }
        // 没有 @@asyncIterator：按 AsyncFromSyncIterator 包装同步迭代器。
        // 不能直接复用同步迭代器记录：包装器的 next() 必须返回 Promise，
        // 且 value 经 PromiseResolve 等待（constructor 读取次数与微任务顺序可观察）。
        val sync = getIterator(value)
        return createAsyncFromSyncIterator(sync)
    }

    /**
     * 创建 AsyncFromSyncIterator 包装器。
     *
     * 规范对象对外只有 next/return/throw 三个方法；包装器本身不需要暴露
     * 同步迭代器。next() 返回的 Promise 在 value 经 Await 后兑现，
     * 与 for-await / yield* 的 Await 组合后形成规范的 tick 顺序。
     */
    private fun createAsyncFromSyncIterator(sync: IteratorRecord): IteratorRecord {
        val wrapper = JSObject(prototype = intrinsics.asyncIteratorPrototype)
        val nextMethod = JSNativeFunction(
            name = "next",
            length = 1,
            prototype = intrinsics.functionPrototype,
        ) { interpreter, _, args ->
            asyncFromSyncNext(
                interpreter,
                sync,
                args.getOrElse(0) { JSUndefined },
                argumentPresent = args.isNotEmpty(),
            )
        }
        wrapper.defineDataProperty(
            key = PropertyKey.Str("next"),
            value = nextMethod,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        val returnMethod = JSNativeFunction(
            name = "return",
            length = 1,
            prototype = intrinsics.functionPrototype,
        ) { interpreter, _, args ->
            asyncFromSyncReturn(
                interpreter,
                sync,
                args.getOrElse(0) { JSUndefined },
                argumentPresent = args.isNotEmpty(),
            )
        }
        wrapper.defineDataProperty(
            key = PropertyKey.Str("return"),
            value = returnMethod,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        val throwMethod = JSNativeFunction(
            name = "throw",
            length = 1,
            prototype = intrinsics.functionPrototype,
        ) { interpreter, _, args ->
            asyncFromSyncThrow(
                interpreter,
                sync,
                args.getOrElse(0) { JSUndefined },
                argumentPresent = args.isNotEmpty(),
            )
        }
        wrapper.defineDataProperty(
            key = PropertyKey.Str("throw"),
            value = throwMethod,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return IteratorRecord(wrapper, nextMethod, isAsync = true)
    }

    /** AsyncFromSyncIteratorPrototype.next：同步 next + AsyncFromSyncIteratorContinuation。 */
    private suspend fun asyncFromSyncNext(
        interpreter: Interpreter,
        sync: IteratorRecord,
        argument: JSValue,
        argumentPresent: Boolean,
    ): JSValue {
        val capability = PromiseSupport.newPromiseCapability(
            interpreter,
            interpreter.intrinsics.promiseConstructor,
        )
        val result = try {
            interpreter.callFunction(
                sync.nextMethod,
                sync.iterator,
                if (argumentPresent) listOf(argument) else emptyList(),
            )
        } catch (error: JSException) {
            PromiseSupport.callReject(interpreter, capability.reject, error)
            return capability.promise
        }
        if (result !is JSObject) {
            PromiseSupport.callReject(
                interpreter,
                capability.reject,
                JSException(
                    JSError(
                        errorName = "TypeError",
                        messageText = "Iterator result is not an object",
                        prototype = interpreter.intrinsics.typeErrorPrototype,
                    )
                ),
            )
            return capability.promise
        }
        return asyncFromSyncContinuation(interpreter, sync, result, capability, closeOnRejection = true)
    }

    /** AsyncFromSyncIteratorPrototype.return：转发同步 return 并等待其结果。 */
    private suspend fun asyncFromSyncReturn(
        interpreter: Interpreter,
        sync: IteratorRecord,
        value: JSValue,
        argumentPresent: Boolean,
    ): JSValue {
        val capability = PromiseSupport.newPromiseCapability(
            interpreter,
            interpreter.intrinsics.promiseConstructor,
        )
        val returnMethod = try {
            interpreter.getV(sync.iterator, PropertyKey.Str("return"))
        } catch (error: JSException) {
            PromiseSupport.callReject(interpreter, capability.reject, error)
            return capability.promise
        }
        if (returnMethod is JSUndefined || returnMethod is JSNull) {
            // 没有 return 方法：直接以 { value, done: true } 兑现
            interpreter.callFunction(
                capability.resolve,
                JSUndefined,
                listOf(createIteratorResult(interpreter, value, done = true)),
            )
            return capability.promise
        }
        if (!interpreter.isCallable(returnMethod)) {
            PromiseSupport.callReject(
                interpreter,
                capability.reject,
                JSException(
                    JSError(
                        errorName = "TypeError",
                        messageText = "Iterator return is not a function",
                        prototype = interpreter.intrinsics.typeErrorPrototype,
                    )
                ),
            )
            return capability.promise
        }
        val result = try {
            interpreter.callFunction(
                returnMethod,
                sync.iterator,
                if (argumentPresent) listOf(value) else emptyList(),
            )
        } catch (error: JSException) {
            PromiseSupport.callReject(interpreter, capability.reject, error)
            return capability.promise
        }
        if (result !is JSObject) {
            PromiseSupport.callReject(
                interpreter,
                capability.reject,
                JSException(
                    JSError(
                        errorName = "TypeError",
                        messageText = "Iterator result is not an object",
                        prototype = interpreter.intrinsics.typeErrorPrototype,
                    )
                ),
            )
            return capability.promise
        }
        return asyncFromSyncContinuation(
            interpreter,
            sync,
            result,
            capability,
            closeOnRejection = false,
        )
    }

    /** AsyncFromSyncIteratorPrototype.throw：转发同步 throw；缺失时关闭同步迭代器。 */
    private suspend fun asyncFromSyncThrow(
        interpreter: Interpreter,
        sync: IteratorRecord,
        value: JSValue,
        argumentPresent: Boolean,
    ): JSValue {
        val capability = PromiseSupport.newPromiseCapability(
            interpreter,
            interpreter.intrinsics.promiseConstructor,
        )
        val throwMethod = try {
            interpreter.getV(sync.iterator, PropertyKey.Str("throw"))
        } catch (error: JSException) {
            PromiseSupport.callReject(interpreter, capability.reject, error)
            return capability.promise
        }
        if (throwMethod is JSUndefined || throwMethod is JSNull) {
            // 规范：先用 normal 完成关闭同步迭代器；关闭抛出的错误优先拒绝，
            // 否则以 TypeError 拒绝（协议违规）。
            val closeError = try {
                interpreter.iteratorClose(sync, throwCompletion = false)
                null
            } catch (error: JSException) {
                error
            }
            if (closeError != null) {
                PromiseSupport.callReject(interpreter, capability.reject, closeError)
                return capability.promise
            }
            val error = JSException(
                JSError(
                    errorName = "TypeError",
                    messageText = "The iterator does not provide a 'throw' method",
                    prototype = interpreter.intrinsics.typeErrorPrototype,
                )
            )
            PromiseSupport.callReject(interpreter, capability.reject, error)
            return capability.promise
        }
        if (!interpreter.isCallable(throwMethod)) {
            PromiseSupport.callReject(
                interpreter,
                capability.reject,
                JSException(
                    JSError(
                        errorName = "TypeError",
                        messageText = "Iterator throw is not a function",
                        prototype = interpreter.intrinsics.typeErrorPrototype,
                    )
                ),
            )
            return capability.promise
        }
        val result = try {
            interpreter.callFunction(
                throwMethod,
                sync.iterator,
                if (argumentPresent) listOf(value) else emptyList(),
            )
        } catch (error: JSException) {
            PromiseSupport.callReject(interpreter, capability.reject, error)
            return capability.promise
        }
        if (result !is JSObject) {
            PromiseSupport.callReject(
                interpreter,
                capability.reject,
                JSException(
                    JSError(
                        errorName = "TypeError",
                        messageText = "Iterator result is not an object",
                        prototype = interpreter.intrinsics.typeErrorPrototype,
                    )
                ),
            )
            return capability.promise
        }
        return asyncFromSyncContinuation(
            interpreter,
            sync,
            result,
            capability,
            // throw 的值等待失败时需要关闭同步迭代器
            closeOnRejection = true,
        )
    }

    /**
     * AsyncFromSyncIteratorContinuation：读取 done/value，value 经 PromiseResolve
     * 等待后再以 { value, done } 兑现请求 Promise。
     */
    private suspend fun asyncFromSyncContinuation(
        interpreter: Interpreter,
        sync: IteratorRecord,
        result: JSObject,
        capability: PromiseCapability,
        closeOnRejection: Boolean,
    ): JSValue {
        // done 在异常路径也需要可见：valueWrapper 的 abrupt 完成只有在
        // 尚未完成时才关闭同步迭代器（规范 step 6 的 done is false 条件）。
        var done = false
        try {
            done = interpreter.toBoolean(interpreter.getV(result, PropertyKey.Str("done")))
            val value = interpreter.getV(result, PropertyKey.Str("value"))
            val valueWrapper = PromiseSupport.promiseResolve(interpreter, value)
            val onFulfilled = JSNativeFunction(
                name = "",
                length = 1,
                prototype = interpreter.intrinsics.functionPrototype,
            ) { inner, _, args ->
                createIteratorResult(
                    inner,
                    args.getOrElse(0) { JSUndefined },
                    done,
                )
            }
            val onRejected = JSNativeFunction(
                name = "",
                length = 1,
                prototype = interpreter.intrinsics.functionPrototype,
            ) { inner, _, args ->
                // 值等待失败：按 closeOnRejection 关闭同步迭代器后拒绝
                if (closeOnRejection) closeSyncIteratorQuietly(inner, sync)
                val reason = args.getOrElse(0) { JSUndefined }
                PromiseSupport.callReject(inner, capability.reject, JSException(reason))
                JSUndefined
            }
            PromiseSupport.performThen(
                interpreter,
                valueWrapper,
                onFulfilled,
                onRejected,
                capability,
            )
        } catch (error: JSException) {
            // 值等待/结果属性读取失败：按 closeOnRejection 关闭同步迭代器
            if (closeOnRejection && !done) closeSyncIteratorQuietly(interpreter, sync)
            PromiseSupport.callReject(interpreter, capability.reject, error)
        }
        return capability.promise
    }

    /** 关闭同步迭代器并忽略关闭本身的异常（AsyncFromSync 拒绝路径）。 */
    private suspend fun closeSyncIteratorQuietly(interpreter: Interpreter, sync: IteratorRecord) {
        try {
            interpreter.iteratorClose(sync, throwCompletion = true)
        } catch (_: JSException) {
            // 关闭失败不能覆盖原始拒绝原因
        }
    }

    /** CreateIterResultObject：{ value, done }（属性顺序与规范一致）。 */
    private fun createIteratorResult(
        interpreter: Interpreter,
        value: JSValue,
        done: Boolean,
    ): JSObject {
        val result = JSObject(prototype = interpreter.intrinsics.objectPrototype)
        result.defineDataProperty(
            key = PropertyKey.Str("value"),
            value = value,
            writable = true,
            enumerable = true,
            configurable = true,
        )
        result.defineDataProperty(
            key = PropertyKey.Str("done"),
            value = JSBoolean.getJsBoolean(done),
            writable = true,
            enumerable = true,
            configurable = true,
        )
        return result
    }

    /** 是否具有可调用的 @@iterator（Array.from 的数组式回退判断）。 */
    suspend fun isIterable(value: JSValue): Boolean {
        if (value is JSNull || value is JSUndefined) return false
        val objectValue = if (value is JSObject) value else toObject(value)
        return isCallable(getV(objectValue, PropertyKey.Sym(intrinsics.symbolIterator)))
    }

    /**
     * GetMethod(value, @@iterator)：缺失返回 null，非可调用抛 TypeError。
     *
     * Array.from 需要在构造结果对象前只读取一次方法，再在稍后用它创建迭代器，
     * 避免重复触发带副作用的 Symbol.iterator getter。
     */
    suspend fun getIteratorMethod(value: JSValue): JSValue? {
        if (value is JSNull || value is JSUndefined) return null
        val objectValue = if (value is JSObject) value else toObject(value)
        val method = getV(objectValue, PropertyKey.Sym(intrinsics.symbolIterator))
        if (method is JSUndefined || method is JSNull) return null
        if (!isCallable(method)) {
            throw typeError("${describeValue(value)} is not iterable")
        }
        return method
    }

    /** 使用已取得的 @@iterator 方法逐项消费；consumer 抛错时关闭迭代器并保留原异常。 */
    suspend fun consumeIterator(
        value: JSValue,
        iteratorMethod: JSValue,
        consumer: suspend (JSValue) -> Unit,
    ) {
        if (value is JSNull || value is JSUndefined) {
            throw typeError("Cannot convert undefined or null to object")
        }
        val objectValue = if (value is JSObject) value else toObject(value)
        val iteratorValue = callFunction(iteratorMethod, objectValue, emptyList())
        if (iteratorValue !is JSObject) {
            throw typeError("Result of the Symbol.iterator method is not an object")
        }
        val record = IteratorRecord(
            iteratorValue,
            getV(iteratorValue, PropertyKey.Str("next")),
        )
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
            while (true) {
                checkExecution()
                val next = iteratorNext(record) ?: break
                // List 采用 Int 索引；到达容器表示上限时必须明确失败，不能返回部分结果。
                if (results.size == Int.MAX_VALUE) rangeError("Too many iterator values")
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

    /**
     * ++/-- 的数值更新：BigInt 走 BigInt 加一/减一，其余按 ToNumber。
     *
     * @return 新值与规范化后的旧值（后缀表达式返回旧值）
     */
    private suspend fun computeUpdate(oldValue: JSValue, delta: Double): Pair<JSValue, JSValue> =
        when (val numeric = conversions.toNumeric(oldValue)) {
            is JSBigInt -> {
                val newValue = if (delta > 0.0) {
                    numeric.add(JSBigInt.ONE)
                } else {
                    numeric.subtract(JSBigInt.ONE)
                }
                newValue to numeric
            }

            else -> {
                val oldNumber = (numeric as JSNumber).value
                JSNumber.of(oldNumber + delta) to numeric
            }
        }

    private suspend fun evaluateUpdate(expression: UpdateExpr): JSValue {
        val delta = if (expression.operator == "++") 1.0 else -1.0
        return when (val target = expression.target) {
            is Identifier -> {
                val owner = findBindingOwner(target.name)
                val oldValue = readIdentifierReference(owner, target.name)
                val (newValue, numericOldValue) = computeUpdate(oldValue, delta)
                writeIdentifierReference(owner, target.name, newValue)
                if (expression.prefix) newValue else numericOldValue
            }

            is MemberExpr -> {
                if (target.obj is SuperExpr) {
                    // GetThisBinding 先于属性表达式；未初始化 this 直接 ReferenceError
                    if (!currentFrame.thisInitialized) {
                        throw referenceError("Must call super constructor before accessing 'super'")
                    }
                    // GetSuperBase 先于 ToPropertyKey（测试会在 toString 中切换原型）
                    val base = superPropertyBaseOrNull()
                    if (base == null) throw typeError("Cannot read properties of null")
                    val key = if (target.computed) {
                        toPropertyKey(evaluate(target.property))
                    } else {
                        PropertyKey.Str((target.property as Identifier).name)
                    }
                    val oldValue = getV(base, key)
                    val (newValue, numericOldValue) = computeUpdate(oldValue, delta)
                    putSuperProperty(
                        base, currentFrame.thisValue, key, newValue, currentFrame.strict,
                    )
                    return if (expression.prefix) newValue else numericOldValue
                }
                val base = evaluate(target.obj)
                val privateMember = (target.property as? PrivateIdentifier)?.name
                if (privateMember != null) {
                    val name = resolvePrivateName(privateMember)
                    val oldValue = getPrivateMember(base, name)
                    val (newValue, numericOldValue) = computeUpdate(oldValue, delta)
                    setPrivateMember(base, name, newValue)
                    if (expression.prefix) newValue else numericOldValue
                } else {
                    val key = memberKey(target)
                    val oldValue = getV(base, key)
                    val (newValue, numericOldValue) = computeUpdate(oldValue, delta)
                    putV(base, key, newValue, currentFrame.strict)
                    if (expression.prefix) newValue else numericOldValue
                }
            }

            is CallExpr -> {
                // Annex B web-compat：调用求值后抛 ReferenceError
                evaluate(target)
                throw referenceError("Invalid left-hand side in update operation")
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
            // 一元 + 是 ToNumber，BigInt 按规范抛 TypeError
            "+" -> when (operand) {
                is JSBigInt -> throw typeError("Cannot convert a BigInt value to a number")
                is JSNumber -> operand
                else -> JSNumber.of(toNumber(operand))
            }

            // 一元 - / ~ 使用 ToNumeric，包装对象解出 BigInt 后仍按 BigInt 运算
            "-" -> when (val numeric = conversions.toNumeric(operand)) {
                is JSBigInt -> numeric.negate()
                else -> JSNumber.of(-(numeric as JSNumber).value)
            }

            "!" -> JSBoolean.getJsBoolean(!toBoolean(operand))
            "~" -> when (val numeric = conversions.toNumeric(operand)) {
                is JSBigInt -> numeric.not()
                else -> JSNumber.of(toInt32Number((numeric as JSNumber).value).inv().toDouble())
            }

            "void" -> JSUndefined
            // 规范 Await：PromiseResolve(%Promise%, value) 后等待落定，
            // 非 Promise 值先被采纳（thenable 的 then 通过微任务调用）
            "await" -> awaitPromiseValue(operand)
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
                        internalDeleteProperty(owner.bindingObject, PropertyKey.Str(operand.name))
                    )
                    owner is DeclarativeEnvironment ->
                        JSBoolean.getJsBoolean(owner.deleteBinding(operand.name))

                    else -> JSBoolean.FALSE
                }
            }

            is MemberExpr -> {
                // delete super.prop 在运行时抛 ReferenceError
                if (operand.obj is SuperExpr) {
                    throw referenceError("Unsupported reference to 'super'")
                }
                val base = evaluate(operand.obj)
                // 规范：属性引用的 delete 先 ToObject(base)，null/undefined 抛 TypeError
                if (base is JSNull || base is JSUndefined) {
                    typeError("Cannot convert undefined or null to object")
                }
                val baseObject = toObject(base)
                val key = memberKey(operand)
                val deleted = internalDeleteProperty(baseObject, key)
                if (!deleted && currentFrame.strict) {
                    throw typeError("Cannot delete property '${key}'")
                }
                JSBoolean.getJsBoolean(deleted)
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

    private suspend fun evaluateBinary(expression: BinaryOp, tail: Boolean = false): JSValue {
        // 逻辑运算短路；右操作数在尾位置时参与尾调用优化
        if (expression.operator == "&&") {
            val left = evaluate(expression.left)
            return if (!toBoolean(left)) left else evaluate(expression.right, tail)
        }
        if (expression.operator == "||") {
            val left = evaluate(expression.left)
            return if (toBoolean(left)) left else evaluate(expression.right, tail)
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
                    val (leftNumeric, rightNumeric) =
                        numericOperands(leftPrimitive, rightPrimitive)
                    if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                        leftNumeric.add(rightNumeric)
                    } else {
                        JSNumber.of(
                            (leftNumeric as JSNumber).value + (rightNumeric as JSNumber).value
                        )
                    }
                }
            }

            "-" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    leftNumeric.subtract(rightNumeric)
                } else {
                    JSNumber.of(
                        (leftNumeric as JSNumber).value - (rightNumeric as JSNumber).value
                    )
                }
            }

            "*" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    leftNumeric.multiply(rightNumeric)
                } else {
                    JSNumber.of(
                        (leftNumeric as JSNumber).value * (rightNumeric as JSNumber).value
                    )
                }
            }

            "/" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    if (rightNumeric.isZero) throw rangeError("Division by zero")
                    leftNumeric.divide(rightNumeric)
                } else {
                    JSNumber.of(
                        (leftNumeric as JSNumber).value / (rightNumeric as JSNumber).value
                    )
                }
            }

            "%" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    if (rightNumeric.isZero) throw rangeError("Division by zero")
                    leftNumeric.remainder(rightNumeric)
                } else {
                    JSNumber.of(
                        (leftNumeric as JSNumber).value % (rightNumeric as JSNumber).value
                    )
                }
            }

            "**" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    // 负指数按规范抛 RangeError；结果超过实现上限同样抛 RangeError
                    leftNumeric.pow(rightNumeric)
                        ?: throw rangeError("Maximum BigInt size exceeded")
                } else {
                    JSNumber.of(
                        (leftNumeric as JSNumber).value.pow((rightNumeric as JSNumber).value)
                    )
                }
            }

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

            "&", "|", "^" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    when (operator) {
                        "&" -> leftNumeric.and(rightNumeric)
                        "|" -> leftNumeric.or(rightNumeric)
                        else -> leftNumeric.xor(rightNumeric)
                    }
                } else {
                    val leftInt = toInt32Number((leftNumeric as JSNumber).value)
                    val rightInt = toInt32Number((rightNumeric as JSNumber).value)
                    JSNumber.of(
                        when (operator) {
                            "&" -> (leftInt and rightInt).toDouble()
                            "|" -> (leftInt or rightInt).toDouble()
                            else -> (leftInt xor rightInt).toDouble()
                        }
                    )
                }
            }

            "<<" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    val shift = bigIntShiftCount(rightNumeric)
                    leftNumeric.shiftLeft(shift)
                        ?: throw rangeError("Maximum BigInt size exceeded")
                } else {
                    // 规范顺序：先 ToInt32(left)，再 ToUint32(right)
                    val leftInt = toInt32Number((leftNumeric as JSNumber).value)
                    val shift = (toUint32Number((rightNumeric as JSNumber).value) and 31L).toInt()
                    JSNumber.of((leftInt shl shift).toDouble())
                }
            }

            ">>" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
                    val shift = bigIntShiftCount(rightNumeric)
                    leftNumeric.shiftRight(shift)
                        ?: throw rangeError("Maximum BigInt size exceeded")
                } else {
                    val leftInt = toInt32Number((leftNumeric as JSNumber).value)
                    val shift = (toUint32Number((rightNumeric as JSNumber).value) and 31L).toInt()
                    JSNumber.of((leftInt shr shift).toDouble())
                }
            }

            ">>>" -> {
                val (leftNumeric, rightNumeric) = numericOperands(left, right)
                if (leftNumeric is JSBigInt || rightNumeric is JSBigInt) {
                    // BigInt 没有无符号右移
                    throw typeError("BigInts have no unsigned right shift, use >> instead")
                }
                val leftInt = toUint32Number((leftNumeric as JSNumber).value)
                val shift = (toUint32Number((rightNumeric as JSNumber).value) and 31L).toInt()
                JSNumber.of((leftInt shr shift).toDouble())
            }

            "in" -> {
                if (right !is JSObject) {
                    throw typeError("Cannot use 'in' operator to search for '${describeValue(left)}' in ${typeOf(right)}")
                }
                JSBoolean.getJsBoolean(internalHasProperty(right, toPropertyKey(left)))
            }

            "instanceof" -> JSBoolean.getJsBoolean(instanceOf(left, right))

            else -> throw syntaxError("Unsupported binary operator '$operator'")
        }
    }

    /**
     * 二元算术/位运算的 ToNumeric 处理。
     *
     * BigInt 与 Number 混用按规范抛 TypeError（`+` 的字符串拼接分支在此之前处理）。
     */
    private suspend fun numericOperands(
        left: JSValue,
        right: JSValue,
    ): Pair<JSValue, JSValue> {
        val leftNumeric = conversions.toNumeric(left)
        val rightNumeric = conversions.toNumeric(right)
        if ((leftNumeric is JSBigInt) != (rightNumeric is JSBigInt)) {
            throw typeError("Cannot mix BigInt and other types, use explicit conversions")
        }
        return leftNumeric to rightNumeric
    }

    /**
     * 把 BigInt 移位量转换为 Long；超出 Long 的移位量按实现上限处理。
     *
     * 左移超限会抛 RangeError；右移超限按符号收敛到 0n/-1n。
     */
    private fun bigIntShiftCount(value: JSBigInt): Long {
        value.toLongOrNull()?.let { return it }
        // 无法放入 Long 的移位量收敛为一个足够大的值（避免 Long.MIN_VALUE 取负溢出）
        val limit = (JSBigInt.MAX_RESULT_BITS + 1).toLong()
        return if (value.isNegative) -limit else limit
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
        var prototype = internalGetPrototypeOf(value)
        while (prototype != null) {
            if (prototype === prototypeObject) return true
            prototype = internalGetPrototypeOf(prototype)
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

        // BigInt 与 String：规范按 StringToBigInt 转换（不是 ToNumber）；
        // 因此 "0."、"1e0" 等不是合法 BigInt 的字符串视为不可比（返回 null → false）
        if (leftPrimitive is JSBigInt && rightPrimitive is JSString) {
            val rightBigInt = conversions.stringToBigInt(rightPrimitive.value) ?: return null
            return leftPrimitive.compareTo(rightBigInt)
        }
        if (leftPrimitive is JSString && rightPrimitive is JSBigInt) {
            val leftBigInt = conversions.stringToBigInt(leftPrimitive.value) ?: return null
            return leftBigInt.compareTo(rightPrimitive)
        }

        val leftNumeric = conversions.toNumericFromPrimitive(leftPrimitive)
        val rightNumeric = conversions.toNumericFromPrimitive(rightPrimitive)
        // 两侧都是 BigInt：直接数值比较
        if (leftNumeric is JSBigInt && rightNumeric is JSBigInt) {
            return leftNumeric.compareTo(rightNumeric)
        }
        // BigInt 与 Number 混用：按数学值精确比较，NaN 时不可比
        if (leftNumeric is JSBigInt) {
            return JSBigInt.compareToDouble(leftNumeric, (rightNumeric as JSNumber).value)
        }
        if (rightNumeric is JSBigInt) {
            val comparison = JSBigInt.compareToDouble(rightNumeric, (leftNumeric as JSNumber).value)
            return comparison?.let { -it }
        }
        val leftNumber = (leftNumeric as JSNumber).value
        val rightNumber = (rightNumeric as JSNumber).value
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
                if (!internalHasProperty(owner.bindingObject, key) && currentFrame.strict) {
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
                if (!internalHasProperty(owner.bindingObject, key) && currentFrame.strict) {
                    throw referenceError("$name is not defined")
                }
                putV(owner.bindingObject, key, value, currentFrame.strict)
            }

            // 引用在 RHS 求值前已不可解析：即使 RHS 期间创建了同名全局属性，
            // 严格模式仍须抛 ReferenceError（不能重新解析后写入）
            null -> {
                if (currentFrame.strict) throw referenceError("$name is not defined")
                context.globalObject.defineDataProperty(
                    key = PropertyKey.Str(name),
                    value = value,
                    writable = true,
                    enumerable = true,
                    configurable = true,
                )
            }

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
                val privateMember = (target.property as? PrivateIdentifier)?.name
                if (privateMember != null) {
                    setPrivateMember(base, resolvePrivateName(privateMember), value)
                } else {
                    // 计算键：规范先 ToObject(base) 再 ToPropertyKey(key)
                    if (target.computed && (base is JSNull || base is JSUndefined)) {
                        typeError(
                            "Cannot set properties of " +
                                if (base is JSNull) "null" else "undefined"
                        )
                    }
                    putV(base, memberKey(target), value, currentFrame.strict)
                }
            }

            is ArrayLiteral, is ObjectLiteral ->
                bindPattern(target, value, kind = null, environment = null)

            is CallExpr -> {
                evaluate(target)
                throw referenceError("Invalid left-hand side in for-in/of")
            }

            else -> throw syntaxError("Invalid assignment target")
        }
    }

    /** 收集对象自身与原型链上的可枚举字符串键（for-in）。 */
    private suspend fun enumerableStringKeysIncludingPrototype(objectValue: JSObject): List<String> {
        val keys = LinkedHashSet<String>()
        // 非枚举的自有属性同样会遮蔽原型上的同名属性，因此单独记录已访问键
        val visited = HashSet<String>()
        var current: JSObject? = objectValue
        while (current != null) {
            for (key in internalOwnPropertyKeys(current)) {
                if (key !is PropertyKey.Str) continue
                if (!visited.add(key.value)) continue
                if (internalGetOwnProperty(current, key)?.enumerable == true) {
                    keys.add(key.value)
                }
            }
            current = internalGetPrototypeOf(current)
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

    fun createRegExp(
        pattern: String,
        flags: String,
        prototype: JSObject = intrinsics.regexpPrototype,
    ): JSValue {
        // 命名捕获组：校验组名/反向引用，并得到规范化后的引擎模式
        if ('u' in flags && 'v' in flags) {
            throw syntaxError("Invalid regular expression flags")
        }
        val unicodeMode = 'u' in flags || 'v' in flags
        val namedAnalysis = try {
            RegexNamedGroups.analyze(pattern, unicodeMode)
        } catch (error: RegexSyntaxError) {
            throw syntaxError(
                error.message ?: "Invalid regular expression: /$pattern/$flags"
            )
        }
        JsRegexSyntax.patternError(pattern)?.let { throw syntaxError(it) }
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
                // v 标志启用扩展字符类，匹配语义由回退引擎实现
                'v' -> Unit
                // d 标志只影响匹配结果（indices 数组），不影响匹配语义
                'd' -> Unit
                else -> throw syntaxError("Invalid regular expression flags")
            }
        }
        val enginePattern = namedAnalysis.enginePattern
        // 回退引擎支持前向命名引用与任意 Unicode 组名；Java 仅接受 ASCII 组名
        val namedGroupNeedsFallback = namedAnalysis.hasForwardReference ||
            namedAnalysis.names.keys.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9]*")) }
        fun buildFallback(): JsRegex = try {
            JsRegexEngine(
                enginePattern,
                flags,
                namedAnalysis.nameCandidates,
                maxSteps = context.limits.maxRegexSteps,
            )
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
        val matcher = if ('d' in flags || 'v' in flags || namedAnalysis.hasNamedGroups ||
            namedAnalysis.hasModifiers || namedGroupNeedsFallback ||
            hasLookbehind(pattern) || hasNumericBackReference(pattern) ||
            ('u' in flags && hasUnicodePropertyEscape(pattern)) ||
            hasWhitespaceEscape(pattern) || hasSurrogatePattern(pattern) ||
            hasClassEscapeRange(pattern) ||
            ('i' in flags && needsCaseFoldingFallback(pattern)) ||
            ('m' in flags && hasCaretAnchor(pattern)) ||
            hasLiteralBrace(enginePattern) ||
            hasDotOutsideClass(enginePattern) || requiresJsRegexEngine(pattern) ||
            (context.limits.maxRegexSteps?.let { it < EngineLimits.DEFAULT_MAX_REGEX_STEPS } == true &&
                hasNestedQuantifier(pattern))
        ) {
            buildFallback()
        } else {
            val kotlinRegex = try {
                Regex(if ('s' in flags) expandDotAll(enginePattern) else enginePattern, options)
            } catch (_: Throwable) {
                null
            }
            if (kotlinRegex != null) {
                KotlinJsRegex(kotlinRegex, namedAnalysis.nameCandidates)
            } else {
                // 非 u/v 模式下 JS 允许 Annex B IdentityEscape（如 \x、\u 缺位）等
                // Kotlin 不支持的构造；语法已由 JsRegexSyntax 按 JS 规则校验，
                // 因此可直接交给回退引擎。u/v 模式仍要求已知 JS-only 构造才回退，
                // 避免错误接受非法正则。
                val unicodeMode = 'u' in flags || 'v' in flags
                if (unicodeMode && !hasLookaheadBackReference(pattern) &&
                    !requiresJsRegexEngine(pattern)
                ) {
                    throw syntaxError("Invalid regular expression: /$pattern/$flags")
                }
                buildFallback()
            }
        }
        return JSRegExp(
            pattern = pattern,
            flags = flags,
            matcher = matcher,
            prototype = prototype,
        )
    }

    /** 是否存在数字反向引用（Java 对前向/自引用语义与 JS 不同，统一由回退引擎处理）。 */
    private fun hasNumericBackReference(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> {
                    val next = pattern.getOrNull(index + 1)
                    if (!inClass && next != null && next in '1'..'9') return true
                    index += 2
                }

                '[' -> {
                    inClass = true
                    index++
                }

                ']' -> {
                    inClass = false
                    index++
                }

                else -> index++
            }
        }
        return false
    }

    /** 字符类外未转义的 `$`：Java 非 m 模式会在末尾换行前匹配，与 JS「仅输入末尾」不同。 */
    private fun hasDollarAnchor(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                '[' -> {
                    inClass = true
                    index++
                }

                ']' -> {
                    inClass = false
                    index++
                }

                '$' -> if (!inClass) return true else index++
                else -> index++
            }
        }
        return false
    }

    /** 字符类外的 `\b`/`\B`：Java 的单词边界把非 ASCII 字母视为单词字符，与 JS 不同。 */
    private fun hasWordBoundary(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> {
                    val next = pattern.getOrNull(index + 1)
                    if (!inClass && (next == 'b' || next == 'B')) return true
                    index += 2
                }

                '[' -> {
                    inClass = true
                    index++
                }

                ']' -> {
                    inClass = false
                    index++
                }

                else -> index++
            }
        }
        return false
    }

    /** 非 v 模式字符类中的 `&&` 是字面量，Java 会解释为集合交集。 */
    private fun hasClassIntersection(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                '[' -> {
                    inClass = true
                    index++
                }

                ']' -> {
                    inClass = false
                    index++
                }

                '&' -> {
                    if (inClass && pattern.getOrNull(index + 1) == '&') return true
                    index++
                }

                else -> index++
            }
        }
        return false
    }

    /** 字符类外未转义的 `^`：Java MULTILINE 的行终止符集合与 JS 不同（含 NEL、CRLF 整体处理）。 */
    private fun hasCaretAnchor(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                '[' -> {
                    inClass = true
                    index++
                }

                ']' -> {
                    inClass = false
                    index++
                }

                '^' -> if (!inClass) return true else index++
                else -> index++
            }
        }
        return false
    }

    /** 是否包含后向断言（变长语义由回退引擎实现）。 */
    private fun hasLookbehind(pattern: String): Boolean =
        pattern.contains("(?<=") || pattern.contains("(?<!")

    /**
     * 是否存在非量词的字面量花括号。
     *
     * JS 允许 `a{b}`、`}` 等作为字面量，但 Java 正则会把它们视为非法重复，
     * 因此这类模式统一交给回退引擎，保证跨平台一致。
     */
    private fun hasLiteralBrace(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> index += 2
                c == '[' -> {
                    inClass = true
                    index++
                }

                c == ']' -> {
                    inClass = false
                    index++
                }

                inClass -> index++
                c == '{' -> {
                    val end = validBraceQuantifierEnd(pattern, index) ?: return true
                    index = end
                }

                c == '}' -> return true
                else -> index++
            }
        }
        return false
    }

    /**
     * 忽略大小写时是否需要回退到纯 Kotlin 引擎。
     *
     * Kotlin/Java 的 UNICODE_CASE 使用简单大小写映射，与规范的 Canonicalize
     * （非 u：单码点大写并保留非 ASCII→ASCII 字符；u：CaseFolding 简单折叠）
     * 在 U+0130、U+212A、U+017F、ß/ẞ 等字符上不同；含字母的模式统一走回退引擎。
     */
    private fun needsCaseFoldingFallback(pattern: String): Boolean =
        pattern.any { it.isLetter() } || hasNonAsciiCasePattern(pattern)

    /** 是否包含需要按非 u 规则做大小写折叠的非 ASCII 字符/转义。 */
    private fun hasNonAsciiCasePattern(pattern: String): Boolean {
        var index = 0
        while (index < pattern.length) {
            val c = pattern[index]
            if (c == '\\') {
                val next = pattern.getOrNull(index + 1)
                if (next == 'u') {
                    if (pattern.getOrNull(index + 2) == '{') {
                        val close = pattern.indexOf('}', index + 3)
                        if (close < 0) return false
                        val codePoint = pattern.substring(index + 3, close).toIntOrNull(16)
                        if (codePoint != null && codePoint > 127) return true
                        index = close + 1
                        continue
                    }
                    if (index + 5 < pattern.length) {
                        val codePoint = pattern.substring(index + 2, index + 6).toIntOrNull(16)
                        if (codePoint != null && codePoint > 127) return true
                        index += 6
                        continue
                    }
                }
                index += 2
                continue
            }
            if (c.code > 127) return true
            index++
        }
        return false
    }

    /**
     * 字符类中是否存在以类转义（\d/\w/\s 等）为端点的范围。
     *
     * 非 u 模式按 Annex B 把 `-` 当作字面量，而 Java 会拒绝此类模式。
     */
    private fun hasClassEscapeRange(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                '[' -> {
                    inClass = true
                    index++
                }

                ']' -> {
                    inClass = false
                    index++
                }

                '-' -> {
                    if (inClass) {
                        val previousEscape = index >= 2 && pattern[index - 2] == '\\' &&
                            pattern[index - 1] in "dDwWsS"
                        val nextEscape = index + 2 < pattern.length &&
                            pattern[index + 1] == '\\' && pattern[index + 2] in "dDwWsS"
                        if (previousEscape || nextEscape) return true
                    }
                    index++
                }

                else -> index++
            }
        }
        return false
    }

    /**
     * 是否包含孤立代理项或 `\uXXXX` 代理项转义。
     *
     * Java Regex 按码点迭代，无法复现 JS 的码元语义；此类模式统一回退。
     */
    private fun hasSurrogatePattern(pattern: String): Boolean {
        var index = 0
        while (index < pattern.length) {
            val c = pattern[index]
            if (c == '\\') {
                val next = pattern.getOrNull(index + 1)
                if (next == 'u' && pattern.getOrNull(index + 2) != '{' &&
                    index + 5 < pattern.length
                ) {
                    val value = pattern.substring(index + 2, index + 6).toIntOrNull(16)
                    if (value != null && value in 0xD800..0xDFFF) return true
                }
                index += 2
                continue
            }
            if (c.isHighSurrogate()) {
                if (index + 1 < pattern.length && pattern[index + 1].isLowSurrogate()) {
                    index += 2
                    continue
                }
                return true
            }
            if (c.isLowSurrogate()) {
                if (index > 0 && pattern[index - 1].isHighSurrogate()) {
                    index++
                    continue
                }
                return true
            }
            index++
        }
        return false
    }

    /**
     * 是否包含 `\s` / `\S`。
     *
     * Java 的 `\s` 仅覆盖 ASCII 空白，与 JS 的 WhiteSpace/LineTerminator 集合不同，
     * 统一交由回退引擎保证跨平台一致。
     */
    private fun hasWhitespaceEscape(pattern: String): Boolean {
        var index = 0
        while (index < pattern.length) {
            if (pattern[index] == '\\') {
                val next = pattern.getOrNull(index + 1)
                if (next == 's' || next == 'S') return true
                index += 2
            } else {
                index++
            }
        }
        return false
    }

    /** 是否包含 Unicode 属性转义 `\p{...}` / `\P{...}`（各平台属性数据不一致，统一回退）。 */
    private fun hasUnicodePropertyEscape(pattern: String): Boolean {
        var index = 0
        while (index < pattern.length) {
            if (pattern[index] == '\\') {
                val next = pattern.getOrNull(index + 1)
                if ((next == 'p' || next == 'P') && pattern.getOrNull(index + 2) == '{') {
                    return true
                }
                index += 2
            } else {
                index++
            }
        }
        return false
    }

    /** 是否存在字符类之外的 `.`（Kotlin/Java 引擎按码点匹配，与 JS 码元语义不同）。 */
    private fun hasDotOutsideClass(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                '[' -> {
                    inClass = true
                    index++
                }

                ']' -> {
                    inClass = false
                    index++
                }

                '.' -> if (inClass) index++ else return true
                else -> index++
            }
        }
        return false
    }

    /**
     * Annex B 旧式八进制数字转义：数字超过捕获组数量时按八进制字符解释。
     *
     * Kotlin/Java 会将其当作反向引用或报错，需要回退到 JsRegexEngine。
     */
    private fun hasLegacyOctalEscape(pattern: String): Boolean {
        // 统计捕获组数量（跳过转义与字符类；命名组也算捕获组）
        var groupCount = 0
        var scan = 0
        var scanInClass = false
        while (scan < pattern.length) {
            val c = pattern[scan]
            when {
                c == '\\' -> scan++
                scanInClass -> if (c == ']') scanInClass = false
                c == '[' -> scanInClass = true
                c == '(' -> {
                    val named = scan + 2 < pattern.length && pattern[scan + 1] == '?' &&
                        pattern[scan + 2] == '<'
                    if (scan + 1 >= pattern.length || pattern[scan + 1] != '?' || named) {
                        groupCount++
                    }
                }
            }
            scan++
        }

        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(index + 1) ?: return false
                    if (next in '0'..'9') {
                        if (inClass) return true
                        var number = next - '0'
                        var cursor = index + 2
                        while (cursor < pattern.length && pattern[cursor] in '0'..'9') {
                            number = number * 10 + (pattern[cursor] - '0')
                            cursor++
                        }
                        if (number > groupCount) return true
                        index = cursor
                        continue
                    }
                    index += 2
                }

                inClass -> {
                    if (c == ']') inClass = false
                    index++
                }

                c == '[' -> {
                    inClass = true
                    index++
                }

                else -> index++
            }
        }
        return false
    }

    /** 存在超过 Int 上限的 `{m,n}` 量词：Java 编译失败，需要回退引擎按钳制值处理。 */
    private fun hasHugeBraceQuantifier(pattern: String): Boolean {
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> index += 2
                inClass -> {
                    if (c == ']') inClass = false
                    index++
                }

                c == '[' -> {
                    inClass = true
                    index++
                }

                c == '{' -> {
                    val close = pattern.indexOf('}', index + 1)
                    if (close < 0) {
                        index++
                        continue
                    }
                    val body = pattern.substring(index + 1, close)
                    if (body.isNotEmpty() &&
                        body.all { it in '0'..'9' || it == ',' } &&
                        body.count { it == ',' } <= 1
                    ) {
                        val parts = body.split(',', limit = 2)
                        if (parts.any { part ->
                                part.length >= 10 &&
                                    (part.toLongOrNull() == null ||
                                        part.toLong() > Int.MAX_VALUE.toLong())
                            }
                        ) {
                            return true
                        }
                    }
                    index = close + 1
                }

                else -> index++
            }
        }
        return false
    }

    /** lookahead 中包含数字反向引用的模式（Java 对反向引用语义与 JS 不同）。 */
    private fun hasLookaheadBackReference(pattern: String): Boolean {
        if (!pattern.contains("(?=") && !pattern.contains("(?!")) return false
        return Regex("""\\[1-9]""").containsMatchIn(pattern)
    }

    /** 正则组复杂度：用于把常见灾难性回溯模式路由到有步数预算的回退引擎。 */
    private class RegexGroupComplexity(
        var hasUnboundedQuantifier: Boolean = false,
    )

    /**
     * 是否包含“组内已有无界量词，又被外层无界量词重复”的高风险结构。
     *
     * 这是保守启发式，不替代完整正则分析：命中时统一走 [JsRegexEngine]，
     * 从而让 `maxRegexSteps` 覆盖常见 `(a+)+`、`(a*)*`、`(a+){2,}` 等模式；
     * 普通 `?`、精确 `{n}` 量词与未命中的模式仍走 Kotlin Regex 快路径。
     */
    private fun hasNestedQuantifier(pattern: String): Boolean {
        val groups = mutableListOf<RegexGroupComplexity>()
        var inClass = false
        var escaped = false
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            if (escaped) {
                escaped = false
                index++
                continue
            }
            if (character == '\\') {
                escaped = true
                index++
                continue
            }
            if (inClass) {
                if (character == ']') inClass = false
                index++
                continue
            }
            when (character) {
                '[' -> inClass = true
                '(' -> groups.add(RegexGroupComplexity())
                ')' -> {
                    val group = groups.removeLastOrNull() ?: RegexGroupComplexity()
                    val nextIndex = index + 1
                    if (group.hasUnboundedQuantifier &&
                        isUnboundedQuantifierAt(pattern, nextIndex)
                    ) {
                        return true
                    }
                    if (group.hasUnboundedQuantifier) {
                        groups.lastOrNull()?.hasUnboundedQuantifier = true
                    }
                }

                '*', '+' -> groups.lastOrNull()?.hasUnboundedQuantifier = true
                '?' -> Unit
                '{' -> {
                    val close = pattern.indexOf('}', index + 1)
                    if (close > index + 1 &&
                        isUnboundedBraceQuantifier(pattern.substring(index + 1, close))
                    ) {
                        groups.lastOrNull()?.hasUnboundedQuantifier = true
                        index = close
                    }
                }
            }
            index++
        }
        return false
    }

    /** 从指定位置开始是否为无界量词（`*`、`+`、`{m,}`）。 */
    private fun isUnboundedQuantifierAt(pattern: String, index: Int): Boolean {
        val character = pattern.getOrNull(index) ?: return false
        if (character == '*' || character == '+') return true
        if (character != '{') return false
        val close = pattern.indexOf('}', index + 1)
        if (close <= index + 1) return false
        return isUnboundedBraceQuantifier(pattern.substring(index + 1, close))
    }

    /** `{m,}` 表示无上界；`{m}` 与 `{m,n}` 不在此列。 */
    private fun isUnboundedBraceQuantifier(body: String): Boolean {
        val comma = body.indexOf(',')
        if (comma < 0) return false
        if (body.substring(comma + 1).isNotEmpty()) return false
        return body.substring(0, comma).isNotEmpty() &&
            body.substring(0, comma).all { it.isDigit() }
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
        if (hasLegacyOctalEscape(pattern)) return true
        if (hasHugeBraceQuantifier(pattern)) return true
        if (hasClassIntersection(pattern)) return true
        if (hasWordBoundary(pattern)) return true
        if (hasDollarAnchor(pattern)) return true
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
        is JSFunction -> !value.isArrow && !value.isAsync && !value.isGenerator && !value.isMethod
        is JSNativeFunction -> value.functionPrototypeObject != null || value.constructFunction != null
        is JSProxy -> isConstructor(value.target)
        else -> false
    }

    /** 错误值的可读描述（用于 "xxx is not a function" 之类消息）。 */
    private fun describeValue(value: JSValue): String = try {
        value.toPrimitiveString()
    } catch (_: Throwable) {
        typeOf(value)
    }

    /**
     * 在修改调用帧之前检查深度；超限是可被 JS 捕获的 RangeError。
     *
     * 尾调用在旧帧 finally 出栈后才压入新帧，因此不会累计深度。全局脚本帧
     * 只用于错误栈展示；普通调用、构造器、访问器、原生函数和 eval 均参与限制。
     */
    private suspend fun pushStackFrame(
        name: String,
        range: SourceRange? = null,
        countsTowardsDepth: Boolean = true,
        sourceInfo: SourceInfo = currentSourceInfo,
    ) {
        if (countsTowardsDepth) {
            val maxDepth = context.limits.maxCallDepth
            if (maxDepth != null && callDepth >= maxDepth - inheritedCallDepth) {
                rangeError("Maximum call stack size exceeded")
            }
            // 每 8 层把 Kotlin 调用栈退回内部恢复循环；逻辑帧仍保留，JS 看不到
            // 此挂起，也不会在此运行 Promise 微任务。小分段兼顾 Native 的栈容量。
            if ((inheritedCallDepth + callDepth) > 0 && (inheritedCallDepth + callDepth) % 8 == 0) {
                yieldExecutionStack()
            }
            callDepth++
        }
        val parent = snapshotStackLink()
        val entryRange = range?.takeIf { it.start.line > 0 }
            ?: currentRange?.takeIf { sourceInfo == currentSourceInfo }
        stackHead = StackFrameLink(StackFrame(name, entryRange, sourceInfo), parent)
        currentRange = entryRange
    }

    private fun popStackFrame(countsTowardsDepth: Boolean = true) {
        if (stackHead != null) {
            stackHead = stackHead?.parent
            currentRange = stackHead?.frame?.range
            if (countsTowardsDepth) callDepth--
        }
    }

    /** 冻结当前执行位置；祖先帧不可变，async 可以安全共享而无需复制整条栈。 */
    private fun snapshotStackLink(rangeOverride: SourceRange? = null): StackFrameLink? {
        val head = stackHead ?: return null
        val range = rangeOverride?.takeIf { it.start.line > 0 } ?: currentRange
        return if (head.frame.range == range) head else StackFrameLink(head.frame.copy(range = range), head.parent)
    }

    /** 仅在捕获错误时展开帧链，按外层到内层保存；迭代遍历避免万层递归占用宿主栈。 */
    internal fun captureStack(skipTopFrame: Boolean = false): List<StackFrame> {
        var link = snapshotStackLink()
        if (skipTopFrame) link = link?.parent
        val frames = mutableListOf<StackFrame>()
        while (link != null) {
            frames.add(link.frame)
            link = link.parent
        }
        frames.reverse()
        return frames
    }

    /** Error 保存首次创建或抛出的位置；构造器调用时跳过其自身的原生帧。 */
    internal fun captureErrorStack(value: JSValue, skipTopFrame: Boolean = false) {
        if (value is JSObject && value.isErrorData && value.errorStack == null) {
            val frames = captureStack(skipTopFrame)
            if (frames.isNotEmpty()) value.errorStack = frames
        }
    }

    /** 在退栈前补齐环境/宿主异常；取消、配额中止、尾调用与生成器 return 信号不属于 JS 错误。 */
    private fun captureFailure(error: Throwable) {
        when (error) {
            is CancellationException, is ExecutionLimitException, is TailCallSignal,
            is GeneratorReturn -> Unit
            is JSException -> {
                // 已有快照时不再次遍历，深递归退栈保持线性复杂度。
                if (error.stack.isEmpty()) error.attachStackIfAbsent(captureStack())
                normalizeErrorValue(error.value)
            }
            else -> if (hostFailure !== error) {
                // finally 可能用新的宿主异常替换旧异常；两者不能共用旧错误的位置。
                hostFailure = error
                hostFailureStack = captureStack()
            }
        }
    }

    /** 子表达式完成后恢复父位置，错误则在恢复之前固定到最内层的有效源码范围。 */
    private suspend inline fun <T> withSourceRange(range: SourceRange, block: () -> T): T {
        val previousRange = currentRange
        if (range.start.line > 0) currentRange = range
        try {
            return block()
        } catch (error: Throwable) {
            captureFailure(error)
            throw error
        } finally {
            currentRange = previousRange
        }
    }

    /** 为内部构造的 JSError 补齐内建错误原型，保证 catch 后 constructor 身份正确。 */
    fun normalizeErrorValue(value: JSValue): JSValue {
        captureErrorStack(value)
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
        stack = captureStack(),
    )

    /** 抛出 ReferenceError。 */
    fun referenceError(message: String): Nothing = throw JSException(
        value = JSError("ReferenceError", message, intrinsics.referenceErrorPrototype),
        stack = captureStack(),
    )

    /** 抛出 SyntaxError。 */
    fun syntaxError(message: String): Nothing = throw JSException(
        value = JSError("SyntaxError", message, intrinsics.syntaxErrorPrototype),
        stack = captureStack(),
    )

    /** 抛出 RangeError。 */
    fun rangeError(message: String): Nothing = throw JSException(
        value = JSError("RangeError", message, intrinsics.rangeErrorPrototype),
        stack = captureStack(),
    )
}
