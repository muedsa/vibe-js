package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 针对审查阶段发现的问题进行规范导向的回归验证。
 */
class ConformanceTest {

    @Test
    fun `Set七个新方法统一处理负小数负零分数NaN及无穷size`() = runTest {
        val runtime = TestRuntime()
        // 声明的 size 可与 keys 数量不同；固定键集合使数值转换与大小分支的差异可观察。
        val expected = """["1|1|||false|true|false","1|1|||false|true|false","1|1|||false|true|false","1|1|||true|true|false","RangeError|RangeError|RangeError|RangeError|RangeError|RangeError|RangeError","TypeError|TypeError|TypeError|TypeError|TypeError|TypeError|TypeError","1|1|||true|false|false","RangeError|RangeError|RangeError|RangeError|RangeError|RangeError|RangeError"]"""
        try {
            assertEquals(
                expected,
                runtime.evalString(
                    """
                    (function () {
                      var methods = ['union','intersection','difference','symmetricDifference','isSubsetOf','isSupersetOf','isDisjointFrom'];
                      return JSON.stringify([-0.5,-0,0.5,1.5,-1,NaN,Infinity,-Infinity].map(function (size) {
                        return methods.map(function (method) {
                          var other = {size:size,has:function(v){return v===1;},keys:function(){return [1].values();}};
                          try {
                            var result = new Set([1])[method](other);
                            return result instanceof Set ? Array.from(result).join(',') : String(result);
                          } catch(e) {return e.name;}
                        }).join('|');
                      }));
                    })()
                    """.trimIndent(),
                ),
            )
        } finally {
            runtime.engine.close()
        }
    }

    @Test
    fun `Set七个新方法只转换一次size并保持后续方法读取顺序`() = runTest {
        val runtime = TestRuntime()
        // getter 的日志只记录属性读取，不混入算法分支调用；valueOf 必须只执行一次。
        val expected = List(7) { "\"size,valueOf,has,keys\"" }.joinToString(prefix = "[", postfix = "]", separator = ",")
        try {
            assertEquals(
                expected,
                runtime.evalString(
                    """
                    (function () {
                      var methods = ['union','intersection','difference','symmetricDifference','isSubsetOf','isSupersetOf','isDisjointFrom'];
                      return JSON.stringify(methods.map(function (method) {
                        var log = [], other = {};
                        Object.defineProperty(other,'size',{get:function(){
                          log.push('size');return {valueOf:function(){log.push('valueOf');return -0.5;}};
                        }});
                        Object.defineProperty(other,'has',{get:function(){log.push('has');return function(){return false;};}});
                        Object.defineProperty(other,'keys',{get:function(){log.push('keys');return function(){return [].values();};}});
                        new Set()[method](other);
                        return log.join(',');
                      }));
                    })()
                    """.trimIndent(),
                ),
            )
        } finally {
            runtime.engine.close()
        }
    }

    @Test
    fun `Set非法size在读取has与keys之前失败且保留转换异常`() = runTest {
        val runtime = TestRuntime()
        // NaN、负整数与负无穷必须提前失败；数值转换自身抛错时也不能继续读取方法。
        val expected = listOf("TypeError", "RangeError", "RangeError", "RangeError", "SyntaxError")
            .joinToString(prefix = "[", postfix = "]", separator = ",") { error ->
                List(7) { "\"size,valueOf,$error\"" }
                    .joinToString(prefix = "[", postfix = "]", separator = ",")
            }
        try {
            assertEquals(
                expected,
                runtime.evalString(
                    """
                    (function () {
                      var methods = ['union','intersection','difference','symmetricDifference','isSubsetOf','isSupersetOf','isDisjointFrom'];
                      return JSON.stringify([NaN,-1,-1.5,-Infinity,'throw'].map(function (size) {
                        return methods.map(function (method) {
                          var log = [], other = {};
                          Object.defineProperty(other,'size',{get:function(){
                            log.push('size');return {valueOf:function(){
                              log.push('valueOf');if(size==='throw')throw new SyntaxError('size');return size;
                            }};
                          }});
                          Object.defineProperty(other,'has',{get:function(){log.push('has');return function(){return false;};}});
                          Object.defineProperty(other,'keys',{get:function(){log.push('keys');return function(){return [].values();};}});
                          try {new Set()[method](other);} catch(e) {log.push(e.name);}
                          return log.join(',');
                        });
                      }));
                    })()
                    """.trimIndent(),
                ),
            )
        } finally {
            runtime.engine.close()
        }
    }

    @Test
    fun `Set小数size参与分支后提前结束仍关闭迭代器并传播关闭错误`() = runTest {
        val runtime = TestRuntime()
        // 1.5 截断为 1 后必须进入 keys 分支；正常耗尽不调用 return，提前结束则校验关闭结果。
        val expected = """[[false,"keys:true,next,return"],[false,"keys:true,next,return"],[true,"keys:true,next,next"],["TypeError","keys:true,next,return"],["Error","keys:true,next,return"]]"""
        try {
            assertEquals(
                expected,
                runtime.evalString(
                    """
                    (function () {
                      function run(method,size,value,mode) {
                        var log=[], n=0, other={size:size,has:function(){throw new Error('unexpected has');},keys:function(){
                          log.push('keys:'+String(this===other));
                          return {
                            next:function(){log.push('next');return n++===0?{value:value,done:false}:{done:true};},
                            return:function(){log.push('return');if(mode==='throw')throw new Error('close');return mode==='primitive'?1:{};}
                          };
                        }};
                        var result;
                        try {result=new Set([1])[method](other);} catch(e){result=e.name;}
                        return [result,log.join(',')];
                      }
                      return JSON.stringify([
                        run('isSupersetOf',1.5,2,'object'),
                        run('isDisjointFrom',-0.5,1,'object'),
                        run('isSupersetOf',1.5,1,'object'),
                        run('isSupersetOf',1.5,2,'primitive'),
                        run('isSupersetOf',1.5,2,'throw')
                      ]);
                    })()
                    """.trimIndent(),
                ),
            )
        } finally {
            runtime.engine.close()
        }
    }

    @Test
    fun `Array from按规范转换大长度且不在十万处截断构造参数`() = runTest {
        val runtime = TestRuntime()
        // 极大长度通过抛出构造参数验证，只有十万边界样本实际创建数组。
        assertEquals(
            "[99999,100000,100001,0,0,0,3,9007199254740991,2147483648,4294967295,4294967296,9007199254740991]",
            runtime.evalString(
                """
                (function () {
                  var lengths = [99999,100000,100001,NaN,-1,-0.5,3.8,Infinity,2147483648,4294967295,4294967296,9007199254740992];
                  return JSON.stringify(lengths.map(function (length) {
                    try { Array.from.call(function (n) { throw n; }, {length:length}); }
                    catch (received) { return received; }
                  }));
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `Array from读取迭代方法及长度后才构造且长度只转换一次`() = runTest {
        val runtime = TestRuntime()
        // 构造器主动终止，验证读取顺序及转换次数，同时确认尚未访问元素。
        assertEquals(
            "[\"iterator\",\"length\",\"valueOf\",\"construct:100001\",\"stop\"]",
            runtime.evalString(
                """
                (function () {
                  var log = [], source = {};
                  Object.defineProperty(source, Symbol.iterator, {get:function(){log.push('iterator');}});
                  Object.defineProperty(source, 'length', {get:function(){
                    log.push('length'); return {valueOf:function(){log.push('valueOf');return 100001;}};
                  }});
                  Object.defineProperty(source, '0', {get:function(){log.push('element');}});
                  try { Array.from.call(function(n){log.push('construct:'+n);throw 'stop';}, source); }
                  catch(e) { log.push(e); }
                  return JSON.stringify(log);
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `Array from保留十万之后的元素并保持数组与自定义构造器边界`() = runTest {
        val runtime = TestRuntime()
        // 实际验证旧截断位置之后的尾元素；非法数组长度应在元素读取之前失败。
        assertEquals(
            "[100001,\"tail\",true,\"RangeError\",false]",
            runtime.evalString(
                """
                (function () {
                  var a = Array.from({length:100001,100000:'tail'});
                  var read = false, error = '';
                  try { Array.from({length:4294967296,get 0(){read=true;}}); }
                  catch(e) {error=e.name;}
                  return JSON.stringify([a.length,a[100000],0 in a,error,read]);
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `移除固定上限后有限迭代正则及映射异常关闭仍保持规范结果`() = runTest {
        val runtime = TestRuntime()
        // 有限输入保留完整结果；映射函数抛出的 JS 异常仍按规范关闭迭代器。
        assertEquals(
            "[[1,2,3],[1,2,3],[1,2,3],[\"x\",\"x\",\"x\"],\"yyy\",[\"fail\",2,1]]",
            runtime.evalString(
                """
                (function () {
                  function iterator(){var n=0;return {next:function(){return n<3?{done:false,value:++n}:{done:true};}};}
                  var source={}; source[Symbol.iterator]=iterator;
                  function regexp(){var r=/x/g,n=0;r.exec=function(){return n<3?{0:'x',index:n++,length:1}:null;};return r;}
                  var calls=0,closed=0,message='', failing={};
                  failing[Symbol.iterator]=function(){return {
                    next:function(){return {done:false,value:++calls};},
                    return:function(){closed++;return {};}
                  };};
                  try {Array.from(failing,function(v,k){if(k===1)throw new Error('fail');return v;});}
                  catch(e){message=e.message;}
                  return JSON.stringify([Array.from(source),[...source],Array.from(new Set().union({size:3,has:function(){return false;},keys:iterator})),
                    regexp()[Symbol.match]('xxx'),regexp()[Symbol.replace]('xxx','y'),[message,calls,closed]]);
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `Map与Set各类迭代器在清空后继续读取新增项`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[[3,4],[30,40],[[3,30],[4,40]],[3,4],[3,4],[[3,3],[4,4]]]",
            runtime.evalString(
                """
                (function () {
                  var out = [];
                  [Map, Set].forEach(function (C) {
                    ['keys', 'values', 'entries'].forEach(function (method) {
                      var c = C === Map ? new Map([[1,10],[2,20]]) : new Set([1,2]);
                      var it = c[method]();
                      it.next();
                      c.clear();
                      if (C === Map) { c.set(3,30); c.set(4,40); }
                      else { c.add(3); c.add(4); }
                      out.push(Array.from(it));
                    });
                  });
                  return JSON.stringify(out);
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `集合反复清空及删除重加保持游标且已结束迭代器不会复活`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[[[4],[4],true,true],[[4],[4],true,true]]",
            runtime.evalString(
                """
                (function () {
                  return JSON.stringify([Map, Set].map(function (C) {
                    var c = C === Map ? new Map([[1,1],[2,2]]) : new Set([1,2]);
                    function add(v) { if (C === Map) c.set(v,v); else c.add(v); }
                    var before = c.keys(), partial = c.keys();
                    partial.next();
                    c.clear(); add(3); c.clear(); add(3); c.delete(3); add(4);
                    var a = Array.from(before), b = Array.from(partial);
                    c.clear(); add(5);
                    return [a,b,before.next().done,partial.next().done];
                  }));
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `嵌套forEach清空集合后内外层分别继续访问新元素`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[\"o1,i3,i5,i6,o5,o6\",\"o1,i3,i5,i6,o5,o6\"]",
            runtime.evalString(
                """
                (function () {
                  return JSON.stringify([Map, Set].map(function (C) {
                    var c = C === Map ? new Map([[1,1],[2,2]]) : new Set([1,2]), seen = [];
                    function add(v) { if (C === Map) c.set(v,v); else c.add(v); }
                    c.forEach(function (value, key) {
                      seen.push('o' + key);
                      if (key === 1) {
                        c.clear(); add(3); add(4);
                        c.forEach(function (inner, innerKey) {
                          seen.push('i' + innerKey);
                          if (innerKey === 3) { c.clear(); add(5); add(6); }
                        });
                      }
                    });
                    return seen.join(',');
                  }));
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `清空后没有新增项的遍历正常结束且后续新增不恢复已结束迭代器`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[[true,true,1],[true,true,1]]",
            runtime.evalString(
                """
                (function () {
                  return JSON.stringify([Map, Set].map(function (C) {
                    var c = C === Map ? new Map([[1,1],[2,2]]) : new Set([1,2]);
                    var it = c.keys(); it.next(); c.clear();
                    var ended = it.next().done;
                    if (C === Map) c.set(3,3); else c.add(3);
                    var visits = 0;
                    c.forEach(function () { visits++; c.clear(); });
                    if (C === Map) c.set(4,4); else c.add(4);
                    return [ended,it.next().done,visits];
                  }));
                })()
                """.trimIndent(),
            ),
        )
    }


    @Test
    fun `全局 this 与 globalThis`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("this === globalThis"))
        assertEquals(true, runtime.evalBoolean("globalThis === this"))
    }

    @Test
    fun `全局变量与全局对象联动`() = runTest {
        val runtime = TestRuntime()
        runtime.eval("var declared = 5;")
        assertEquals(5.0, runtime.evalNumber("globalThis.declared"))
        runtime.eval("implicit = 7;")
        assertEquals(7.0, runtime.evalNumber("globalThis.implicit"))
        assertEquals(7.0, runtime.evalNumber("implicit"))
    }

    @Test
    fun `数组 length 赋值与稀疏赋值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "0:undefined",
            runtime.evalString("var a = [1, 2, 3]; a.length = 0; a.length + ':' + a[0]")
        )
        assertEquals(
            "3:undefined:9",
            runtime.evalString("var a = []; a[2] = 9; a.length + ':' + a[0] + ':' + a[2]")
        )
        assertEquals(3.0, runtime.evalNumber("var a = [1, , 3]; a.length"))
    }

    @Test
    fun `字符串关系比较`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("'apple' < 'banana'"))
        assertEquals(false, runtime.evalBoolean("'banana' < 'apple'"))
    }

    @Test
    fun `字符串边界方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("", runtime.evalString("'abc'.charAt(5)"))
        assertEquals("c", runtime.evalString("'abc'.at(-1)"))
        assertEquals(1.0, runtime.evalNumber("'aundefinedb'.split().length"))
        assertEquals("x", runtime.evalString("'x'.padStart(3, '')"))
        assertEquals("xabab", runtime.evalString("'x'.padEnd(5, 'ab')"))
        assertEquals("aaa", runtime.evalString("'a'.repeat(3)"))
    }

    @Test
    fun `parseFloat 与 Number 判断`() = runTest {
        val runtime = TestRuntime()
        assertEquals(12.0, runtime.evalNumber("parseFloat('12px')"))
        assertEquals(false, runtime.evalBoolean("Number.isNaN('abc')"))
        assertTrue(runtime.evalNumber("Number('12px')").isNaN())
    }

    @Test
    fun `Array 构造器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("new Array(3).length"))
        assertEquals(3.0, runtime.evalNumber("Array(3).length"))
        assertEquals(1.0, runtime.evalNumber("Array('3').length"))
        assertEquals(3.0, runtime.evalNumber("Array.from('abc').length"))
    }

    @Test
    fun `Error 子类构造器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError",
            runtime.evalString("var n = ''; try { throw new TypeError('x'); } catch (e) { n = e.name; } n")
        )
        assertEquals("RangeError: msg", runtime.evalString("'' + new RangeError('msg')"))
    }

    @Test
    fun `JSON 数字与特殊值`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1", runtime.evalString("JSON.stringify(1)"))
        assertEquals("0.5", runtime.evalString("JSON.stringify(0.5)"))
        assertEquals("null", runtime.evalString("JSON.stringify(NaN)"))
        assertEquals("null", runtime.evalString("JSON.stringify(Infinity)"))
        assertEquals("undefined", runtime.evalString("'' + JSON.stringify(undefined)"))
        assertEquals("undefined", runtime.evalString("'' + JSON.stringify(function () {})"))
        assertFailsWith<JSException> {
            runtime.eval("var o = {}; o.self = o; JSON.stringify(o);")
        }
    }

    @Test
    fun `JSON 解析`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("JSON.parse('{\"a\":1}').a"))
        assertEquals("x", runtime.evalString("JSON.parse('{\"a\":\"x\"}').a"))
        assertEquals(2.0, runtime.evalNumber("JSON.parse('[1,2]').length"))
    }

    @Test
    fun `Math 边界`() = runTest {
        val runtime = TestRuntime()
        assertEquals(-2.0, runtime.evalNumber("Math.cbrt(-8)"))
        assertEquals(3.0, runtime.evalNumber("Math.cbrt(27)"))
        assertTrue(runtime.evalNumber("Math.max(1, NaN)").isNaN())
        assertEquals(4.0, runtime.evalNumber("Math.ceil(3.2)"))
    }

    @Test
    fun `数字格式化`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1", runtime.evalString("'' + 1"))
        assertEquals("0.30000000000000004", runtime.evalString("'' + (0.1 + 0.2)"))
        assertEquals("1e+21", runtime.evalString("'' + 1e21"))
        assertEquals("0.000001", runtime.evalString("'' + 0.000001"))
    }

    @Test
    fun `join 对 null 和 undefined 输出空串`() = runTest {
        val runtime = TestRuntime()
        assertEquals("||1", runtime.evalString("[null, undefined, 1].join('|')"))
        assertEquals(",", runtime.evalString("'' + [null, undefined]"))
    }
}
