package ca.uwaterloo.flix.runtime.datastore

import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.runtime.{Solver, Value}
import ca.uwaterloo.flix.util.BitOps

import scala.collection.mutable

/**
 * A class that stores a relation in an indexed database. An index is a subset of the columns encoded in binary.
 *
 * An index on the first column corresponds to 0b0000...0001.
 * An index on the first and third columns corresponds to 0b0000...0101.
 *
 * @param relation the relation.
 * @param indexes the indexes.
 * @param default the default index.
 */
final class IndexedRelation(relation: TypedAst.Collection.Relation, indexes: Set[Int], default: Int)(implicit sCtx: Solver.SolverContext) extends IndexedCollection {

  /**
   * A map from indexes to keys to rows of values.
   */
  private val store = mutable.Map.empty[Int, mutable.Map[Key, mutable.Set[Array[Value]]]]

  /**
   * Records the number of indexed lookups, i.e. exact lookups.
   */
  private var indexedLookups = 0

  /**
   * Records the number of indexed scans, i.e. table scans which can use an index.
   */
  private var indexedScans = 0

  /**
   * Records the number of full scans, i.e. table scans which cannot use an index.
   */
  private var fullScans = 0

  /**
   * Initialize the store for all indexes.
   */
  for (idx <- indexes) {
    store(idx) = mutable.Map.empty
  }

  /**
   * Returns the size of the relation.
   */
  def getSize: Int = scan.size

  /**
   * Returns the number of indexed lookups.
   */
  def getNumberOfIndexedLookups: Int = indexedLookups

  /**
   * Returns the number of indexed scans.
   */
  def getNumberOfIndexedScans: Int = indexedScans

  /**
   * Returns the number of full scans.
   */
  def getNumberOfFullScans: Int = fullScans

  /**
   * Processes a new inferred `fact`.
   *
   * Adds the fact to the relation. All entries in the fact must be non-null.
   *
   * Returns `true` iff the fact did not already exist in the relation.
   */
  def inferredFact(fact: Array[Value]): Boolean = {
    if (lookup(fact).isEmpty) {
      newFact(fact)
      return true
    }
    false
  }

  /**
   * Updates all indexes and tables with a new `fact`.
   */
  private def newFact(fact: Array[Value]): Unit = {
    // loop through all the indexes and update the tables.
    for (idx <- indexes) {
      val key = keyOf(idx, fact)
      val table = store(idx).getOrElseUpdate(key, mutable.Set.empty[Array[Value]])
      table += fact
    }
  }

  /**
   * Performs a lookup of the given pattern `pat`. 
   *
   * If the pattern contains `null` entries these are interpreted as free variables.
   *
   * Returns an iterator over the matching rows.
   */
  def lookup(pat: Array[Value]): Iterator[Array[Value]] = {
    // case 1: Check if there is an exact index.
    var idx = getExactIndex(indexes, pat)
    if (idx != 0) {
      // an exact index exists. Use it.
      indexedLookups += 1
      val key = keyOf(idx, pat)
      store(idx).getOrElseUpdate(key, mutable.Set.empty).iterator
    } else {
      // case 2: No exact index available. Check if there is an approximate index.
      idx = getApproximateIndex(indexes, pat)
      val table = if (idx != 0) {
        // case 2.1: An approximate index exists. Use it.
        indexedScans += 1
        val key = keyOf(idx, pat)
        store(idx).getOrElseUpdate(key, mutable.Set.empty).iterator
      } else {
        // case 2.2: No usable index. Perform a full table scan.
        fullScans += 1
        scan
      }

      // filter rows returned by a partial index or table scan.
      table filter {
        case row => matchRow(pat, row)
      }
    }
  }

  /**
   * Returns all rows in the relation using a table scan.
   */
  def scan: Iterator[Array[Value]] = store(default).iterator.flatMap {
    case (key, value) => value
  }

  /**
   * Returns `true` if the given pattern `pat` matches the given `row`.
   *
   * A pattern matches if all is non-null entries are equal to the row.
   */
  private def matchRow(pat: Array[Value], row: Array[Value]): Boolean = {
    var i = 0
    while (i < pat.length) {
      val pv = pat(i)
      if (pv != null)
        if (pv != row(i))
          return false
      i = i + 1
    }
    return true
  }

}