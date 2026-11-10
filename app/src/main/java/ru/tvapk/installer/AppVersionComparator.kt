package ru.tvapk.installer

import java.math.BigInteger

/**
 * Compares numeric dotted app versions without narrowing components to Int.
 * Both inputs must be plain numeric versions, optionally prefixed with "v".
 */
internal object AppVersionComparator {
    private val versionPattern = Regex("^v?\\d+(?:\\.\\d+){1,3}$")

    fun compare(first: String, second: String): Int {
        require(versionPattern.matches(first)) { "Invalid first app version" }
        require(versionPattern.matches(second)) { "Invalid second app version" }

        val firstParts = first.removePrefix("v").split('.').map(::BigInteger)
        val secondParts = second.removePrefix("v").split('.').map(::BigInteger)
        for (index in 0 until maxOf(firstParts.size, secondParts.size)) {
            val left = firstParts.getOrElse(index) { BigInteger.ZERO }
            val right = secondParts.getOrElse(index) { BigInteger.ZERO }
            val comparison = left.compareTo(right)
            if (comparison != 0) return comparison
        }
        return 0
    }
}
