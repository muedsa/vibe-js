# Vibe JS - A Kotlin Multiplatform JavaScript Runtime

Vibe JS 是一个用 Kotlin 多平台实现的轻量级 JavaScript 运行时，支持多种平台（Android、iOS、JVM、LinuxX64）。

## 已支持的 JavaScript 特性

### 数据类型
- **基本类型**：Number、String、Boolean、Null、Undefined
- **复合类型**：Array、Object、Function

### 运算符
- **算术运算符**：`+`、`-`、`*`、`/`、`%`、`**`
- **比较运算符**：`<`、`<=`、`>`、`>=`、`==`、`!=`、`===`、`!==`
- **逻辑运算符**：`&&`、`||`、`!`
- **位运算符**：`&`、`|`、`^`、`~`、`<<`、`>>`、`>>>`
- **赋值运算符**：`=`、`+=`、`-=`、`*=`、`/=`、`%=`
- **更新运算符**：`++`、`--`
- **条件运算符**：`?:`
- **空值合并运算符**：`??`
- **逗号运算符**：`,`

### 控制流
- **条件语句**：`if-else`
- **循环语句**：`while`、`for`
- **分支语句**：`switch`
- **跳转语句**：`break`、`continue`

### 函数
- **函数声明**：使用 `function` 关键字定义函数
- **函数调用**：支持普通函数调用
- **函数参数**：支持多个参数和默认参数
- **闭包**：支持函数闭包
- **返回值**：支持 `return` 语句
- **构造函数**：支持 `new` 关键字创建对象
- **原生函数**：支持注册和调用原生函数

### 对象和数组
- **对象字面量**：`{ key: value }`
- **数组字面量**：`[element1, element2, ...]`
- **成员访问**：支持点符号（`.property`）和括号符号（`[property]`）
- **成员赋值**：支持直接赋值和计算属性赋值
- **数组操作**：支持数组元素的访问和修改
- **标准库方法**：
    - **Array**: `push`, `pop`, `shift`, `unshift`, `slice`, `splice`, `concat`, `reverse`, `sort`, `join`, `indexOf`, `includes`, `forEach`, `map`, `filter`, `reduce`, `reduceRight`, `every`, `some`, `find`, `findIndex`, `at`, `toString`, `from`, `isArray`, `of`
    - **String**: `charAt`, `charCodeAt`, `codePointAt`, `concat`, `endsWith`, `includes`, `indexOf`, `lastIndexOf`, `padEnd`, `padStart`, `repeat`, `replace`, `replaceAll`, `slice`, `split`, `startsWith`, `substring`, `toLowerCase`, `toUpperCase`, `trim`, `trimEnd`, `trimStart`, `toString`, `at`, `fromCharCode`, `fromCodePoint`
    - **Math**: `E`, `LN2`, `LN10`, `LOG2E`, `LOG10E`, `PI`, `SQRT1_2`, `SQRT2`, `abs`, `acos`, `acosh`, `asin`, `asinh`, `atan`, `atanh`, `atan2`, `cbrt`, `ceil`, `clz32`, `cos`, `cosh`, `exp`, `expm1`, `floor`, `fround`, `hypot`, `imul`, `log`, `log1p`, `log10`, `log2`, `max`, `min`, `pow`, `random`, `round`, `sign`, `sin`, `sinh`, `sqrt`, `tan`, `tanh`, `trunc`

### 变量
- **变量声明**：支持 `var` `let` `const`关键字
- **作用域**：支持词法作用域和作用域链
- **变量操作**：支持变量的定义、获取和设置

### 异常处理
- **Error 对象**：支持创建和抛出错误
- **异常处理**：支持异常的抛出和捕获
