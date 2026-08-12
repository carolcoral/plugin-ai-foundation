package run.halo.aifoundation.service.usage;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.sqlite.JDBC;

@Slf4j
@Component
public class SqliteUsageStatisticsStore implements UsageStatisticsStore {

    static final int BUSY_TIMEOUT_MILLIS = 5_000;
    static final long WAL_SIZE_LIMIT_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CONCURRENT_READERS = 4;
    private static final int CALL_RETENTION_DAYS = 90;
    private static final int EXECUTION_RETENTION_DAYS = 30;
    private static final Duration ROLLUP_SAFETY_DELAY = Duration.ofHours(1);

    private static final AtomicInteger LIVE_STORES = new AtomicInteger();

    private final UsageDatabasePaths paths;
    private final Semaphore readerPermits = new Semaphore(MAX_CONCURRENT_READERS, true);
    private final java.util.Set<Connection> activeReaders = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closing = new AtomicBoolean();
    private Connection writer;
    private volatile boolean initialized;

    public SqliteUsageStatisticsStore(UsageDatabasePaths paths) {
        this.paths = paths;
    }

    @Override
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        try {
            Files.createDirectories(paths.database().getParent());
            Files.createDirectories(paths.backupDirectory());
            ensureDriverRegistered();
            var recovered = UsageSqliteFiles.recoverIfRequired(paths);
            writer = openConnection();
            configureWriter(writer);
            UsageSqliteSchema.migrate(writer, paths);
            UsageSqliteSchema.validateRecognized(writer);
            var integrity = quickCheck(writer);
            if (!"ok".equalsIgnoreCase(integrity)) {
                throw new UsageDatabaseIntegrityException(
                    "SQLite quick_check failed: " + integrity);
            }
            if (recovered) {
                try (var statement = writer.prepareStatement("""
                    UPDATE ai_statistics_health SET integrity_error = ?,
                      affected_since_ms = COALESCE(affected_since_ms, ?) WHERE id = 1
                    """)) {
                    statement.setString(1, "RECOVERED_FROM_INVALID_DATABASE");
                    statement.setLong(2, System.currentTimeMillis());
                    statement.executeUpdate();
                }
            }
            initialized = true;
            LIVE_STORES.incrementAndGet();
        } catch (Exception error) {
            closeSilently();
            if (LIVE_STORES.get() == 0) {
                deregisterPluginDrivers();
            }
            throw new IllegalStateException("Failed to initialize AI usage statistics", error);
        }
    }

    @Override
    public synchronized long currentEpoch() {
        requireInitialized();
        return longMeta(writer, "statistics_epoch", 1L);
    }

    @Override
    public synchronized UsageHealthState readHealth() {
        requireInitialized();
        try (var statement = writer.prepareStatement(
            "SELECT * FROM ai_statistics_health WHERE id = 1");
            var row = statement.executeQuery()) {
            if (!row.next()) {
                return UsageHealthState.empty();
            }
            return new UsageHealthState(row.getLong("dropped_events"),
                row.getLong("incomplete_calls"), row.getLong("write_failures"),
                instant(row, "last_write_error_at_ms"), instant(row, "affected_since_ms"),
                row.getString("migration_error"), row.getString("integrity_error"));
        } catch (SQLException error) {
            throw databaseError("read statistics health", error);
        }
    }

    @Override
    public synchronized void writeHealth(UsageHealthState health) {
        requireInitialized();
        var sql = """
            UPDATE ai_statistics_health SET affected_since_ms = ?, last_write_error_at_ms = ?,
              dropped_events = ?, incomplete_calls = ?, write_failures = ?,
              migration_error = ?, integrity_error = ? WHERE id = 1
            """;
        try (var statement = writer.prepareStatement(sql)) {
            setInstant(statement, 1, health.affectedSince());
            setInstant(statement, 2, health.lastWriteErrorAt());
            statement.setLong(3, health.droppedEvents());
            statement.setLong(4, health.incompleteCalls());
            statement.setLong(5, health.writeFailures());
            statement.setString(6, health.migrationError());
            statement.setString(7, health.integrityError());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError("write statistics health", error);
        }
    }

    @Override
    public synchronized void startCall(UsageCallStart start) {
        requireInitialized();
        if (start.epoch() != currentEpoch()) {
            return;
        }
        insertStart(writer, start);
    }

    @Override
    public synchronized void recordExecution(UsageExecutionRecord execution) {
        requireInitialized();
        if (execution.epoch() != currentEpoch()) {
            return;
        }
        var sql = """
            INSERT INTO ai_model_executions (
              id, call_id, epoch, unit_kind, unit_index, attempt_index,
              started_at_ms, completed_at_ms, status, error_type, error_code,
              request_model_id, response_model_id,
              input_tokens, output_tokens, cache_read_input_tokens,
              cache_creation_input_tokens, reasoning_output_tokens,
              provider_total_tokens, accounted_total_tokens, usage_quality
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(call_id, unit_kind, unit_index, attempt_index) DO NOTHING
            """;
        try (var statement = writer.prepareStatement(sql)) {
            var index = 1;
            statement.setString(index++, execution.id());
            statement.setString(index++, execution.callId());
            statement.setLong(index++, execution.epoch());
            statement.setString(index++, execution.unitKind().name());
            statement.setInt(index++, execution.unitIndex());
            statement.setInt(index++, execution.attemptIndex());
            statement.setLong(index++, execution.startedAt().toEpochMilli());
            setInstant(statement, index++, execution.completedAt());
            statement.setString(index++, execution.status().name());
            statement.setString(index++, value(execution.error(), UsageError::type));
            statement.setString(index++, value(execution.error(), UsageError::code));
            statement.setString(index++, execution.requestModelId());
            statement.setString(index++, execution.responseModelId());
            index = bindUsage(statement, index, execution.usage());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError("record execution", error);
        }
    }

    @Override
    public synchronized void finishCall(UsageCallTerminal terminal) {
        requireInitialized();
        if (terminal.epoch() != currentEpoch()) {
            return;
        }
        insertStart(writer, terminal.start());
        var sql = """
            UPDATE ai_calls SET
              completed_at_ms = ?, duration_ms = ?, status = ?, error_type = ?, error_code = ?,
              response_model_id = ?, step_count = ?, attempt_count = ?,
              missing_execution_count = ?, complete = ?, input_tokens = ?, output_tokens = ?,
              cache_read_input_tokens = ?, cache_creation_input_tokens = ?,
              reasoning_output_tokens = ?, provider_total_tokens = ?,
              accounted_total_tokens = ?, usage_quality = ?
            WHERE id = ? AND epoch = ? AND status = 'IN_PROGRESS'
            """;
        try (var statement = writer.prepareStatement(sql)) {
            var index = 1;
            statement.setLong(index++, terminal.completedAt().toEpochMilli());
            var duration = terminal.completedAt().toEpochMilli()
                - terminal.start().startedAt().toEpochMilli();
            statement.setLong(index++, Math.max(0, duration));
            statement.setString(index++, terminal.status().name());
            statement.setString(index++, value(terminal.error(), UsageError::type));
            statement.setString(index++, value(terminal.error(), UsageError::code));
            statement.setString(index++, terminal.responseModelId());
            statement.setInt(index++, terminal.stepCount());
            statement.setInt(index++, terminal.attemptCount());
            statement.setInt(index++, terminal.missingExecutionCount());
            statement.setInt(index++, terminal.complete() ? 1 : 0);
            index = bindUsage(statement, index, terminal.usage());
            statement.setString(index++, terminal.callId());
            statement.setLong(index, terminal.epoch());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError("finish call", error);
        }
    }

    @Override
    public UsageSummary summary(UsageQuery query, boolean complete) {
        requireInitialized();
        return withReader(connection -> querySummary(connection, query, complete));
    }

    @Override
    public List<UsageTrendPoint> trends(UsageQuery query, boolean complete) {
        requireInitialized();
        return withReader(connection -> queryTrends(connection, query, complete));
    }

    @Override
    public UsageCallPage listCalls(UsageQuery query, int size, String cursor) {
        requireInitialized();
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }
        return withReader(connection -> queryCalls(connection, query, size, cursor));
    }

    @Override
    public Optional<UsageCallDetail> getCall(String id) {
        requireInitialized();
        return withReader(connection -> {
            var call = findCall(connection, id);
            if (call.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new UsageCallDetail(call.get(), findExecutions(connection, id)));
        });
    }

    @Override
    public synchronized long reset() {
        requireInitialized();
        try {
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                statement.executeUpdate("DELETE FROM ai_model_executions");
                statement.executeUpdate("DELETE FROM ai_calls");
                statement.executeUpdate("DELETE FROM ai_usage_daily");
                statement.executeUpdate("DELETE FROM ai_token_usage_daily");
                statement.executeUpdate("""
                    UPDATE ai_statistics_health SET affected_since_ms = NULL,
                      last_write_error_at_ms = NULL, dropped_events = 0, incomplete_calls = 0,
                      write_failures = 0, migration_error = NULL, integrity_error = NULL
                    WHERE id = 1
                    """);
            }
            var nextEpoch = currentEpoch() + 1;
            putMeta(writer, "statistics_epoch", Long.toString(nextEpoch));
            writer.commit();
            return nextEpoch;
        } catch (SQLException error) {
            rollback(writer);
            throw databaseError("reset statistics", error);
        } finally {
            setAutoCommit(writer, true);
        }
    }

    @Override
    public synchronized void reconcileAbandoned(Instant now) {
        requireInitialized();
        var sql = """
            UPDATE ai_calls SET status = 'ABANDONED', completed_at_ms = ?,
              duration_ms = MAX(0, ? - started_at_ms), complete = 0
            WHERE status = 'IN_PROGRESS'
            """;
        try (var statement = writer.prepareStatement(sql)) {
            statement.setLong(1, now.toEpochMilli());
            statement.setLong(2, now.toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError("reconcile abandoned calls", error);
        }
    }

    @Override
    public synchronized void rollupAndRetain(Clock clock) {
        requireInitialized();
        var now = clock.instant();
        var today = now.atZone(ZoneOffset.UTC).toLocalDate();
        var rollupBefore = now.minus(ROLLUP_SAFETY_DELAY)
            .atZone(ZoneOffset.UTC).toLocalDate();
        var callCutoff = today.minusDays(CALL_RETENTION_DAYS);
        var executionCutoff = today.minusDays(EXECUTION_RETENTION_DAYS);
        try {
            writer.setAutoCommit(false);
            var callDays = rollupDays(writer, rollupBefore,
                "call_rollup_frozen_watermark");
            var tokenDays = rollupDays(writer, rollupBefore, "rollup_frozen_watermark");
            for (var day : callDays) {
                rollupDay(writer, day);
            }
            for (var day : tokenDays) {
                rollupTokenDay(writer, day);
            }
            putMeta(writer, "rollup_watermark", rollupBefore.minusDays(1).toString());
            putMeta(writer, "call_rollup_frozen_watermark",
                callCutoff.minusDays(1).toString());
            putMeta(writer, "rollup_frozen_watermark",
                executionCutoff.minusDays(1).toString());
            putMeta(writer, "execution_detail_start", executionCutoff.toString());
            try (var deleteExecutions = writer.prepareStatement(
                "DELETE FROM ai_model_executions WHERE started_at_ms < ?");
                var deleteCalls = writer.prepareStatement(
                    "DELETE FROM ai_calls WHERE started_at_ms < ?")) {
                deleteExecutions.setLong(1,
                    executionCutoff.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
                deleteExecutions.executeUpdate();
                deleteCalls.setLong(1,
                    callCutoff.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
                deleteCalls.executeUpdate();
            }
            writer.commit();
        } catch (SQLException error) {
            rollback(writer);
            throw databaseError("roll up and retain statistics", error);
        } finally {
            setAutoCommit(writer, true);
        }
    }

    private static List<LocalDate> rollupDays(Connection connection, LocalDate rollupBefore,
        String frozenWatermarkKey) throws SQLException {
        var days = new ArrayList<LocalDate>();
        var frozenWatermark = stringMeta(connection, frozenWatermarkKey);
        try (var statement = connection.prepareStatement("""
            SELECT DISTINCT date(started_at_ms / 1000, 'unixepoch') AS day
            FROM ai_calls
            WHERE started_at_ms < ?
              AND (? IS NULL OR date(started_at_ms / 1000, 'unixepoch') > ?)
            ORDER BY day
            """)) {
            statement.setLong(1,
                rollupBefore.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
            statement.setString(2, frozenWatermark);
            statement.setString(3, frozenWatermark);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    days.add(LocalDate.parse(rows.getString("day")));
                }
            }
        }
        return days;
    }

    @Override
    public void backup() {
        requireInitialized();
        withReader(connection -> {
            UsageSqliteFiles.backup(connection, paths);
            return null;
        });
    }

    @Override
    public String quickCheck() {
        requireInitialized();
        return withReader(this::quickCheck);
    }

    @Override
    public synchronized void close() {
        if (!initialized || !closing.compareAndSet(false, true)) {
            return;
        }
        initialized = false;
        boolean acquired = false;
        try {
            acquired = readerPermits.tryAcquire(MAX_CONCURRENT_READERS, BUSY_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            log.warn("Timed out waiting for AI usage statistics readers; closing them");
            activeReaders.forEach(SqliteUsageStatisticsStore::close);
        }
        closeSilently();
        if (acquired) {
            readerPermits.release(MAX_CONCURRENT_READERS);
        }
        if (LIVE_STORES.decrementAndGet() == 0) {
            deregisterPluginDrivers();
        }
    }

    private Connection openConnection() throws SQLException {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
                statement.execute("PRAGMA wal_autocheckpoint = 1000");
                statement.execute("PRAGMA journal_size_limit = " + WAL_SIZE_LIMIT_BYTES);
            }
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    private void configureWriter(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            if (!rows.next() || !"wal".equalsIgnoreCase(rows.getString(1))) {
                throw new SQLException("SQLite did not enter WAL journal mode");
            }
        }
    }

    private void insertStart(Connection connection, UsageCallStart start) {
        var sql = """
            INSERT INTO ai_calls (
              id, epoch, started_at_ms, caller_plugin_name, caller_plugin_version,
              caller_detection_source, feature, operation, model_type, model_name,
              provider_name, provider_type, request_model_id, streaming, status,
              complete, usage_quality
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', 1, 'MISSING')
            ON CONFLICT(id) DO NOTHING
            """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, start.id());
            statement.setLong(2, start.epoch());
            statement.setLong(3, start.startedAt().toEpochMilli());
            statement.setString(4, start.callerPluginName());
            statement.setString(5, start.callerPluginVersion());
            statement.setString(6, start.callerDetectionSource());
            statement.setString(7, start.feature());
            statement.setString(8, start.operation());
            statement.setString(9, start.modelType());
            statement.setString(10, start.modelName());
            statement.setString(11, start.providerName());
            statement.setString(12, start.providerType());
            statement.setString(13, start.requestModelId());
            statement.setInt(14, start.streaming() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError("start call", error);
        }
    }

    private UsageSummary querySummary(Connection connection, UsageQuery query, boolean complete)
        throws SQLException {
        var plan = sourcePlan(connection, query);
        var raw = SummaryValues.empty();
        for (var interval : plan.raw()) {
            raw = raw.add(querySummarySource(connection, interval, false));
        }
        var daily = plan.daily() == null ? SummaryValues.empty()
            : querySummarySource(connection, plan.daily(), true);
        var tokens = TokenValues.empty();
        for (var interval : plan.raw()) {
            tokens = tokens.add(queryTokenSource(connection, interval, false));
        }
        if (plan.daily() != null) {
            tokens = tokens.add(queryTokenSource(connection, plan.daily(), true));
        }
        var calls = raw.calls + daily.calls;
        var known = raw.known + daily.known;
        var missing = raw.missing + daily.missing;
        return new UsageSummary(calls, raw.inProgress + daily.inProgress,
            raw.succeeded + daily.succeeded,
            raw.failed + daily.failed, raw.timedOut + daily.timedOut,
            raw.cancelled + daily.cancelled, raw.abandoned + daily.abandoned,
            tokens.input, tokens.output, tokens.cacheRead, tokens.cacheCreation,
            tokens.reasoning, tokens.total, known, missing,
            calls == 0 ? 1D : (double) known / calls,
            complete && raw.incomplete + daily.incomplete == 0,
            daily.calls > 0 ? "DAY" : "MILLISECOND", query.from(), query.to());
    }

    private SummaryValues querySummarySource(Connection connection, UsageQuery query, boolean daily)
        throws SQLException {
        var filter = filter(query, daily ? "day" : "started_at_ms", daily);
        var count = daily ? "SUM(call_count)" : "COUNT(*)";
        var statusValue = daily ? "call_count" : "1";
        var knownValue = daily ? "known_usage_calls"
            : "CASE WHEN usage_quality <> 'MISSING' THEN 1 ELSE 0 END";
        var missingValue = daily ? "missing_usage_calls"
            : "CASE WHEN usage_quality = 'MISSING' THEN 1 ELSE 0 END";
        var incompleteValue = daily ? "incomplete_call_count"
            : "CASE WHEN complete = 0 THEN 1 ELSE 0 END";
        var table = daily ? "ai_usage_daily" : "ai_calls";
        var sql = "SELECT " + count + " call_count,"
            + " SUM(CASE WHEN status = 'IN_PROGRESS' THEN " + statusValue
            + " ELSE 0 END) in_progress_count,"
            + " SUM(CASE WHEN status = 'SUCCEEDED' THEN " + statusValue
            + " ELSE 0 END) success_count,"
            + " SUM(CASE WHEN status = 'FAILED' THEN " + statusValue + " ELSE 0 END) failed_count,"
            + " SUM(CASE WHEN status = 'TIMED_OUT' THEN " + statusValue
            + " ELSE 0 END) timed_out_count,"
            + " SUM(CASE WHEN status = 'CANCELLED' THEN " + statusValue
            + " ELSE 0 END) cancelled_count,"
            + " SUM(CASE WHEN status = 'ABANDONED' THEN " + statusValue
            + " ELSE 0 END) abandoned_count,"
            + " SUM(" + knownValue + ") known_usage_calls, SUM(" + missingValue
            + ") missing_usage_calls, SUM(" + incompleteValue + ") incomplete_calls FROM "
            + table + " " + filter.sql();
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, filter.parameters());
            try (var row = statement.executeQuery()) {
                row.next();
                return new SummaryValues(row.getLong("call_count"),
                    row.getLong("in_progress_count"), row.getLong("success_count"),
                    row.getLong("failed_count"),
                    row.getLong("timed_out_count"), row.getLong("cancelled_count"),
                    row.getLong("abandoned_count"),
                    row.getLong("known_usage_calls"), row.getLong("missing_usage_calls"),
                    row.getLong("incomplete_calls"));
            }
        }
    }

    private TokenValues queryTokenSource(Connection connection, UsageQuery query, boolean daily)
        throws SQLException {
        if (daily) {
            return queryTokenTable(connection, "ai_token_usage_daily",
                "", filter(query, "day", true));
        }
        return queryTokenTable(connection,
            "ai_model_executions e JOIN ai_calls c ON c.id = e.call_id",
            "e.", rawTokenFilter(query, true)).add(queryTokenTable(connection, "ai_calls c",
            "c.", rawTokenFilter(query, false)));
    }

    private TokenValues queryTokenTable(Connection connection, String table, String columnPrefix,
        SqlFilter filter) throws SQLException {
        var sql = "SELECT SUM(" + columnPrefix + "input_tokens) input_tokens, SUM("
            + columnPrefix + "output_tokens) output_tokens, SUM(" + columnPrefix
            + "cache_read_input_tokens) cache_read_input_tokens, SUM(" + columnPrefix
            + "cache_creation_input_tokens) cache_creation_input_tokens, SUM(" + columnPrefix
            + "reasoning_output_tokens) reasoning_output_tokens, SUM(" + columnPrefix
            + "accounted_total_tokens) accounted_total_tokens FROM " + table + " "
            + filter.sql();
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, filter.parameters());
            try (var row = statement.executeQuery()) {
                row.next();
                return new TokenValues(nullableLong(row, "input_tokens"),
                    nullableLong(row, "output_tokens"),
                    nullableLong(row, "cache_read_input_tokens"),
                    nullableLong(row, "cache_creation_input_tokens"),
                    nullableLong(row, "reasoning_output_tokens"),
                    nullableLong(row, "accounted_total_tokens"));
            }
        }
    }

    private SqlFilter rawTokenFilter(UsageQuery query, boolean execution) {
        var clauses = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        var fact = execution ? "e" : "c";
        clauses.add(fact + ".started_at_ms >= ?");
        parameters.add(query.from().toEpochMilli());
        clauses.add(fact + ".started_at_ms < ?");
        parameters.add(query.to().toEpochMilli());
        addFilter(clauses, parameters, "c.caller_plugin_name", query.callerPlugin());
        addFilter(clauses, parameters, "c.feature", query.feature());
        addFilter(clauses, parameters, "c.provider_name", query.providerName());
        addFilter(clauses, parameters, "c.model_name", query.modelName());
        addFilter(clauses, parameters, "c.model_type", query.modelType());
        addFilter(clauses, parameters, "c.operation", query.operation());
        addFilter(clauses, parameters, fact + ".status",
            query.status() == null ? null : query.status().name());
        addFilter(clauses, parameters, fact + ".usage_quality",
            query.usageQuality() == null ? null : query.usageQuality().name());
        if (!execution) {
            clauses.add("NOT EXISTS (SELECT 1 FROM ai_model_executions e WHERE e.call_id = c.id)");
        }
        return new SqlFilter("WHERE " + String.join(" AND ", clauses), parameters);
    }

    private List<UsageTrendPoint> queryTrends(Connection connection, UsageQuery query,
        boolean complete)
        throws SQLException {
        var points = new java.util.TreeMap<Instant, UsageTrendPoint>();
        var plan = sourcePlan(connection, query);
        for (var interval : plan.raw()) {
            queryTrendSource(connection, interval, false).forEach(point ->
                points.merge(point.bucketStart(), point, SqliteUsageStatisticsStore::mergePoint));
            queryTokenTrendSource(connection, interval, false).forEach(point ->
                points.merge(point.bucketStart(), point, SqliteUsageStatisticsStore::mergePoint));
        }
        if (plan.daily() != null) {
            queryTrendSource(connection, plan.daily(), true).forEach(point ->
                points.put(point.bucketStart(), point));
            queryTokenTrendSource(connection, plan.daily(), true).forEach(point ->
                points.merge(point.bucketStart(), point, SqliteUsageStatisticsStore::mergePoint));
        }
        return points.values().stream()
            .map(point -> point.withComplete(complete && point.complete()))
            .toList();
    }

    private List<UsageTrendPoint> queryTrendSource(Connection connection, UsageQuery query,
        boolean daily) throws SQLException {
        var filter = filter(query, daily ? "day" : "started_at_ms", daily);
        var bucket = daily ? "day || 'T00:00:00Z'"
            : trendBucket("started_at_ms", query.effectiveResolution());
        var count = daily ? "SUM(call_count)" : "COUNT(*)";
        var known = daily ? "SUM(known_usage_calls)"
            : "SUM(CASE WHEN usage_quality <> 'MISSING' THEN 1 ELSE 0 END)";
        var missing = daily ? "SUM(missing_usage_calls)"
            : "SUM(CASE WHEN usage_quality = 'MISSING' THEN 1 ELSE 0 END)";
        var incomplete = daily ? "SUM(incomplete_call_count)"
            : "SUM(CASE WHEN complete = 0 THEN 1 ELSE 0 END)";
        var table = daily ? "ai_usage_daily" : "ai_calls";
        var resolution = daily ? UsageTrendResolution.DAY : query.effectiveResolution();
        var sql = "SELECT " + bucket + " bucket, " + count
            + " call_count, NULL input_tokens, NULL output_tokens,"
            + " NULL accounted_total_tokens, " + known
            + " known_usage_calls, " + missing + " missing_usage_calls, " + incomplete
            + " incomplete_calls FROM " + table + " " + filter.sql()
            + " GROUP BY bucket ORDER BY bucket";
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, filter.parameters());
            try (var rows = statement.executeQuery()) {
                var points = new ArrayList<UsageTrendPoint>();
                while (rows.next()) {
                    points.add(new UsageTrendPoint(Instant.parse(rows.getString("bucket")),
                        resolution,
                        rows.getLong("call_count"), nullableLong(rows, "input_tokens"),
                        nullableLong(rows, "output_tokens"),
                        nullableLong(rows, "accounted_total_tokens"),
                        rows.getLong("known_usage_calls"), rows.getLong("missing_usage_calls"),
                        rows.getLong("incomplete_calls") == 0));
                }
                return List.copyOf(points);
            }
        }
    }

    private List<UsageTrendPoint> queryTokenTrendSource(Connection connection, UsageQuery query,
        boolean daily) throws SQLException {
        if (daily) {
            return queryTokenTrendTable(connection, "ai_token_usage_daily",
                "day || 'T00:00:00Z'", "", UsageTrendResolution.DAY,
                filter(query, "day", true));
        }
        var resolution = query.effectiveResolution();
        var points = new java.util.TreeMap<Instant, UsageTrendPoint>();
        queryTokenTrendTable(connection,
            "ai_model_executions e JOIN ai_calls c ON c.id = e.call_id",
            trendBucket("e.started_at_ms", resolution),
            "e.", resolution, rawTokenFilter(query, true)).forEach(point ->
            points.merge(point.bucketStart(), point, SqliteUsageStatisticsStore::mergePoint));
        queryTokenTrendTable(connection, "ai_calls c",
            trendBucket("c.started_at_ms", resolution),
            "c.", resolution, rawTokenFilter(query, false)).forEach(point ->
            points.merge(point.bucketStart(), point, SqliteUsageStatisticsStore::mergePoint));
        return List.copyOf(points.values());
    }

    private List<UsageTrendPoint> queryTokenTrendTable(Connection connection, String table,
        String bucket, String columnPrefix, UsageTrendResolution resolution, SqlFilter filter)
        throws SQLException {
        var sql = "SELECT " + bucket + " bucket, SUM(" + columnPrefix
            + "input_tokens) input_tokens, SUM(" + columnPrefix
            + "output_tokens) output_tokens, SUM(" + columnPrefix
            + "accounted_total_tokens) accounted_total_tokens FROM " + table + " "
            + filter.sql() + " GROUP BY bucket ORDER BY bucket";
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, filter.parameters());
            try (var rows = statement.executeQuery()) {
                var points = new ArrayList<UsageTrendPoint>();
                while (rows.next()) {
                    points.add(new UsageTrendPoint(Instant.parse(rows.getString("bucket")),
                        resolution, 0,
                        nullableLong(rows, "input_tokens"), nullableLong(rows, "output_tokens"),
                        nullableLong(rows, "accounted_total_tokens"), 0, 0));
                }
                return List.copyOf(points);
            }
        }
    }

    private UsageCallPage queryCalls(Connection connection, UsageQuery query, int size,
        String encodedCursor) throws SQLException {
        var base = filter(query, "started_at_ms", false);
        var parameters = new ArrayList<>(base.parameters());
        var sql = new StringBuilder("SELECT * FROM ai_calls ").append(base.sql());
        if (encodedCursor != null && !encodedCursor.isBlank()) {
            var cursor = UsageCursor.decode(encodedCursor, query);
            sql.append(" AND (started_at_ms < ? OR (started_at_ms = ? AND id < ?))");
            parameters.add(cursor.startedAt().toEpochMilli());
            parameters.add(cursor.startedAt().toEpochMilli());
            parameters.add(cursor.id());
        }
        sql.append(" ORDER BY started_at_ms DESC, id DESC LIMIT ?");
        parameters.add(size + 1);
        try (var statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (var rows = statement.executeQuery()) {
                var items = new ArrayList<UsageCallItem>();
                while (rows.next()) {
                    items.add(mapCall(rows));
                }
                String nextCursor = null;
                if (items.size() > size) {
                    items.removeLast();
                    var last = items.getLast();
                    nextCursor = UsageCursor.encode(last.startedAt(), last.id(), query);
                }
                return new UsageCallPage(List.copyOf(items), nextCursor);
            }
        }
    }

    private Optional<UsageCallItem> findCall(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM ai_calls WHERE id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapCall(rows)) : Optional.empty();
            }
        }
    }

    private List<UsageExecutionRecord> findExecutions(Connection connection, String callId)
        throws SQLException {
        var sql = """
            SELECT * FROM ai_model_executions WHERE call_id = ?
            ORDER BY started_at_ms, unit_index, attempt_index
            """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, callId);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<UsageExecutionRecord>();
                while (rows.next()) {
                    result.add(new UsageExecutionRecord(rows.getString("id"),
                        rows.getString("call_id"), rows.getLong("epoch"),
                        UsageUnitKind.valueOf(rows.getString("unit_kind")),
                        rows.getInt("unit_index"), rows.getInt("attempt_index"),
                        instant(rows, "started_at_ms"), instant(rows, "completed_at_ms"),
                        UsageStatus.valueOf(rows.getString("status")),
                        error(rows), rows.getString("request_model_id"),
                        rows.getString("response_model_id"), usage(rows)));
                }
                return List.copyOf(result);
            }
        }
    }

    private UsageCallItem mapCall(ResultSet row) throws SQLException {
        return new UsageCallItem(row.getString("id"), instant(row, "started_at_ms"),
            instant(row, "completed_at_ms"), nullableLong(row, "duration_ms"),
            row.getString("caller_plugin_name"), row.getString("caller_plugin_version"),
            row.getString("caller_detection_source"), row.getString("feature"),
            row.getString("operation"), row.getString("model_type"),
            row.getString("model_name"), row.getString("provider_name"),
            row.getString("provider_type"), row.getString("request_model_id"),
            row.getString("response_model_id"), row.getInt("streaming") != 0,
            UsageStatus.valueOf(row.getString("status")), row.getString("error_type"),
            row.getString("error_code"), row.getInt("step_count"),
            row.getInt("attempt_count"), row.getInt("missing_execution_count"),
            row.getInt("complete") != 0, usage(row));
    }

    private void rollupDay(Connection connection, LocalDate day) throws SQLException {
        var from = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        var to = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        try (var delete = connection.prepareStatement("DELETE FROM ai_usage_daily WHERE day = ?")) {
            delete.setString(1, day.toString());
            delete.executeUpdate();
        }
        var sql = """
            INSERT INTO ai_usage_daily (
              day, caller_plugin_name, feature, provider_name, provider_type, model_name,
              model_type, operation, status, usage_quality, call_count, input_tokens,
              output_tokens, cache_read_input_tokens, cache_creation_input_tokens,
              reasoning_output_tokens, accounted_total_tokens, known_usage_calls,
              missing_usage_calls, duration_sum_ms, incomplete_call_count
            )
            SELECT ?, COALESCE(caller_plugin_name, ''), COALESCE(feature, ''),
              provider_name, provider_type, model_name, model_type, operation, status,
              usage_quality, COUNT(*), SUM(input_tokens), SUM(output_tokens),
              SUM(cache_read_input_tokens), SUM(cache_creation_input_tokens),
              SUM(reasoning_output_tokens), SUM(accounted_total_tokens),
              SUM(CASE WHEN usage_quality <> 'MISSING' THEN 1 ELSE 0 END),
              SUM(CASE WHEN usage_quality = 'MISSING' THEN 1 ELSE 0 END),
              COALESCE(SUM(duration_ms), 0), SUM(CASE WHEN complete = 0 THEN 1 ELSE 0 END)
            FROM ai_calls WHERE started_at_ms >= ? AND started_at_ms < ?
            GROUP BY COALESCE(caller_plugin_name, ''), COALESCE(feature, ''), provider_name,
              provider_type, model_name, model_type, operation, status, usage_quality
            """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, day.toString());
            statement.setLong(2, from);
            statement.setLong(3, to);
            statement.executeUpdate();
        }
    }

    private void rollupTokenDay(Connection connection, LocalDate day) throws SQLException {
        var from = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        var to = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        try (var delete = connection.prepareStatement(
            "DELETE FROM ai_token_usage_daily WHERE day = ?")) {
            delete.setString(1, day.toString());
            delete.executeUpdate();
        }
        var sql = """
            INSERT INTO ai_token_usage_daily (
              day, caller_plugin_name, feature, provider_name, provider_type, model_name,
              model_type, operation, status, usage_quality, fact_count, input_tokens,
              output_tokens, cache_read_input_tokens, cache_creation_input_tokens,
              reasoning_output_tokens, accounted_total_tokens
            )
            SELECT day, caller_plugin_name, feature, provider_name, provider_type, model_name,
              model_type, operation, status, usage_quality, COUNT(*), SUM(input_tokens),
              SUM(output_tokens), SUM(cache_read_input_tokens),
              SUM(cache_creation_input_tokens), SUM(reasoning_output_tokens),
              SUM(accounted_total_tokens)
            FROM (
              SELECT ? day, COALESCE(c.caller_plugin_name, '') caller_plugin_name,
                COALESCE(c.feature, '') feature, c.provider_name, c.provider_type, c.model_name,
                c.model_type, c.operation, e.status, e.usage_quality, e.input_tokens,
                e.output_tokens, e.cache_read_input_tokens, e.cache_creation_input_tokens,
                e.reasoning_output_tokens, e.accounted_total_tokens
              FROM ai_model_executions e JOIN ai_calls c ON c.id = e.call_id
              WHERE e.started_at_ms >= ? AND e.started_at_ms < ?
              UNION ALL
              SELECT ? day, COALESCE(c.caller_plugin_name, ''), COALESCE(c.feature, ''),
                c.provider_name, c.provider_type, c.model_name, c.model_type, c.operation,
                c.status, c.usage_quality, c.input_tokens, c.output_tokens,
                c.cache_read_input_tokens, c.cache_creation_input_tokens,
                c.reasoning_output_tokens, c.accounted_total_tokens
              FROM ai_calls c WHERE c.started_at_ms >= ? AND c.started_at_ms < ?
                AND NOT EXISTS (
                  SELECT 1 FROM ai_model_executions e WHERE e.call_id = c.id
                )
            ) facts
            GROUP BY day, caller_plugin_name, feature, provider_name, provider_type, model_name,
              model_type, operation, status, usage_quality
            """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, day.toString());
            statement.setLong(2, from);
            statement.setLong(3, to);
            statement.setString(4, day.toString());
            statement.setLong(5, from);
            statement.setLong(6, to);
            statement.executeUpdate();
        }
    }

    private SqlFilter filter(UsageQuery query, String timeColumn, boolean daily) {
        var clauses = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        if (daily) {
            var fromDay = query.from().atZone(ZoneOffset.UTC).toLocalDate();
            if (!query.from().equals(fromDay.atStartOfDay().toInstant(ZoneOffset.UTC))) {
                fromDay = fromDay.plusDays(1);
            }
            clauses.add("day >= ?");
            parameters.add(fromDay.toString());
            clauses.add("day < ?");
            parameters.add(query.to().atZone(ZoneOffset.UTC).toLocalDate().toString());
        } else {
            clauses.add(timeColumn + " >= ?");
            parameters.add(query.from().toEpochMilli());
            clauses.add(timeColumn + " < ?");
            parameters.add(query.to().toEpochMilli());
        }
        addFilter(clauses, parameters, "caller_plugin_name", query.callerPlugin());
        addFilter(clauses, parameters, "feature", query.feature());
        addFilter(clauses, parameters, "provider_name", query.providerName());
        addFilter(clauses, parameters, "model_name", query.modelName());
        addFilter(clauses, parameters, "model_type", query.modelType());
        addFilter(clauses, parameters, "operation", query.operation());
        addFilter(clauses, parameters, "status",
            query.status() == null ? null : query.status().name());
        addFilter(clauses, parameters, "usage_quality",
            query.usageQuality() == null ? null : query.usageQuality().name());
        return new SqlFilter("WHERE " + String.join(" AND ", clauses), parameters);
    }

    private SourcePlan sourcePlan(Connection connection, UsageQuery query) throws SQLException {
        var watermarkValue = stringMeta(connection, "rollup_watermark");
        if (watermarkValue == null) {
            return new SourcePlan(List.of(query), null);
        }
        var watermarkEnd = LocalDate.parse(watermarkValue).plusDays(1)
            .atStartOfDay().toInstant(ZoneOffset.UTC);
        if (query.effectiveResolution() == UsageTrendResolution.HOUR) {
            var detailStartValue = stringMeta(connection, "execution_detail_start");
            if (detailStartValue != null) {
                var detailStart = LocalDate.parse(detailStartValue)
                    .atStartOfDay().toInstant(ZoneOffset.UTC);
                if (detailStart.isBefore(watermarkEnd)) {
                    watermarkEnd = detailStart;
                }
            }
        }
        var fromDay = query.from().atZone(ZoneOffset.UTC).toLocalDate();
        var dailyStart = query.from().equals(fromDay.atStartOfDay().toInstant(ZoneOffset.UTC))
            ? query.from() : fromDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        var toDay = query.to().atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay().toInstant(ZoneOffset.UTC);
        var dailyEnd = toDay.isBefore(watermarkEnd) ? toDay : watermarkEnd;
        if (!dailyStart.isBefore(dailyEnd)) {
            return new SourcePlan(List.of(query), null);
        }
        var raw = new ArrayList<UsageQuery>(2);
        if (query.from().isBefore(dailyStart)) {
            raw.add(withRange(query, query.from(), dailyStart));
        }
        if (dailyEnd.isBefore(query.to())) {
            raw.add(withRange(query, dailyEnd, query.to()));
        }
        return new SourcePlan(List.copyOf(raw), withRange(query, dailyStart, dailyEnd));
    }

    private static UsageQuery withRange(UsageQuery source, Instant from, Instant to) {
        return new UsageQuery(from, to, source.callerPlugin(), source.feature(),
            source.providerName(), source.modelName(), source.modelType(), source.operation(),
            source.status(), source.usageQuality(), source.resolution());
    }

    private static String trendBucket(String timestampColumn, UsageTrendResolution resolution) {
        return resolution == UsageTrendResolution.HOUR
            ? "strftime('%Y-%m-%dT%H:00:00Z', " + timestampColumn + " / 1000, 'unixepoch')"
            : "strftime('%Y-%m-%dT00:00:00Z', " + timestampColumn + " / 1000, 'unixepoch')";
    }

    private static UsageTrendPoint mergePoint(UsageTrendPoint left, UsageTrendPoint right) {
        return new UsageTrendPoint(left.bucketStart(), left.resolution(),
            left.callCount() + right.callCount(), add(left.inputTokens(), right.inputTokens()),
            add(left.outputTokens(), right.outputTokens()),
            add(left.accountedTotalTokens(), right.accountedTotalTokens()),
            left.knownUsageCalls() + right.knownUsageCalls(),
            left.missingUsageCalls() + right.missingUsageCalls(),
            left.complete() && right.complete());
    }

    private static Long add(Long left, Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.addExact(left, right);
    }

    private static void addFilter(List<String> clauses, List<Object> parameters, String column,
        String value) {
        if (value != null && !value.isBlank()) {
            clauses.add(column + " = ?");
            parameters.add(value);
        }
    }

    private String quickCheck(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("PRAGMA quick_check")) {
            return rows.next() ? rows.getString(1) : "no-result";
        }
    }

    private <T> T withReader(SqlFunction<Connection, T> operation) {
        boolean acquired = false;
        try {
            readerPermits.acquire();
            acquired = true;
            requireInitialized();
            try (var connection = openConnection()) {
                activeReaders.add(connection);
                try {
                    return operation.apply(connection);
                } finally {
                    activeReaders.remove(connection);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for a statistics reader", error);
        } catch (SQLException error) {
            throw databaseError("read statistics", error);
        } finally {
            if (acquired) {
                readerPermits.release();
            }
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters)
        throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    private static int bindUsage(PreparedStatement statement, int index, NormalizedUsage usage)
        throws SQLException {
        var value = usage == null ? NormalizedUsage.missing() : usage;
        setLong(statement, index++, value.inputTokens());
        setLong(statement, index++, value.outputTokens());
        setLong(statement, index++, value.cacheReadInputTokens());
        setLong(statement, index++, value.cacheCreationInputTokens());
        setLong(statement, index++, value.reasoningOutputTokens());
        setLong(statement, index++, value.providerTotalTokens());
        setLong(statement, index++, value.accountedTotalTokens());
        statement.setString(index++, value.quality().name());
        return index;
    }

    private static NormalizedUsage usage(ResultSet row) throws SQLException {
        return new NormalizedUsage(nullableLong(row, "input_tokens"),
            nullableLong(row, "output_tokens"), nullableLong(row, "cache_read_input_tokens"),
            nullableLong(row, "cache_creation_input_tokens"),
            nullableLong(row, "reasoning_output_tokens"),
            nullableLong(row, "provider_total_tokens"),
            nullableLong(row, "accounted_total_tokens"),
            UsageQuality.valueOf(row.getString("usage_quality")));
    }

    private static UsageError error(ResultSet row) throws SQLException {
        var type = row.getString("error_type");
        var code = row.getString("error_code");
        return type == null && code == null ? null : new UsageError(type, code);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = nullableLong(row, column);
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        var value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void setLong(PreparedStatement statement, int index, Long value)
        throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value)
        throws SQLException {
        setLong(statement, index, value == null ? null : value.toEpochMilli());
    }

    private static long longMeta(Connection connection, String key, long fallback) {
        try (var statement = connection.prepareStatement(
            "SELECT value FROM ai_statistics_meta WHERE key = ?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Long.parseLong(rows.getString(1)) : fallback;
            }
        } catch (SQLException error) {
            throw databaseError("read metadata", error);
        }
    }

    private static String stringMeta(Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT value FROM ai_statistics_meta WHERE key = ?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static void putMeta(Connection connection, String key, String value)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO ai_statistics_meta(key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("AI usage statistics are not initialized");
        }
    }

    private void closeSilently() {
        var connection = writer;
        writer = null;
        close(connection);
    }

    private static synchronized void ensureDriverRegistered() throws SQLException {
        try {
            DriverManager.getDriver("jdbc:sqlite::memory:");
        } catch (SQLException ignored) {
            DriverManager.registerDriver(new JDBC());
        }
    }

    private static void deregisterPluginDrivers() {
        var pluginClassLoader = SqliteUsageStatisticsStore.class.getClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            var driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() != pluginClassLoader) {
                continue;
            }
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException error) {
                log.warn("Failed to deregister JDBC driver {}", driver.getClass().getName(), error);
            }
        }
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Best effort during shutdown.
            }
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            log.warn("Failed to roll back AI usage statistics transaction", rollbackError);
        }
    }

    private static void setAutoCommit(Connection connection, boolean value) {
        try {
            connection.setAutoCommit(value);
        } catch (SQLException error) {
            log.warn("Failed to restore AI usage statistics auto-commit", error);
        }
    }

    private static IllegalStateException databaseError(String operation, SQLException error) {
        return new IllegalStateException("Failed to " + operation, error);
    }

    private static <T, R> R value(T source, java.util.function.Function<T, R> mapper) {
        return source == null ? null : mapper.apply(source);
    }

    private record SqlFilter(String sql, List<Object> parameters) {
    }

    private record SummaryValues(long calls, long inProgress, long succeeded, long failed,
                                 long timedOut,
                                 long cancelled, long abandoned, long known, long missing,
                                 long incomplete) {

        private static SummaryValues empty() {
            return new SummaryValues(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        private SummaryValues add(SummaryValues other) {
            return new SummaryValues(calls + other.calls, inProgress + other.inProgress,
                succeeded + other.succeeded,
                failed + other.failed, timedOut + other.timedOut,
                cancelled + other.cancelled, abandoned + other.abandoned,
                known + other.known, missing + other.missing, incomplete + other.incomplete);
        }
    }

    private record SourcePlan(List<UsageQuery> raw, UsageQuery daily) {
    }

    private record TokenValues(Long input, Long output, Long cacheRead, Long cacheCreation,
                               Long reasoning, Long total) {

        private static TokenValues empty() {
            return new TokenValues(null, null, null, null, null, null);
        }

        private TokenValues add(TokenValues other) {
            return new TokenValues(SqliteUsageStatisticsStore.add(input, other.input),
                SqliteUsageStatisticsStore.add(output, other.output),
                SqliteUsageStatisticsStore.add(cacheRead, other.cacheRead),
                SqliteUsageStatisticsStore.add(cacheCreation, other.cacheCreation),
                SqliteUsageStatisticsStore.add(reasoning, other.reasoning),
                SqliteUsageStatisticsStore.add(total, other.total));
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }
}
