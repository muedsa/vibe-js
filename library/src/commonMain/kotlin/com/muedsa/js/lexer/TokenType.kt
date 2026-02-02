package com.muedsa.js.lexer

enum class TokenType {
    // 字面量 (Literals)
    NUMBER,     // 数字字面量 123, 3.14
    NUMBER_HEX, // 数字字面量
    NUMBER_OCT, // 数字字面量
    NUMBER_BIN, // 数字字面量
    // 123, 3.14
    STRING,     // 字符串字面量，如 "hello", 'world'
    NULL,       // null 字面量
    IDENTIFIER, // 标识符，如变量名、函数名等

    // 关键字 (Keywords)
    VAR,        // var 声明变量关键字
    LET,        // let 声明块级作用域变量关键字
    CONST,      // const 声明常量关键字
    FUNCTION,   // function 函数声明关键字
    IF,         // if 条件语句关键字
    ELSE,       // else 条件语句关键字
    WHILE,      // while 循环关键字
    FOR,        // for 循环关键字
    RETURN,     // return 函数返回关键字
    TRUE,       // true 布尔值关键字
    FALSE,      // false 布尔值关键字
    NEW,        // new 创建对象实例关键字
    THIS,       // this 当前对象引用关键字
    SWITCH,     // switch 多分支选择关键字
    CASE,       // case switch语句分支关键字
    DEFAULT,    // default switch语句默认分支关键字
    BREAK,      // break 跳出循环或switch语句关键字
    CONTINUE,   // continue 跳过当前循环迭代关键字
    THROW,      // throw 抛出异常关键字
    TRY,        // try 异常处理关键字
    CATCH,      // catch 捕获异常关键字
    FINALLY,    // finally 无论是否异常都会执行的代码块关键字
    TYPEOF,     // typeof 获取类型关键字

    // 算术运算符 (Arithmetic Operators)
    PLUS,       // + 加法运算符
    MINUS,      // - 减法运算符
    MULTIPLY,   // * 乘法运算符
    DIVIDE,     // / 除法运算符
    MODULO,     // % 取模运算符
    INCREMENT,  // ++ 自增运算符
    DECREMENT,  // -- 自减运算符
    EXPONENT,   // ** 幂运算符

    // 赋值运算符 (Assignment Operators)
    ASSIGN,             // = 赋值运算符
    PLUS_ASSIGN,        // += 加法赋值运算符
    MINUS_ASSIGN,       // -= 减法赋值运算符
    MULTIPLY_ASSIGN,    // *= 乘法赋值运算符
    DIVIDE_ASSIGN,      // /= 除法赋值运算符
    MODULO_ASSIGN,      // %= 取模赋值运算符

    // 比较运算符 (Comparison Operators)
    EQ,         // == 相等比较运算符
    NEQ,        // != 不等比较运算符
    LT,         // < 小于比较运算符
    LTE,        // <= 小于等于比较运算符
    GT,         // > 大于比较运算符
    GTE,        // >= 大于等于比较运算符
    STRICT_EQ,  // === 严格相等比较运算符
    STRICT_NEQ, // !== 严格不等比较运算符

    // 逻辑运算符 (Logical Operators)
    AND,        // && 逻辑与运算符
    OR,         // || 逻辑或运算符
    NOT,        // ! 逻辑非运算符

    // 位运算符 (Bitwise Operators)
    BITWISE_AND,            // & 按位与运算符
    BITWISE_OR,             // | 按位或运算符
    BITWISE_XOR,            // ^ 按位异或运算符
    BITWISE_NOT,            // ~ 按位非运算符
    LEFT_SHIFT,             // << 左移运算符
    RIGHT_SHIFT,            // >> 右移运算符
    UNSIGNED_RIGHT_SHIFT,   // >>> 无符号右移运算符

    // 其他 (Others)
    QUESTION,   // ? 条件（三元）运算符
    COALESCE,   // ?? 空值合并运算符

    // 分隔符 (Delimiters)
    LPAREN,     // ( 左圆括号
    RPAREN,     // ) 右圆括号
    LBRACE,     // { 左花括号
    RBRACE,     // } 右花括号
    LBRACKET,   // [ 左方括号
    RBRACKET,   // ] 右方括号
    SEMICOLON,  // ; 分号
    COMMA,      // , 逗号
    DOT,        // . 点号（成员访问）
    COLON,      // : 冒号
    ARROW,      // => 箭头函数符号

    // 特殊 (Special)
    EOF,        // 文件结束标记
    NEWLINE     // 换行符
}
