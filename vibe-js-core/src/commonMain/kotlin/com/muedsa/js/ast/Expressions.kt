package com.muedsa.js.ast

import com.muedsa.js.lexer.SourceRange

/** 标识符（变量名）。 */
class Identifier(
    val name: String,
    override val range: SourceRange,
) : Expression()

/** 数字字面量。 */
class NumberLiteral(
    val value: Double,
    override val range: SourceRange,
) : Expression()

/** 正则字面量。 */
class RegExpLiteral(
    val pattern: String,
    val flags: String,
    override val range: SourceRange,
) : Expression()

/**
 * 模板字符串。
 *
 * @property quasis 文本段（长度 = expressions.size + 1）
 * @property expressions `${...}` 中的表达式
 */
class TemplateLiteral(
    val quasis: List<String>,
    val expressions: List<Expression>,
    override val range: SourceRange,
) : Expression()

/** 字符串字面量（已解码）。 */
class StringLiteral(
    val value: String,
    override val range: SourceRange,
) : Expression()

/** 布尔字面量。 */
class BooleanLiteral(
    val value: Boolean,
    override val range: SourceRange,
) : Expression()

/** null 字面量。 */
class NullLiteral(
    override val range: SourceRange,
) : Expression()

/** this 表达式。 */
class ThisExpr(
    override val range: SourceRange,
) : Expression()

/** 展开元素 `...expr`（数组字面量、调用参数、解构 rest）。 */
class SpreadElement(
    val argument: Expression,
    override val range: SourceRange,
) : Expression()

/**
 * 数组字面量。
 *
 * elements 中的 null 表示数组空洞（hole），SpreadElement 表示展开。
 */
class ArrayLiteral(
    val elements: List<Expression?>,
    override val range: SourceRange,
) : Expression()

/** 对象字面量属性的种类。 */
enum class PropertyKind {
    INIT,
    GET,
    SET,
}

/** 对象字面量元素基类。 */
sealed class ObjectLiteralElement : ASTNode()

/**
 * 对象字面量的一个属性。
 *
 * 支持：
 * - `key: value`（kind = INIT）
 * - 简写 `{ a }` / `{ a = 1 }`（后者用于解构默认值）
 * - 方法 `{ f() {} }`（value 为 FunctionExpression）
 * - getter/setter `{ get x() {} }` / `{ set x(v) {} }`
 * - 计算键 `{ [expr]: value }`（computed = true）
 */
class ObjectProperty(
    val key: Expression,
    val kind: PropertyKind,
    val value: Expression,
    val computed: Boolean,
    override val range: SourceRange,
) : ObjectLiteralElement()

/** 对象展开属性 `...expr`。 */
class ObjectSpreadProperty(
    val argument: Expression,
    override val range: SourceRange,
) : ObjectLiteralElement()

/** 对象字面量。属性保持源码顺序。 */
class ObjectLiteral(
    val properties: List<ObjectLiteralElement>,
    override val range: SourceRange,
) : Expression()

/**
 * 函数表达式，例如 `function (a) { ... }`。
 *
 * @property name 可选函数名（命名函数表达式）
 * @property strict 函数体是否为严格模式
 * @property varDeclarations 函数体内需要提升的 var
 * @property functionDeclarations 函数体内需要提升的函数声明
 */
class FunctionExpression(
    val name: String?,
    val params: List<Parameter>,
    val body: BlockStatement,
    val strict: Boolean,
    val isAsync: Boolean,
    val varDeclarations: List<String>,
    val functionDeclarations: List<FunctionDeclaration>,
    override val range: SourceRange,
) : Expression()

/**
 * 箭头函数。
 *
 * body 与 expressionBody 恰有一个非空。
 */
class ArrowFunction(
    val params: List<Parameter>,
    val body: BlockStatement?,
    val expressionBody: Expression?,
    val strict: Boolean,
    val isAsync: Boolean,
    val varDeclarations: List<String>,
    val functionDeclarations: List<FunctionDeclaration>,
    override val range: SourceRange,
) : Expression()

/** 成员访问，例如 `obj.prop` 或 `obj[key]`。 */
class MemberExpr(
    val obj: Expression,
    val property: Expression,
    val computed: Boolean,
    override val range: SourceRange,
) : Expression()

/** 函数调用。 */
class CallExpr(
    val callee: Expression,
    val arguments: List<Expression>,
    override val range: SourceRange,
) : Expression()

/** new 表达式。 */
class NewExpr(
    val constructor: Expression,
    val arguments: List<Expression>,
    override val range: SourceRange,
) : Expression()

/**
 * 赋值表达式（包括复合赋值）。
 *
 * @property target 赋值目标（Identifier 或 MemberExpr）
 * @property operator 赋值运算符，例如 "="、"+="
 */
class AssignmentExpr(
    val target: Expression,
    val operator: String,
    val value: Expression,
    override val range: SourceRange,
) : Expression()

/**
 * 自增/自减表达式。
 *
 * @property target 操作目标（Identifier 或 MemberExpr）
 * @property operator "++" 或 "--"
 * @property prefix 是否为前缀形式
 */
class UpdateExpr(
    val target: Expression,
    val operator: String,
    val prefix: Boolean,
    override val range: SourceRange,
) : Expression()

/** 一元运算表达式。 */
class UnaryOp(
    val operator: String,
    val operand: Expression,
    override val range: SourceRange,
) : Expression()

/** 二元运算表达式。 */
class BinaryOp(
    val left: Expression,
    val operator: String,
    val right: Expression,
    override val range: SourceRange,
) : Expression()

/** 三元条件表达式。 */
class ConditionalExpr(
    val condition: Expression,
    val thenBranch: Expression,
    val elseBranch: Expression,
    override val range: SourceRange,
) : Expression()

/** class 表达式。 */
class ClassExpression(
    val name: String?,
    val superClass: Expression?,
    val members: List<ClassMember>,
    override val range: SourceRange,
) : Expression()

/** super 引用（用于 super() 与 super.method）。 */
class SuperExpr(
    override val range: SourceRange,
) : Expression()

/** new.target 元属性。 */
class NewTargetExpr(
    override val range: SourceRange,
) : Expression()

/** 标签模板：tag`...${expr}...`。 */
class TaggedTemplate(
    val tag: Expression,
    val quasis: List<String>,
    val expressions: List<Expression>,
    override val range: SourceRange,
) : Expression()

/** 空值合并表达式 `??`。 */
class CoalesceExpr(
    val left: Expression,
    val right: Expression,
    override val range: SourceRange,
) : Expression()

/** 逗号表达式。 */
class SequenceExpr(
    val expressions: List<Expression>,
    override val range: SourceRange,
) : Expression()
