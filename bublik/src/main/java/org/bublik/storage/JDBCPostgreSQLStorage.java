package org.bublik.storage;

import de.bytefish.pgbulkinsert.exceptions.BinaryWriteFailedException;
import de.bytefish.pgbulkinsert.pgsql.constants.DataType;
import de.bytefish.pgbulkinsert.pgsql.model.interval.Interval;
import de.bytefish.pgbulkinsert.pgsql.model.range.Range;
import de.bytefish.pgbulkinsert.row.SimpleRow;
import de.bytefish.pgbulkinsert.row.SimpleRowWriter;
import de.bytefish.pgbulkinsert.util.PostgreSqlUtils;
import oracle.sql.INTERVALDS;
import oracle.sql.INTERVALYM;
import org.bublik.constants.PGKeywords;
import org.bublik.exception.TableNotExistsException;
import org.bublik.model.*;
import org.bublik.service.JDBCStorageService;
import org.bublik.service.TableService;
import org.postgresql.PGConnection;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import static org.bublik.exception.Utils.getStackTrace;
import static org.bublik.util.ColumnUtil.*;

public class JDBCPostgreSQLStorage extends JDBCStorage implements JDBCStorageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JDBCPostgreSQLStorage.class);
    private static JDBCPostgreSQLStorage toInstance;
    private static JDBCPostgreSQLStorage fromInstance;

    private JDBCPostgreSQLStorage(StorageClass storageClass,
                                  ConnectionProperty connectionProperty,
                                  Boolean isSource) throws SQLException {
        super(storageClass, connectionProperty, isSource);
    }

    public static synchronized JDBCPostgreSQLStorage getInstance(StorageClass storageClass,
                                                                 ConnectionProperty connectionProperty,
                                                                 Boolean isSource) throws SQLException{
        try {
            if (isSource) {
                if (toInstance == null) {
                    toInstance = new JDBCPostgreSQLStorage(storageClass, connectionProperty, isSource);
                }
                return toInstance;
            }
            if (fromInstance == null) {
                fromInstance = new JDBCPostgreSQLStorage(storageClass, connectionProperty, isSource);
            }
            return fromInstance;
        } catch (Exception e) {
            LOGGER.error("Connection error: {}", getStackTrace(e));
            throw e;
        }
    }

    @Override
    public Map<Integer, Chunk<?>> getChunkMap(List<Config> configs) throws SQLException {
        Map<Integer, Chunk<?>> chunkHashMap = new TreeMap<>();
        String sql = buildStartEndOfChunk(configs);
        LOGGER.debug("SQL to fetch metadata of chunks: \n{}", sql);
        StringBuffer sb = new StringBuffer();
        for (Config c : configs)
            sb.append("\n").append(buildFetchStatement(c));
        LOGGER.debug("SQL to fetch chunks: {}", sb);
        Connection initialConnection = getConnection();
        PreparedStatement statement = initialConnection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery();
        if (resultSet.isBeforeFirst()) {
            while (resultSet.next()) {
                Config config = findByTaskName(configs, resultSet.getString("task_name"));
                Table sourceTable = TableService.getTable(initialConnection, config.fromSchemaName(), config.fromTableName());
                if (!sourceTable.exists(initialConnection)) {
                    initialConnection.close();
                    LOGGER.error("\u001B[31mThe Source Table: {}.{} does not exist.\u001B[0m", sourceTable.getSchemaName(),
                            sourceTable.getTableName());
                    throw new TableNotExistsException(sourceTable.getSchemaName(), sourceTable.getTableName());
                }
                String query = buildFetchStatement(config);
                chunkHashMap.put(resultSet.getInt("rownum"),
                        new PGChunk<>(
                                resultSet.getInt("chunk_id"),
                                resultSet.getLong("start_page"),
                                resultSet.getLong("end_page"),
                                config,
                                sourceTable,
                                query,
                                this
                        )
                );
            }
        }
        resultSet.close();
        statement.close();
        initialConnection.close();
        return chunkHashMap;
    }

    @Override
    public String buildStartEndOfChunk(List<Config> configs) {
        List<String> taskNames = new ArrayList<>();
        configs.forEach(sqlStatement -> taskNames.add(sqlStatement.fromTaskName()));
        return "select row_number() over (order by chunk_id) as rownum, chunk_id, start_page, end_page, task_name from public.ctid_chunks where task_name in ('" +
                String.join("', '", taskNames) + "') " +
                "and status <> 'PROCESSED' ";
    }

    @Override
    public LogMessage transferToTarget(Chunk<?> chunk) throws SQLException, BinaryWriteFailedException {
        ResultSet fetchResultSet = chunk.getResultSet();
        if (fetchResultSet.next()) {
            Connection connectionTo;
            while (true) {
                try {
                    connectionTo = getConnection();
                    break;
                } catch (SQLException e) {
                    LOGGER.warn("There are no available connections in target pool ... {}", e.getMessage());
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ignored) {
                        ;
                    }
                }
            }
            Table table = TableService.getTable(connectionTo, chunk.getConfig().toSchemaName(), chunk.getConfig().toTableName());
//            System.out.println(table.exists(connectionTo));
            if (table.exists(connectionTo)) {
                chunk.setTargetTable(table);
                try {
                    LogMessage logMessage = fetchAndCopy(connectionTo, fetchResultSet, chunk);
                    connectionTo.close();
                    return logMessage;
                } catch (SQLException e) {
                    connectionTo.rollback();
                    connectionTo.close();
                    throw e;
                } catch (BinaryWriteFailedException b) {
                    PGConnection pgConnection = PostgreSqlUtils.getPGConnection(connectionTo);
                    pgConnection.cancelQuery();
                    LOGGER.error("BinaryWriteFailedException caught by transferToTarget() {}", getStackTrace(b));
//                    connectionTo.abort( null);
                    throw b;
                } finally {
                    ;
                }
            } else {
                LOGGER.error("\u001B[31mThe Target Table: {}.{} does not exist.\u001B[0m", chunk.getConfig().toSchemaName(),
                        chunk.getConfig().toTableName());
                throw new TableNotExistsException("The Target Table "
                        + chunk.getConfig().toSchemaName() + "."
                        + chunk.getConfig().toTableName() + " does not exist.");
            }
        } else {
            return new LogMessage(
                    0,
                    chunk.getStartTime(),
                    System.currentTimeMillis(),
                    "NO ROWS FETCH",
                    chunk);
        }
    }

    private LogMessage fetchAndCopy(Connection connectionTo,
                                    ResultSet fetchResultSet,
                                    Chunk<?> chunk) throws SQLException, BinaryWriteFailedException {
        int recordCount = 0;

        try {
            chunk.insertProcessedChunkInfo(connectionTo, recordCount);
            connectionTo.rollback();
        } catch (PSQLException p) {
            connectionTo.rollback();
            return new LogMessage(
                    0,
                    chunk.getStartTime(),
                    System.currentTimeMillis(),
                    "The chunk's already been copied",
                    chunk);
        }

        Map<String, PGColumn> neededColumnsToDB = readTargetColumnsAndTypes(connectionTo, chunk);
//        neededColumnsToDB.forEach((s, pgColumn) -> System.out.println(s + " " + pgColumn.getColumnName() + ":" + pgColumn.getColumnType()));
        Map<List<String>, PGColumn> neededColumnsFromMany = readTargetColumnsAndTypesFromMany(connectionTo, chunk);
        Map<String, PGEncryptedColumn> neededEncryptedColumns = readTargetEncryptedColumnsAndTypes(connectionTo, chunk);
/*
        neededEncryptedColumns.forEach((s1, pgEncryptedColumn) -> System.out.println(s1 + " " +
                pgEncryptedColumn.pgColumn().getColumnName() + " " +
                pgEncryptedColumn.encryptedColumn().targetEncColumnName() + " " +
                pgEncryptedColumn.encryptedColumn().targetEncMetaColumnName()));
*/
        PGConnection pgConnection = PostgreSqlUtils.getPGConnection(connectionTo);
        String[] columnNames = neededColumnsToDB
                .values()
                .stream()
                .map(PGColumn::getColumnName)
                .toList()
                .toArray(String[]::new);
        String[] metaColumnNames = neededEncryptedColumns
                .values()
                .stream()
                .map(PGEncryptedColumn::encryptedColumn)
                .toList()
                .stream().map(EncryptedColumn::targetEncMetaColumnName)
                .filter(Objects::nonNull)
                .toArray(String[]::new);
        String[] cNames = Arrays.copyOf(columnNames, columnNames.length + metaColumnNames.length);
        System.arraycopy(metaColumnNames, 0, cNames, columnNames.length, metaColumnNames.length);
//        System.out.println(Arrays.toString(cNames));
        SimpleRowWriter.Table table =
                new SimpleRowWriter.Table(chunk.getTargetTable().getSchemaName(),
                        chunk.getTargetTable().getFinalTableName(true), cNames);

        SimpleRowWriter writer = new SimpleRowWriter(table, pgConnection);
        Consumer<SimpleRow> simpleRowConsumer =
            s -> {
                try {
                    simpleRowConsume(s, neededColumnsToDB, neededEncryptedColumns, neededColumnsFromMany,
                            fetchResultSet, chunk, connectionTo, writer);
                } catch (BinaryWriteFailedException | SQLException e) {
                    LOGGER.error("{}.{} {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                }
            };

        do {
            try {
                writer.startRow(simpleRowConsumer);
            } catch (BinaryWriteFailedException e) {
//                LOGGER.error("BinaryWriteFailedException caught by writer.startRow() {}", getStackTrace(e));
                throw e;
            }
            recordCount++;
        } while (fetchResultSet.next());

        try {
            writer.close();
        } catch (BinaryWriteFailedException b) {
            LOGGER.error("Can't close stream. {}", getStackTrace(b));
            throw b;
        }

        chunk.insertProcessedChunkInfo(connectionTo, recordCount);
        connectionTo.commit();

        return new LogMessage(
                recordCount,
                chunk.getStartTime(),
                System.currentTimeMillis(),
                "PostgreSQL COPY",
                chunk);
    }

    protected Map<String, PGColumn> readTargetColumnsAndTypes(Connection connectionTo, Chunk<?> chunk) {
        Map<String, PGColumn> columnMap = new HashMap<>();
        try {
            ResultSet resultSet = connectionTo.getMetaData().getColumns(
                    null,
                    chunk.getTargetTable().getSchemaName().toLowerCase(),
                    chunk.getTargetTable().getFinalTableName(false),
                    null);
            Map<String, String> columnToColumnMap = chunk.getConfig().columnToColumn();
            Map<String, String> expressionToColumnMap = chunk.getConfig().expressionToColumn();
            Map<String, List<String>> columnFromManyMap = chunk.getConfig().columnFromMany();
            Map<String, EncryptedColumn> encryptedEntityMap = chunk.getConfig().expressionToCrypto();
            Map<String, String> cryptoToColumnMap = chunk.getConfig().cryptoToColumn();

            while (resultSet.next()) {
                String columnName = resultSet.getString(4);
                String columnType = resultSet.getString(6);
                Integer columnPosition = resultSet.getInt(17);

                if (columnToColumnMap != null) {
                    columnToColumnMap
                            .entrySet()
                            .stream()
                            .filter(s -> s.getValue().replaceAll("\"", "").equalsIgnoreCase(columnName))
                            .forEach(i -> columnMap.put(i.getKey(), new PGColumn(columnPosition, i.getValue(), columnType.equals("bigserial") ? "bigint" : columnType)));
                }

                if (expressionToColumnMap != null) {
                    expressionToColumnMap
                            .entrySet()
                            .stream()
                            .filter(s -> s.getValue().replaceAll("\"", "").equalsIgnoreCase(columnName))
                            .forEach(i -> columnMap.put(columnName, new PGColumn(columnPosition, i.getValue(), columnType.equals("bigserial") ? "bigint" : columnType)));
                }

                if (encryptedEntityMap != null) {
                    encryptedEntityMap
                            .entrySet()
                            .stream()
                            .filter(s -> {
                                if (s.getValue().targetEncColumnName() != null) {
                                    return s.getValue().targetEncColumnName().replaceAll("\"", "").equalsIgnoreCase(columnName);
                                }
                                return false;
                            })
                            .forEach(i -> columnMap.put(columnName, new PGColumn(columnPosition, i.getValue().targetEncColumnName(), columnType.equals("bigserial") ? "bigint" : columnType)));
                }

                if (cryptoToColumnMap != null) {
                    cryptoToColumnMap
                            .entrySet()
                            .stream()
                            .filter(s -> s.getValue().replaceAll("\"", "").equalsIgnoreCase(columnName))
                            .forEach(i -> columnMap.put(i.getKey(), new PGColumn(columnPosition, i.getValue(), columnType.equals("bigserial") ? "bigint" : columnType)));
                }

                if (columnFromManyMap != null) {
                    columnFromManyMap
                            .entrySet()
                            .stream()
                            .filter(s -> s.getKey().replaceAll("\"", "").equalsIgnoreCase(columnName))
                            .forEach(i -> columnMap.put(i.getKey(), new PGColumn(columnPosition, i.getKey(), columnType.equals("bigserial") ? "bigint" : columnType)));
                }
            }
            resultSet.close();
        } catch (SQLException e) {
            LOGGER.error("{}", e.getMessage());
        }
        return columnMap;
    }

    protected Map<List<String>, PGColumn> readTargetColumnsAndTypesFromMany(Connection connectionTo, Chunk<?> chunk) {
        Map<List<String>, PGColumn> columnMap = new HashMap<>();
        try {
            ResultSet resultSet = connectionTo.getMetaData().getColumns(
                    null,
                    chunk.getTargetTable().getSchemaName().toLowerCase(),
                    chunk.getTargetTable().getFinalTableName(false),
                    null);
            Map<String, List<String>> columnFromManyMap = chunk.getConfig().columnFromMany();

            while (resultSet.next()) {
                String columnName = resultSet.getString(4);
                String columnType = resultSet.getString(6);
                Integer columnPosition = resultSet.getInt(17);

                if (columnFromManyMap != null) {
                    columnFromManyMap
                            .entrySet()
                            .stream()
                            .filter(s -> s.getKey().replaceAll("\"", "").equalsIgnoreCase(columnName))
                            .forEach(i -> columnMap.put(i.getValue(), new PGColumn(columnPosition, i.getKey(), columnType.equals("bigserial") ? "bigint" : columnType)));
                }
            }
            resultSet.close();
        } catch (SQLException e) {
            LOGGER.error("{}", getStackTrace(e));
        }
        return columnMap;
    }

    protected Map<String, PGEncryptedColumn> readTargetEncryptedColumnsAndTypes(Connection connectionTo, Chunk<?> chunk) {
        Map<String, PGEncryptedColumn> columnMap = new HashMap<>();
        try {
            ResultSet resultSet = connectionTo.getMetaData().getColumns(
                    null,
                    chunk.getTargetTable().getSchemaName().toLowerCase(),
                    chunk.getTargetTable().getFinalTableName(false),
                    null);
            Map<String, EncryptedColumn> encryptedEntityMap = chunk.getConfig().expressionToCrypto();

            while (resultSet.next()) {
                String columnName = resultSet.getString(4);
                String columnType = resultSet.getString(6);
                Integer columnPosition = resultSet.getInt(17);

                if (encryptedEntityMap != null) {
                    encryptedEntityMap
                            .entrySet()
                            .stream()
                            .filter(entry -> {
                                if (entry.getValue().targetEncColumnName() != null) {
                                    return entry.getValue().targetEncColumnName().replaceAll("\"", "").equalsIgnoreCase(columnName);
                                } else return entry.getValue().targetEncMetaColumnName() != null;
                            })
                            .forEach(entry -> columnMap.put(
                                columnName,
                                    new PGEncryptedColumn(
                                        new PGColumn(columnPosition,
                                                entry.getValue().targetEncColumnName() == null ? entry.getValue().targetEncMetaColumnName() : entry.getValue().targetEncColumnName(),
                                                columnType.equals("bigserial") ? "bigint" : columnType),
                                        entry.getValue()
                            )));
                }

            }
            resultSet.close();
        } catch (SQLException e) {
            LOGGER.error("{}", e.getMessage());
        }
        return columnMap;
    }

    private void simpleRowConsume(SimpleRow row,
                                  Map<String, PGColumn> neededColumnsToDB,
                                  Map<String, PGEncryptedColumn> neededEncryptedColumns,
                                  Map<List<String>, PGColumn> neededColumnsFromMany,
                                  ResultSet fetchResultSet,
                                  Chunk<?> chunk,
                                  Connection connectionTo,
                                  SimpleRowWriter writer) throws SQLException, BinaryWriteFailedException {
//        System.out.println("simpleRowConsume...");
        for (Map.Entry<String, PGColumn> entry : neededColumnsToDB.entrySet()) {
            String sourceColumn = entry.getKey().replaceAll("\"", "");
            String targetColumn = entry.getValue().getColumnName();
            String targetType = entry.getValue().getColumnType();

    /*
                try {
                    tmpString
                            .append(targetType)
                            .append(" : ")
                            .append(targetColumn)
                            .append(" : ")
                            .append(fetchResultSet.getString(sourceColumn)).append("\n");
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
    */

            switch (targetType) {
                case "varchar": {
                    try {
                        String s = fetchResultSet.getString(sourceColumn);
                        if (s == null) {
                            row.setText(targetColumn, null);
                            break;
                        }
/*
                        if (neededEncryptedColumns.get(targetColumn) != null) {
                            String aad = fetchResultSet.getObject(neededEncryptedColumns.get(targetColumn).encryptedColumn().sourceAadColumnName()).toString();
                            System.out.println(aad);
                            EncryptedEntity encryptedEntity = SecureUtil.getEncryptedEntity(getConnectionProperty(), s, aad);
                            String e = encryptedEntity.obtainEncryptedData();
                            if (neededEncryptedColumns.get(targetColumn).encryptedColumn().targetEncColumnName() != null) {
                                row.setText(neededEncryptedColumns.get(targetColumn).encryptedColumn().targetEncColumnName(), e);
                            }
                            if (neededEncryptedColumns.get(targetColumn).encryptedColumn().targetEncMetaColumnName() != null) {
                                String m = encryptedEntity.obtainEncryptedMetaData();
                                row.setJsonb(neededEncryptedColumns.get(targetColumn).encryptedColumn().targetEncMetaColumnName(), m);
                            }
                            break;
                        }
*/
                        row.setText(targetColumn, s.replaceAll("\u0000", ""));
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
/*
                    } catch (ClassNotFoundException | InvocationTargetException | NoSuchMethodException |
                             InstantiationException | IllegalAccessException e) {
                        LOGGER.error("{}", getStackTrace(e));
                    }
*/
                }
                case "bpchar":
                    try {
                        String string = fetchResultSet.getString(sourceColumn);
                        row.setText(targetColumn, string);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                case "text": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setText(targetColumn, null);
                            break;
                        }
                        String text;
                        int cIndex = getColumnIndexByColumnName(fetchResultSet, sourceColumn);
                        if (cIndex != 0 && fetchResultSet.getMetaData().getColumnType(cIndex) == 2005) {
                            text = convertClobToString(fetchResultSet, sourceColumn);
                        } else {
                            text = fetchResultSet.getString(sourceColumn);
                        }
                        row.setText(targetColumn, text.replaceAll("\u0000", ""));
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "jsonb": {
                    try {
                    Object o = fetchResultSet.getObject(sourceColumn);
                    if (o == null) {
                        row.setJsonb(targetColumn, null);
                        break;
                    }
                    String s = null;
                    if (chunk instanceof OraChunk<?>) {
                            int columnIndex = getColumnIndexByColumnName(fetchResultSet, sourceColumn.toUpperCase());
                            int columnType = fetchResultSet.getMetaData().getColumnType(columnIndex);
                            switch (columnType) {
                                // CLOB
                                case 2005:
                                    s = convertClobToString(fetchResultSet, sourceColumn).replaceAll("\u0000", "");
                                    break;
                                // NCLOB
                                case 2011:
                                    s = convertClobToString(fetchResultSet, sourceColumn).replaceAll("\u0000", "");
//                                    System.out.println(s);
                                    break;
                                default:
                                    s = fetchResultSet.getString(sourceColumn).replaceAll("\u0000", "");
                                    break;
                            }
                        } else if (chunk instanceof PGChunk<?>) {
                            s = fetchResultSet.getString(sourceColumn);
                        }
                        row.setJsonb(targetColumn, s);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "smallserial", "int2": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setShort(targetColumn, null);
                            break;
                        }
                        Short aShort = fetchResultSet.getShort(sourceColumn);
                        row.setShort(targetColumn, aShort);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "serial", "int4": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setInteger(targetColumn, null);
                            break;
                        }
                        int i = fetchResultSet.getInt(sourceColumn);
                        row.setInteger(targetColumn, i);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "bigint", "int8": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setLong(targetColumn, null);
                            break;
                        }
                        long l = fetchResultSet.getLong(sourceColumn);
                        row.setLong(targetColumn, l);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("\u001B[31m{}.{} {} -> {}\u001B[0m: {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), sourceColumn, targetColumn, getStackTrace(e));
                        throw e;
                    }
                }
                case "numeric": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setNumeric(targetColumn, null);
                            break;
                        }
                        row.setNumeric(targetColumn, (Number) o);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("\u001B[31m{}.{} {} -> {}\u001B[0m: {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), sourceColumn, targetColumn, getStackTrace(e));
                        throw e;
                    }
                }
                case "float4": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setDouble(targetColumn, null);
                            break;
                        }
                        Float aFloat = fetchResultSet.getFloat(sourceColumn);
                        row.setFloat(targetColumn, aFloat);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("\u001B[31m{}.{} {} -> {}\u001B[0m: {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), sourceColumn, targetColumn, getStackTrace(e));
                        throw e;
                    }
                }
                case "float8": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setDouble(targetColumn, null);
                            break;
                        }
                        Double aDouble = fetchResultSet.getDouble(sourceColumn);
                        row.setDouble(targetColumn, aDouble);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("\u001B[31m{}.{} {} -> {}\u001B[0m: {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), sourceColumn, targetColumn, getStackTrace(e));
                        throw e;
                    }
                }
                case "time": {
                    try {
                        Time time = fetchResultSet.getTime(sourceColumn);
                        if (time == null) {
                            row.setTimeStamp(targetColumn, null);
                            break;
                        }
                        long l = time.getTime();
                        LocalTime localTime = LocalTime.ofInstant(Instant.ofEpochMilli(l),
                                TimeZone.getDefault().toZoneId());
                        row.setValue(targetColumn, DataType.Time, localTime);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "timestamp": {
                    try {
                        Timestamp timestamp = fetchResultSet.getTimestamp(sourceColumn);
//                        System.out.println(timestamp);
                        if (timestamp == null) {
                            row.setTimeStamp(targetColumn, null);
                            break;
                        }
/*
                        long l = timestamp.getTime();
                        LocalDateTime localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(l),
                                TimeZone.getDefault().toZoneId());
*/
                        LocalDateTime localDateTime = timestamp.toLocalDateTime();
//                        System.out.println(localDateTime);
                        row.setTimeStamp(targetColumn, localDateTime);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "timestamptz": {
                    try {
                        Timestamp timestamp = fetchResultSet.getTimestamp(sourceColumn);
                        if (timestamp == null) {
                            row.setTimeStamp(targetColumn, null);
                            break;
                        }
/*
                        ZonedDateTime zonedDateTime =
                                ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp.getTime()),
                                        ZoneOffset.UTC);
*/
                        ZonedDateTime zonedDateTime =
                                ZonedDateTime.ofInstant(timestamp.toInstant(), ZoneId.of("UTC"));
                        row.setTimeStampTz(targetColumn, zonedDateTime);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "date":
                    try {
                        Date date = fetchResultSet.getDate(sourceColumn);
                        if (date == null) {
                            row.setDate(targetColumn, null);
                            break;
                        }
                        row.setDate(targetColumn, date.toLocalDate());
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                case "tstzrange":
                    try {
                        List<String> sourceColumns = neededColumnsFromMany
                                .entrySet()
                                .stream()
                                .filter(i -> i.getValue().getColumnName().equals(targetColumn))
                                .map(Map.Entry::getKey)
                                .toList().getLast();
                        Timestamp start = fetchResultSet.getTimestamp(sourceColumns.getFirst());
                        Timestamp end = fetchResultSet.getTimestamp(sourceColumns.getLast());
                        ZonedDateTime lowerBound = null;
                        if (start != null) {
                             lowerBound = ZonedDateTime.ofInstant(start.toInstant(), ZoneId.of("UTC"));
                        }
                        ZonedDateTime upperBound = null;
                        if (end != null) {
                            upperBound = ZonedDateTime.ofInstant(end.toInstant(), ZoneId.of("UTC"));
                        }
                        Range<ZonedDateTime> localDateTimeRange = new Range<>(
                                lowerBound,
                                true,
                                lowerBound == null,
                                upperBound,
                                true,
                                upperBound == null);
                        row.setTsTzRange(targetColumn, localDateTimeRange);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("\u001B[31mtstzrange\u001B[0m : {}.{} - {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                case "interval":
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setDouble(targetColumn, null);
                            break;
                        }
                        Interval interval = null;
                        if (chunk instanceof OraChunk<?>) {
                            int columnIndex = getColumnIndexByColumnName(fetchResultSet, sourceColumn.toUpperCase());
                            int columnType = fetchResultSet.getMetaData().getColumnType(columnIndex);
                            switch (columnType) {
                                // INTERVALYM
                                case -103:
                                    INTERVALYM intervalym = (INTERVALYM) fetchResultSet.getObject(sourceColumn);
                                    interval = intervalYM2Interval(intervalym);
                                    break;
                                // INTERVALDS
                                case -104:
                                    INTERVALDS intervalds = (INTERVALDS) fetchResultSet.getObject(sourceColumn);
                                    interval = intervalDS2Interval(intervalds);
                                    break;
                                default:
                                    break;
                            }
                        } else if (chunk instanceof PGChunk<?>) {
                            PGInterval pgInterval = (PGInterval) fetchResultSet.getObject(sourceColumn);
                            interval = new Interval(
                                    pgInterval.getYears() * 12 + pgInterval.getMonths(),
                                    pgInterval.getDays(),
                                    pgInterval.getHours(),
                                    pgInterval.getMinutes(),
                                    (int) pgInterval.getSeconds(),
                                    pgInterval.getMicroSeconds());
                        }
                        row.setInterval(targetColumn, interval);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                case "bytea": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setByteArray(targetColumn, null);
                            break;
                        }
                        byte[] bytes = new byte[0];
                        if (chunk instanceof OraChunk<?>) {
                            int columnIndex = getColumnIndexByColumnName(fetchResultSet, sourceColumn.toUpperCase());
                            int columnType = fetchResultSet.getMetaData().getColumnType(columnIndex);
                            switch (columnType) {
                                // RAW
                                case -3:
                                    bytes = fetchResultSet.getBytes(sourceColumn);
                                    break;
                                // LONG RAW
                                case -4:
                                    bytes = fetchResultSet.getBytes(sourceColumn);
                                    break;
                                // BLOB
                                case 2004:
                                    bytes = convertBlobToBytes(fetchResultSet, sourceColumn);
                                    break;
                                default:
                                    break;
                            }
                        } else if (chunk instanceof PGChunk<?>) {
                            bytes = fetchResultSet.getBytes(sourceColumn);
                        }
                        row.setByteArray(targetColumn, bytes);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "bool": {
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setBoolean(targetColumn, null);
                            break;
                        }
                        boolean b = fetchResultSet.getBoolean(sourceColumn);
                        row.setBoolean(targetColumn, b);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                }
                case "uuid":
                    try {
                        Object o = fetchResultSet.getObject(sourceColumn);
                        if (o == null) {
                            row.setUUID(targetColumn, null);
                            break;
                        }
                        UUID uuid = null;
                        try {
                            uuid = (UUID) o;
                        } catch (ClassCastException e) {
                            try {
                                uuid = UUID.fromString((String) o);
                            } catch (Exception e1) {
                                e1.printStackTrace();
                                System.out.println(targetColumn + " : " + o);
                            }
                        }
                        row.setUUID(targetColumn, uuid);
                        break;
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
                default:
                    try {
                        if (chunk.getConfig().tryCharIfAny() != null) {
                            if (chunk.getConfig().tryCharIfAny().contains(targetColumn)) {
                                String s = fetchResultSet.getString(sourceColumn);
                                if (s == null) {
                                    row.setText(targetColumn, null);
                                    break;
                                }
                                row.setText(targetColumn, s.replaceAll("\u0000", ""));
                                break;
                            } else {
                                LOGGER.error("\u001B[31mThere is no handler for type : {}\u001B[0m", targetType);
                                writer.close();
                                connectionTo.close();
                            }
                        } else {
                            LOGGER.error("\u001B[31mtryCharIfAny is NULL\u001B[0m");
                            throw new RuntimeException();
                        }
                    } catch (BinaryWriteFailedException | SQLException e) {
                        LOGGER.error("{}.{} : {}", chunk.getTargetTable().getSchemaName(), chunk.getTargetTable().getTableName(), getStackTrace(e));
                        throw e;
                    }
            }
        }
    }

    @Override
    public String buildFetchStatement(Config config) {
        List<String> strings = new ArrayList<>();
        Map<String, String> columnToColumnMap = config.columnToColumn();
        Map<String, String> expressionToColumnMap = config.expressionToColumn();
        Map<String, EncryptedColumn> encryptedEntityMap = config.expressionToCrypto();
        Map<String, String> cryptoToColumnMap = config.cryptoToColumn();
        if (columnToColumnMap != null) {
            strings.addAll(columnToColumnMap.keySet());
        }
        if (expressionToColumnMap != null) {
            strings.addAll(expressionToColumnMap.keySet());
        }
        if (encryptedEntityMap != null) {
            strings.addAll(encryptedEntityMap.keySet());
        }
        if (cryptoToColumnMap != null) {
            strings.addAll(cryptoToColumnMap.keySet());
        }
        String columnToColumn = String.join(", ", strings);
        return PGKeywords.SELECT + " " +
                columnToColumn + " " +
                PGKeywords.FROM + " " +
                config.fromSchemaName() +
                "." +
                config.fromTableName() + " " +
                (config.fromTableAlias() == null ? "" : config.fromTableAlias()) + " " +
                (config.fromTableAdds() == null ? "" : config.fromTableAdds()) + " " +
                PGKeywords.WHERE + " " +
                config.fetchWhereClause() +
                " and " +
                (config.fromTableAlias() == null ? "" : config.fromTableAlias() + ".") +
                "ctid >= " + "concat('(', ? ,',1)')::tid" +
                " and " +
                (config.fromTableAlias() == null ? "" : config.fromTableAlias() + ".") +
                "ctid < " + "concat('(', ? ,',1)')::tid";
    }
}
