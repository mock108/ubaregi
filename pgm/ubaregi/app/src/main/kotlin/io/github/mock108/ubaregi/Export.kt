package io.github.mock108.ubaregi

import android.content.Context
import android.net.Uri
import io.github.mock108.ubaregi.data.CashEntry
import io.github.mock108.ubaregi.data.ExportSnapshot
import io.github.mock108.ubaregi.data.RegisterRepository
import io.github.mock108.ubaregi.data.RegisterSession
import io.github.mock108.ubaregi.domain.RegisterSummary
import io.github.mock108.ubaregi.domain.calculateAmounts
import io.github.mock108.ubaregi.domain.calculateRegisterSummary
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ExportFormat(
    val extension: String,
    val mimeType: String,
) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
}

data class ExportRequest(
    val format: ExportFormat,
    val fileName: String,
)

@Serializable
private data class ExportDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("dataset_id") val datasetId: String,
    @SerialName("snapshot_revision") val snapshotRevision: Long,
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("app_version") val appVersion: String,
    val currency: String,
    @SerialName("display_timezone") val displayTimezone: String,
    val sessions: List<ExportSession>,
    val entries: List<ExportEntry>,
)

@Serializable
private data class ExportSession(
    val id: String,
    val sequence: Long,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("opening_float_yen") val openingFloatYen: Long,
    @SerialName("actual_cash_yen") val actualCashYen: Long?,
    @SerialName("next_float_yen") val nextFloatYen: Long?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val revision: Long,
    val summary: ExportSummary,
)

@Serializable
private data class ExportSummary(
    @SerialName("payment_collected_yen") val paymentCollectedYen: Long,
    @SerialName("cash_in_yen") val cashInYen: Long,
    @SerialName("cash_out_yen") val cashOutYen: Long,
    @SerialName("net_cash_yen") val netCashYen: Long,
    @SerialName("expected_cash_yen") val expectedCashYen: Long,
    @SerialName("difference_yen") val differenceYen: Long?,
    @SerialName("actual_change_yen") val actualChangeYen: Long?,
    @SerialName("withdrawal_yen") val withdrawalYen: Long?,
)

@Serializable
private data class ExportEntry(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val sequence: Long,
    val kind: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("product_amount_yen") val productAmountYen: Long?,
    @SerialName("received_amount_yen") val receivedAmountYen: Long?,
    @SerialName("amount_yen") val amountYen: Long?,
    @SerialName("is_voided") val isVoided: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val revision: Long,
    @SerialName("change_yen") val changeYen: Long?,
    @SerialName("cash_in_yen") val cashInYen: Long,
    @SerialName("cash_out_yen") val cashOutYen: Long,
    @SerialName("net_cash_yen") val netCashYen: Long,
)

object ExportSerializer {
    private const val SCHEMA_VERSION = 1
    private const val CURRENCY = "JPY"
    private const val DISPLAY_TIMEZONE = "Asia/Tokyo"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    private val utcFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .toFormatter()
        .withZone(ZoneOffset.UTC)

    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        .withZone(ZoneId.of(DISPLAY_TIMEZONE))

    private val csvColumns = listOf(
        "schema_version", "dataset_id", "snapshot_revision", "exported_at", "app_version", "record_type",
        "id", "session_id", "sequence", "status", "kind", "occurred_at", "opened_at", "closed_at",
        "created_at", "updated_at", "revision", "is_voided", "opening_float_yen", "product_amount_yen",
        "received_amount_yen", "change_yen", "amount_yen", "payment_collected_yen", "cash_in_yen",
        "cash_out_yen", "net_cash_yen", "expected_cash_yen", "actual_cash_yen", "difference_yen",
        "actual_change_yen", "next_float_yen", "withdrawal_yen",
    )

    fun fileName(format: ExportFormat, timestamp: Long, snapshotRevision: Long): String =
        "ubaregi_${fileNameFormatter.format(Instant.ofEpochMilli(timestamp))}_r$snapshotRevision.${format.extension}"

    fun json(snapshot: ExportSnapshot, appVersion: String): String {
        val entriesBySession = snapshot.entries.groupBy { it.sessionId }
        return json.encodeToString(
            ExportDocument(
                schemaVersion = SCHEMA_VERSION,
                datasetId = snapshot.meta.datasetId,
                snapshotRevision = snapshot.meta.snapshotRevision,
                exportedAt = formatUtc(snapshot.exportedAt),
                appVersion = appVersion,
                currency = CURRENCY,
                displayTimezone = DISPLAY_TIMEZONE,
                sessions = snapshot.sessions.sortedBy { it.sequence }.map { session ->
                    session.toExportSession(entriesBySession[session.id].orEmpty())
                },
                entries = snapshot.entries
                    .sortedWith(compareBy<CashEntry> { entry ->
                        snapshot.sessions.firstOrNull { it.id == entry.sessionId }?.sequence ?: Long.MAX_VALUE
                    }.thenBy { it.sequence })
                    .map(::toExportEntry),
            ),
        )
    }

    fun csv(snapshot: ExportSnapshot, appVersion: String): String {
        val entriesBySession = snapshot.entries.groupBy { it.sessionId }
        val rows = mutableListOf<List<Any?>>(
            csvColumns,
            csvRow(
                snapshot = snapshot,
                appVersion = appVersion,
                recordType = "METADATA",
            ),
        )
        snapshot.sessions.sortedBy { it.sequence }.forEach { session ->
            rows += csvRow(
                snapshot = snapshot,
                appVersion = appVersion,
                recordType = "SESSION",
                session = session,
                summary = session.toExportSummary(entriesBySession[session.id].orEmpty()),
            )
        }
        snapshot.entries
            .sortedWith(compareBy<CashEntry> { entry ->
                snapshot.sessions.firstOrNull { it.id == entry.sessionId }?.sequence ?: Long.MAX_VALUE
            }.thenBy { it.sequence })
            .forEach { entry ->
                rows += csvRow(
                    snapshot = snapshot,
                    appVersion = appVersion,
                    recordType = "ENTRY",
                    entry = entry,
                )
            }

        return buildString {
            append('\uFEFF')
            rows.forEach { row ->
                append(row.joinToString(",") { csvEscape(it?.toString()) })
                append("\r\n")
            }
        }
    }

    private fun RegisterSession.toExportSession(entries: List<CashEntry>): ExportSession {
        val summary = toExportSummary(entries)
        return ExportSession(
            id = id,
            sequence = sequence,
            status = status.name,
            openedAt = formatUtc(openedAt),
            closedAt = closedAt?.let(::formatUtc),
            openingFloatYen = openingFloatYen,
            actualCashYen = actualCashYen,
            nextFloatYen = nextFloatYen,
            createdAt = formatUtc(createdAt),
            updatedAt = formatUtc(updatedAt),
            revision = revision,
            summary = summary,
        )
    }

    private fun RegisterSession.toExportSummary(entries: List<CashEntry>): ExportSummary =
        calculateRegisterSummary(this, entries).toExportSummary()

    private fun RegisterSummary.toExportSummary() = ExportSummary(
        paymentCollectedYen = paymentCollectedYen,
        cashInYen = cashInYen,
        cashOutYen = cashOutYen,
        netCashYen = netCashYen,
        expectedCashYen = expectedCashYen,
        differenceYen = differenceYen,
        actualChangeYen = actualChangeYen,
        withdrawalYen = withdrawalYen,
    )

    private fun toExportEntry(entry: CashEntry): ExportEntry {
        val amounts = entry.calculateAmounts()
        return ExportEntry(
            id = entry.id,
            sessionId = entry.sessionId,
            sequence = entry.sequence,
            kind = entry.kind.name,
            occurredAt = formatUtc(entry.occurredAt),
            productAmountYen = entry.productAmountYen,
            receivedAmountYen = entry.receivedAmountYen,
            amountYen = entry.amountYen,
            isVoided = entry.isVoided,
            createdAt = formatUtc(entry.createdAt),
            updatedAt = formatUtc(entry.updatedAt),
            revision = entry.revision,
            changeYen = amounts.changeYen,
            cashInYen = amounts.cashInYen,
            cashOutYen = amounts.cashOutYen,
            netCashYen = amounts.netCashYen,
        )
    }

    private fun csvRow(
        snapshot: ExportSnapshot,
        appVersion: String,
        recordType: String,
        session: RegisterSession? = null,
        entry: CashEntry? = null,
        summary: ExportSummary? = null,
    ): List<Any?> {
        val values = MutableList<Any?>(csvColumns.size) { null }
        fun set(name: String, value: Any?) {
            values[csvColumns.indexOf(name)] = value
        }

        set("schema_version", SCHEMA_VERSION)
        set("dataset_id", snapshot.meta.datasetId)
        set("snapshot_revision", snapshot.meta.snapshotRevision)
        set("exported_at", formatUtc(snapshot.exportedAt))
        set("app_version", appVersion)
        set("record_type", recordType)

        session?.let {
            set("id", it.id)
            set("sequence", it.sequence)
            set("status", it.status.name)
            set("opened_at", formatUtc(it.openedAt))
            set("closed_at", it.closedAt?.let(::formatUtc))
            set("created_at", formatUtc(it.createdAt))
            set("updated_at", formatUtc(it.updatedAt))
            set("revision", it.revision)
            set("opening_float_yen", it.openingFloatYen)
            set("actual_cash_yen", it.actualCashYen)
            set("next_float_yen", it.nextFloatYen)
            summary?.let { sessionSummary ->
                set("payment_collected_yen", sessionSummary.paymentCollectedYen)
                set("cash_in_yen", sessionSummary.cashInYen)
                set("cash_out_yen", sessionSummary.cashOutYen)
                set("net_cash_yen", sessionSummary.netCashYen)
                set("expected_cash_yen", sessionSummary.expectedCashYen)
                set("difference_yen", sessionSummary.differenceYen)
                set("actual_change_yen", sessionSummary.actualChangeYen)
                set("withdrawal_yen", sessionSummary.withdrawalYen)
            }
        }

        entry?.let {
            val amounts = it.calculateAmounts()
            set("id", it.id)
            set("session_id", it.sessionId)
            set("sequence", it.sequence)
            set("kind", it.kind.name)
            set("occurred_at", formatUtc(it.occurredAt))
            set("created_at", formatUtc(it.createdAt))
            set("updated_at", formatUtc(it.updatedAt))
            set("revision", it.revision)
            set("is_voided", it.isVoided)
            set("product_amount_yen", it.productAmountYen)
            set("received_amount_yen", it.receivedAmountYen)
            set("change_yen", amounts.changeYen)
            set("amount_yen", it.amountYen)
            set("cash_in_yen", amounts.cashInYen)
            set("cash_out_yen", amounts.cashOutYen)
            set("net_cash_yen", amounts.netCashYen)
        }
        return values
    }

    private fun csvEscape(value: String?): String {
        if (value == null) return ""
        return if (value.any { it == ',' || it == '\r' || it == '\n' || it == '"' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun formatUtc(epochMillis: Long): String = utcFormatter.format(Instant.ofEpochMilli(epochMillis))
}

class AndroidExportService(
    private val context: Context,
    private val repository: RegisterRepository,
    private val appVersion: String,
) {
    suspend fun export(format: ExportFormat, destination: Uri) = withContext(Dispatchers.IO) {
        val snapshot = repository.exportSnapshot()
        val temporaryFile = File(context.noBackupFilesDir, "ubaregi-export-${UUID.randomUUID()}.tmp")
        try {
            val content = when (format) {
                ExportFormat.JSON -> ExportSerializer.json(snapshot, appVersion)
                ExportFormat.CSV -> ExportSerializer.csv(snapshot, appVersion)
            }
            temporaryFile.writeText(content, Charsets.UTF_8)
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("保存先を開けませんでした")
            output.use { stream ->
                temporaryFile.inputStream().use { input -> input.copyTo(stream) }
                stream.flush()
            }
        } finally {
            temporaryFile.delete()
        }
    }

    companion object {
        private const val TEMP_PREFIX = "ubaregi-export-"

        fun cleanupTemporaryFiles(context: Context) {
            context.noBackupFilesDir.listFiles()
                ?.filter { it.name.startsWith(TEMP_PREFIX) && it.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
        }
    }
}
