package edu.byu.ece.rapidSmith.util

fun <T, K, V> Sequence<T>.put(
	transform: (T) -> Pair<K, V>
): Map<K, V> {
	return this.map { transform(it) }
		.groupingBy { it.first }
		.aggregate { _, _, t, _ -> t.second }
}

fun <T, K, V> Sequence<T>.putTo(
	result: MutableMap<K, V>,
	transform: (T) -> Pair<K, V>
) {
	this.map { transform(it) }
		.groupingBy { it.first }
		.aggregateTo(result) { _, _, t, _ -> t.second }
}

fun <T, K, V> Iterable<T>.put(
	transform: (T) -> Pair<K, V>
): Map<K, V> {
	return this.map { transform(it) }
		.groupingBy { it.first }
		.aggregate { _, _, t, _ -> t.second }
}

fun <T, K, V> Iterable<T>.putTo(
	result: MutableMap<K, V>,
	transform: (T) -> Pair<K, V>
) {
	this.map { transform(it) }
		.groupingBy { it.first }
		.aggregateTo(result) { _, _, t, _ -> t.second }
}
