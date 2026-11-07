package com.muedsa.js.conformance

/**
 * S2 扩展的 L 系列异步差分用例：async generator、`for await...of` 与
 * `Symbol.asyncIterator`。
 *
 * 与同步用例相同，脚本把可观察结果写入 `globalThis.__diffResult`；
 * 运行器在微任务队列清空后读取并与 Node 对比。
 */
internal val lSeriesAsyncDifferentialCases: List<AsyncDifferentialCase> = listOf(
    AsyncDifferentialCase(
        "async-generator-basic",
        """
        var log = [];
        async function* g() {
          log.push('start');
          yield 1;
          await null;
          yield 2;
          return 'end';
        }
        (async function () {
          const values = [];
          for await (const v of g()) values.push(v);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-next-queue",
        """
        var log = [];
        async function* g() { var x = yield 'a'; log.push('got ' + x); yield 'b'; return 'c'; }
        var it = g();
        it.next().then(function (r) { log.push(r.value + ':' + r.done); });
        it.next('V').then(function (r) { log.push(r.value + ':' + r.done); });
        it.next().then(function (r) { log.push(r.value + ':' + r.done); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-return-throw",
        """
        var log = [];
        async function* g() {
          try { yield 1; yield 2; } finally { log.push('finally'); }
        }
        (async function () {
          var it = g();
          var a = await it.next();
          log.push(a.value + ':' + a.done);
          var b = await it.return('R');
          log.push(b.value + ':' + b.done);
          var c = await it.next();
          log.push(String(c.value) + ':' + c.done);
          var it2 = g();
          await it2.next();
          try { await it2.throw('E'); } catch (e) { log.push('caught ' + e); }
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-for-await-sync-iterable",
        """
        var log = [];
        var iterable = {
          [Symbol.iterator]: function () {
            var i = 0;
            return {
              next: function () {
                return i < 3 ? { value: Promise.resolve(++i), done: false } : { done: true };
              },
              return: function () { log.push('sync-return'); return { done: true }; }
            };
          }
        };
        (async function () {
          for await (var v of iterable) { log.push('v' + v); if (v === 2) break; }
          log.push('after');
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-symbol-asyncIterator",
        """
        var log = [];
        var obj = {
          [Symbol.asyncIterator]: function () {
            var i = 0;
            return {
              next: function () {
                log.push('next' + i);
                return Promise.resolve(i < 3 ? { value: i++, done: false } : { value: undefined, done: true });
              },
              return: function (v) {
                log.push('return ' + v);
                return Promise.resolve({ value: v, done: true });
              }
            };
          }
        };
        (async function () {
          var out = [];
          for await (var v of obj) { out.push(v); if (v === 1) break; }
          log.push('values ' + out.join(','));
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-yield-delegate",
        """
        var log = [];
        async function* innerAsync() { yield 'ia'; await null; yield 'ib'; return 'iret'; }
        function* innerSync() { yield 'sa'; yield 'sb'; }
        async function* outer() {
          var r = yield* innerAsync();
          log.push('r1 ' + r);
          yield* innerSync();
          yield 'tail';
        }
        (async function () {
          var values = [];
          for await (var v of outer()) values.push(v);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-for-await-next-reject",
        """
        var log = [];
        var obj = {
          [Symbol.asyncIterator]: function () {
            var i = 0;
            return {
              next: function () {
                log.push('n' + i);
                if (i++ === 1) return Promise.reject(new Error('bad'));
                return Promise.resolve({ value: i, done: false });
              },
              return: function () { log.push('r'); return Promise.resolve({ done: true }); }
            };
          }
        };
        (async function () {
          try {
            for await (var v of obj) { log.push('v' + v); }
          } catch (e) {
            log.push('caught ' + e.message);
          }
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-for-await-throw-close",
        """
        var log = [];
        async function* g() {
          try { yield 1; yield 2; } finally { log.push('g finally'); }
        }
        (async function () {
          try {
            for await (var v of g()) { log.push('v' + v); throw new Error('body'); }
          } catch (e) { log.push('caught ' + e.message); }
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
)
