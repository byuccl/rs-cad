package edu.byu.ece.rapidSmith.util

import java.io.Serializable
import java.lang.RuntimeException

data class Version(val major: Int, val minor: Int) : Serializable {
	operator fun compareTo(other: Version): Int {
		return compareBy<Version> { it.major }
			.thenComparingInt { it.minor }
			.compare(this, other)
	}

	override fun toString(): String {
		return "v$major.$minor"
	}
}

class VersionException : RuntimeException {
	constructor() : super()
	constructor(p0: String?) : super(p0)
	constructor(p0: String?, p1: Throwable?) : super(p0, p1)
	constructor(p0: Throwable?) : super(p0)
	constructor(p0: String?, p1: Throwable?, p2: Boolean, p3: Boolean) : super(p0, p1, p2, p3)
	constructor(actual: Version, expected: Version) : super("Expected version $expected, got $actual")
}
