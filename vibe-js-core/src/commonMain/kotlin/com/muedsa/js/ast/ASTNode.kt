package com.muedsa.js.ast

import com.muedsa.js.lexer.SourceRange

/**
 * 抽象语法树（AST）节点基类。
 *
 * 设计约束：节点在构造后不可变。这样做有两个好处：
 * 1. 便于后续缓存分析结果（如变量提升信息、常量折叠）；
 * 2. 为未来把 AST 编译成内部指令/闭包保留空间，而不需要修改 Parser 与运行时语义。
 */
sealed class ASTNode {
    /** 节点对应的源码范围，用于错误定位。 */
    abstract val range: SourceRange
}

/** 语句节点基类。 */
sealed class Statement : ASTNode()

/** 表达式节点基类。 */
sealed class Expression : ASTNode()

/**
 * 程序（脚本）根节点。
 *
 * 顶层的作用域规则与函数体一致，因此也携带变量提升信息。
 *
 * @property body 语句列表
 * @property strict 是否为严格模式（由开头的 "use strict" 指令决定）
 * @property varDeclarations 需要提升到当前作用域的 var 名称（已去重）
 * @property functionDeclarations 需要提升的函数声明（按源码顺序）
 */
class Program(
    val body: List<Statement>,
    val strict: Boolean,
    val varDeclarations: List<String>,
    val functionDeclarations: List<FunctionDeclaration>,
    override val range: SourceRange,
) : ASTNode()
