package com.muedsa.js.conformance

/**
 * S2 扩展的 L 系列差分用例：Generator、async generator、Proxy、Reflect、
 * TypedArray、ArrayBuffer/DataView 与 BigInt。
 *
 * 每条 expression 返回可 JSON 序列化的值；抛错时由运行器比较错误名。
 * 与 [extraDifferentialCases] 分文件维护，避免超大文件继续膨胀。
 *
 * 注意：`__detach(buffer)` 是差分运行器注入的宿主钩子，Node 侧用
 * `structuredClone(buffer, { transfer: [buffer] })` 实现，两侧语义一致。
 */
internal val lSeriesDifferentialCases: List<DifferentialCase> = listOf(
    // ================================================================
    // Generator：next/return/throw、yield*、finally、for...of
    // ================================================================
    DifferentialCase(
        "generator-next-sequence",
        """
        (function () {
          function* g(a) {
            var x = yield a + 1;
            yield x * 2;
            return 'done';
          }
          var it = g(1);
          var r1 = it.next();
          var r2 = it.next(10);
          var r3 = it.next();
          var r4 = it.next();
          return [
            r1.value,
            String(r1.done),
            r2.value,
            String(r2.done),
            r3.value,
            String(r3.done),
            String(r4.value),
            String(r4.done)
          ].join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-return-finally",
        """
        (function () {
          var log = [];
          function* g() {
            try {
              log.push('start');
              yield 1;
              log.push('after1');
              yield 2;
            } finally {
              log.push('finally');
            }
            log.push('end');
          }
          var it = g();
          log.push(it.next().value);
          log.push(it.return('ret').value);
          var after = it.next();
          log.push(String(after.done), String(after.value));
          return log.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-return-in-finally",
        """
        (function () {
          function* g() {
            try {
              yield 1;
            } finally {
              return 'override';
            }
          }
          var it = g();
          it.next();
          var r = it.return('outer');
          return r.value + '|' + String(r.done);
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-throw-catch",
        """
        (function () {
          var log = [];
          function* g() {
            try {
              yield 1;
            } catch (e) {
              log.push('caught ' + e);
              yield 2;
            } finally {
              log.push('finally');
            }
            log.push('end');
          }
          var it = g();
          log.push(it.next().value);
          log.push(it.throw('boom').value);
          var after = it.next();
          log.push(String(after.value) + ':' + String(after.done));
          log.push(String(it.next().done));
          return log.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-throw-uncaught",
        """
        (function () {
          var log = [];
          function* g() {
            try {
              yield 1;
            } finally {
              log.push('finally');
            }
          }
          var it = g();
          it.next();
          var name = '';
          try {
            it.throw('x');
          } catch (e) {
            name = typeof e === 'string' ? e : e.name;
          }
          log.push(name, String(it.next().done));
          return log.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-yield-delegate",
        """
        (function () {
          function* inner() {
            var v = yield 'a';
            yield 'b:' + v;
            return 'ret';
          }
          function* outer() {
            var r = yield* inner();
            yield 'got:' + r;
            yield* ['x', 'y'];
            return 'end';
          }
          var it = outer();
          var out = [];
          var r = it.next();
          out.push(r.value + ':' + String(r.done));
          r = it.next('V');
          out.push(r.value + ':' + String(r.done));
          r = it.next();
          out.push(r.value + ':' + String(r.done));
          r = it.next();
          out.push(r.value + ':' + String(r.done));
          r = it.next();
          out.push(r.value + ':' + String(r.done));
          r = it.next();
          out.push(r.value + ':' + String(r.done));
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-yield-delegate-throw",
        """
        (function () {
          var log = [];
          function* inner() {
            try {
              yield 1;
            } catch (e) {
              log.push('inner caught ' + e);
              yield 2;
            } finally {
              log.push('inner finally');
            }
          }
          function* outer() {
            yield* inner();
            log.push('outer after');
          }
          var it = outer();
          it.next();
          it.throw('T');
          it.next();
          return log.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-for-of",
        """
        (function () {
          function* g() {
            yield 1;
            yield 2;
            yield 3;
          }
          var out = [];
          for (var v of g()) {
            out.push(v);
            if (v === 2) break;
          }
          var collected = [];
          for (var x of g()) collected.push(x);
          return out.join(',') + '|' + collected.join(',');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-for-of-close-finally",
        """
        (function () {
          var log = [];
          function* g() {
            try {
              yield 1;
              yield 2;
            } finally {
              log.push('closed');
            }
          }
          for (var v of g()) {
            log.push('v' + v);
            break;
          }
          return log.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-iterator-protocol",
        """
        (function () {
          function* g() {}
          var it = g();
          var proto = Object.getPrototypeOf(it);
          return [
            typeof it.next,
            typeof it.return,
            typeof it.throw,
            it[Symbol.iterator]() === it,
            Object.prototype.toString.call(it),
            Object.getPrototypeOf(Object.getPrototypeOf(proto)) ===
              Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]())),
            proto[Symbol.toStringTag],
            String(it.next().done)
          ].join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-spread-destructuring",
        """
        (function () {
          function* g() {
            yield 1;
            yield 2;
            yield 3;
          }
          var [a, ...rest] = g();
          var arr = [...g()];
          var from = Array.from(g());
          return [a, rest.join(','), arr.length, from.join(',')].join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-reentry-done",
        """
        (function () {
          var log = [];
          function* g() {
            yield 1;
          }
          var it = g();
          log.push(it.next().value);
          log.push(String(it.next().done));
          log.push(String(it.next().done));
          var thrown = '';
          try {
            it.throw('x');
          } catch (e) {
            thrown = typeof e === 'string' ? e : e.name;
          }
          log.push(thrown);
          return log.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "generator-methods",
        """
        (function () {
          var obj = {
            *gen(n) {
              yield n;
              yield n + 1;
            }
          };
          class C {
            *values() {
              yield 'c';
            }
            static *statics() {
              yield 's';
            }
          }
          var out = [];
          for (var v of obj.gen(1)) out.push(v);
          for (var v of new C().values()) out.push(v);
          for (var v of C.statics()) out.push(v);
          return out.join(',');
        })()
        """.trimIndent()
    ),

    // ================================================================
    // Proxy：13 个 trap、不变量、revocable
    // ================================================================
    DifferentialCase(
        "proxy-13-traps",
        """
        (function () {
          var seen = {};
          function mark(name) {
            seen[name] = true;
          }
          var handler = {
            get: function (t, k, r) {
              mark('get');
              return Reflect.get(t, k, r);
            },
            set: function (t, k, v, r) {
              mark('set');
              return Reflect.set(t, k, v, r);
            },
            has: function (t, k) {
              mark('has');
              return Reflect.has(t, k);
            },
            deleteProperty: function (t, k) {
              mark('deleteProperty');
              return Reflect.deleteProperty(t, k);
            },
            ownKeys: function (t) {
              mark('ownKeys');
              return Reflect.ownKeys(t);
            },
            getOwnPropertyDescriptor: function (t, k) {
              mark('getOwnPropertyDescriptor');
              return Reflect.getOwnPropertyDescriptor(t, k);
            },
            defineProperty: function (t, k, d) {
              mark('defineProperty');
              return Reflect.defineProperty(t, k, d);
            },
            getPrototypeOf: function (t) {
              mark('getPrototypeOf');
              return Reflect.getPrototypeOf(t);
            },
            setPrototypeOf: function (t, p) {
              mark('setPrototypeOf');
              return Reflect.setPrototypeOf(t, p);
            },
            isExtensible: function (t) {
              mark('isExtensible');
              return Reflect.isExtensible(t);
            },
            preventExtensions: function (t) {
              mark('preventExtensions');
              Reflect.preventExtensions(t);
              return true;
            },
            apply: function (t, thisArg, args) {
              mark('apply');
              return Reflect.apply(t, thisArg, args);
            },
            construct: function (t, args, newTarget) {
              mark('construct');
              return Reflect.construct(t, args, newTarget);
            }
          };
          var p = new Proxy({ a: 1 }, handler);
          var got = p.a;
          p.b = 2;
          var hasA = 'a' in p;
          var deleted = delete p.b;
          var keys = Object.keys(p).join(',');
          var desc = Object.getOwnPropertyDescriptor(p, 'a');
          Object.defineProperty(p, 'c', { value: 3, configurable: true });
          var proto = Object.getPrototypeOf(p);
          Object.setPrototypeOf(p, null);
          var ext = Object.isExtensible(p);
          function fn(a, b) {
            this.sum = a + b;
            return a + b;
          }
          var fp = new Proxy(fn, handler);
          var applied = fp(1, 2);
          var made = new fp(3, 4);
          Object.preventExtensions(p);
          var names = Object.keys(seen).sort().join(',');
          return [
            names,
            got,
            hasA,
            deleted,
            keys,
            desc.value,
            ext,
            applied,
            made.sum,
            proto === Object.prototype
          ].join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "proxy-get-set-receiver",
        """
        (function () {
          var log = [];
          var target = { _v: 1 };
          var p = new Proxy(target, {
            get: function (t, k, r) {
              log.push('get:' + String(k) + ':' + (r === p));
              return Reflect.get(t, k, r);
            },
            set: function (t, k, v, r) {
              log.push('set:' + String(k) + ':' + v + ':' + (r === p));
              return Reflect.set(t, k, v, r);
            }
          });
          p.x = 5;
          var got = p._v;
          return log.join('|') + '#' + got + '#' + target.x;
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "proxy-invariants-get",
        """
        (function () {
          var out = [];
          var target = {};
          Object.defineProperty(target, 'ro', { value: 1, writable: false, configurable: false });
          var p = new Proxy(target, {
            get: function () {
              return 2;
            }
          });
          try {
            p.ro;
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          var p2 = new Proxy(target, {
            getOwnPropertyDescriptor: function () {
              return undefined;
            }
          });
          try {
            Object.getOwnPropertyDescriptor(p2, 'ro');
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          var t4 = {};
          Object.defineProperty(t4, 'x', { value: 1, configurable: false });
          var p4 = new Proxy(t4, {
            has: function () {
              return false;
            }
          });
          try {
            'x' in p4;
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "proxy-invariants-ownKeys",
        """
        (function () {
          var out = [];
          var target = {};
          Object.defineProperty(target, 'fixed', { value: 1, configurable: false });
          var p = new Proxy(target, {
            ownKeys: function () {
              return [];
            }
          });
          try {
            Object.keys(p);
            out.push('keys-ok');
          } catch (e) {
            out.push('keys:' + e.name);
          }
          try {
            Reflect.ownKeys(p);
            out.push('reflect-ok');
          } catch (e) {
            out.push('reflect:' + e.name);
          }
          var t2 = { a: 1 };
          var p2 = new Proxy(t2, {
            ownKeys: function () {
              return ['a', 'a'];
            }
          });
          try {
            Reflect.ownKeys(p2);
            out.push('dup-ok');
          } catch (e) {
            out.push('dup:' + e.name);
          }
          var t3 = { a: 1, b: 2 };
          var p3 = new Proxy(t3, {
            getOwnPropertyDescriptor: function () {
              return undefined;
            }
          });
          try {
            Object.keys(p3);
            out.push('gopd-ok');
          } catch (e) {
            out.push('gopd:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "proxy-invariants-set-define",
        """
        (function () {
          var out = [];
          var target = {};
          Object.defineProperty(target, 'ro', { value: 1, writable: false, configurable: false });
          var p = new Proxy(target, {
            set: function () {
              return true;
            }
          });
          try {
            p.ro = 2;
            out.push('set-ok');
          } catch (e) {
            out.push('set:' + e.name);
          }
          var pd = new Proxy(target, {
            defineProperty: function () {
              return true;
            }
          });
          try {
            Object.defineProperty(pd, 'ro', { value: 3 });
            out.push('define-ok');
          } catch (e) {
            out.push('define:' + e.name);
          }
          var t2 = { x: 1 };
          var p2 = new Proxy(t2, {
            getOwnPropertyDescriptor: function () {
              return { value: 1, configurable: false, writable: true, enumerable: true };
            }
          });
          try {
            Object.defineProperty(p2, 'x', { value: 2 });
            out.push('define2-ok');
          } catch (e) {
            out.push('define2:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "proxy-revocable",
        """
        (function () {
          var out = [];
          var r = Proxy.revocable(
            { a: 1 },
            {
              get: function (t, k) {
                return 'g:' + k;
              }
            }
          );
          out.push(r.proxy.a);
          r.revoke();
          var ops = [
            function () {
              return r.proxy.a;
            },
            function () {
              return 'a' in r.proxy;
            },
            function () {
              r.proxy.a = 1;
            },
            function () {
              delete r.proxy.a;
            },
            function () {
              return Object.keys(r.proxy);
            },
            function () {
              return Object.getPrototypeOf(r.proxy);
            },
            function () {
              return Object.isExtensible(r.proxy);
            },
            function () {
              Object.preventExtensions(r.proxy);
            },
            function () {
              Object.defineProperty(r.proxy, 'a', { value: 1 });
            },
            function () {
              return Object.getOwnPropertyDescriptor(r.proxy, 'a');
            }
          ];
          for (var i = 0; i < ops.length; i++) {
            try {
              ops[i]();
              out.push('ok');
            } catch (e) {
              out.push(e.name);
            }
          }
          r.revoke();
          out.push('after-second-revoke');
          out.push(typeof r.proxy);
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "proxy-apply-construct",
        """
        (function () {
          var log = [];
          function target(a, b) {
            return { sum: a + b, thisValue: this };
          }
          var p = new Proxy(target, {
            apply: function (t, thisArg, args) {
              log.push('apply:' + args.join(',') + ':' + (thisArg && thisArg.tag));
              return Reflect.apply(t, thisArg, args).sum;
            },
            construct: function (t, args, newTarget) {
              log.push('construct:' + args.join(',') + ':' + (newTarget === p));
              return { made: args[0] * 2 };
            }
          });
          var r1 = p.call({ tag: 'ctx' }, 1, 2);
          var r2 = new p(3);
          var r3 = p(4, 5);
          return [r1, r2.made, r3, log.join('|'), p.name, p.length].join('#');
        })()
        """.trimIndent()
    ),

    // ================================================================
    // Reflect：13 个方法、receiver、失败返回 false
    // ================================================================
    DifferentialCase(
        "reflect-13-methods",
        """
        (function () {
          var f = function (a, b) {
            return this.base + a + b;
          };
          var C = function (v) {
            this.v = v;
          };
          C.prototype.m = function () {
            return this.v;
          };
          var obj = { a: 1 };
          var out = [];
          out.push(Reflect.apply(f, { base: 10 }, [1, 2]));
          var inst = Reflect.construct(C, [7]);
          out.push(inst.v, inst instanceof C, Object.getPrototypeOf(inst) === C.prototype);
          out.push(
            Reflect.defineProperty(obj, 'b', {
              value: 2,
              configurable: true,
              writable: true,
              enumerable: true
            })
          );
          out.push(Reflect.deleteProperty(obj, 'b'));
          out.push(Reflect.get(obj, 'a'));
          var d = Reflect.getOwnPropertyDescriptor(obj, 'a');
          out.push(d.value, d.writable, d.enumerable, d.configurable);
          out.push(Reflect.getPrototypeOf(obj) === Object.prototype);
          out.push(Reflect.has(obj, 'a'));
          out.push(Reflect.isExtensible(obj));
          out.push(Reflect.ownKeys({ x: 1 }).join(','));
          out.push(Reflect.setPrototypeOf(obj, null));
          out.push(Reflect.getPrototypeOf(obj) === null);
          out.push(Reflect.preventExtensions(obj));
          out.push(Reflect.isExtensible(obj));
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "reflect-construct-newtarget",
        """
        (function () {
          function A() {
            this.a = 1;
          }
          function B() {
            this.b = 2;
          }
          var obj = Reflect.construct(A, [], B);
          var out = [];
          out.push(
            obj.a,
            String(obj.b),
            Object.getPrototypeOf(obj) === B.prototype,
            obj instanceof B,
            obj instanceof A
          );
          function D() {
            return { d: 1 };
          }
          var d = Reflect.construct(D, []);
          out.push(d.d);
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "reflect-get-set-receiver",
        """
        (function () {
          var log = [];
          var proto = {
            get v() {
              return this.tag + ':get';
            },
            set v(x) {
              log.push('set ' + x + ' ' + this.tag);
            }
          };
          var obj = Object.create(proto);
          obj.tag = 'T';
          var receiver = { tag: 'R' };
          var out = [];
          out.push(Reflect.get(obj, 'v'));
          out.push(Reflect.get(obj, 'v', receiver));
          out.push(Reflect.set(obj, 'v', 1));
          out.push(Reflect.set(obj, 'v', 2, receiver));
          out.push(obj.hasOwnProperty('v'), receiver.hasOwnProperty('v'));
          return out.join('|') + '#' + log.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "reflect-failure-returns-false",
        """
        (function () {
          var out = [];
          var frozen = Object.freeze({ a: 1 });
          out.push(Reflect.set(frozen, 'a', 2));
          out.push(Reflect.deleteProperty(frozen, 'a'));
          out.push(Reflect.defineProperty(frozen, 'a', { value: 2 }));
          out.push(Reflect.setPrototypeOf(frozen, null));
          var nonExt = Object.preventExtensions({});
          out.push(Reflect.defineProperty(nonExt, 'x', { value: 1 }));
          out.push(Reflect.setPrototypeOf(nonExt, null));
          try {
            Reflect.get(1, 'a');
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            Reflect.ownKeys(null);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            Reflect.apply(function () {}, null, {});
            out.push('apply-ok');
          } catch (e) {
            out.push('apply:' + e.name);
          }
          try {
            Reflect.construct(function () {}, {});
            out.push('construct-ok');
          } catch (e) {
            out.push('construct:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "reflect-defineProperty-descriptor",
        """
        (function () {
          var obj = {};
          var out = [];
          out.push(
            Reflect.defineProperty(obj, 'a', {
              get: function () {
                return 1;
              },
              configurable: true
            })
          );
          out.push(obj.a);
          out.push(Reflect.defineProperty(obj, 'a', { value: 2 }));
          var d = Reflect.getOwnPropertyDescriptor(obj, 'a');
          out.push(
            d.value,
            d.writable,
            d.enumerable,
            d.configurable,
            d.get === undefined,
            d.set === undefined
          );
          try {
            Reflect.defineProperty(obj, 'b');
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            Reflect.defineProperty(obj, 'b', 1);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),

    // ================================================================
    // TypedArray：11 种、越界、set/subarray/slice、BigInt 视图
    // ================================================================
    DifferentialCase(
        "typedarray-kinds",
        """
        (function () {
          var names = [
            'Int8Array',
            'Uint8Array',
            'Uint8ClampedArray',
            'Int16Array',
            'Uint16Array',
            'Int32Array',
            'Uint32Array',
            'Float32Array',
            'Float64Array',
            'BigInt64Array',
            'BigUint64Array'
          ];
          return names
            .map(function (n) {
              var C = globalThis[n];
              var t = new C(2);
              return (
                n +
                ':' +
                C.BYTES_PER_ELEMENT +
                ':' +
                (t.BYTES_PER_ELEMENT === C.BYTES_PER_ELEMENT) +
                ':' +
                t.length +
                ':' +
                t.byteLength +
                ':' +
                Object.prototype.toString.call(t)
              );
            })
            .join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "typedarray-coercion",
        """
        (function () {
          var out = [];
          var u8 = new Uint8Array([256, -1, 1.9, NaN, Infinity, '7']);
          out.push(Array.prototype.join.call(u8, ','));
          var i8 = new Int8Array([127, 128, -129, 255]);
          out.push(Array.prototype.join.call(i8, ','));
          var clamped = new Uint8ClampedArray([-1, 0.5, 1.5, 2.5, 255.5, 300, NaN]);
          out.push(Array.prototype.join.call(clamped, ','));
          var f32 = new Float32Array([1 / 3, 1e40, -0, NaN]);
          out.push(f32[0], f32[1], Object.is(f32[2], -0), Number.isNaN(f32[3]));
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "typedarray-bounds",
        """
        (function () {
          var a = new Uint8Array(2);
          a[0] = 1;
          a[2] = 9;
          a[-1] = 9;
          a[1.5] = 9;
          var out = [];
          out.push(
            a[0],
            String(a[2]),
            String(a[-1]),
            a.length,
            a.hasOwnProperty('0'),
            a.hasOwnProperty('2'),
            a.hasOwnProperty('-1'),
            a.hasOwnProperty('1.5')
          );
          out.push(2 in a, '-1' in a, delete a[0], a[0]);
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "typedarray-set-subarray-slice",
        """
        (function () {
          var buf = new ArrayBuffer(8);
          var view = new Uint8Array(buf);
          var out = [];
          view.set([1, 2, 3]);
          view.set([9, 9], 1);
          out.push(view.join(','));
          var sub = view.subarray(1, 4);
          out.push(sub.length, sub.byteOffset, sub.buffer === buf, sub.join(','));
          sub[0] = 7;
          out.push(view.join(','));
          var copy = view.slice(1, 4);
          copy[0] = 5;
          out.push(view.join(','), copy.join(','), copy.buffer === buf);
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "typedarray-methods",
        """
        (function () {
          var a = new Int16Array([3, 1, 2]);
          var out = [];
          out.push(
            a
              .map(function (x) {
                return x * 2;
              })
              .join(',')
          );
          out.push(
            a
              .filter(function (x) {
                return x > 1;
              })
              .join(',')
          );
          out.push(
            a.reduce(function (s, x) {
              return s + x;
            }, 0)
          );
          out.push(
            Array.from(a.keys()).join(','),
            Array.from(a.values()).join(','),
            Array.from(a.entries())
              .map(function (e) {
                return e.join(':');
              })
              .join(',')
          );
          out.push(
            a.indexOf(2),
            a.includes(3),
            a.find(function (x) {
              return x > 1;
            }),
            a.findIndex(function (x) {
              return x > 1;
            })
          );
          a.sort();
          out.push(a.join(','));
          a.reverse();
          out.push(a.join(','));
          out.push(a.slice(1).join(','), a.subarray(1).join(','));
          out.push(
            a.every(function (x) {
              return x > 0;
            }),
            a.some(function (x) {
              return x === 3;
            })
          );
          out.push(a.join('-'), a.toString());
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "typedarray-of-from",
        """
        (function () {
          var out = [];
          out.push(Uint8Array.from([1, 2, 3]).join(','));
          out.push(Uint8Array.from({ length: 2, 0: '5', 1: '6' }).join(','));
          out.push(Uint8Array.of(1, 2, 300).join(','));
          out.push(
            Float64Array.from([1, 2], function (x) {
              return x / 4;
            }).join(',')
          );
          var a = new Uint8Array([1, 2]);
          var b = new Uint8Array(a);
          out.push(b.join(','), b !== a);
          var c = new Uint8Array(a.buffer, 1, 1);
          out.push(c.length, c[0]);
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "bigint-typedarray",
        """
        (function () {
          var out = [];
          var a = new BigInt64Array([1n, -2n, 9223372036854775807n]);
          out.push(a[0], a[1], a[2], a.length, a.BYTES_PER_ELEMENT);
          var u = new BigUint64Array(1);
          u[0] = -1n;
          out.push(u[0], String(u[0]));
          out.push(BigInt64Array.from([3n, 4n]).join(','));
          var buf = new ArrayBuffer(16);
          var v = new BigInt64Array(buf, 8, 1);
          v[0] = 42n;
          out.push(v.length, v.byteOffset, new DataView(buf).getBigInt64(8, true));
          try {
            a[0] = 1;
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            new BigInt64Array([1]);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),

    // ================================================================
    // ArrayBuffer / DataView：大小端、Float32 舍入与 detach
    // ================================================================
    DifferentialCase(
        "arraybuffer-basics",
        """
        (function () {
          var buf = new ArrayBuffer(16);
          var out = [];
          out.push(
            buf.byteLength,
            ArrayBuffer.isView(buf),
            ArrayBuffer.isView(new Uint8Array(1)),
            ArrayBuffer.isView({})
          );
          var s = buf.slice(2, 6);
          out.push(s.byteLength, s === buf);
          try {
            new ArrayBuffer(-1);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            new ArrayBuffer(2.5);
            out.push('ok');
          } catch (e) {
            out.push(e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "dataview-endian",
        """
        (function () {
          var buf = new ArrayBuffer(16);
          var dv = new DataView(buf);
          dv.setUint16(0, 0x1234);
          dv.setUint16(2, 0x1234, true);
          var out = [
            dv.getUint8(0).toString(16),
            dv.getUint8(1).toString(16),
            dv.getUint8(2).toString(16),
            dv.getUint8(3).toString(16)
          ];
          dv.setInt32(4, -123456789);
          out.push(dv.getInt32(4), dv.getInt32(4, true));
          dv.setFloat64(8, Math.PI, true);
          out.push(dv.getFloat64(8, true), dv.getFloat64(8));
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "dataview-types",
        """
        (function () {
          var dv = new DataView(new ArrayBuffer(8));
          var out = [];
          dv.setInt8(0, -1);
          out.push(dv.getUint8(0), dv.getInt8(0));
          dv.setUint8(1, 255);
          out.push(dv.getInt8(1));
          dv.setInt16(2, -2, true);
          out.push(dv.getUint16(2, true), dv.getInt16(2, true));
          dv.setFloat32(4, 1 / 3, true);
          out.push(dv.getFloat32(4, true));
          dv.setBigInt64(0, -1n, true);
          out.push(dv.getBigUint64(0, true), dv.getBigInt64(0, true));
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "dataview-bounds",
        """
        (function () {
          var dv = new DataView(new ArrayBuffer(2));
          var out = [];
          try {
            dv.getUint32(0);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            dv.getUint8(-1);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            dv.setUint8(2, 1);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            new DataView(new ArrayBuffer(2), 3);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            dv.setBigInt64(0, 1);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            dv.setInt8(0, 1n);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "float32-rounding",
        """
        (function () {
          var values = [
            0.1,
            1 / 3,
            1e-45,
            3.4028235e38,
            3.4028236e38,
            1.0000000596046448,
            1.0000001788139343,
            16777217
          ];
          var f = new Float32Array(values.length);
          for (var i = 0; i < values.length; i++) f[i] = values[i];
          var dv = new DataView(new ArrayBuffer(4));
          var bits = [];
          for (var i = 0; i < f.length; i++) {
            dv.setFloat32(0, f[i]);
            bits.push(dv.getUint32(0).toString(16));
          }
          return bits.join(',') + '#' + f.join(',');
        })()
        """.trimIndent()
    ),
    // 注意：Node v24 在 ArrayBuffer 分离后访问 DataView.buffer/byteOffset/byteLength
    // 会抛 TypeError，而当前 ECMA-262（IsViewOutOfBounds）与 Test262 期望 buffer 原样返回、
    // byteOffset/byteLength 返回 0；这是 Node 与规范的差异，因此差分只比较双方一致的部分
    //（视图长度、元素读取、slice/构造抛错、DataView 读写方法抛错），访问器语义由
    // ArrayBufferDetachTest 按规范单独覆盖。
    DifferentialCase(
        "arraybuffer-detach-basics",
        """
        (function () {
          var buf = new ArrayBuffer(8);
          var view = new Uint8Array(buf, 2, 4);
          var dv = new DataView(buf, 1, 2);
          var before = [view.length, view.byteLength, view.byteOffset, dv.byteLength, dv.byteOffset].join(
            ','
          );
          __detach(buf);
          var out = [before, buf.byteLength, view.length, view.byteLength, view.byteOffset];
          out.push(String(view[0]), ArrayBuffer.isView(view));
          try {
            dv.getUint8(0);
            out.push('dv-get-ok');
          } catch (e) {
            out.push('dv-get:' + e.name);
          }
          try {
            buf.slice(0);
            out.push('slice-ok');
          } catch (e) {
            out.push('slice:' + e.name);
          }
          try {
            new Uint8Array(buf);
            out.push('ctor-ok');
          } catch (e) {
            out.push('ctor:' + e.name);
          }
          try {
            new DataView(buf);
            out.push('dv-ctor-ok');
          } catch (e) {
            out.push('dv-ctor:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "arraybuffer-detach-typedarray-ops",
        """
        (function () {
          var buf = new ArrayBuffer(8);
          var a = new Uint8Array(buf);
          a[0] = 1;
          __detach(buf);
          var out = [];
          a[1] = 9;
          out.push(String(a[0]), Object.keys(a).length, a.hasOwnProperty('0'));
          try {
            a.fill(1);
            out.push('fill-ok');
          } catch (e) {
            out.push('fill:' + e.name);
          }
          try {
            a.slice(0);
            out.push('slice-ok');
          } catch (e) {
            out.push('slice:' + e.name);
          }
          try {
            a.set([1]);
            out.push('set-ok');
          } catch (e) {
            out.push('set:' + e.name);
          }
          try {
            a.join(',');
            out.push('join-ok');
          } catch (e) {
            out.push('join:' + e.name);
          }
          try {
            for (var v of a) out.push('iter ' + v);
            out.push('iter-ok');
          } catch (e) {
            out.push('iter:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "dataview-detach",
        """
        (function () {
          var buf = new ArrayBuffer(4);
          var dv = new DataView(buf);
          dv.setUint8(0, 7);
          var before = dv.byteLength + ':' + dv.byteOffset;
          __detach(buf);
          var out = [before];
          try {
            dv.getUint8(0);
            out.push('get-ok');
          } catch (e) {
            out.push('get:' + e.name);
          }
          try {
            dv.setUint8(0, 1);
            out.push('set-ok');
          } catch (e) {
            out.push('set:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),

    // ================================================================
    // BigInt：算术、位运算、转换、比较与错误
    // ================================================================
    DifferentialCase(
        "bigint-arithmetic",
        """
        (function () {
          var out = [];
          out.push(
            String(1n + 2n),
            String(5n - 8n),
            String(6n * 7n),
            String(7n / 2n),
            String(-7n / 2n),
            String(7n % 3n),
            String(-7n % 3n)
          );
          out.push(String(2n ** 10n), String((-2n) ** 3n), String(-(-5n)));
          out.push(String(0x1fn), String(0b101n), String(0o17n));
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "bigint-bitwise",
        """
        (function () {
          var out = [];
          out.push(String(5n & 3n), String(5n | 3n), String(5n ^ 3n), String(~5n));
          out.push(String(1n << 10n), String(1024n >> 3n), String(-8n >> 1n));
          try {
            -1n >>> 0n;
            out.push('no-throw');
          } catch (e) {
            out.push('ushr:' + e.name);
          }
          try {
            1n << 1;
            out.push('no-throw');
          } catch (e) {
            out.push('shift:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "bigint-conversion",
        """
        (function () {
          var out = [];
          out.push(
            typeof 1n,
            Object.prototype.toString.call(1n),
            String(123n),
            255n.toString(16),
            255n.toString(2)
          );
          out.push(BigInt('0x10'), BigInt(42), BigInt(true), BigInt(false));
          out.push(Number(9007199254740993n), Number(1n));
          out.push(
            String(BigInt.asIntN(8, 255n)),
            String(BigInt.asUintN(8, -1n)),
            String(BigInt.asIntN(64, -1n))
          );
          try {
            BigInt(1.5);
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            BigInt('1.5');
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            Number(1) + 1n;
            out.push('no-throw');
          } catch (e) {
            out.push(e.name);
          }
          try {
            JSON.stringify(1n);
            out.push('json-ok');
          } catch (e) {
            out.push('json:' + e.name);
          }
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "bigint-comparison",
        """
        (function () {
          var out = [];
          out.push(1n == 1, 1n === 1, 0n == false, 1n < 2, 2n > 1, 1n <= 1n, 1n >= 2n);
          out.push(1n < 1.5, 1.5 < 2n, 9007199254740993n > 9007199254740992);
          var arr = [3n, 1n, 2n];
          arr.sort(function (a, b) {
            return a < b ? -1 : a > b ? 1 : 0;
          });
          out.push(arr.join(','));
          return out.join('|');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "bigint-errors",
        """
        (function () {
          var out = [];
          var fns = [
            function () {
              return 1n / 0n;
            },
            function () {
              return 1n % 0n;
            },
            function () {
              return 1n + 1;
            },
            function () {
              return 1n * 2;
            },
            function () {
              return 2n ** -1n;
            },
            function () {
              return 1n << 1;
            },
            function () {
              return 1n >>> 1n;
            },
            function () {
              return new BigInt(1);
            },
            function () {
              return BigInt(Symbol('x'));
            }
          ];
          for (var i = 0; i < fns.length; i++) {
            try {
              fns[i]();
              out.push('ok');
            } catch (e) {
              out.push(e.name);
            }
          }
          out.push(typeof Object(1n), Object(1n).valueOf() === 1n);
          return out.join('|');
        })()
        """.trimIndent()
    ),
)
