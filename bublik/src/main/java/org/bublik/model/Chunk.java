package org.bublik.model;

import org.bublik.service.ChunkService;
import org.bublik.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public abstract class Chunk<T> implements ChunkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(Chunk.class);

    private final Integer id;
    private final T start;
    private final T end;
    private final Config config;
    private final Table sourceTable;
    private final String fetchQuery;
    private Table targetTable;
    private final Storage sourceStorage;
    private long startTime;
    private Storage targetStorage;
    private Connection sourceConnection;
    private Connection targetConnection;
    private LogMessage logMessage;
    private ResultSet resultSet;

    public Chunk(Integer id, T start, T end, Config config, Table sourceTable,
                 String fetchQuery, Storage sourceStorage) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.config = config;
        this.sourceTable = sourceTable;
        this.fetchQuery = fetchQuery;
        this.sourceStorage = sourceStorage;
    }

    public Integer getId() {
        return id;
    }

    public T getStart() {
        return start;
    }

    public T getEnd() {
        return end;
    }

    public Config getConfig() {
        return config;
    }

    public Table getSourceTable() {
        return sourceTable;
    }

    public Table getTargetTable() {
        return targetTable;
    }

    public Storage getSourceStorage() {
        return sourceStorage;
    }

    public void setTargetTable(Table table) {
        this.targetTable = table;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public Storage getTargetStorage() {
        return targetStorage;
    }

    public Connection getSourceConnection() {
        return sourceConnection;
    }

    public void setSourceConnection(Connection sourceConnection) {
        this.sourceConnection = sourceConnection;
    }

    public Connection getTargetConnection() {
        return targetConnection;
    }

    public void setTargetConnection(Connection targetConnection) {
        this.targetConnection = targetConnection;
    }

    public LogMessage getLogMessage() {
        return logMessage;
    }

    public void setLogMessage(LogMessage logMessage) {
        this.logMessage = logMessage;
    }

    public ResultSet getResultSet() {
        return resultSet;
    }

    public void setResultSet(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    public void setTargetStorage(Storage targetStorage) {
        this.targetStorage = targetStorage;
    }

    public String getFetchQuery() {
        return fetchQuery;
    }

    public Chunk<?> assignSourceConnection() throws SQLException {
        while (true) {
            try {
                Connection sourceConnection = getSourceStorage().getConnection();
                setSourceConnection(sourceConnection);
                return this;
            } catch (SQLException e) {
                LOGGER.error("There are no available connections in source pool ...");
            }
        }
    }

    @Override
    public Chunk<?> assignSourceResultSet() throws SQLException {
        setStartTime(System.currentTimeMillis());
        ResultSet resultSet = getData(getSourceConnection(), getFetchQuery());
        setResultSet(resultSet);
        return this;
    }

    public Chunk<?> assignResultLogMessage() throws SQLException {
        try {
            LogMessage logMessage = this.getTargetStorage().transferToTarget(this);
            this.setLogMessage(logMessage);
            ResultSet resultSet = this.getResultSet();
            resultSet.close();
            return this;
        } catch (SQLException | RuntimeException e) {
            this.setLogMessage(new LogMessage (0, 0, 0, " UNREACHABLE TASK ", this));
            throw e;
        }
    }

    public Chunk<?> closeChunkSourceConnection() throws SQLException {
        Connection connection = getSourceConnection();
        if (connection.isValid(0)) {
            connection.close();
        } else {
            throw new RuntimeException();
        }
        return this;
    }
}
