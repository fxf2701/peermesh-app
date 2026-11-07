package app.peermesh.beta

object AppVersionComparator {
    fun compare(left: String?, right: String?): Int {
        val leftParts = parse(left)
        val rightParts = parse(right)
        val limit = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until limit) {
            val leftValue = leftParts.getOrElse(index) { 0 }
            val rightValue = rightParts.getOrElse(index) { 0 }
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return 0
    }

    private fun parse(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.trim()
            .split('.', '-', '_')
            .mapNotNull { token ->
                token.takeWhile { it.isDigit() }
                    .takeIf { it.isNotBlank() }
                    ?.toIntOrNull()
            }
    }
}
