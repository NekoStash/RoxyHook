package hk.uwu.roxyhook

/** Identity-based key; create one private key per extension service. */
class RuntimeKey<T : Any>(val name: String) {
    init { require(name.isNotBlank()) }
    override fun toString(): String = "RuntimeKey($name)"
}
