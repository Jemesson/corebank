package com.corebank.helpers;

import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

public final class ResultSets {

    private ResultSets() {}

    public static Builder row() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> columns = new LinkedHashMap<>();

        public Builder with(String column, Object value) {
            columns.put(column, value);
            return this;
        }

        public ResultSet build() {
            ResultSet rs = mock(ResultSet.class);
            try {
                lenient().when(rs.getString(anyString())).thenAnswer(read(v -> (String) v));
                lenient().when(rs.getBigDecimal(anyString())).thenAnswer(read(v -> (BigDecimal) v));
                lenient().when(rs.getLong(anyString()))
                        .thenAnswer(read(v -> v == null ? 0L : ((Number) v).longValue()));
                lenient().when(rs.getInt(anyString()))
                        .thenAnswer(read(v -> v == null ? 0 : ((Number) v).intValue()));
                lenient().when(rs.getTimestamp(anyString()))
                        .thenAnswer(read(v -> v == null ? null : Timestamp.from((Instant) v)));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return rs;
        }

        private Answer<Object> read(java.util.function.Function<Object, Object> converter) {
            return invocation -> {
                String column = invocation.getArgument(0);
                if (!columns.containsKey(column)) {
                    throw new SQLException("Column not present in the fake result set: " + column);
                }
                return converter.apply(columns.get(column));
            };
        }
    }
}
