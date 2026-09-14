package hk.uwu.roxyhook.ksp

import java.security.MessageDigest

/** Kept separate from the KSP adapter so generation and validation can be tested independently. */
data class EntryModel(val qualifiedName: String, val isObject: Boolean) {
    init {
        require(qualifiedName.split('.').all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) }) {
            "Entry names must be portable Java identifiers: $qualifiedName"
        }
    }
    val generatedPackage = "hk.uwu.roxyhook.generated"
    val generatedName: String get() = "RoxyEntry_" + MessageDigest.getInstance("SHA-256")
        .digest(qualifiedName.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    val generatedQualifiedName: String get() = "$generatedPackage.$generatedName"
    val kotlinReference: String get() = qualifiedName.split('.').joinToString(".") { "`$it`" }
}
