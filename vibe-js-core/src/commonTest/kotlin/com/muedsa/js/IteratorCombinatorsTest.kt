package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Iterator.concat / zip / zipKeyed 的核心边界。
 *
 * Node 24 尚未提供这些静态方法，因此这里用核心单测覆盖，不进入 Node 差分。
 */
class IteratorCombinatorsTest {

    @Test
    fun `Iterator concat 按顺序串联多个可迭代对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1,2,3,4",
            runtime.evalString("Iterator.concat([1, 2], new Set([3, 4])).toArray().join(',')"),
        )
    }

    @Test
    fun `Iterator concat 无参数返回空迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            0.0,
            runtime.evalNumber("Iterator.concat().toArray().length"),
        )
    }

    @Test
    fun `Iterator zip 默认按 shortest 截断`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "[[1,3]]",
            runtime.evalString(
                "JSON.stringify(Iterator.zip([[1], [3, 4]]).toArray())",
            ),
        )
    }

    @Test
    fun `Iterator zip longest 使用 padding 补齐`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "[[1,3],[0,4]]",
            runtime.evalString(
                "JSON.stringify(Iterator.zip([[1], [3, 4]], " +
                    "{ mode: 'longest', padding: [0, 0] }).toArray())",
            ),
        )
    }

    @Test
    fun `Iterator zip strict 长度不一致时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError",
            runtime.evalString(
                """(function () {
  try {
    Iterator.zip([[1, 2], [3]], { mode: 'strict' }).toArray();
    return 'no-throw';
  } catch (error) {
    return error.name;
  }
})()""",
            ),
        )
    }

    @Test
    fun `Iterator zipKeyed 返回 null 原型对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "[{\"0\":1,\"1\":3}]",
            runtime.evalString(
                "JSON.stringify(Iterator.zipKeyed([[1], [3, 4]], " +
                    "{ mode: 'shortest' }).toArray())",
            ),
        )
    }

    @Test
    fun `Iterator concat 传播 next 抛错且不提前打开后续迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "next,caught",
            runtime.evalString(
                """(function () {
  var log = [];
  var first = {
    [Symbol.iterator]: function () {
      return {
        next: function () { log.push('next'); throw new Error('boom'); },
        return: function () { log.push('closed'); return { done: true }; }
      };
    }
  };
  var second = {
    [Symbol.iterator]: function () { log.push('opened'); return [1][Symbol.iterator](); }
  };
  try {
    Iterator.concat(first, second).toArray();
  } catch (error) {
    log.push('caught');
  }
  return log.join(',');
})()""",
            ),
        )
    }
}
