package com.muedsa.js.runtime

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 通用的并发 FIFO：生产者可以来自任意宿主线程，状态通过标准库原子引用发布。
 * 两条不可变链分别保存队首与倒序队尾，只有队首耗尽时才翻转队尾，避免长 Promise
 * 链逐次复制整个队列。这里不执行用户代码，也不依赖平台锁或原生绑定。
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RuntimeQueue<T : Any> {
    private class Node<T>(val value: T, val next: Node<T>?)
    private class State<T>(val front: Node<T>?, val rear: Node<T>?, val size: Int)
    private val state = AtomicReference(State<T>(null, null, 0))

    val size: Int get() = state.load().size

    fun addLast(value: T) {
        while (true) {
            val old = state.load()
            if (state.compareAndSet(old, State(old.front, Node(value, old.rear), old.size + 1))) return
        }
    }

    fun removeFirstOrNull(): T? {
        while (true) {
            val old = state.load()
            if (old.size == 0) return null
            var front = old.front
            var rear = old.rear
            if (front == null) {
                while (rear != null) {
                    front = Node(rear.value, front)
                    rear = rear.next
                }
            }
            val first = front ?: return null
            if (state.compareAndSet(old, State(first.next, rear, old.size - 1))) return first.value
        }
    }

    fun clear() { state.store(State(null, null, 0)) }
}
