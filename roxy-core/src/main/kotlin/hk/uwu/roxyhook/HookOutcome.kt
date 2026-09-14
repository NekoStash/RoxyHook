package hk.uwu.roxyhook
internal sealed interface Outcome {
    data object Pending : Outcome
    data class Returned(val value: Any?) : Outcome
    data class Thrown(val error: Throwable) : Outcome
}
internal enum class Phase { BEFORE, REPLACE, AFTER }
