package edu.byu.ece.rapidSmith.util

import java.util.*
import kotlin.collections.HashMap

/**
 *
 */
class StackedHashMap<K, V> : MutableMap<K, V> {
	private val stack = ArrayDeque<HashMap<K, V>>()
	private var current = HashMap<K, V>()

	fun checkPoint() {
		stack.push(current)
		current = HashMap(current)
	}

	fun rollBack() {
		current = stack.pop()
	}

	fun reset() {
		stack.clear()
		current.clear()
	}

	override val size: Int
		get() = current.size

	override fun isEmpty(): Boolean {
		return size == 0
	}

	override fun containsKey(key: K): Boolean {
		return current.containsKey(key)
	}

	override fun containsValue(value: V): Boolean {
		return current.containsValue(value)
	}

	override fun get(key: K): V? = current[key]

	override fun put(key: K, value: V): V? {
		return current.put(key, value)
	}

	override fun remove(key: K): V? {
		return current.remove(key)
	}

	override fun putAll(from: Map<out K, V>) {
		current.putAll(from)
	}

	override fun clear() {
		current.clear()
	}

	override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
		get() = current.entries
	override val keys: MutableSet<K>
		get() = current.keys
	override val values: MutableCollection<V>
		get() = current.values
}

