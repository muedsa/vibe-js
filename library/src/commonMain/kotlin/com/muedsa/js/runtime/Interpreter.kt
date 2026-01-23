package com.muedsa.js.runtime

import com.muedsa.js.ast.*
import com.muedsa.js.lexer.TokenType
import com.muedsa.js.runtime.exception.*
import com.muedsa.js.runtime.value.*
import kotlin.math.pow

class Interpreter {
    private val globalEnv = Environment(parent = null, isVarScope = true)
    private var currentEnv = globalEnv
    private val callStack = mutableListOf<StackFrame>()

    init {
        globalEnv.define("Object", ObjectConstructor, VariableKind.CONST)
        globalEnv.define("Number", NumberConstructor, VariableKind.CONST)
        globalEnv.define("Boolean", BooleanConstructor, VariableKind.CONST)
        globalEnv.define("String", StringConstructor, VariableKind.CONST)
        globalEnv.define("Array", ArrayConstructor, VariableKind.CONST)
        globalEnv.define("Error", ErrorConstructor, VariableKind.CONST)
        
        globalEnv.define("undefined", JSUndefined, VariableKind.CONST)
        globalEnv.define("NaN", JSNumber(Double.NaN), VariableKind.CONST)
        globalEnv.define("Infinity", JSNumber(Double.POSITIVE_INFINITY), VariableKind.CONST)
    }

    fun interpret(program: BlockStatement): JSValue {
        return executeBlock(program.statements, currentEnv)
    }

    /**
     * 从当前环境获取变量值（推荐使用）
     * @param name 变量名
     * @return JS值，如果不存在则返回 JSUndefined
     */
    fun getValue(name: String): JSValue {
        return currentEnv.getSafe(name)
    }

    /**
     * 从当前环境获取所有可见的变量（包括作用域链中的变量）
     * @return 所有可见变量的映射
     */
    fun getAllValues(): Map<String, JSValue> {
        return currentEnv.getAllVariables()
    }

    /**
     * 从当前环境仅获取本地变量（不包括父作用域）
     * @return 本地变量的映射
     */
    fun getLocalValues(): Map<String, JSValue> {
        return currentEnv.getLocalVariables()
    }

    /**
     * 从全局环境获取变量值
     * @param name 变量名
     * @return JS值
     */
    fun getGlobalValue(name: String): JSValue {
        return globalEnv.get(name)
    }

    /**
     * 获取全局环境
     */
    fun getGlobalEnv(): Environment {
        return globalEnv
    }

    /**
     * 获取当前环境
     */
    fun getCurrentEnv(): Environment {
        return currentEnv
    }

    fun getCallStack(): List<StackFrame> {
        return callStack.toList()
    }

    private fun pushStackFrame(name: String) {
        callStack.add(StackFrame(name))
    }

    private fun popStackFrame() {
        if (callStack.isNotEmpty()) {
            callStack.removeAt(callStack.size - 1)
        }
    }

    private fun executeStatement(statement: Statement): JSValue {
        return when (statement) {
            is ExpressionStatement -> evaluate(statement.expression)
            is VarDeclaration -> {
                val value = statement.initializer?.let { evaluate(it) } ?: JSUndefined
                // 将 AST 的字符串类型转换为 Enum
                val kind = when (statement.kind) {
                    "const" -> VariableKind.CONST
                    "let" -> VariableKind.LET
                    else -> VariableKind.VAR
                }
                // 检查 const 必须初始化
                if (kind == VariableKind.CONST && statement.initializer == null) {
                    throw JSException(JSError("SyntaxError", "Missing initializer in const declaration"))
                }

                if (kind == VariableKind.VAR) {
                    // var 已经在提升阶段定义过了，这里只需赋值
                    // 使用 assign 确保遵循作用域链找到之前提升的那个位置
                    currentEnv.assign(statement.name, value)
                } else {
                    // let 和 const 依然在执行到这一行时才定义
                    currentEnv.define(statement.name, value, kind)
                }
                JSUndefined
            }

            is FunctionDeclaration -> {
                val function = JSFunction(
                    statement.name,
                    statement.params,
                    statement.body,
                    currentEnv,
                )
                currentEnv.define(statement.name, function, VariableKind.VAR)
                function
            }

            is BlockStatement -> executeBlock(
                statement.statements,
                Environment(parent = currentEnv, isVarScope = false)
            )

            is IfStatement -> {
                if (getPrimitiveBoolean(evaluate(statement.condition))) {
                    executeStatement(statement.consequent)
                } else if (statement.alternate != null) {
                    executeStatement(statement.alternate)
                }
                JSUndefined
            }

            is WhileStatement -> {
                while (getPrimitiveBoolean(evaluate(statement.condition))) {
                    try {
                        executeStatement(statement.body)
                    } catch (_: BreakException) {
                        break
                    } catch (_: ContinueException) {
                        continue
                    }
                }
                JSUndefined
            }

            is ForStatement -> {
                val forEnv = Environment(currentEnv)
                val prevEnv = currentEnv
                currentEnv = forEnv

                try {
                    statement.init?.let { executeStatement(it) }

                    // 识别是否是 let/const 声明的循环变量
                    val loopVars = mutableListOf<String>()
                    val initStmt = statement.init
                    if (initStmt is VarDeclaration && initStmt.kind != "var") {
                        loopVars.add(initStmt.name)
                    }

                    while (statement.condition?.let { getPrimitiveBoolean(evaluate(it)) } != false) {
                        // 如果有 let 变量，创建迭代作用域
                        val iterationEnv = if (loopVars.isNotEmpty()) {
                            val env = Environment(parent = forEnv)
                            // 将当前 forEnv 中的值复制到新的迭代作用域中
                            for (name in loopVars) {
                                // 使用 LET 类型定义，允许在迭代内修改（如果是 const 其实 for 循环 update 会挂，但 body 内不应修改）
                                // 这里简化处理，确保闭包捕获的是这个 env 里的变量
                                env.define(name, forEnv.get(name), VariableKind.LET)
                            }
                            env
                        } else null

                        if (iterationEnv != null) {
                            currentEnv = iterationEnv
                        }

                        try {
                            executeStatement(statement.body)
                            // 正常执行结束，如果有 let 变量，需要将值同步回 forEnv (以便 update 表达式使用)
                            if (iterationEnv != null) {
                                for (name in loopVars) {
                                    forEnv.assign(name, iterationEnv.get(name))
                                }
                            }
                        } catch (_: BreakException) {
                            break
                        } catch (_: ContinueException) {
                            // continue 时也需要同步回 forEnv
                            if (iterationEnv != null) {
                                for (name in loopVars) {
                                    forEnv.assign(name, iterationEnv.get(name))
                                }
                            }
                            // 继续执行
                        } finally {
                            // 恢复环境用于执行 update
                            if (iterationEnv != null) {
                                currentEnv = forEnv
                            }
                        }
                        
                        statement.update?.let { evaluate(it) }
                    }
                } finally {
                    currentEnv = prevEnv
                }
                JSUndefined
            }

            is SwitchStatement -> {
                val discriminant = evaluate(statement.discriminant)
                var matched = false

                try {
                    for (case in statement.cases) {
                        if (!matched) {
                            matched = if (case.test == null) {
                                // default case
                                true
                            } else {
                                compareStrict(discriminant, evaluate(case.test))
                            }
                        }

                        if (matched) {
                            for (stmt in case.consequent) {
                                executeStatement(stmt)
                            }
                        }
                    }
                } catch (_: BreakException) {
                    // break 跳出 switch
                }
                JSUndefined
            }

            is BreakStatement -> {
                throw BreakException()
            }

            is ContinueStatement -> {
                throw ContinueException()
            }

            /**
             * 处理 throw 语句
             * throw 会抛出 JSException，包含要抛出的值
             */
            is ThrowStatement -> {
                val value = evaluate(statement.argument)
                throw JSException(value, getCallStack())
            }
            /**
             * 处理 try-catch-finally 语句
             * 流程:
             * 1. 首先执行 try 块
             * 2. 如果 try 块抛出异常，捕获并执行 catch 块
             * 3. 最后总是执行 finally 块
             * 4. 如果异常没有被处理，继续抛出
             */
            is TryStatement -> {
                var result: JSValue = JSUndefined
                var exception: JSException? = null

                try {
                    // 执行 try 块
                    val tryEnv = Environment(currentEnv)
                    result = executeBlock(statement.block.statements, tryEnv)
                } catch (e: JSException) {
                    // 捕获 throw 抛出的异常
                    exception = e

                    // 如果有 catch 块，执行它
                    if (statement.handler != null) {
                        val catchEnv = Environment(currentEnv)

                        // 如果 catch 有参数，将异常值绑定到参数
                        if (statement.handler.param != null) {
                            catchEnv.define(statement.handler.param, e.value, VariableKind.VAR)
                        }

                        try {
                            // 执行 catch 块
                            result = executeBlock(statement.handler.body.statements, catchEnv)
                            // 如果 catch 块成功执行，清除异常
                            exception = null
                        } catch (catchException: JSException) {
                            // 如果 catch 块中再次抛出异常，更新异常
                            exception = catchException
                        }
                    }
                } finally {
                    // 无论是否有异常，finally 块总是会执行
                    if (statement.finalizer != null) {
                        val finallyEnv = Environment(currentEnv)
                        executeBlock(statement.finalizer.statements, finallyEnv)
                    }
                }

                // 如果异常没有被处理，继续抛出
                if (exception != null) {
                    throw exception
                }
                result
            }

            is ReturnStatement -> {
                val value = statement.value?.let { evaluate(it) } ?: JSUndefined
                throw ReturnValue(value)
            }
        }
    }

    /**
     * 变量提升预扫描 (Hoisting)
     * * 在进入一个新的函数作用域或全局作用域时，在执行任何具体语句之前调用此方法。
     * 它会遍历 AST 树，找出所有需要“提升”的声明。
     * * 规则：
     * 1. 函数声明 (FunctionDeclaration): 提升名称和函数体定义。
     * 2. var 变量声明: 提升名称并初始化为 undefined。
     * 3. let/const 声明: 不进行提升（它们处于暂时性死区 TDZ，在执行阶段处理）。
     * 4. 穿透性: var 声明会穿透普通的 Block（如 if, for, while），直到遇到函数边界。
     * * @param statements 当前作用域下的语句列表
     * @param env 当前需要注入提升变量的环境（通常是 isVarScope = true 的环境）
     */
    private fun hoistDeclarations(statements: List<Statement>, env: Environment) {
        for (statement in statements) {
            when (statement) {
                // 1. 处理函数声明
                // 例子: function test() {}
                is FunctionDeclaration -> {
                    val function = JSFunction(
                        name = statement.name,
                        params = statement.params,
                        body = statement.body,
                        closure = env // 闭包捕获当前环境
                    )
                    // 函数声明提升优先级高，且直接绑定函数体
                    env.define(statement.name, function, VariableKind.VAR)
                }

                // 2. 处理 var 变量声明
                // 例子: var a = 1;
                is VarDeclaration -> {
                    // 仅处理关键字为 "var" 的声明
                    if (statement.kind == "var") {
                        // 检查是否已经定义过（防止函数名被同名 var 覆盖提升，JS中函数提升优先级更高）
                        // 如果环境里还没这个变量，则初始化为 JSUndefined
                        if (!env.hasLocal(statement.name)) {
                            env.define(statement.name, JSUndefined, VariableKind.VAR)
                        }
                    }
                    // 注意：let 和 const 不在此处处理，它们在 executeStatement 时才进入环境
                }

                // --- 递归扫描（处理 var 的穿透性） ---

                // 3. 处理块语句中的 var
                // 例子: { var x = 10; } -> x 应该提升到块外部的函数作用域
                is BlockStatement -> {
                    hoistDeclarations(statement.statements, env)
                }

                // 4. 处理 If 语句分支
                // 例子: if(true) { var y = 20; }
                is IfStatement -> {
                    // 扫描 then 分支（consequent 通常是一个 BlockStatement 或单条语句）
                    hoistDeclarations(listOf(statement.consequent), env)
                    // 扫描 else 分支
                    statement.alternate?.let { hoistDeclarations(listOf(it), env) }
                }

                // 5. 处理 While/Do-While 循环
                is WhileStatement -> {
                    hoistDeclarations(listOf(statement.body), env)
                }

                // 6. 处理 For 循环
                is ForStatement -> {
                    // for(var i = 0; ... ) 这里的 var i 也需要提升
                    val init = statement.init
                    if (init is VarDeclaration && init.kind == "var") {
                        env.define(init.name, JSUndefined, VariableKind.VAR)
                    }
                    // 扫描循环体
                    hoistDeclarations(listOf(statement.body), env)
                }

                // 7. 处理 Switch 语句
                is SwitchStatement -> {
                    statement.cases.forEach { case ->
                        hoistDeclarations(case.consequent, env)
                    }
                }

                // 8. 处理 Try-Catch
                is TryStatement -> {
                    hoistDeclarations(statement.block.statements, env)
                    statement.handler?.let {
                        hoistDeclarations(it.body.statements, env)
                    }
                    statement.finalizer?.let {
                        hoistDeclarations(it.statements, env)
                    }
                }

                // 其他语句（如 ExpressionStatement, Return, Break 等）不包含声明，跳过
                else -> {}
            }
        }
    }

    private fun executeBlock(statements: List<Statement>, environment: Environment): JSValue {
        // 保存当前环境以便后续恢复
        val prevEnv = currentEnv
        // 切换到新的作用域环境
        currentEnv = environment

        // 如果是函数作用域或全局作用域，执行变量提升扫描
        if (environment.isVarScope) {
            hoistDeclarations(statements, environment)
        }

        // 初始化执行结果
        var result: JSValue = JSUndefined

        try {
            // 依次执行代码块中的每个语句
            for (statement in statements) {
                result = executeStatement(statement)
            }
        } finally {
            // 无论执行是否成功，都要恢复之前的环境
            currentEnv = prevEnv
        }

        return result
    }

    private fun evaluate(expression: Expression): JSValue {
        return when (expression) {
            is NumberLiteral -> JSNumber(expression.value)
            is StringLiteral -> JSString(expression.value)
            is BooleanLiteral -> JSBoolean.getJsBoolean(expression.value)
            is NullLiteral -> JSNull
            is ThisExpr -> currentEnv.getThis()
            is Identifier -> currentEnv.get(expression.name)
            is BinaryOp -> evaluateBinaryOp(expression)
            is UnaryOp -> evaluateUnaryOp(expression)
            is UpdateExpr -> evaluateUpdateExpr(expression)
            is AssignmentExpr -> {
                val value = evaluate(expression.value)
                currentEnv.assign(expression.target, value)
                value
            }

            is CompoundAssignmentExpr -> evaluateCompoundAssignment(expression)
            is MemberAssignmentExpr -> evaluateMemberAssignment(expression)
            is CallExpr -> evaluateFunctionCall(expression)
            is NewExpr -> evaluateNewExpr(expression)
            is ConditionalExpr -> {
                if (getPrimitiveBoolean(evaluate(expression.condition))) {
                    evaluate(expression.thenBranch)
                } else {
                    evaluate(expression.elseBranch)
                }
            }

            is CoalesceExpr -> {
                val left = evaluate(expression.left)
                if (left is JSNull || left is JSUndefined) {
                    evaluate(expression.right)
                } else {
                    left
                }
            }

            is ArrayLiteral -> {
                JSArray(expression.elements.map { evaluate(it) }.toMutableList())
            }

            is FunctionExpression -> {
                JSFunction(
                    name = expression.name ?: "anonymous",
                    params = expression.params,
                    body = expression.body,
                    closure = currentEnv
                )
            }

            is ObjectLiteral -> {
                JSObject(expression.properties.mapValues { (_, value) ->
                    evaluate(value)
                }.toMutableMap())
            }

            is MemberExpr -> evaluateMemberExpr(expression)
        }
    }

    private fun evaluateBinaryOp(expr: BinaryOp): JSValue {
        val left = evaluate(expr.left)

        // Short-circuiting for logical operators
        if (expr.operator == "&&") {
            return if (!getPrimitiveBoolean(left)) left else evaluate(expr.right)
        }
        if (expr.operator == "||") {
            return if (getPrimitiveBoolean(left)) left else evaluate(expr.right)
        }

        val right = evaluate(expr.right)

        return when (expr.operator) {
            "+" -> when {
                left is JSString || right is JSString ->
                    JSString(getPrimitiveString(left) + getPrimitiveString(right))

                else -> JSNumber(getPrimitiveNumber(left) + getPrimitiveNumber(right))
            }

            "-" -> JSNumber(getPrimitiveNumber(left) - getPrimitiveNumber(right))
            "*" -> JSNumber(getPrimitiveNumber(left) * getPrimitiveNumber(right))
            "/" -> JSNumber(getPrimitiveNumber(left) / getPrimitiveNumber(right))
            "%" -> JSNumber(getPrimitiveNumber(left) % getPrimitiveNumber(right))
            "**" -> JSNumber(getPrimitiveNumber(left).pow(getPrimitiveNumber(right)))
            "<" -> JSBoolean.getJsBoolean(getPrimitiveNumber(left) < getPrimitiveNumber(right))
            "<=" -> JSBoolean.getJsBoolean(getPrimitiveNumber(left) <= getPrimitiveNumber(right))
            ">" -> JSBoolean.getJsBoolean(getPrimitiveNumber(left) > getPrimitiveNumber(right))
            ">=" -> JSBoolean.getJsBoolean(getPrimitiveNumber(left) >= getPrimitiveNumber(right))
            "==" -> JSBoolean.getJsBoolean(compareEqual(left, right))
            "!=" -> JSBoolean.getJsBoolean(!compareEqual(left, right))
            "===" -> JSBoolean.getJsBoolean(compareStrict(left, right))
            "!==" -> JSBoolean.getJsBoolean(!compareStrict(left, right))
            "&" -> JSNumber((getPrimitiveNumber(left).toInt() and getPrimitiveNumber(right).toInt()).toDouble())
            "|" -> JSNumber((getPrimitiveNumber(left).toInt() or getPrimitiveNumber(right).toInt()).toDouble())
            "^" -> JSNumber((getPrimitiveNumber(left).toInt() xor getPrimitiveNumber(right).toInt()).toDouble())
            "<<" -> JSNumber((getPrimitiveNumber(left).toInt() shl getPrimitiveNumber(right).toInt()).toDouble())
            ">>" -> JSNumber((getPrimitiveNumber(left).toInt() shr getPrimitiveNumber(right).toInt()).toDouble())
            ">>>" -> JSNumber((getPrimitiveNumber(left).toInt() ushr getPrimitiveNumber(right).toInt()).toDouble())
            else -> JSUndefined
        }
    }

    private fun evaluateUnaryOp(expr: UnaryOp): JSValue {
        val operand = if (expr.operator == "typeof" && expr.operand is Identifier) {
            // Special case for typeof on an identifier: doesn't throw ReferenceError if not defined
            currentEnv.getSafe(expr.operand.name)
        } else {
            evaluate(expr.operand)
        }

        return when (expr.operator) {
            "-" -> JSNumber(-getPrimitiveNumber(operand))
            "+" -> JSNumber(getPrimitiveNumber(operand))
            "!" -> JSBoolean.getJsBoolean(!getPrimitiveBoolean(operand))
            "~" -> JSNumber((getPrimitiveNumber(operand).toInt().inv()).toDouble())
            "typeof" -> JSString(when (operand) {
                is JSNumber -> "number"
                is JSString -> "string"
                is JSBoolean -> "boolean"
                is JSUndefined -> "undefined"
                is JSNull -> "object" // JS legacy
                is JSArray -> "object"
                is JSFunction, is JSNativeFunction -> "function"
                is JSObject -> "object"
            })

            else -> JSUndefined
        }
    }

    private fun evaluateUpdateExpr(expr: UpdateExpr): JSValue {
        val arg = expr.argument

        when (arg) {
            is Identifier -> {
                val currentValue = currentEnv.get(arg.name)
                val currentNum = getPrimitiveNumber(currentValue)
                val newValNum = if (expr.operator == "++") currentNum + 1 else currentNum - 1
                val newValue = JSNumber(newValNum)
                currentEnv.assign(arg.name, newValue)
                return if (expr.prefix) newValue else JSNumber(currentNum)
            }

            is MemberExpr -> {
                val obj = evaluate(arg.obj)
                if (obj is JSUndefined || obj is JSNull) {
                    val typeStr = if (obj is JSUndefined) "undefined" else "null"
                    throw JSException(
                        JSError(
                            "TypeError",
                            "Cannot read property '${expressionToSource(arg.property)}' of $typeStr"
                        )
                    )
                }

                val key = if (arg.computed) {
                    getPrimitiveString(evaluate(arg.property))
                } else {
                    (arg.property as Identifier).name
                }

                val currentValue = if (obj is JSObject) obj.getProperty(key) else JSUndefined
                val currentNum = getPrimitiveNumber(currentValue)
                val newValNum = if (expr.operator == "++") currentNum + 1 else currentNum - 1
                val newValue = JSNumber(newValNum)

                if (obj is JSObject) {
                    obj.setProperty(key, newValue)
                }

                return if (expr.prefix) newValue else JSNumber(currentNum)
            }

            else -> {
                throw JSException(JSError("ReferenceError", "Invalid left-hand side expression in update expression"))
            }
        }
    }

    private fun evaluateCompoundAssignment(expr: CompoundAssignmentExpr): JSValue {
        val currentValue = currentEnv.get(expr.target)
        val rightValue = evaluate(expr.value)

        val result = when (expr.operator) {
            "+=" -> when {
                currentValue is JSString || rightValue is JSString ->
                    JSString(getPrimitiveString(currentValue) + getPrimitiveString(rightValue))

                else -> JSNumber(getPrimitiveNumber(currentValue) + getPrimitiveNumber(rightValue))
            }

            "-=" -> JSNumber(getPrimitiveNumber(currentValue) - getPrimitiveNumber(rightValue))
            "*=" -> JSNumber(getPrimitiveNumber(currentValue) * getPrimitiveNumber(rightValue))
            "/=" -> JSNumber(getPrimitiveNumber(currentValue) / getPrimitiveNumber(rightValue))
            "%=" -> JSNumber(getPrimitiveNumber(currentValue) % getPrimitiveNumber(rightValue))
            else -> JSUndefined
        }

        currentEnv.assign(expr.target, result)
        return result
    }

    private fun evaluateFunctionCall(expr: CallExpr): JSValue {
        // 1. 关键修改: 分离 callee 和 thisValue
        val (callee, thisValue) = if (expr.callee is MemberExpr) {
            // Case A: obj.method() -> this 是 obj
            val obj = evaluate(expr.callee.obj)
            // 注意：这里需要从 obj 中获取属性值作为 callee

            val propName = if (expr.callee.computed) {
                val prop = evaluate(expr.callee.property)
                if (prop is JSUndefined) {
                    throw JSException(
                        JSError(
                            name = "ReferenceError",
                            message = "${expressionToSource(expr.callee.property)} is not defined"
                        )
                    )
                }
                getPrimitiveString(prop)
            } else {
                if (expr.callee.property !is Identifier) {
                    throw JSException(
                        JSError(
                            name = "SyntaxError",
                            message = "Unexpected ${expressionToSource(expr.callee.property)}"
                        )
                    )
                }
                expr.callee.property.name
            }
            val func = if (obj is JSObject) obj.getProperty(propName) else JSUndefined
            Pair(func, obj)
        } else {
            // Case B: func() -> this 是 undefined (或 global)
            Pair(evaluate(expr.callee), JSUndefined)
        }

        val args = expr.arguments.map { evaluate(it) }

        return evaluateFunction(callee, thisValue, args) { expressionToSource(expr) }
    }

    internal fun evaluateFunction(
        callee: JSValue,
        thisValue: JSValue,
        args: List<JSValue>,
        getSource: () -> String = { "" },
    ): JSValue {
        return when (callee) {
            is JSFunction -> {
                pushStackFrame(callee.name)
                try {
                    val callEnv = Environment(parent = callee.closure, isVarScope = true)
                    if (thisValue != JSUndefined) {
                        callEnv.define("this", thisValue, VariableKind.CONST)
                    } else {
                        callEnv.define("this", JSUndefined, VariableKind.CONST)
                    }

                    for (i in callee.params.indices) {
                        // 函数参数被视为 var
                        val value = if (i < args.size) args[i] else JSUndefined
                        callEnv.define(callee.params[i], value, VariableKind.VAR)
                    }

                    val prevEnv = currentEnv
                    currentEnv = callEnv

                    try {
                        executeBlock(callee.body.statements, callEnv)
                        JSUndefined
                    } catch (e: ReturnValue) {
                        e.value
                    } finally {
                        currentEnv = prevEnv
                    }
                } finally {
                    popStackFrame()
                }
            }

            is JSNativeFunction -> {
                pushStackFrame(callee.name)
                try {
                    val returnValue = callee.function(this@Interpreter, thisValue, args)
                    returnValue
                } finally {
                    popStackFrame()
                }
            }

            else -> {
                throw JSException(JSError("TypeError", "${getSource()} is not a function"))
            }
        }
    }

    private fun evaluateNewExpr(expr: NewExpr): JSValue {
        val constructorValue = evaluate(expr.objConstructor)
        val args = expr.arguments.map { evaluate(it) }

        return when (constructorValue) {
            is JSFunction -> {
                // 调试友好的栈帧名称
                pushStackFrame("${constructorValue.name} (constructor)")
                try {
                    // 创建新实例对象
                    val instance = JSObject(mutableMapOf())
                    // 创建函数执行环境
                    // 关键点：isVarScope = true，表示这是一个函数作用域边界
                    // var 声明会提升到这里，而不会穿透到父级
                    val callEnv = Environment(parent = constructorValue.closure, isVarScope = true)
                    // 定义 'this'
                    // 关键点：使用 CONST，确保 'this' 在构造函数执行期间不可被重新赋值
                    callEnv.define("this", instance, VariableKind.CONST)
                    // 绑定参数
                    for (i in constructorValue.params.indices) {
                        val value = if (i < args.size) args[i] else JSUndefined
                        callEnv.define(constructorValue.params[i], value, VariableKind.VAR)
                    }
                    val prevEnv = currentEnv
                    currentEnv = callEnv
                    try {
                        // 执行构造函数体
                        // 注意：这里的 executeBlock 会复用 callEnv，而不是创建新环境
                        // 因为函数体的顶级作用域就是函数作用域本身
                        executeBlock(constructorValue.body.statements, callEnv)
                        // 默认返回新实例
                        instance
                    } catch (e: ReturnValue) {
                        // 处理构造函数的返回值
                        // 规则：如果构造函数显式返回了一个对象，则 new 表达式的结果是该对象
                        // 否则（返回基本类型或无返回），结果是新创建的实例 'this'
                        if (e.value is JSObject || e.value is JSArray || e.value is JSFunction) {
                            e.value
                        } else {
                            instance
                        }
                    } finally {
                        currentEnv = prevEnv
                    }
                } finally {
                    popStackFrame()
                }

            }

            is JSNativeFunction -> {
                // 调试友好的栈帧名称
                pushStackFrame(constructorValue.name)
                try {
                    val returnValue = constructorValue.function(this@Interpreter, JSUndefined, args)
                    returnValue
                } finally {
                    popStackFrame()
                }
            }

            else -> throw RuntimeException("${expr.objConstructor} is not a constructor")
        }
    }

    private fun evaluateMemberAssignment(expr: MemberAssignmentExpr): JSValue {
        val obj = evaluate(expr.obj)
        val value = evaluate(expr.value)

        return if (obj is JSObject) {
            val key: String = if (expr.computed) {
                val keyValue = evaluate(expr.property)
                getPrimitiveString(keyValue)
            } else {
                (expr.property as Identifier).name
            }
            obj.setProperty(key, value)
            value
        } else {
            throw JSException(JSError("", ""))
        }
    }

    private fun evaluateMemberExpr(expr: MemberExpr): JSValue {
        // 获取基础对象
        val obj = evaluate(expr.obj)
        // 针对 undefined 和 null 进行深度报错处理
        if (obj is JSUndefined || obj is JSNull) {
            val typeStr = if (obj is JSUndefined) "undefined" else "null"
            // 获取导致错误的源码片段，例如 "a.b"
            val source = expressionToSource(expr.obj)
            // 获取试图访问的属性名
            val propName = when {
                !expr.computed && expr.property is Identifier -> expr.property.name
                else -> try {
                    getPrimitiveString(evaluate(expr.property))
                } catch (_: Exception) {
                    "property"
                }
            }
            throw JSException(
                JSError(
                    "TypeError",
                    "Cannot read property '$propName' of $typeStr (at '$source')"
                )
            )
        }
        // 成员访问逻辑
        return if (obj is JSObject) {
            val key: String = if (expr.computed) {
                val keyValue = evaluate(expr.property)
                getPrimitiveString(keyValue)
            } else {
                (expr.property as Identifier).name
            }
            obj.getProperty(key)
        } else JSUndefined
    }

    private fun expressionToSource(expr: Expression): String {
        return expr.tokens.joinToString("") {
            if (it.type == TokenType.STRING) {
                "'${it.value}'"
            } else {
                it.value
            }
        }
    }

    /**
     * 实现 JavaScript 的非严格相等比较（==）
     * 规则:
     * 1. 如果类型相同，使用严格相等规则
     * 2. null == undefined (特殊情况)
     * 3. number == string:   将字符串转换为数字比较
     * 4. boolean == 其他:   将布尔值转换为数字比较
     * 5. object == 基本类型:  将对象转换为基本类型比较
     */
    private fun compareEqual(left: JSValue, right: JSValue): Boolean {
        // 规则1: 类型相同则使用严格比较
        if (left::class == right::class) {
            return compareStrict(left, right)
        }

        // 规则2: null == undefined (特殊情况)
        if ((left is JSNull && right is JSUndefined) ||
            (left is JSUndefined && right is JSNull)
        ) {
            return true
        }

        // 规则3: number 和 string 的比较
        if (left is JSNumber && right is JSString) {
            val rightNum = getPrimitiveNumber(right)
            return left.value == rightNum
        }
        if (left is JSString && right is JSNumber) {
            val leftNum = getPrimitiveNumber(left)
            return leftNum == right.value
        }

        // 规则4: boolean 转换为 number
        if (left is JSBoolean) {
            return compareEqual(JSNumber(getPrimitiveNumber(left)), right)
        }
        if (right is JSBoolean) {
            return compareEqual(left, JSNumber(getPrimitiveNumber(right)))
        }

        // 规则5: object 和基本类型的比较
        // 数组或对象转换为原始值
        if ((left is JSObject || left is JSArray) && (right is JSNumber || right is JSString || right is JSBoolean)) {
            return compareEqual(JSString(getPrimitiveString(left)), right)
        }
        if ((right is JSObject || right is JSArray) && (left is JSNumber || left is JSString || left is JSBoolean)) {
            return compareEqual(left, JSString(getPrimitiveString(right)))
        }

        // 其他情况：不相等
        return false
    }

    /**
     * 实现 JavaScript 的严格相等比较（===）
     * 规则:
     * 1. 类型必须完全相同
     * 2. 值必须相等
     * 3. NaN !== NaN
     * 4. +0 === -0
     */
    private fun compareStrict(left: JSValue, right: JSValue): Boolean {
        return when {
            // 类型不同，直接返回 false
            left::class != right::class -> false

            // 数字比较（需要特殊处理 NaN）
            left is JSNumber && right is JSNumber -> {
                // NaN !== NaN
                if (left.value.isNaN() && right.value.isNaN()) {
                    return false
                }
                // 其他数字正常比较
                left.value == right.value
            }

            // 字符串比较
            left is JSString && right is JSString -> left.value == right.value

            // 布尔值比较
            left is JSBoolean && right is JSBoolean -> left.value == right.value

            // null 比较
            left is JSNull && right is JSNull -> true

            // undefined 比较
            left is JSUndefined && right is JSUndefined -> true

            // 数组比较（引用比较）
            left is JSArray && right is JSArray -> left === right

            // 对象比较（引用比较）
            left is JSObject && right is JSObject -> left === right

            // 函数比较（引用比较）
            left is JSFunction && right is JSFunction -> left === right
            left is JSNativeFunction && right is JSNativeFunction -> left === right

            // 其他情况都不相等
            else -> false
        }
    }

    fun getPrimitiveBoolean(value: JSValue): Boolean {
        return value.toPrimitiveBoolean()
    }

    fun getPrimitiveNumber(value: JSValue): Double {
        return value.toPrimitiveNumber()
    }

    fun getPrimitiveString(value: JSValue): String {
        return if (value.isPrimitive) {
            value.toPrimitiveString()
        } else if (value is JSObject) {
            val toStringFun = value.getProperty("toString")
            if (toStringFun is JSFunction || toStringFun is JSNativeFunction) {
                val toStringResult = evaluateFunction(toStringFun, value, listOf())
                if (toStringResult.isPrimitive) {
                    toStringResult.toPrimitiveString()
                } else {
                    throw JSException(JSError("TypeError", "cannot convert object to primitive string"))
                }
            } else {
                throw JSException(JSError("TypeError", "cannot convert object to primitive string"))
            }
        } else throw JSException(JSError("TypeError", "cannot convert object to primitive string"))
    }

}