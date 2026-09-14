package hk.uwu.roxyhook.gradle

/** Pure validation shared by the Gradle task and offline generator contracts. */
object EntryMetadataValidator {
    fun validate(lists: List<String>): String {
        require(lists.size == 1) { "Expected one KSP-generated java_init.list; found ${lists.size}. Add one @RoxyEntry extending RoxyModule." }
        val entries = lists.single().lineSequence().filter { it.isNotBlank() }.toList()
        require(entries.size == 1 && entries.single().matches(Regex("""hk\.uwu\.roxyhook\.generated\.RoxyEntry_[a-f0-9]{24}"""))) {
            "Invalid RoxyHook generated entry metadata: $entries"
        }
        return entries.single()
    }
}
