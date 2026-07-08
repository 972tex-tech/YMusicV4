package kmphttp.internal.publicsuffix

actual object PublicSuffixDatabase {
    actual suspend fun getEffectiveTldPlusOne(domain: String): String? {
        return PublicSuffixList.getEffectiveTldPlusOne(domain)
    }
}
