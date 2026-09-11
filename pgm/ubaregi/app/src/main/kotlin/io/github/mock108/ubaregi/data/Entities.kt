package io.github.mock108.ubaregi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

const val MAX_AMOUNT_YEN = 9_999_999L

enum class RegisterStatus {
    OPEN,
    CLOSED,
}

enum class CashEntryKind {
    PAYMENT,
    CASH_IN,
    CASH_OUT,
}

class DatabaseConverters {
    @TypeConverter
    fun registerStatusToString(value: RegisterStatus): String = value.name

    @TypeConverter
    fun stringToRegisterStatus(value: String): RegisterStatus = RegisterStatus.valueOf(value)

    @TypeConverter
    fun cashEntryKindToString(value: CashEntryKind): String = value.name

    @TypeConverter
    fun stringToCashEntryKind(value: String): CashEntryKind = CashEntryKind.valueOf(value)
}

@Entity(tableName = "dataset_meta")
data class DatasetMeta(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "dataset_id")
    val datasetId: String,
    @ColumnInfo(name = "snapshot_revision")
    val snapshotRevision: Long = 0,
    @ColumnInfo(name = "next_session_sequence")
    val nextSessionSequence: Long = 1,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "register_session",
    indices = [
        Index(value = ["sequence"], unique = true),
        Index(value = ["open_slot"], unique = true, name = "index_register_session_open_slot"),
    ],
)
data class RegisterSession(
    @PrimaryKey
    val id: String,
    val sequence: Long,
    val status: RegisterStatus,
    @ColumnInfo(name = "open_slot")
    val openSlot: Int?,
    @ColumnInfo(name = "opened_at")
    val openedAt: Long,
    @ColumnInfo(name = "closed_at")
    val closedAt: Long?,
    @ColumnInfo(name = "opening_float_yen")
    val openingFloatYen: Long,
    @ColumnInfo(name = "actual_cash_yen")
    val actualCashYen: Long?,
    @ColumnInfo(name = "next_float_yen")
    val nextFloatYen: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    val revision: Long = 1,
    @ColumnInfo(name = "close_revision")
    val closeRevision: Long?,
)

@Entity(
    tableName = "cash_entry",
    foreignKeys = [
        ForeignKey(
            entity = RegisterSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["session_id", "sequence"], unique = true),
    ],
)
data class CashEntry(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val sequence: Long,
    val kind: CashEntryKind,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "product_amount_yen")
    val productAmountYen: Long?,
    @ColumnInfo(name = "received_amount_yen")
    val receivedAmountYen: Long?,
    @ColumnInfo(name = "amount_yen")
    val amountYen: Long?,
    @ColumnInfo(name = "is_voided")
    val isVoided: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    val revision: Long = 1,
)
