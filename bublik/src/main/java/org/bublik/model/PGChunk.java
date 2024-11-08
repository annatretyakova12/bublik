package org.bublik.model;

import org.bublik.constants.ChunkStatus;
import org.bublik.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.bublik.constants.SQLConstants.*;

public class PGChunk<T extends Long> extends Chunk<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PGChunk.class);
    public PGChunk(Integer id, T start, T end, Config config, Table sourceTable, String fetchQuery, Storage sourceStorage) {
        super(id, start, end, config, sourceTable, fetchQuery, sourceStorage);
    }

    @Override
    public PGChunk<T> setChunkStatus(ChunkStatus status, Integer errNum, String errMsg) throws SQLException {
        Connection connection = this.getSourceConnection();
        PreparedStatement updateStatus;
        if (errMsg == null) {
            updateStatus = connection.prepareStatement(PLSQL_UPDATE_STATUS_CTID_CHUNKS);
            updateStatus.setString(1, status.toString());
            updateStatus.setLong(2, this.getId());
            updateStatus.setString(3, this.getConfig().fromTaskName());
        } else {
            updateStatus = connection.prepareStatement(DML_UPDATE_STATUS_CTID_CHUNKS_WITH_ERRORS);
            updateStatus.setString(1, status.toString());
            updateStatus.setString(2, errMsg.substring(0, errMsg.length() > 2048 ? 2047 : errMsg.length()));
            updateStatus.setLong(3, this.getId());
            updateStatus.setString(4, this.getConfig().fromTaskName());
        }
        int rows = updateStatus.executeUpdate();
        updateStatus.close();
        connection.commit();
//        LOGGER.debug("setChunkStatus {}", status);
        return this;
    }

    @Override
    public ResultSet getData(Connection connection, String query) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(query);
        statement.setLong(1, this.getStart());
        statement.setLong(2, this.getEnd());
        statement.setFetchSize(10000);
        return statement.executeQuery();
    }

    @Override
    public void insertProcessedChunkInfo(Connection connection, int rows) throws SQLException {
        PreparedStatement chunkInsert = connection.prepareStatement(DML_INSERT_BUBLIK_OUTBOX_CTID);
        chunkInsert.setLong(1, getId());
        chunkInsert.setLong(2, getStart());
        chunkInsert.setLong(3, getEnd());
        chunkInsert.setLong(4, rows);
        chunkInsert.setString(5, getConfig().fromTaskName());
        chunkInsert.setString(6, getTargetTable().getSchemaName().toLowerCase());
        chunkInsert.setString(7, getTargetTable().getFinalTableName(false));
        long r = chunkInsert.executeUpdate();
        chunkInsert.close();
    }
}
