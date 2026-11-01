package com.muedsa.js.conformance

/**
 * 异步差分用例：source 为一段脚本，通过 Promise/async 调度后把可观察结果写入
 * `globalThis.__diffResult`；运行器在微任务队列清空后读取该值并与 Node 对比。
 *
 * 约束：只使用核心内建（不依赖宿主 queueMicrotask/setTimeout），
 * 以便裸 Engine 与 Node 都能在纯微任务层面比较调度顺序。
 */
internal data class AsyncDifferentialCase(
    val name: String,
    val source: String,
)

internal val asyncDifferentialCases: List<AsyncDifferentialCase> = listOf(
    AsyncDifferentialCase(
        "async-then-order",
        """
        var log = [];
        Promise.resolve().then(function () { log.push('a'); }).then(function () { log.push('b'); });
        Promise.resolve().then(function () { log.push('c'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-await-interleave",
        """
        var log = [];
        async function f() {
          log.push(1);
          await null;
          log.push(2);
          await Promise.resolve();
          log.push(3);
        }
        f();
        Promise.resolve().then(function () { log.push(4); });
        log.push(0);
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-thenable-adoption",
        """
        var log = [];
        var thenable = { then: function (resolve) { log.push('then'); resolve('v'); } };
        Promise.resolve(thenable).then(function (v) { log.push('got ' + v); });
        log.push('sync');
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-rejection",
        """
        var log = [];
        Promise.reject('e').then(
          function () { log.push('ok'); },
          function (e) { log.push('rej ' + e); }
        );
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-combinators",
        """
        var log = [];
        Promise.all([1, Promise.resolve(2)]).then(function (v) { log.push('all ' + v.join(',')); });
        Promise.race([Promise.resolve(1), Promise.resolve(2)]).then(function (v) {
          log.push('race ' + v);
        });
        Promise.allSettled([Promise.reject('x'), Promise.resolve('y')]).then(function (v) {
          log.push(v.map(function (r) {
            return r.status + ':' + (r.status === 'fulfilled' ? r.value : r.reason);
          }).join('|'));
        });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-return-thenable",
        """
        var log = [];
        async function g() {
          log.push('g1');
          return Promise.resolve('x');
        }
        g().then(function (v) { log.push('g ' + v); });
        Promise.resolve().then(function () { log.push('p'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-throw-catch",
        """
        var log = [];
        async function h() { log.push('h'); throw 'boom'; }
        h().catch(function (e) { log.push('caught ' + e); });
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-then-chain-loop",
        """
        var log = [];
        var p = Promise.resolve(0);
        for (var i = 0; i < 3; i++) {
          (function (n) {
            p = p.then(function (v) { log.push(n + ':' + v); return v + 1; });
          })(i);
        }
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-nested-microtask",
        """
        var log = [];
        Promise.resolve().then(function () {
          log.push('outer');
          Promise.resolve().then(function () { log.push('inner'); });
        });
        Promise.resolve().then(function () { log.push('second'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-finally-order",
        """
        var log = [];
        async function k() {
          try {
            log.push('try');
            await null;
            log.push('after-await');
            return 'r';
          } finally {
            log.push('finally');
          }
        }
        k().then(function (v) { log.push('then ' + v); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-finally",
        """
        var log = [];
        Promise.resolve('v').finally(function () { log.push('fin'); }).then(function (v) {
          log.push('then ' + v);
        });
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-then-return-thenable",
        """
        var log = [];
        Promise.resolve().then(function () {
          return { then: function (resolve) { log.push('adopt'); resolve('x'); } };
        }).then(function (v) { log.push('got ' + v); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-all-reject",
        """
        var log = [];
        Promise.all([Promise.reject('e'), Promise.resolve(1)]).then(
          function () { log.push('ok'); },
          function (e) { log.push('all-rej ' + e); }
        );
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-catch-chain",
        """
        var log = [];
        async function f() { throw new Error('x'); }
        f().catch(function (e) { log.push('c1 ' + e.message); return 'v'; }).then(function (v) {
          log.push('c2 ' + v);
        });
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-nested-async",
        """
        var log = [];
        async function inner() {
          log.push('i1');
          await null;
          log.push('i2');
          return 'r';
        }
        async function outer() {
          log.push('o1');
          var v = await inner();
          log.push('o2 ' + v);
          return 'done';
        }
        outer().then(function (v) { log.push('then ' + v); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
)
