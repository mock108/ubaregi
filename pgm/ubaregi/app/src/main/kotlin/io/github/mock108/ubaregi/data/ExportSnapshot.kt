package io.github.mock108.ubaregi.data

/** A point-in-time, transactionally consistent view of all business records. */
data class ExportSnapshot(
    val meta: DatasetMeta,
    val sessions: List<RegisterSession>,
    val entries: List<CashEntry>,
    val exportedAt: Long,
)
