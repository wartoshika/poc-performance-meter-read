package poc

import io.agroal.api.AgroalDataSource
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Singleton
import java.sql.Timestamp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Stage 2: every successful reading goes into a table partitioned by month. The primary key
 * (meter_id, due_at) makes a repeated write a no-op (ON CONFLICT DO NOTHING).
 */
@Singleton
class ReadingStore(
    private val config: PocConfig,
    private val stats: ReadStats,
    private val dataSources: Instance<AgroalDataSource>,
) {
    private val dataSource: AgroalDataSource by lazy { dataSources.get() }

    // Runs before the scheduler (higher priority value runs later).
    fun onStart(@Observes @jakarta.annotation.Priority(100) event: StartupEvent) {
        if (!config.dbEnabled()) return
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    CREATE TABLE IF NOT EXISTS meter_reading (
                        meter_id      text          NOT NULL,
                        due_at        timestamptz   NOT NULL,
                        reading_value numeric(14,3) NOT NULL,
                        reading_at    timestamptz   NOT NULL,
                        status_code   text          NOT NULL,
                        stored_at     timestamptz   NOT NULL DEFAULT now(),
                        CONSTRAINT meter_reading_pk PRIMARY KEY (meter_id, due_at)
                    ) PARTITION BY RANGE (due_at)
                    """.trimIndent()
                )
                val now = YearMonth.now(ZoneOffset.UTC)
                for (offset in -1L..2L) {
                    val month = now.plusMonths(offset)
                    val from = month.atDay(1)
                    val to = month.plusMonths(1).atDay(1)
                    val name = "meter_reading_y%04dm%02d".format(month.year, month.monthValue)
                    s.execute(
                        "CREATE TABLE IF NOT EXISTS $name PARTITION OF meter_reading " +
                            "FOR VALUES FROM ('$from 00:00:00+00') TO ('$to 00:00:00+00')"
                    )
                }
            }
        }
        Log.info("meter_reading table and monthly partitions are in place")
    }

    fun write(meterId: String, dueAt: Instant, reading: MeterReading) {
        val t0 = System.nanoTime()
        val result = try {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO meter_reading (meter_id, due_at, reading_value, reading_at, status_code) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT (meter_id, due_at) DO NOTHING"
                ).use { ps ->
                    ps.setString(1, meterId)
                    ps.setTimestamp(2, Timestamp.from(dueAt))
                    ps.setBigDecimal(3, reading.value)
                    ps.setTimestamp(4, Timestamp.from(reading.readAt))
                    ps.setString(5, reading.statusCode)
                    if (ps.executeUpdate() == 1) "ok" else "duplicate"
                }
            }
        } catch (e: Exception) {
            Log.warnf("write %s failed: %s", meterId, e.toString())
            "error"
        }
        stats.dbWrite(result, System.nanoTime() - t0)
    }
}
