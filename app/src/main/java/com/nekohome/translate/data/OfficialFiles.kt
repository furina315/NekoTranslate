package com.nekohome.translate.data

/** One file of an official GGUF release: exact size and SHA-256 as listed by Hugging Face. */
data class OfficialFile(
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    /** "Q4_K_M" from "Index-Nailong-2B.Q4_K_M.gguf". */
    val quant: String get() = fileName.removeSuffix(".gguf").substringAfterLast('.')
}

/** How an imported model compares with the known official files. */
enum class OfficialStatus {
    /** Size and SHA-256 equal an official file. */
    VERIFIED,

    /** Size equals an official file; no checksum on record (imported before checksums were kept). */
    SIZE_MATCH,

    /** Size equals an official file but the checksum differs (modified or damaged copy; still usable). */
    MISMATCH,
}

data class OfficialMatch(val file: OfficialFile, val status: OfficialStatus)

/**
 * Official files the app can recognise. Only a label on the model card: a model that matches
 * none of them works exactly the same. Values from
 * https://huggingface.co/api/models/IndexTeam/Index-Nailong-2B-GGUF/tree/main (repo revision
 * 9ee4e59, 2026-10-03; size = lfs.size, sha256 = lfs.oid). mmproj files are not listed here.
 */
object OfficialFiles {
    const val NAILONG_2B_REPO = "IndexTeam/Index-Nailong-2B-GGUF"
    const val NAILONG_2B_RECOMMENDED = "Q4_K_M"

    private fun n(quant: String, size: Long, sha: String) =
        OfficialFile(NAILONG_2B_REPO, "Index-Nailong-2B.$quant.gguf", size, sha)

    val ALL: List<OfficialFile> = listOf(
        n("Q2_K", 968_541_920L, "1efddfad8fa4f2216a9b7de880e11135fa406767f4a27e7dda67a29dd5f5e393"),
        n("Q3_K_S", 1_020_173_024L, "ee0e967ca3cf7f8d37eb9ef82779c887e84b01e359b4b87ab58e3d6267558a1a"),
        n("Q3_K_M", 1_099_258_592L, "a1d3f3d8fb1eaf6f09a6c6828be02f2b8777caa597b2e1e24deab963be8cd7f3"),
        n("Q3_K_L", 1_164_532_448L, "4d6621576f53b77b68103f360eac8b99ddb94f66545dfc5fe1567459dea259e3"),
        n("IQ4_XS", 1_201_860_320L, "fd7690a67baba99c8c2f2dfed51232a84de3fc97f4a139d327bf6a06786e91c8"),
        n("Q4_K_S", 1_212_055_264L, "cbf87fe8e8b9c01f5d4c8e9e0947f95fea10c2d819999a2ea4df44720798bbea"),
        n("Q4_K_M", 1_274_396_384L, "5d2653169f3ea73d29b39e341d65300f79b7d1da0b28aceeb11f0e729bdebeb0"),
        n("Q5_K_S", 1_374_076_640L, "e82612af31ae9627db45aa67d5280cb6e04ac32af9540f09d152bae7dc70f61f"),
        n("Q5_K_M", 1_411_120_864L, "90e2ff1c111670181b7a639fe543736677b6373bd2000276f8dd1669bb183f21"),
        n("Q6_K", 1_556_390_624L, "7d541a36af6ada67f28d01e38a54bf99438aa7398669a45e3172d3504570e2e3"),
        n("Q8_0", 2_012_012_256L, "2798dd29f2cfa304751521c4c9d72eb8b099082fed649f0de8cb805a41782acf"),
        n("f16", 3_775_708_896L, "20a8edefcb17656cf65c73cdcc28791d970d2833e0eb07daf117f91b402e003a"),
    )

    /** The official file with exactly this size (sizes are distinct), or null. */
    fun bySize(sizeBytes: Long): OfficialFile? = ALL.firstOrNull { it.sizeBytes == sizeBytes }

    /** [sha256] = the checksum computed at import, if any. Null when the size matches nothing. */
    fun match(sizeBytes: Long, sha256: String?): OfficialMatch? {
        val f = bySize(sizeBytes) ?: return null
        val status = when {
            sha256 == null -> OfficialStatus.SIZE_MATCH
            sha256.equals(f.sha256, ignoreCase = true) -> OfficialStatus.VERIFIED
            else -> OfficialStatus.MISMATCH
        }
        return OfficialMatch(f, status)
    }

    /** Whether an import of this size is worth hashing (only official sizes are compared). */
    fun isCandidateSize(sizeBytes: Long): Boolean = bySize(sizeBytes) != null

    /** Lower-case hex of a digest. */
    fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
