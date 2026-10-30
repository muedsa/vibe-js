package com.muedsa.js.ast

import com.muedsa.js.lexer.SourceRange

/** 空语句：单独的分号 `;`。 */
class EmptyStatement(
    override val range: SourceRange,
) : Statement()

/** 代码块 `{ ... }`。 */
class BlockStatement(
    val statements: List<Statement>,
    override val range: SourceRange,
) : Statement()

/** 表达式语句，例如 `a = 1;` 或函数调用。 */
class ExpressionStatement(
    val expression: Expression,
    override val range: SourceRange,
) : Statement()

/** 变量声明种类。 */
enum class DeclarationKind {
    VAR,
    LET,
    CONST,
}

/**
 * 函数参数。
 *
 * name 与 pattern 恰有一个非空（rest 参数只支持标识符）。
 *
 * @property name 参数名（简单参数）
 * @property pattern 解构参数模式
 * @property defaultValue 默认值表达式（undefined 时求值）
 * @property rest 是否为剩余参数 `...args`
 */
class Parameter(
    val name: String?,
    val pattern: Expression?,
    val defaultValue: Expression?,
    val rest: Boolean,
    override val range: SourceRange,
) : ASTNode()

/**
 * 单个变量声明项。
 *
 * name 与 pattern 恰有一个非空：
 * - 简单标识符：name 非空；
 * - 解构模式：pattern 非空（ArrayLiteral / ObjectLiteral）。
 */
class VariableDeclarator(
    val name: String?,
    val pattern: Expression?,
    val initializer: Expression?,
    override val range: SourceRange,
) : ASTNode()

/**
 * 变量声明语句，例如 `var a = 1, b;`。
 */
class VarDeclaration(
    val kind: DeclarationKind,
    val declarations: List<VariableDeclarator>,
    override val range: SourceRange,
) : Statement()

/**
 * 函数声明，例如 `function foo(a, b) { ... }`。
 *
 * @property strict 函数体是否为严格模式（函数自身的 "use strict" 指令）
 * @property varDeclarations 函数体内需要提升的 var
 * @property functionDeclarations 函数体内需要提升的函数声明
 */
class FunctionDeclaration(
    val name: String,
    val params: List<Parameter>,
    val body: BlockStatement,
    val strict: Boolean,
    val isAsync: Boolean,
    val varDeclarations: List<String>,
    val functionDeclarations: List<FunctionDeclaration>,
    override val range: SourceRange,
) : Statement()

/** if 语句。 */
class IfStatement(
    val condition: Expression,
    val consequent: Statement,
    val alternate: Statement?,
    override val range: SourceRange,
) : Statement()

/** while 语句。 */
class WhileStatement(
    val condition: Expression,
    val body: Statement,
    override val range: SourceRange,
) : Statement()

/** do-while 语句（阶段 B 启用解析）。 */
class DoWhileStatement(
    val body: Statement,
    val condition: Expression,
    override val range: SourceRange,
) : Statement()

/**
 * for 语句。
 *
 * @property init 初始化语句（变量声明或表达式语句）
 * @property condition 条件表达式；为空表示恒真
 * @property update 更新表达式
 */
class ForStatement(
    val init: Statement?,
    val condition: Expression?,
    val update: Expression?,
    val body: Statement,
    override val range: SourceRange,
) : Statement()

/** switch 语句。 */
class SwitchStatement(
    val discriminant: Expression,
    val cases: List<SwitchCase>,
    override val range: SourceRange,
) : Statement()

/**
 * switch 的一个分支。
 *
 * @property test case 后的表达式；default 分支为 null
 * @property consequent 该分支下的语句列表
 */
class SwitchCase(
    val test: Expression?,
    val consequent: List<Statement>,
    override val range: SourceRange,
) : ASTNode()

/**
 * for-in 语句。
 *
 * 两种形式：
 * - `for (var/let/const name in object)`：declarationKind 非空，variableName 为变量名；
 * - `for (target in object)`：target 为 Identifier 或 MemberExpr。
 */
class ForInStatement(
    val declarationKind: DeclarationKind?,
    val variableName: String?,
    val pattern: Expression?,
    val target: Expression?,
    val right: Expression,
    val body: Statement,
    override val range: SourceRange,
) : Statement()

/**
 * for-of 语句。
 *
 * 与 for-in 类似，但遍历可迭代对象的值。
 */
class ForOfStatement(
    val declarationKind: DeclarationKind?,
    val variableName: String?,
    val pattern: Expression?,
    val target: Expression?,
    val right: Expression,
    val body: Statement,
    override val range: SourceRange,
) : Statement()

/**
 * 标签语句：`label: statement`。
 */
class LabeledStatement(
    val label: String,
    val body: Statement,
    override val range: SourceRange,
) : Statement()

/** debugger 语句（无运行时行为）。 */
class DebuggerStatement(
    override val range: SourceRange,
) : Statement()

/** with 语句（严格模式禁止）。 */
class WithStatement(
    val objectExpression: Expression,
    val body: Statement,
    override val range: SourceRange,
) : Statement()

/** class 成员种类。 */
enum class ClassMemberKind {
    CONSTRUCTOR,
    METHOD,
    GET,
    SET,
}

/** class 成员（方法/访问器/构造器）。 */
class ClassMember(
    val kind: ClassMemberKind,
    val isStatic: Boolean,
    val key: Expression,
    val computed: Boolean,
    val value: FunctionExpression,
    override val range: SourceRange,
) : ASTNode()

/** class 声明。 */
class ClassDeclaration(
    val name: String,
    val superClass: Expression?,
    val members: List<ClassMember>,
    override val range: SourceRange,
) : Statement()

/** break 语句（可选标签）。 */
class BreakStatement(
    val label: String?,
    override val range: SourceRange,
) : Statement()

/** continue 语句（可选标签）。 */
class ContinueStatement(
    val label: String?,
    override val range: SourceRange,
) : Statement()

/** return 语句。 */
class ReturnStatement(
    val value: Expression?,
    override val range: SourceRange,
) : Statement()

/** throw 语句。 */
class ThrowStatement(
    val argument: Expression,
    override val range: SourceRange,
) : Statement()

/** try-catch-finally 语句。 */
class TryStatement(
    val block: BlockStatement,
    val handler: CatchClause?,
    val finalizer: BlockStatement?,
    override val range: SourceRange,
) : Statement()

/**
 * catch 子句。
 *
 * @property param 绑定异常值的变量名；省略 catch 参数时为 null
 */
class CatchClause(
    val param: String?,
    val pattern: Expression?,
    val body: BlockStatement,
    override val range: SourceRange,
) : ASTNode()
