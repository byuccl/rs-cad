package edu.byu.ece.rapidSmith.util

import java.io.Serializable
import kotlin.math.max

/** Constant for Index(0, 0) */
val ZERO_INDEX = Index(0, 0)
/** Constant for Offset(0, 0) */
val ZERO_OFFSET = Offset(0, 0)
val DOWN = Offset(-1, 0)
val UP = Offset(1, 0)
val LEFT = Offset(0, -1)
val RIGHT = Offset(0, 1)

/**
 * Size of a grid as defined by the number of rows and columns in the grid.
 * @property rows number of rows in the grid
 * @property columns number of columns in the grid
 * @throws IllegalArgumentException if rows or columns is less than 0
 */
data class Dimensions(val rows: Int, val columns: Int) {
	init {
		require(rows >= 0) { "numRows ($rows) less than 0" }
		require(columns >= 0) { "columns($columns) less than 0" }
	}

	/** the total number of elements in a grid of this size */
	val numElements: Int get() = rows * columns
	
	/** Returns a rectangle based at (0,0) with this size */
	fun asRectangle() = Rectangle(ZERO_INDEX, Index(rows-1, columns-1))
	
	override fun toString(): String = "Size{$rows rows x $columns columns)}"
}

/**
 * An index in a grid.
 * @property row the row of the index
 * @property column the column of the index
 */
data class Index(val row: Int, val column: Int): Serializable {
	/**
	 * Returns the index of this index shifted by [offset]
	 */
	operator fun plus(offset: Offset) : Index =
		Index(row + offset.rows, column + offset.columns)

	/**
	 * Returns the index of this index reverse shifted by [offset]
	 */
	operator fun minus(offset: Offset) : Index =
		Index(row - offset.rows, column - offset.columns)

	/**
	 * Returns the offset between this index and [other].
	 */
	operator fun minus(other: Index): Offset =
		Offset(row - other.row, column - other.column)

	override fun toString() = "[$row, $column]"

	operator fun rangeTo(other: Index): Rectangle = Rectangle(this, other)


	fun until(other: Index): Rectangle = Rectangle(this, other - Offset(1, 1))


	fun toOffset(): Offset = Offset(this.row, this.column)
}

/**
 * A row, column offset from a grid index.
 */
data class Offset(val rows: Int, val columns: Int): Serializable {
	/** Returns the offset computed by adding this offset with [other]. */
	operator fun plus(other: Offset): Offset =
		Offset(rows + other.rows, columns + other.columns)
	
	/** Returns the index computed by shifting [index] by this offset. */
	operator fun plus(index: Index) = index.plus(this)
	
	/** Returns the offset computed by subtracting [other] from this offset. */
	operator fun minus(other: Offset): Offset =
		Offset(rows - other.rows, columns - other.columns)
	
	/**
	 * Returns the offset computed by multiplying this offset (both row and column)
	 * by [value].
	 * */
	operator fun times(value: Int) =
		Offset(rows * value, columns * value)

	/**
	 * Returns the offset computed by dividing this offset (both row and column)
	 * by [value].  Operation performed is integer division.
	 * */
	operator fun div(value: Int) =
		Offset(rows / value, columns / value)
	
	override fun toString() = "Offset($rows, $columns)"
}

operator fun Int.times(offset: Offset) = offset * this

/**
 * A rectangle shape describing a grid's shape.  The lower bound is inclusive
 * while the upper bound is exclusive.  This allows for zero width and/or height
 * rectangle to be described.
 * @property lower the lower bound index (inclusive) of the rectangle
 * @property upper the upper bound index (exclusive) of the rectangle
 */
data class Rectangle(val lower: Index, val upper: Index): Iterable<Index> {
	constructor(top: Int, left: Int, bottom: Int, right: Int) :
		this(Index(top, left), Index(bottom, right))

	/** The dimensions of this rectangle. */
	val dimensions: Dimensions
		get() = Dimensions(max(0, height), max(0, width))

	/** The height of this rectangle (in rows). */
	val height: Int get() = upper.row - lower.row + 1

	/** The width of this rectangle (in columns). */
	val width: Int get() = upper.column - lower.column + 1

	val numRows: Int get() = height
	val numColumns: Int get() = width

	val rows: IntRange get() = lower.row..upper.row
	val columns: IntRange get() = lower.column..upper.column

	/**
	 * Returns true if [index] exists inside this rectangle.
	 */
	operator fun contains(index: Index): Boolean = contains(index.row, index.column)

	fun contains(row: Int, column: Int): Boolean {
		return row in lower.row..upper.row &&
			column in lower.column..upper.column
	}

	override fun iterator(): Iterator<Index> {
		var row = lower.row
		var column = lower.column

		return object : Iterator<Index> {
			override fun hasNext(): Boolean {
				return row <= upper.row
			}

			override fun next(): Index {
				val index = Index(row, column)
				column += 1
				if (column > upper.column) {
					column = lower.column
					row += 1
				}
				return index
			}
		}
	}
}

/**
 * A read-only 2 dimensional grid.
 */
interface Grid<out T>: Iterable<T> {
	/**
	 * The size of this grid.
	 */
	val dimensions: Dimensions get() = rectangle.dimensions

	/**
	 * Returns true if this grid contains no elements.
	 */
	fun isEmpty() = dimensions.rows == 0 || dimensions.columns == 0

	/**
	 * The rectangle structure of this grid.  This is equivalent to
	 * `Rectangle(Index(0, 0), Index(grid.size.numRows-1, grid.size.columns-1))`.
	 */
	val rectangle: Rectangle

	/**
	 * The rectangle defining the bounds of a subgrid.  The bounds are relative to
	 * original parent grid.  For a top level grid, this value is equivalent to 
	 * [rectangle].
	 */
	val absolute: Rectangle

	/**
	 * Returns the element at [index].
	 */
	operator fun get(index: Index): T = get(index.row, index.column)
	operator fun get(row: Int, column: Int): T

	/**
	 * Shorthand for this.rectangle.lower.
	 */
	val lower: Index get() = rectangle.lower
	/**
	 * Shorthand for this.rectangle.upper.
	 */
	val upper: Index get() = rectangle.upper

	/**
	 * Returns the subgrid formed from the elements bound by [rectangle].  The
	 * rectangle is always relative to the indices in this grid, not its parent
	 * grid for subgrids.
	 * @param rectangle the rectangle bounding the subgrid
	 * @param extendBounds if true, allows for accessing elements outside the
	 *     subgrids bounds but within the parent grids bounds
	 * @throws IndexOutOfBoundsException if rectangle extends beyond the bounds of
	 *     this grid
	 */
	fun subgrid(rectangle: Rectangle, extendBounds: Boolean=false): Grid<T> {
		subgridRectangleCheck(rectangle, this.rectangle)
		return SubGrid(this, extendBounds, rectangle)
	}

	override fun iterator(): GridIterator<T> = BaseGridIterator(this)
}

/**
 * A 2-dimensional grid with support for updating elements.  This class does not
 * support resizing the grid.
 */
interface MutableGrid<T> : Grid<T>, MutableIterable<T> {
	/** Sets the element at [index] to [value]. */
	operator fun set(index: Index, value: T) {
		set(index.row, index.column, value)
	}

	/** Sets the element at [row]/[column] to [value]. */
	operator fun set(row: Int, column: Int, value: T)

	override fun subgrid(rectangle: Rectangle, extendBounds: Boolean): MutableGrid<T>{
		subgridRectangleCheck(rectangle, this.rectangle)
		return SubMutableGrid(this, extendBounds, rectangle)
	}

	override fun iterator(): MutableGridIterator<T> = BaseMutableGridIterator(this)
}

/**
 * An iterator over individual elements in a [Grid].
 */
interface GridIterator<out T> : Iterator<T> {
	/**
	 * Returns the index of the element that will be retrieved through a call to next.
	 * This method does not progress the iterator.
	 * @return the next index or null if the grid has reached the end.
	 */
	fun nextIndex(): Index?
}

/**
 * A MutableIterator over individual elements in a [Grid].  This method supports
 * updating the last value in the grid through the [set] method.
 */
interface MutableGridIterator<T> : GridIterator<T>, MutableIterator<T> {
	/**
	 * Sets the value of the last element accessed to [value].
	 * @throws IllegalStateException if the iterator is in an invalid state.
	 */
	fun set(value: T)

	/**
	 * Unsupported for grids.
	 * @throws UnsupportedOperationException
	 */
	override fun remove() {
		throw UnsupportedOperationException("invalid operation on grid")
	}
}

private open class BaseGridIterator<out T>(
	protected val grid: Grid<T>
) : GridIterator<T> {
	private var nextIndex: Index? =
		if (grid.isEmpty()) null else ZERO_INDEX
	protected var curIndex: Index? = null

	override fun hasNext(): Boolean = nextIndex != null

	override fun next(): T {
		if (!hasNext())
			throw NoSuchElementException()
		val newIndex = nextIndex!!
		this.curIndex = newIndex
		nextIndex = newIndex.increment()
		return grid[newIndex]
	}

	override fun nextIndex(): Index? = nextIndex

	/** Increments the index to the next location in the grid */
	protected open fun Index.increment(): Index? {
		var next = this + RIGHT
		if (next.column in grid.rectangle.columns)
			return next
		next = Index(next.row + 1, grid.rectangle.lower.column)
		if (next.row in grid.rectangle.rows)
			return next
		return null
	}
}

private class BaseMutableGridIterator<T>(
	mutableGrid: MutableGrid<T>
) : BaseGridIterator<T>(mutableGrid), MutableGridIterator<T> {
	val mutableMatrix get() = grid as MutableGrid<T>
	override fun set(value: T) {
		val curIndex = this.curIndex ?:
			throw IllegalStateException("next() not yet called")
		mutableMatrix[curIndex] = value
	}
}

private open class SubGrid<out T>(
	open val parent: Grid<T>,
	protected val extendBounds: Boolean,
	bounds: Rectangle
) : Grid<T> {
	val offset = bounds.lower.toOffset()
	override val rectangle: Rectangle = bounds.dimensions.asRectangle()
	override val absolute: Rectangle
		get() = Rectangle(ZERO_INDEX + offset, offset + rectangle.upper )

	override val dimensions get() = rectangle.dimensions

	override fun get(row: Int, column: Int): T {
		if (!extendBounds) rangeCheck(row, column, rectangle)
		val adjusted = adjustIndex(row, column)
		return parent[adjusted]
	}

	protected fun adjustIndex(row: Int, column: Int): Index =
		Index(offset.rows + row, offset.columns + column)

	protected fun adjustIndex(index: Index): Index =
		adjustIndex(index.row, index.column)

	override fun subgrid(rectangle: Rectangle, extendBounds: Boolean): Grid<T> {
		subgridRectangleCheck(rectangle, rectangle)
		val adjustedTL = adjustIndex(rectangle.lower)
		val adjustedBR = adjustIndex(rectangle.upper)
		val adjustedRect = Rectangle(adjustedTL, adjustedBR)
		return SubGrid(parent, extendBounds, adjustedRect)
	}

	override fun iterator(): GridIterator<T> = BaseGridIterator(this)
}

private open class SubMutableGrid<T>(
	override val parent: MutableGrid<T>,
	extendBounds: Boolean,
	rectangle: Rectangle
) : SubGrid<T>(parent, extendBounds, rectangle), MutableGrid<T> {
	override fun set(row: Int, column: Int, value: T) {
		if (!extendBounds) rangeCheck(row, column, rectangle)
		val adjusted = adjustIndex(row, column)
		parent[adjusted] = value
	}

	override fun subgrid(rectangle: Rectangle, extendBounds: Boolean): MutableGrid<T> {
		subgridRectangleCheck(rectangle, this.rectangle)
		val adjustedTL = adjustIndex(rectangle.lower)
		val adjustedBR = adjustIndex(rectangle.upper)
		val adjustedRect = Rectangle(adjustedTL, adjustedBR)
		return SubMutableGrid(parent, extendBounds, adjustedRect)
	}

	override fun iterator(): MutableGridIterator<T> = BaseMutableGridIterator(this)
}

private fun rangeCheck(row: Int, column: Int, rectangle: Rectangle) {
	if (Index(row ,column) !in rectangle)
		throw IndexOutOfBoundsException("Index: ${Index(row, column)}, Bounds: $rectangle")
}

private fun subgridRectangleCheck(new: Rectangle, old: Rectangle) {
	if (new.lower !in old)
		throw IndexOutOfBoundsException("Index: ${new.lower}, Bounds: $old")

	if (new.upper !in old)
		throw IndexOutOfBoundsException("Index: ${new.upper}, Bounds: $old")
}

private fun unflattenIndex(flat: Int, dimensions: Dimensions): Index {
	val row = flat / dimensions.columns
	val column = flat.rem(dimensions.columns)
	return Index(row, column)
}

private fun flattenIndex(row: Int, column: Int, dimensions: Dimensions): Int {
	return row * dimensions.columns + column
}

/**
 * A [Grid] implemented as a flattened array structure.
 */
class ArrayGrid<T>(
	override val dimensions: Dimensions,
	init: (Index) -> T
) : MutableGrid<T> {
	constructor(rows: Int, columns: Int, init: (Index) -> T)
		: this(Dimensions(rows, columns), init)

	@Suppress("USELESS_CAST")
	private val array: Array<Any?> =
		Array(dimensions.numElements) { init(unflattenIndex(it, dimensions)) as T }

	override val rectangle: Rectangle
		get() {
			val topLeft = Index(0, 0)
			val bottomRight = Index(dimensions.rows-1, dimensions.columns-1)
			return Rectangle(topLeft, bottomRight)
		}

	override val absolute: Rectangle
		get() = rectangle

	override operator fun get(row: Int, column: Int): T {
		rangeCheck(row, column, rectangle)
		@Suppress("UNCHECKED_CAST")
		return array[flattenIndex(row, column, dimensions)] as T
	}

	override operator fun set(row: Int, column: Int, value: T) {
		rangeCheck(row, column, rectangle)
		array[flattenIndex(row, column, dimensions)] = value
	}
}

fun <T> gridOf(): Grid<T> {
	return object : Grid<T> {
		override val rectangle: Rectangle
			get() = Rectangle(ZERO_INDEX, Index(-1, -1))

		override val absolute: Rectangle
			get() = rectangle

		override fun get(row: Int, column: Int): T {
			throw IndexOutOfBoundsException("${Index(row, column)}")
		}
	}
}

fun <T> gridOf(vararg rows: List<T>): Grid<T> {
	if (rows.isEmpty())
		return gridOf()
	val size = Dimensions(rows.lastIndex, rows[0].lastIndex)
	return ArrayGrid(size) {
		val row = rows[it.row]
		row[it.column]
	}
}
