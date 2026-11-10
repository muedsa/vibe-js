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
    AsyncDifferentialCase(
        "async-promise-try",
        """
        var log = [];
        Promise.try(function () { log.push('t1'); return 1; }).then(function (v) {
          log.push('t2 ' + v);
        });
        Promise.resolve().then(function () { log.push('p'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-with-resolvers",
        """
        var log = [];
        var r = Promise.withResolvers();
        r.promise.then(function (v) { log.push('ok ' + v); });
        Promise.resolve().then(function () { log.push('p'); r.resolve('v'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-any-order",
        """
        var log = [];
        Promise.any([Promise.reject('a'), Promise.resolve('b')]).then(function (v) {
          log.push('any ' + v);
        });
        Promise.reject('c').then(null, function (e) { log.push('rej ' + e); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-then-throws",
        """
        var log = [];
        Promise.resolve(1).then(function () { throw 'x'; }).catch(function (e) {
          log.push('catch ' + e);
        });
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-await-thenable-throws",
        """
        var log = [];
        async function f() {
          try {
            await { then: function (resolve, reject) { log.push('then'); reject('bad'); } };
            log.push('after');
          } catch (e) {
            log.push('caught ' + e);
          }
        }
        f();
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-multiple-await-order",
        """
        var log = [];
        async function f() { await null; log.push(1); await null; log.push(2); }
        async function g() { await null; log.push(3); await null; log.push(4); }
        f();
        g();
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-finally-reject",
        """
        var log = [];
        Promise.reject('x').finally(function () { log.push('fin'); }).catch(function (e) {
          log.push('caught ' + e);
        });
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-allsettled-thenables",
        """
        var log = [];
        Promise.allSettled([
          { then: function (resolve) { log.push('t1'); resolve(1); } },
          Promise.resolve(2)
        ]).then(function (v) {
          log.push(v.map(function (x) { return x.status + ':' + x.value; }).join(','));
        });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-allsettled-thenable-getter-throw",
        """
        var log = [];
        var thenable = {
          get then() {
            throw new Error('getter');
          }
        };
        Promise.allSettled([thenable, Promise.resolve(2)]).then(function (results) {
          log.push(results[0].status + ':' + results[0].reason.message);
          log.push(results[1].status + ':' + results[1].value);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-all-thenable-getter-throw",
        """
        var log = [];
        var thenable = {
          get then() {
            throw new Error('all-getter');
          }
        };
        Promise.all([thenable, Promise.resolve(2)]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),

    AsyncDifferentialCase(
        "async-array-from-async",
        """
        var log = [];
        Array.fromAsync([1, Promise.resolve(2), 3]).then(function (values) {
          log.push(values.join(','));
          return Array.fromAsync([1, 2], function (x) { return x * 2; });
        }).then(function (values) {
          log.push(values.join(','));
          return Array.fromAsync({ length: 2, 0: 'a', 1: Promise.resolve('b') });
        }).then(function (values) {
          log.push(values.join(','));
          return Array.fromAsync([Promise.reject('bad')]).then(null, function (e) {
            log.push('rej:' + e);
          });
        }).then(function () {
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-promise",
        """
        var log = [];
        Array.fromAsync([1, 2], function (value) {
          return Promise.resolve(value * 2);
        }).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-index-array-like",
        """
        var log = [];
        Array.fromAsync(
          { length: 3, 0: 'a', 1: 'b', 2: 'c' },
          function (value, index) {
            return index + ':' + value;
          }
        ).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-index-iterable",
        """
        var log = [];
        Array.fromAsync([10, 20], function (value, index) {
          return index + ':' + value;
        }).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-index-async-iterable",
        """
        var log = [];
        var source = {
          [Symbol.asyncIterator]: function () {
            var index = 0;
            return {
              next: function () {
                if (index < 2) {
                  return Promise.resolve({ value: 5 + index++, done: false });
                }
                return Promise.resolve({ done: true });
              }
            };
          }
        };
        Array.fromAsync(source, function (value, index) {
          return index + ':' + value;
        }).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-returns-object",
        """
        var log = [];
        function Result() {
          return { tag: 'obj' };
        }
        Array.fromAsync.call(Result, [1, 2]).then(function (values) {
          log.push(values.tag);
          log.push(values.length);
          log.push(values[1]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-throw",
        """
        var log = [];
        function Result() {
          throw new Error('constructor-throw');
        }
        Array.fromAsync.call(Result, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-nonextensible",
        """
        var log = [];
        function Result() {
          return Object.preventExtensions({});
        }
        Array.fromAsync.call(Result, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-result-length-setter",
        """
        var log = [];
        var result = {};
        Object.defineProperty(result, 'length', {
          configurable: true,
          set: function () {
            throw new Error('length-setter');
          }
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, []).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-called-once",
        """
        var log = [];
        var calls = 0;
        function Result() {
          calls += 1;
          return {};
        }
        Array.fromAsync.call(Result, [1, 2, 3]).then(function (values) {
          log.push(calls);
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-existing-index-conflict",
        """
        var log = [];
        var result = {};
        Object.defineProperty(result, '0', {
          value: 9,
          writable: false,
          configurable: false
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-element-failure-closes-iterator",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            return this.index++ < 2 ? { value: 1, done: false } : { done: true };
          },
          return: function () {
            log.push('return');
            return { done: true };
          }
        };
        function Result() {
          return Object.preventExtensions({});
        }
        Array.fromAsync.call(Result, {
          [Symbol.iterator]: function () {
            return iterator;
          }
        }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push(error.name);
        }).then(function () {
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-length-failure-closes-iterator",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            return this.index++ < 2 ? { value: 1, done: false } : { done: true };
          },
          return: function () {
            log.push('return');
            return { done: true };
          }
        };
        var result = {};
        Object.defineProperty(result, 'length', {
          set: function () {
            throw new Error('length');
          }
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, {
          [Symbol.iterator]: function () {
            return iterator;
          }
        }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push(error.message);
        }).then(function () {
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-writable-length",
        """
        var log = [];
        var result = [];
        Object.defineProperty(result, 'length', {
          writable: false
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1, 2]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push(error.name);
        }).then(function () {
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-result-length",
        """
        var log = [];
        var target = {};
        var proxy = new Proxy(target, {
          set: function (object, key, value) {
            log.push(String(key) + '=' + value);
            return Reflect.set(object, key, value);
          }
        });
        function Result() {
          return proxy;
        }
        Array.fromAsync.call(Result, [1, 2]).then(function (values) {
          log.push(values === proxy);
          log.push(proxy.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-writable-length-zero",
        """
        var log = [];
        var result = [];
        Object.defineProperty(result, 'length', {
          writable: false
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, []).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-length-accessor-without-setter",
        """
        var log = [];
        var result = {};
        Object.defineProperty(result, 'length', {
          get: function () {
            return 0;
          },
          configurable: true
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, []).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-iterator-before-constructor",
        """
        var log = [];
        function Result() {
          log.push('construct');
          return [];
        }
        var source = {
          get [Symbol.iterator]() {
            log.push('iterator');
            return function () {
              return {
                next: function () {
                  return { done: true };
                }
              };
            };
          }
        };
        Array.fromAsync.call(Result, source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-throw-no-close",
        """
        var log = [];
        function Result() {
          log.push('construct');
          throw new Error('constructor');
        }
        var source = {
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return { value: 1, done: false };
              },
              return: function () {
                log.push('return');
                return { done: true };
              }
            };
          }
        };
        Array.fromAsync.call(Result, source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push(error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-constructor-this-object",
        """
        var log = [];
        Array.fromAsync.call({}, [1, 2]).then(function (values) {
          log.push(Array.isArray(values));
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-constructor-this-function",
        """
        var log = [];
        Array.fromAsync.call(function () {}, [1, 2]).then(function (values) {
          log.push(Array.isArray(values));
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-no-arguments",
        """
        var log = [];
        function Result() {
          log.push(arguments.length);
          return [];
        }
        Array.fromAsync.call(Result, [1]).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-construct-result",
        """
        var log = [];
        var target = [];
        var proxy = new Proxy(function () {}, {
          construct: function (constructor, args) {
            log.push('construct:' + args.length);
            return target;
          }
        });
        Array.fromAsync.call(proxy, [1, 2]).then(function (values) {
          log.push(values === target);
          log.push(target.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-iterator-call-contract",
        """
        var log = [];
        var iterator = {
          next: function () {
            return { done: true };
          }
        };
        var source = {
          [Symbol.iterator]: function () {
            log.push(arguments.length);
            log.push(this === source);
            return iterator;
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-next-call-contract",
        """
        var log = [];
        var iterator = {
          next: function () {
            log.push(arguments.length);
            log.push(this === iterator);
            return { done: true };
          }
        };
        var source = {
          [Symbol.iterator]: function () {
            return iterator;
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-call-contract",
        """
        var log = [];
        Array.fromAsync([1, 2], function (value, index) {
          log.push(arguments.length);
          log.push(value);
          log.push(index);
          return value;
        }).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-undefined-values",
        """
        var log = [];
        Array.fromAsync([undefined, undefined], function (value, index) {
          log.push(value === undefined);
          log.push(index);
          return index;
        }).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-second-throw",
        """
        var log = [];
        var index = 0;
        var source = {
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return index++ < 2 ? { value: index, done: false } : { done: true };
              },
              return: function () {
                log.push('return');
                return { done: true };
              }
            };
          }
        };
        Array.fromAsync(source, function (value) {
          log.push('map:' + value);
          if (value === 2) {
            throw new Error('second');
          }
          return value;
        }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-second-reject",
        """
        var log = [];
        var index = 0;
        var source = {
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return index++ < 2 ? { value: index, done: false } : { done: true };
              },
              return: function () {
                log.push('return');
                return { done: true };
              }
            };
          }
        };
        Array.fromAsync(source, function (value) {
          log.push('map:' + value);
          if (value === 2) {
            return Promise.reject(new Error('second'));
          }
          return value;
        }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-delete-during-map",
        """
        var log = [];
        var source = {
          length: 2,
          0: 'a',
          get 1() {
            delete source[1];
            return 'b';
          }
        };
        Array.fromAsync(source, function (value) {
          log.push(String(value));
          return value;
        }).then(function (values) {
          log.push(values.length);
          log.push(values[1] === undefined);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-index-read-once",
        """
        var log = [];
        var reads = 0;
        var source = {
          length: 2,
          get 0() {
            reads += 1;
            return 'a';
          },
          get 1() {
            reads += 1;
            return 'b';
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.join(','));
          log.push(reads);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-length-deleted-during-map",
        """
        var log = [];
        var source = {
          length: 2,
          0: 'a'
        };
        Array.fromAsync(source, function (value) {
          log.push(String(value));
          if (value === 'a') {
            delete source.length;
          }
          return value;
        }).then(function (values) {
          log.push(values.length);
          log.push(values[1] === undefined);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-length-shrunk-in-getter",
        """
        var log = [];
        var source = {
          length: 2,
          get 0() {
            source.length = 0;
            return 'a';
          },
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          log.push(values[1]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-string-length",
        """
        var log = [];
        var source = {
          length: '2',
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-negative-zero-length",
        """
        var log = [];
        var source = {
          length: -0,
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0] === undefined);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-nan-length",
        """
        var log = [];
        var source = {
          length: NaN,
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0] === undefined);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-boolean-length",
        """
        var log = [];
        var source = {
          length: true,
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-array-length",
        """
        var log = [];
        var source = {
          length: [2],
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-object-length",
        """
        var log = [];
        var source = {
          length: {},
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-multi-array-length",
        """
        var log = [];
        var source = {
          length: [1, 2],
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-hex-string-length",
        """
        var log = [];
        var source = {
          length: '0x2',
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-negative-string-length",
        """
        var log = [];
        var source = {
          length: ' -2 ',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-fraction-string-length",
        """
        var log = [];
        var source = {
          length: '2.9',
          0: 'a',
          1: 'b',
          2: 'c'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-blank-string-length",
        """
        var log = [];
        var source = {
          length: '   ',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-tab-string-length",
        """
        var log = [];
        var source = {
          length: '\t',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-empty-array-length",
        """
        var log = [];
        var source = {
          length: [],
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-null-array-length",
        """
        var log = [];
        var source = {
          length: [null],
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-binary-string-length",
        """
        var log = [];
        var source = {
          length: '0b10',
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-octal-string-length",
        """
        var log = [];
        var source = {
          length: '0o2',
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-padded-hex-length",
        """
        var log = [];
        var source = {
          length: ' 0x10 ',
          0: 0,
          15: 15
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          log.push(values[15]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-numeric-separator-length",
        """
        var log = [];
        var source = {
          length: '1_0',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-incomplete-hex-length",
        """
        var log = [];
        var source = {
          length: '0x',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-signed-hex-length",
        """
        var log = [];
        var source = {
          length: '-0x10',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-invalid-binary-length",
        """
        var log = [];
        var source = {
          length: '0b2',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-invalid-octal-length",
        """
        var log = [];
        var source = {
          length: '0o8',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-incomplete-binary-length",
        """
        var log = [];
        var source = {
          length: '0b',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-incomplete-octal-length",
        """
        var log = [];
        var source = {
          length: '0o',
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-padded-binary-length",
        """
        var log = [];
        var source = {
          length: ' 0b10 ',
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-like-newline-octal-length",
        """
        var log = [];
        var source = {
          length: '0o2\n',
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-index-getter",
        """
        var log = [];
        var array = [];
        Object.defineProperty(array, '0', {
          get: function () {
            log.push('getter');
            return 'a';
          },
          configurable: true
        });
        array.length = 1;
        Array.fromAsync(array).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-index-getter-throw",
        """
        var log = [];
        var array = [];
        Object.defineProperty(array, '0', {
          get: function () {
            throw new Error('index-getter');
          },
          configurable: true
        });
        array.length = 1;
        Array.fromAsync(array).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-inherited-index",
        """
        var log = [];
        var proto = Object.create(Array.prototype);
        proto[0] = 'proto';
        var array = [];
        Object.setPrototypeOf(array, proto);
        array.length = 1;
        Array.fromAsync(array).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-hole-inherited-index",
        """
        var log = [];
        var proto = Object.create(Array.prototype);
        proto[0] = 'proto';
        var array = [,];
        Object.setPrototypeOf(array, proto);
        array.length = 1;
        Array.fromAsync(array).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-own-iterator",
        """
        var log = [];
        var array = [1, 2];
        array[Symbol.iterator] = function () {
          return {
            next: function () {
              return { value: 'custom', done: true };
            }
          };
        };
        Array.fromAsync(array).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-null-iterator",
        """
        var log = [];
        var array = [1, 2];
        array[Symbol.iterator] = null;
        Array.fromAsync(array).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-null-async-iterator-sync-fallback",
        """
        var log = [];
        var source = {
          [Symbol.asyncIterator]: null,
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return { value: 'sync', done: false };
              }
            };
          }
        };
        var index = 0;
        source[Symbol.iterator] = function () {
          return {
            next: function () {
              return index++ === 0 ? { value: 'sync', done: false } : { done: true };
            }
          };
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-null-async-iterator-array-like",
        """
        var log = [];
        var source = {
          [Symbol.asyncIterator]: null,
          length: 1,
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-undefined-async-iterator-sync",
        """
        var log = [];
        var index = 0;
        var source = {
          [Symbol.asyncIterator]: undefined,
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return index++ === 0 ? { value: 'sync', done: false } : { done: true };
              }
            };
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-undefined-async-iterator-sync-getter-throw",
        """
        var log = [];
        var source = {
          [Symbol.asyncIterator]: undefined,
          get [Symbol.iterator]() {
            throw new Error('sync-getter');
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-iterator-skips-sync-getter",
        """
        var log = [];
        var index = 0;
        var source = {
          [Symbol.asyncIterator]: function () {
            return {
              next: function () {
                return Promise.resolve(index++ === 0 ? { value: 'async', done: false } : { done: true });
              }
            };
          },
          get [Symbol.iterator]() {
            throw new Error('sync-getter');
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-iterator-getter-throw-before-sync",
        """
        var log = [];
        var source = {
          get [Symbol.asyncIterator]() {
            throw new Error('async-getter');
          },
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return { done: true };
              }
            };
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-next-undefined",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.resolve(undefined);
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-next-null",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.resolve(null);
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-sync-next-undefined",
        """
        var log = [];
        var iterator = {
          next: function () {
            return undefined;
          }
        };
        Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-sync-next-null",
        """
        var log = [];
        var iterator = {
          next: function () {
            return null;
          }
        };
        Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-thenable",
        """
        var log = [];
        Array.fromAsync([1], function () {
          return {
            then: function (resolve) {
              resolve(3);
            }
          };
        }).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-then-getter-throw",
        """
        var log = [];
        Array.fromAsync([1], function () {
          return {
            get then() {
              throw new Error('then-getter');
            }
          };
        }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-then-non-callable",
        """
        var log = [];
        var value = { then: 1 };
        Array.fromAsync([1], function () {
          return value;
        }).then(function (values) {
          log.push(values[0] === value);
          log.push(values[0].then);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-thenable-resolve-twice",
        """
        var log = [];
        Array.fromAsync([1], function () {
          return {
            then: function (resolve) {
              resolve(3);
              resolve(9);
            }
          };
        }).then(function (values) {
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-prototype-getter-throw",
        """
        var log = [];
        function Result() {
          return [];
        }
        Object.defineProperty(Result, 'prototype', {
          get: function () {
            throw new Error('prototype-getter');
          }
        });
        Array.fromAsync.call(Result, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-arrow-this",
        """
        var log = [];
        var arrow = () => {};
        Array.fromAsync.call(arrow, [1]).then(function (values) {
          log.push(Array.isArray(values));
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-primitive-return",
        """
        var log = [];
        function Result() {
          return 1;
        }
        Array.fromAsync.call(Result, [1, 2]).then(function (values) {
          log.push(Array.isArray(values));
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-constructor-null-return",
        """
        var log = [];
        function Result() {
          return null;
        }
        Array.fromAsync.call(Result, [1]).then(function (values) {
          log.push(Array.isArray(values));
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-existing-accessor-index",
        """
        var log = [];
        var result = {};
        Object.defineProperty(result, '0', {
          get: function () {
            return 'old';
          },
          configurable: true
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-existing-writable-index",
        """
        var log = [];
        var result = { 0: 'old' };
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1]).then(function (values) {
          log.push(values === result);
          log.push(values[0]);
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-existing-non-writable-index-same-value",
        """
        var log = [];
        var result = {};
        Object.defineProperty(result, '0', {
          value: 1,
          writable: false,
          configurable: true
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1]).then(function (values) {
          log.push(values[0]);
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-existing-non-configurable-writable-index",
        """
        var log = [];
        var result = {};
        Object.defineProperty(result, '0', {
          value: 'old',
          writable: true,
          configurable: false
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-configurable-writable-length",
        """
        var log = [];
        var result = {};
        Object.defineProperty(result, 'length', {
          value: 0,
          writable: true,
          configurable: false
        });
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1, 2]).then(function (values) {
          log.push(values === result);
          log.push(values.length);
          log.push(values[1]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-length-setter-receives-length",
        """
        var log = [];
        var result = {
          set length(value) {
            log.push('length:' + value);
          }
        };
        function Result() {
          return result;
        }
        Array.fromAsync.call(Result, [1, 2]).then(function (values) {
          log.push(values === result);
          log.push(values[0]);
          log.push(values[1]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-subclass-this",
        """
        var log = [];
        class Sub extends Array {}
        Array.fromAsync.call(Sub, [1, 2]).then(function (values) {
          log.push(values instanceof Sub);
          log.push(Object.getPrototypeOf(values) === Sub.prototype);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-subclass-this-async-iterable",
        """
        var log = [];
        class Sub extends Array {}
        var index = 0;
        var source = {
          [Symbol.asyncIterator]: function () {
            return {
              next: function () {
                return Promise.resolve(index++ === 0 ? { value: 1, done: false } : { done: true });
              }
            };
          }
        };
        Array.fromAsync.call(Sub, source).then(function (values) {
          log.push(values instanceof Sub);
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-subclass-species-ignored",
        """
        var log = [];
        class Sub extends Array {
          static get [Symbol.species]() {
            log.push('species');
            throw new Error('species');
          }
        }
        Array.fromAsync.call(Sub, [1]).then(function (values) {
          log.push(values instanceof Sub);
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-array-this",
        """
        var log = [];
        Array.fromAsync.call(Array, [1, 2]).then(function (values) {
          log.push(Array.isArray(values));
          log.push(Object.getPrototypeOf(values) === Array.prototype);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-bound-constructor-this",
        """
        var log = [];
        var result = [];
        function Target() {
          log.push('construct');
          return result;
        }
        var Bound = Target.bind(null);
        Array.fromAsync.call(Bound, [1, 2]).then(function (values) {
          log.push(values === result);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-bound-arrow-this",
        """
        var log = [];
        var Bound = (() => {}).bind(null);
        Array.fromAsync.call(Bound, [1]).then(function (values) {
          log.push(Array.isArray(values));
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-construct-primitive",
        """
        var log = [];
        var proxy = new Proxy(function () {}, {
          construct: function () {
            return 1;
          }
        });
        Array.fromAsync.call(proxy, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-construct-throw",
        """
        var log = [];
        var proxy = new Proxy(function () {}, {
          construct: function () {
            throw new Error('proxy-construct');
          }
        });
        Array.fromAsync.call(proxy, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-revoked-constructor",
        """
        var log = [];
        var revocable = Proxy.revocable(function () {}, {});
        revocable.revoke();
        Array.fromAsync.call(revocable.proxy, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-prototype-throw",
        """
        var log = [];
        var proxy = new Proxy(function () {}, {
          get: function (target, key, receiver) {
            if (key === 'prototype') {
              throw new Error('proxy-prototype');
            }
            return Reflect.get(target, key, receiver);
          }
        });
        Array.fromAsync.call(proxy, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-date-this",
        """
        var log = [];
        Array.fromAsync.call(Date, [1]).then(function (values) {
          log.push(values instanceof Date);
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-map-this",
        """
        var log = [];
        Array.fromAsync.call(Map, [1]).then(function (values) {
          log.push(values instanceof Map);
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-promise-this",
        """
        var log = [];
        Array.fromAsync.call(Promise, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-boolean-this",
        """
        var log = [];
        Array.fromAsync.call(Boolean, [1]).then(function (values) {
          log.push(values instanceof Boolean);
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-number-this",
        """
        var log = [];
        Array.fromAsync.call(Number, [1]).then(function (values) {
          log.push(values instanceof Number);
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-bigint-this",
        """
        var log = [];
        Array.fromAsync.call(BigInt, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-symbol-this",
        """
        var log = [];
        Array.fromAsync.call(Symbol, [1]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-this-arg",
        """
        var log = [];
        var context = { tag: 1 };
        Array.fromAsync([1], function (value) {
          log.push(this === context);
          return value;
        }, context).then(function (values) {
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-this-default",
        """
        var log = [];
        Array.fromAsync([1], function (value) {
          log.push(this === globalThis);
          return value;
        }).then(function (values) {
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-this-primitive-sloppy",
        """
        var log = [];
        Array.fromAsync([1], function (value) {
          log.push(this instanceof Number);
          log.push(this.valueOf());
          return value;
        }, 1).then(function (values) {
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-this-primitive-strict",
        """
        var log = [];
        Array.fromAsync([1], function (value) {
          'use strict';
          log.push(this === 1);
          return value;
        }, 1).then(function (values) {
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-extra-arguments-ignored",
        """
        var log = [];
        Array.fromAsync([1], function (value) {
          log.push(value);
          return value;
        }, null, 'extra').then(function (values) {
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-null",
        """
        var log = [];
        Array.fromAsync([1], null).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-object-non-callable",
        """
        var log = [];
        Array.fromAsync([1], { call: function () {} }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-mapper-undefined",
        """
        var log = [];
        Array.fromAsync([1, 2], undefined).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-string",
        """
        var log = [];
        Array.fromAsync('ab').then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-sparse",
        """
        var log = [];
        var source = [];
        source.length = 2;
        source[1] = 'b';
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(0 in values);
          log.push(1 in values);
          log.push(values[0]);
          log.push(values[1]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-length-getter-throw",
        """
        var log = [];
        var source = {
          get length() {
            throw new Error('length-getter');
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-index-getter-throw",
        """
        var log = [];
        var source = {
          length: 1,
          get 0() {
            throw new Error('index-getter');
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-callable-mapper",
        """
        var log = [];
        Array.fromAsync([1], 1).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-generator-return",
        """
        var log = [];
        function* source() {
          yield 1;
          return 2;
        }
        Array.fromAsync(source()).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-iterator-getter-throw",
        """
        var log = [];
        var source = {
          get [Symbol.iterator]() {
            throw new Error('iterator-getter');
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-callable-iterator",
        """
        var log = [];
        var source = {
          [Symbol.iterator]: 1
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-iterator-non-object",
        """
        var log = [];
        var source = {
          [Symbol.iterator]: function () {
            return 1;
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-fractional-length",
        """
        var log = [];
        var source = {
          length: 2.9,
          0: 'a',
          1: 'b',
          2: 'c'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-symbol-length",
        """
        var log = [];
        var source = {
          length: Symbol('length')
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-negative-length",
        """
        var log = [];
        var source = {
          length: -1,
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-negative-length-mapper",
        """
        var log = [];
        var calls = 0;
        var source = {
          length: -1,
          0: 'a'
        };
        Array.fromAsync(source, function (value) {
          calls += 1;
          return value;
        }).then(function (values) {
          log.push(values.length);
          log.push(calls);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-missing-length-mapper",
        """
        var log = [];
        var calls = 0;
        var source = {
          0: 'a'
        };
        Array.fromAsync(source, function (value) {
          calls += 1;
          return value;
        }).then(function (values) {
          log.push(values.length);
          log.push(calls);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-length-read-once",
        """
        var log = [];
        var reads = 0;
        var source = {
          get length() {
            reads += 1;
            return 2;
          },
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.join(','));
          log.push(reads);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-iterable-precedence",
        """
        var log = [];
        var index = 0;
        var source = {
          0: 'a',
          length: 1,
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return index++ === 0 ? { value: 'i', done: false } : { done: true };
              }
            };
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-normal-completion-no-return",
        """
        var log = [];
        var index = 0;
        var iterator = {
          next: function () {
            return index++ === 0 ? { value: 1, done: false } : { done: true };
          },
          return: function () {
            log.push('return');
            return { done: true };
          }
        };
        Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-normal-completion-no-return",
        """
        var log = [];
        var index = 0;
        var iterator = {
          next: function () {
            return Promise.resolve(index++ === 0 ? { value: 1, done: false } : { done: true });
          },
          return: function () {
            log.push('return');
            return Promise.resolve({ done: true });
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function (values) {
          log.push(values.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-done-true-no-return",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.resolve({
              done: true,
              get value() {
                throw new Error('async-done-value');
              }
            });
          },
          return: function () {
            log.push('return');
            return Promise.resolve({ done: true });
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function (values) {
          log.push(values.length);
          log.push(log.length);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-array-like",
        """
        var log = [];
        var target = { length: 2, 0: 'a', 1: 'b' };
        var proxy = new Proxy(target, {
          get: function (object, key, receiver) {
            log.push(String(key));
            return Reflect.get(object, key, receiver);
          }
        });
        Array.fromAsync(proxy).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-length-throw",
        """
        var log = [];
        var proxy = new Proxy({}, {
          get: function (object, key) {
            if (key === 'length') {
              throw new Error('proxy-length');
            }
            return undefined;
          }
        });
        Array.fromAsync(proxy).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-has-not-called",
        """
        var log = [];
        var target = { length: 2, 0: 'a', 1: 'b' };
        var proxy = new Proxy(target, {
          has: function (object, key) {
            log.push('has:' + String(key));
            return Reflect.has(object, key);
          },
          get: function (object, key, receiver) {
            log.push('get:' + String(key));
            return Reflect.get(object, key, receiver);
          }
        });
        Array.fromAsync(proxy).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-proxy-iterator-precedence",
        """
        var log = [];
        var index = 0;
        var target = {
          length: 2,
          0: 'a',
          1: 'b',
          [Symbol.iterator]: function () {
            return {
              next: function () {
                return index++ === 0 ? { value: 'i', done: false } : { done: true };
              }
            };
          }
        };
        var proxy = new Proxy(target, {
          get: function (object, key, receiver) {
            log.push('get:' + String(key));
            return Reflect.get(object, key, receiver);
          }
        });
        Array.fromAsync(proxy).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-null-input",
        """
        var log = [];
        Array.fromAsync(null).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-undefined-input",
        """
        var log = [];
        Array.fromAsync(undefined).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-primitive-input",
        """
        var log = [];
        Array.fromAsync(1).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-next-sync-result",
        """
        var log = [];
        var index = 0;
        var iterator = {
          next: function () {
            return index++ === 0 ? { value: 1, done: false } : { done: true };
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-next-primitive",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.resolve(1);
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-iterator-getter-throw",
        """
        var log = [];
        var source = {
          get [Symbol.asyncIterator]() {
            throw new Error('async-iterator-getter');
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-iterator-non-object",
        """
        var log = [];
        var source = {
          [Symbol.asyncIterator]: function () {
            return 1;
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-next-getter-throw",
        """
        var log = [];
        var iterator = {
          get next() {
            throw new Error('async-next-getter');
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-iterator-non-callable",
        """
        var log = [];
        var source = {
          [Symbol.asyncIterator]: 1
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-next-non-callable",
        """
        var log = [];
        var iterator = {
          next: 1
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-next-reject",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.reject(new Error('async-next-reject'));
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-done-getter-throw",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.resolve({
              get done() {
                throw new Error('async-done-getter');
              }
            });
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-value-getter-throw",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.resolve({
              done: false,
              get value() {
                throw new Error('async-value-getter');
              }
            });
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-value-not-awaited",
        """
        var log = [];
        var promise = Promise.resolve(9);
        var index = 0;
        var iterator = {
          next: function () {
            return Promise.resolve(index++ === 0 ? { value: promise, done: false } : { done: true });
          }
        };
        Array.fromAsync({ [Symbol.asyncIterator]: function () { return iterator; } }).then(function (values) {
          log.push(values.length);
          log.push(values[0] === promise);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-sync-value-awaited",
        """
        var log = [];
        var index = 0;
        var iterator = {
          next: function () {
            return index++ === 0 ? { value: Promise.resolve(7), done: false } : { done: true };
          }
        };
        Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(function (values) {
          log.push(values.length);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-missing-length",
        """
        var log = [];
        var source = {
          0: 'a'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-bigint-length",
        """
        var log = [];
        var source = {
          length: 1n
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-length-too-large",
        """
        var log = [];
        var source = {
          length: 4294967296
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name + ':' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-non-callable-next",
        """
        var log = [];
        var iterator = {
          next: 1
        };
        var source = {
          [Symbol.iterator]: function () {
            return iterator;
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-next-non-object",
        """
        var log = [];
        var iterator = {
          next: function () {
            return 1;
          }
        };
        var source = {
          [Symbol.iterator]: function () {
            return iterator;
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-next-empty-result",
        """
        var log = [];
        var index = 0;
        var iterator = {
          next: function () {
            return index++ === 0 ? {} : { done: true };
          }
        };
        var source = {
          [Symbol.iterator]: function () {
            return iterator;
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.length);
          log.push(0 in values);
          log.push(values[0]);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-done-getter-throw",
        """
        var log = [];
        var iterator = {
          next: function () {
            return {
              get done() {
                throw new Error('done-getter');
              }
            };
          }
        };
        var source = {
          [Symbol.iterator]: function () {
            return iterator;
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-value-getter-throw",
        """
        var log = [];
        var iterator = {
          next: function () {
            return {
              done: false,
              get value() {
                throw new Error('value-getter');
              }
            };
          }
        };
        var source = {
          [Symbol.iterator]: function () {
            return iterator;
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-array-from-async-async-done-true-value",
        """
        var log = [];
        var iterator = {
          next: function () {
            return Promise.resolve({
              done: true,
              get value() {
                throw new Error('async-done-value');
              }
            });
          }
        };
        var source = {
          [Symbol.asyncIterator]: function () {
            return iterator;
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push('ok:' + values.length);
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-iterable",
        """
        var log = [];
        var iterable = {
          [Symbol.asyncIterator]: function () {
            var i = 0;
            return {
              next: function () {
                log.push('n' + i);
                return Promise.resolve(i < 3 ? { value: ++i, done: false } : { done: true });
              }
            };
          }
        };
        Array.fromAsync(iterable).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),

    AsyncDifferentialCase(
        "async-from-async-mapper-close",
        """
        var log = [];
        var iterable = {
          [Symbol.asyncIterator]: function () {
            var i = 0;
            return {
              next: function () {
                log.push('n' + i);
                return Promise.resolve(i < 3 ? { value: ++i, done: false } : { done: true });
              },
              return: function () { log.push('r'); return Promise.resolve({ done: true }); }
            };
          }
        };
        Array.fromAsync(iterable, function (v) {
          if (v === 2) throw new Error('m');
          return v;
        }).then(function () {
          log.push('ok');
        }, function (e) {
          log.push('rej:' + e.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-next-reject",
        """
        var log = [];
        var iterable = {
          [Symbol.asyncIterator]: function () {
            var i = 0;
            return {
              next: function () {
                log.push('n' + i);
                if (i++ === 0) return Promise.reject(new Error('x'));
                return Promise.resolve({ done: true });
              },
              return: function () { log.push('r'); return Promise.resolve({ done: true }); }
            };
          }
        };
        Array.fromAsync(iterable).then(null, function (e) {
          log.push('rej:' + e.message);
        }).then(function () {
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),

    AsyncDifferentialCase(
        "async-throw-plain-object",
        """
        var log = [];
        async function f() { throw { code: 'X' }; }
        f().then(function (value) {
          log.push('ok:' + value);
        }, function (error) {
          log.push('rej:' + (error && error.code));
        });
        Promise.resolve().then(function () { log.push('t'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),

    AsyncDifferentialCase(
        "async-microtask-matrix",
        """
        var log = [];
        function mark(value) { log.push(value); }

        Promise.resolve().then(function () { mark('t1'); });
        Promise.resolve().then(function () {
          mark('t2');
          Promise.resolve().then(function () { mark('t2a'); });
        }).then(function () { mark('t2b'); });

        async function f() {
          mark('f1');
          await null;
          mark('f2');
          await Promise.resolve();
          mark('f3');
        }
        f();

        (async function () {
          mark('g1');
          await 'x';
          mark('g2');
        })();

        new Promise(function (resolve) { mark('exec'); resolve('v'); })
          .then(function (value) { mark('then:' + value); });

        Promise.reject(new Error('e')).catch(function () { mark('catch'); });

        var thenable = { then: function (resolve) { mark('thenable'); resolve('tv'); } };
        Promise.resolve(thenable).then(function (value) { mark('adopt:' + value); });

        Promise.all([Promise.resolve(1), 2]).then(function (values) {
          mark('all:' + values.join('-'));
        });
        Promise.race([new Promise(function () { mark('pending'); }), Promise.resolve('r')])
          .then(function (value) { mark('race:' + value); });
        Promise.allSettled([Promise.resolve(1), Promise.reject('x')]).then(function (results) {
          mark('settled:' + results.map(function (result) { return result.status; }).join('-'));
        });
        Promise.resolve().finally(function () { mark('finally'); })
          .then(function () { mark('finallyThen'); });

        for (var i = 0; i < 3; i++) {
          (function (index) {
            Promise.resolve(index).then(function (value) { mark('loop' + value); });
          })(i);
        }

        Promise.resolve().then(function () {
          mark('throw');
          throw new Error('boom');
        }).catch(function () { mark('recover'); })
          .then(function () { mark('afterRecover'); });

        new Promise(function (resolve) { mark('double'); resolve(1); resolve(2); })
          .then(function (value) { mark('doubleThen:' + value); });

        async function h() {
          mark('h1');
          throw new Error('h');
        }
        h().catch(function () { mark('hCatch'); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-await-using-order",
        """
        var log = [];
        async function run() {
          await using first = {
            [Symbol.asyncDispose]: function () {
              log.push('first');
              return Promise.resolve();
            }
          };
          log.push('body');
          await null;
          log.push('after');
        }
        run();
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-await-using-throw",
        """
        var log = [];
        async function run() {
          await using resource = {
            [Symbol.asyncDispose]: function () {
              log.push('dispose');
              return Promise.resolve();
            }
          };
          throw new Error('body');
        }
        run().catch(function (error) { log.push(error.message); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-disposable-stack-order",
        """
        var log = [];
        async function run() {
          var stack = new AsyncDisposableStack();
          stack.defer(function () {
            log.push('defer');
            return Promise.resolve();
          });
          stack.use({
            [Symbol.asyncDispose]: function () {
              log.push('use');
              return Promise.resolve();
            }
          });
          await stack.disposeAsync();
          log.push('done');
        }
        run();
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-thenable-getter-throws",
        """
        var thenable = {
          get then() {
            throw new Error('getter');
          }
        };
        Promise.resolve(thenable).catch(function (error) {
          globalThis.__diffResult = error.message;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-executor-throw-after-resolve",
        """
        var log = [];
        new Promise(function (resolve) {
          resolve(1);
          throw new Error('ignored');
        }).then(function (value) { log.push('then:' + value); });
        globalThis.__diffResult = log;
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-subclass",
        """
        class P extends Promise {}
        var value = P.resolve(1);
        globalThis.__diffResult = [
          value instanceof P,
          value instanceof Promise
        ];
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-species",
        """
        class P extends Promise {
          static get [Symbol.species]() { return Promise; }
        }
        var value = new P(function (resolve) { resolve(1); });
        var chained = value.then(function (result) { return result + 1; });
        globalThis.__diffResult = [
          chained instanceof P,
          chained instanceof Promise
        ];
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-all-subclass",
        """
        var log = [];
        class P extends Promise {}
        var combined = P.all([Promise.resolve(1), 2]);
        log.push(combined instanceof P, combined instanceof Promise);
        combined.then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-all-subclass-species",
        """
        var log = [];
        class P extends Promise {
          static get [Symbol.species]() { return Promise; }
        }
        var combined = P.all([1]);
        log.push(combined instanceof P, combined instanceof Promise);
        combined.then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-return-finally",
        """
        var log = [];
        async function* g() {
          try {
            yield 1;
            yield 2;
          } finally {
            log.push('finally');
          }
        }
        (async function () {
          var iterator = g();
          log.push('first:' + (await iterator.next()).value);
          var result = await iterator.return('ret');
          log.push('ret:' + result.value + ':' + result.done);
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-throw-catch",
        """
        var log = [];
        async function* g() {
          try {
            yield 1;
          } catch (error) {
            yield 'caught:' + error.message;
          }
        }
        (async function () {
          var iterator = g();
          log.push('first:' + (await iterator.next()).value);
          var result = await iterator.throw(new Error('boom'));
          log.push('thrown:' + result.value + ':' + result.done);
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-for-await-break-close",
        """
        var log = [];
        async function* g() {
          try {
            yield 1;
            yield 2;
          } finally {
            log.push('finally');
          }
        }
        (async function () {
          for await (var value of g()) {
            log.push('value:' + value);
            break;
          }
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-yield-delegate-return",
        """
        var log = [];
        async function* inner() {
          try {
            yield 'a';
            yield 'b';
          } finally {
            log.push('inner finally');
          }
        }
        async function* outer() {
          var result = yield* inner();
          log.push('delegated:' + result);
        }
        (async function () {
          var iterator = outer();
          log.push('first:' + (await iterator.next()).value);
          await iterator.return('stop');
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-return-pending-await",
        """
        var log = [];
        var release;
        async function* g() {
          try {
            log.push('start');
            await new Promise(function (resolve) { release = resolve; });
            log.push('after await');
            yield 1;
          } finally {
            log.push('finally');
          }
        }
        (async function () {
          var iterator = g();
          var pending = iterator.next();
          log.push('next queued');
          await null;
          var returned = iterator.return('ret');
          log.push('return queued');
          release();
          var result = await returned;
          log.push('ret:' + result.value + ':' + result.done);
          await pending.catch(function () {});
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-generator-throw-pending-await",
        """
        var log = [];
        var release;
        async function* g() {
          try {
            await new Promise(function (resolve) { release = resolve; });
          } catch (error) {
            log.push('caught:' + error.message);
            yield 2;
          } finally {
            log.push('finally');
          }
        }
        (async function () {
          var iterator = g();
          iterator.next();
          await null;
          var thrown = iterator.throw(new Error('boom'));
          log.push('throw queued');
          release();
          try {
            var result = await thrown;
            log.push('result:' + result.value + ':' + result.done);
          } catch (error) {
            log.push('rejected:' + error.message);
          }
          globalThis.__diffResult = log;
        })();
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-getter-once",
        """
        var log = [];
        var source = {
          get [Symbol.asyncIterator]() {
            log.push('async-get');
            return function () {
              var index = 0;
              return {
                next: function () {
                  log.push('next');
                  return Promise.resolve(index < 2
                    ? { value: ++index, done: false }
                    : { done: true });
                }
              };
            };
          },
          get [Symbol.iterator]() {
            log.push('sync-get');
            return function () { return [1][Symbol.iterator](); };
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-sync-getter-once",
        """
        var log = [];
        var source = {
          get [Symbol.iterator]() {
            log.push('get');
            var index = 0;
            return function () {
              return {
                next: function () {
                  log.push('next');
                  return index < 2
                    ? { value: ++index, done: false }
                    : { done: true };
                }
              };
            };
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-sync-mapper-close",
        """
        var log = [];
        var source = {
          [Symbol.iterator]: function () {
            var index = 0;
            return {
              next: function () {
                log.push('next');
                return index < 3
                  ? { value: ++index, done: false }
                  : { done: true };
              },
              return: function () { log.push('return'); return { done: true }; }
            };
          }
        };
        Array.fromAsync(source, function (value) {
          if (value === 2) throw new Error('map');
          return value;
        }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-mapper-reject",
        """
        var log = [];
        var source = {
          [Symbol.asyncIterator]: function () {
            var index = 0;
            return {
              next: function () {
                log.push('next');
                return Promise.resolve(index < 2
                  ? { value: ++index, done: false }
                  : { done: true });
              },
              return: function () {
                log.push('return');
                return Promise.resolve({ done: true });
              }
            };
          }
        };
        Array.fromAsync(source, function (value) {
          return Promise.reject(new Error('async-map'));
        }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-array-like-length-throw",
        """
        var log = [];
        var source = {
          get length() {
            log.push('length');
            throw new Error('length-error');
          },
          get 0() {
            log.push('index0');
            return 'x';
          }
        };
        Array.fromAsync(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-array-like-length-once",
        """
        var log = [];
        var reads = 0;
        var source = {
          get length() {
            reads += 1;
            log.push('length:' + reads);
            return 2;
          },
          0: 'a',
          1: 'b'
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.join(','));
          log.push('reads:' + reads);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-array-like-index-order",
        """
        var log = [];
        var source = {
          length: 2,
          get 0() {
            log.push('index:0');
            return 'a';
          },
          get 1() {
            log.push('index:1');
            return 'b';
          }
        };
        Array.fromAsync(source).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-from-async-array-like-invalid-length",
        """
        var log = [];
        Array.fromAsync({ length: Infinity }).then(function () {
          log.push('ok');
        }, function (error) {
          log.push(error.name + ':' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-all-proxy-iterable",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            log.push('next');
            return this.index < 2
              ? { value: ++this.index, done: false }
              : { done: true };
          }
        };
        var target = {
          [Symbol.iterator]: function () {
            log.push('iterator');
            return iterator;
          }
        };
        var source = new Proxy(target, {
          get: function (object, key, receiver) {
            log.push('get:' + String(key));
            return Reflect.get(object, key, receiver);
          }
        });
        Promise.all(source).then(function (values) {
          log.push(values.join(','));
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-all-next-throw",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            log.push('next' + this.index);
            if (this.index++ === 1) throw new Error('next-failed');
            return { value: 1, done: false };
          },
          return: function () { log.push('return'); return { done: true }; }
        };
        var source = { [Symbol.iterator]: function () { return iterator; } };
        Promise.all(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-all-next-non-object",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            log.push('next' + this.index);
            if (this.index++ === 1) return 1;
            return { value: 1, done: false };
          },
          return: function () { log.push('return'); return { done: true }; }
        };
        var source = { [Symbol.iterator]: function () { return iterator; } };
        Promise.all(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.constructor.name);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-race-next-throw",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            log.push('next' + this.index);
            if (this.index++ === 1) throw new Error('race-next');
            return { value: 1, done: false };
          },
          return: function () { log.push('return'); return { done: true }; }
        };
        var source = { [Symbol.iterator]: function () { return iterator; } };
        Promise.race(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-any-next-throw",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            log.push('next' + this.index);
            if (this.index++ === 1) throw new Error('any-next');
            return { value: 1, done: false };
          },
          return: function () { log.push('return'); return { done: true }; }
        };
        var source = { [Symbol.iterator]: function () { return iterator; } };
        Promise.any(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-any-all-rejected",
        """
        var log = [];
        Promise.any([Promise.reject('a'), Promise.reject('b')]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push(error.name);
          log.push(error instanceof AggregateError);
          log.push(error.errors.join(','));
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-any-thenable-getter-throw",
        """
        var log = [];
        var thenable = {
          get then() {
            throw new Error('any-getter');
          }
        };
        Promise.any([thenable, Promise.reject('other')]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push(error.name);
          log.push(error.errors.length);
          log.push(error.errors[0].message);
          log.push(error.errors[1]);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-race-thenable-getter-throw",
        """
        var log = [];
        var thenable = {
          get then() {
            throw new Error('race-getter');
          }
        };
        Promise.race([thenable, Promise.resolve(2)]).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-then-non-callable",
        """
        var log = [];
        var value = { then: 1 };
        Promise.resolve(value).then(function (result) {
          log.push(result === value);
          log.push(typeof result.then);
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-combinators-empty",
        """
        var log = [];
        Promise.all([]).then(function (values) {
          log.push('all:' + values.length);
        });
        Promise.allSettled([]).then(function (values) {
          log.push('allSettled:' + values.length);
        });
        Promise.any([]).then(function () {
          log.push('any:ok');
        }, function (error) {
          log.push('any:' + error.name + ':' + error.errors.length);
        });
        Promise.race([]).then(function () {
          log.push('race:fulfilled');
        }, function () {
          log.push('race:rejected');
        });
        Promise.resolve().then(function () {
          return null;
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-promise-allsettled-order",
        """
        var log = [];
        var resolveFirst;
        var rejectSecond;
        var first = new Promise(function (resolve) {
          resolveFirst = resolve;
        });
        var second = new Promise(function (_, reject) {
          rejectSecond = reject;
        });
        Promise.allSettled([first, second]).then(function (results) {
          log.push(results.map(function (result) {
            return result.status + ':' +
              (result.status === 'fulfilled' ? result.value : result.reason);
          }).join('|'));
          globalThis.__diffResult = log;
        });
        Promise.resolve().then(function () {
          rejectSecond('bad');
        }).then(function () {
          resolveFirst('ok');
        });
        """.trimIndent(),
    ),
    AsyncDifferentialCase(
        "async-allsettled-next-throw",
        """
        var log = [];
        var iterator = {
          index: 0,
          next: function () {
            log.push('next' + this.index);
            if (this.index++ === 1) throw new Error('settled-next');
            return { value: Promise.resolve(1), done: false };
          },
          return: function () { log.push('return'); return { done: true }; }
        };
        var source = { [Symbol.iterator]: function () { return iterator; } };
        Promise.allSettled(source).then(function () {
          log.push('ok');
        }, function (error) {
          log.push('reject:' + error.message);
        }).then(function () {
          log.push('after');
          globalThis.__diffResult = log;
        });
        """.trimIndent(),
    ),
)
