package si.rainalator.analysis

/** A caller-fixable problem with the query arguments; the REST layer turns these into 400s. */
class InvalidQueryException(override val message: String) : RuntimeException(message)
