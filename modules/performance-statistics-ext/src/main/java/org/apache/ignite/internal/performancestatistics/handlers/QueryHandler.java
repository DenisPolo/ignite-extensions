/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.performancestatistics.handlers;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ignite.internal.performancestatistics.util.OrderedFixedSizeStructure;
import org.apache.ignite.internal.processors.cache.query.GridCacheQueryType;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.T3;

import static org.apache.ignite.internal.performancestatistics.util.Utils.MAPPER;

/**
 * Builds JSON with aggregated query statistics.
 *
 * Example:
 * <pre>
 * {
 *      $textOrCacheName : {
 *          "count" : $executionsCount,
 *          "duration" : $duration,
 *          "logicalReads" : $logicalReads,
 *          "physicalReads" : $physicalReads,
 *          "failures" : $failures,
 *          "properties" : {
 *              $propName : {"value" : $propValue, "count" : $propCount},
 *              ...
 *          },
 *          "rows" : {
 *              $action : $rowsCount,
 *              ...
 *          }
 *      }
 * }
 * </pre>
 * Example of slowest queries:
 * <pre>
 * [
 *  {
 *      "text" : $textOrCacheName,
 *      "startTime" : $startTime,
 *      "duration" : $duration,
 *      "nodeId" : $nodeId,
 *      "logicalReads" : $logicalReads,
 *      "physicalReads" : $physicalReads,
 *      "success" : $success,
 *      "properties" : {
 *          $propName : {"value" : $propValue, "count" : $propCount},
 *          ...
 *      },
 *      "rows" : {
 *          $action : $rowsCount,
 *          ...
 *      }
 *  }
 * ]
 * </pre>
 */
public class QueryHandler implements IgnitePerformanceStatisticsHandler {
    /**  Queries results: queryType -> queryText -> aggregatedInfo. */
    private final Map<GridCacheQueryType, Map<String, AggregatedQueryInfo>> aggrQuery =
        new EnumMap<>(GridCacheQueryType.class);

    /** Parsed reads: queryType -> queryNodeId -> queryId -> reads. */
    private final Map<GridCacheQueryType, Map<UUID, Map<Long, long[]>>> readsById =
        new EnumMap<>(GridCacheQueryType.class);

    /** Parsed SQL query rows: queryNodeId -> queryId -> action -> rows. */
    private final Map<UUID, Map<Long, Map<String, long[]>>> rowsById = new HashMap<>();

    /** Parsed SQL query properties: queryNodeId -> queryId -> property name + value -> property. */
    private final Map<UUID, Map<Long, Map<String, T3<String, String, long[]>>>> propsById = new HashMap<>();

    /** Structure to store top of slow SQL queries: queryType -> duration -> query. */
    private final Map<GridCacheQueryType, OrderedFixedSizeStructure<Long, Query>> topSlow =
        new EnumMap<>(GridCacheQueryType.class);

    /** {@inheritDoc} */
    @Override public void query(UUID nodeId, GridCacheQueryType type, String text, long id, long startTime,
        long duration, boolean success) {
        Query query = new Query(type, text, nodeId, id, startTime, duration, success);

        OrderedFixedSizeStructure<Long, Query> tree = topSlow.computeIfAbsent(type,
            queryType -> new OrderedFixedSizeStructure<>());

        tree.put(duration, query);

        AggregatedQueryInfo info = aggrQuery.computeIfAbsent(type, queryType -> new HashMap<>())
            .computeIfAbsent(text, queryText -> new AggregatedQueryInfo());

        info.merge(nodeId, id, duration, success);
    }

    /** {@inheritDoc} */
    @Override public void queryReads(UUID nodeId, GridCacheQueryType type, UUID queryNodeId, long id, long logicalReads,
        long physicalReads) {

        Map<Long, long[]> ids = readsById.computeIfAbsent(type, queryType -> new HashMap<>())
            .computeIfAbsent(queryNodeId, node -> new HashMap<>());

        long[] readsArr = ids.computeIfAbsent(id, queryId -> new long[] {0, 0});

        readsArr[0] += logicalReads;
        readsArr[1] += physicalReads;
    }

    /** {@inheritDoc} */
    @Override public void queryRows(
        UUID nodeId,
        GridCacheQueryType type,
        UUID qryNodeId,
        long id,
        String action,
        long rows
    ) {
        Map<String, long[]> actions = rowsById.computeIfAbsent(qryNodeId, node -> new HashMap<>())
            .computeIfAbsent(id, qryId -> new HashMap<>());

        long[] rowsArr = actions.computeIfAbsent(action.intern(), act -> new long[] {0});

        rowsArr[0] += rows;
    }

    /** {@inheritDoc} */
    @Override public void queryProperty(
        UUID nodeId,
        GridCacheQueryType type,
        UUID qryNodeId,
        long id,
        String name,
        String val
    ) {
        Map<String, T3<String, String, long[]>> props = propsById.computeIfAbsent(qryNodeId, node -> new HashMap<>())
            .computeIfAbsent(id, qryId -> new HashMap<>());

        String key = (name + '=' + val).intern();

        T3<String, String, long[]> prop = props.computeIfAbsent(key,
            nv -> new T3<>(name.intern(), val.intern(), new long[] {0}));

        prop.get3()[0]++;
    }

    /** {@inheritDoc} */
    @Override public Map<String, JsonNode> results() {
        ObjectNode sqlRes = MAPPER.createObjectNode();
        ObjectNode scanRes = MAPPER.createObjectNode();
        ObjectNode indexRes = MAPPER.createObjectNode();

        buildResult(GridCacheQueryType.SQL_FIELDS, sqlRes);
        buildResult(GridCacheQueryType.SCAN, scanRes);
        buildResult(GridCacheQueryType.INDEX, indexRes);

        ArrayNode topSlowSql = MAPPER.createArrayNode();
        ArrayNode topSlowScan = MAPPER.createArrayNode();
        ArrayNode topSlowIndex = MAPPER.createArrayNode();

        buildTopSlowResult(GridCacheQueryType.SQL_FIELDS, topSlowSql);
        buildTopSlowResult(GridCacheQueryType.SCAN, topSlowScan);
        buildTopSlowResult(GridCacheQueryType.INDEX, topSlowIndex);

        Map<String, JsonNode> res = new HashMap<>();

        res.put("sql", sqlRes);
        res.put("scan", scanRes);
        res.put("index", indexRes);
        res.put("topSlowSql", topSlowSql);
        res.put("topSlowScan", topSlowScan);
        res.put("topSlowIndex", topSlowIndex);

        return res;
    }

    /** Builds JSON. */
    private void buildResult(GridCacheQueryType type, ObjectNode jsonRes) {
        if (!aggrQuery.containsKey(type))
            return;

        Map<String, AggregatedQueryInfo> res = aggrQuery.get(type);

        res.forEach((text, info) -> {
            info.ids.forEach((uuid, ids) -> {
                if (readsById.containsKey(type) && readsById.get(type).containsKey(uuid)) {
                    Map<Long, long[]> reads = readsById.get(type).get(uuid);

                    ids.forEach(id -> {
                        long[] readsArr = reads.get(id);

                        if (readsArr != null) {
                            info.logicalReads += readsArr[0];
                            info.physicalReads += readsArr[1];
                        }
                    });
                }

                if (type == GridCacheQueryType.SQL_FIELDS) {
                    Map<Long, Map<String, long[]>> nodeRows = rowsById.get(uuid);
                    Map<Long, Map<String, T3<String, String, long[]>>> nodeProps = propsById.get(uuid);

                    ids.forEach(id -> {
                        Map<String, T3<String, String, long[]>> qryProps = nodeProps == null ? null : nodeProps.get(id);

                        if (!F.isEmpty(qryProps)) {
                            qryProps.forEach((propKey0, prop0) -> info.props.compute(propKey0, (propKey1, prop1) -> {
                                if (prop1 == null)
                                    return new T3<>(prop0.get1(), prop0.get2(), new long[] {prop0.get3()[0]});
                                else {
                                    prop1.get3()[0] += prop0.get3()[0];
                                    return prop1;
                                }
                            }));
                        }

                        Map<String, long[]> qryRows = nodeRows == null ? null : nodeRows.get(id);

                        if (!F.isEmpty(qryRows)) {
                            qryRows.forEach((act0, rows0) -> info.rows.compute(act0, (act1, rows1) -> {
                                if (rows1 == null)
                                    return new long[] {rows0[0]};
                                else {
                                    rows1[0] += rows0[0];
                                    return rows1;
                                }
                            }));
                        }
                    });
                }
            });

            ObjectNode sql = (ObjectNode)jsonRes.get(text);

            if (sql == null) {
                sql = MAPPER.createObjectNode();

                sql.put("count", info.count);
                sql.put("duration", TimeUnit.NANOSECONDS.toMillis(info.totalDuration));
                sql.put("logicalReads", info.logicalReads);
                sql.put("physicalReads", info.physicalReads);
                sql.put("failures", info.failures);

                if (!F.isEmpty(info.props)) {
                    ObjectNode node = MAPPER.createObjectNode();

                    info.props.forEach((propKey, prop) -> {
                        ObjectNode valCntNode = MAPPER.createObjectNode();

                        valCntNode.put("value", prop.get2());
                        valCntNode.put("count", prop.get3()[0]);

                        node.putIfAbsent(prop.get1(), valCntNode);
                    });

                    sql.putIfAbsent("properties", node);
                }

                if (!F.isEmpty(info.rows)) {
                    ObjectNode node = MAPPER.createObjectNode();

                    info.rows.forEach((action, cnt) -> node.put(action, cnt[0]));

                    sql.putIfAbsent("rows", node);
                }

                jsonRes.set(text, sql);
            }
        });
    }

    /** Builds JSON. */
    private void buildTopSlowResult(GridCacheQueryType type, ArrayNode jsonRes) {
        if (!topSlow.containsKey(type))
            return;

        OrderedFixedSizeStructure<Long, Query> tree = topSlow.get(type);

        tree.values().forEach(query -> {
            ObjectNode json = MAPPER.createObjectNode();

            json.put("text", query.text);
            json.put("startTime", query.startTime);
            json.put("duration", TimeUnit.NANOSECONDS.toMillis(query.duration));
            json.put("nodeId", String.valueOf(query.queryNodeId));
            json.put("success", query.success);
            json.put("logicalReads", 0);
            json.put("physicalReads", 0);

            jsonRes.add(json);

            if (readsById.containsKey(type) && readsById.get(type).containsKey(query.queryNodeId)) {
                long[] readsArr = readsById.get(type).get(query.queryNodeId).get(query.id);

                if (readsArr != null) {
                    json.put("logicalReads", readsArr[0]);
                    json.put("physicalReads", readsArr[1]);
                }
            }

            if (type == GridCacheQueryType.SQL_FIELDS) {
                if (propsById.containsKey(query.queryNodeId) && propsById.get(query.queryNodeId).containsKey(query.id)) {
                    ObjectNode node = MAPPER.createObjectNode();

                    Collection<T3<String, String, long[]>> props = propsById.get(query.queryNodeId).get(query.id).values();

                    props.forEach(prop -> {
                        ObjectNode valCntNode = MAPPER.createObjectNode();
                        valCntNode.put("value", prop.get2());
                        valCntNode.put("count", prop.get3()[0]);

                        node.putIfAbsent(prop.get1(), valCntNode);
                    });

                    json.putIfAbsent("properties", node);
                }

                if (rowsById.containsKey(query.queryNodeId) && rowsById.get(query.queryNodeId).containsKey(query.id)) {
                    ObjectNode node = MAPPER.createObjectNode();

                    Map<String, long[]> rows = rowsById.get(query.queryNodeId).get(query.id);

                    rows.forEach((action, rowsCnt) -> node.put(action, rowsCnt[0]));

                    json.putIfAbsent("rows", node);
                }
            }
        });
    }

    /** Aggregated query info. */
    private static class AggregatedQueryInfo {
        /** Executions count. */
        int count;

        /** Total duration. */
        long totalDuration;

        /** Number of logical reads. */
        long logicalReads;

        /** Number of physical reads. */
        long physicalReads;

        /** Failures count. */
        int failures;

        /** Aggregated query properties. */
        Map<String, T3<String, String, long[]>> props = new TreeMap<>();

        /** Number of processed rows (by different actions). */
        Map<String, long[]> rows = new TreeMap<>();

        /** Query ids. Parsed from global query id: NodeId -> queryIds */
        final Map<UUID, Set<Long>> ids = new HashMap<>();

        /** */
        public void merge(UUID queryNodeId, long id, long duration, boolean success) {
            count += 1;
            totalDuration += duration;

            if (!success)
                failures += 1;

            ids.computeIfAbsent(queryNodeId, k -> new HashSet<>())
                .add(id);
        }
    }

    /** Query. */
    private static class Query {
        /** Cache query type. */
        final GridCacheQueryType type;

        /** Query text in case of SQL query. Cache name in case of SCAN query. */
        final String text;

        /** Originating node id (as part of global query id). */
        final UUID queryNodeId;

        /** Query id. */
        final long id;

        /** Start time. */
        final long startTime;

        /** Duration. */
        final long duration;

        /** Success flag. */
        final boolean success;

        /**
         * @param type Cache query type.
         * @param text Query text in case of SQL query. Cache name in case of SCAN query.
         * @param queryNodeId Originating node id.
         * @param id Query id.
         * @param startTime Start time.
         * @param duration Duration.
         * @param success Success flag.
         */
        public Query(GridCacheQueryType type, String text, UUID queryNodeId, long id, long startTime, long duration,
            boolean success) {
            this.type = type;
            this.text = text;
            this.queryNodeId = queryNodeId;
            this.id = id;
            this.startTime = startTime;
            this.duration = duration;
            this.success = success;
        }
    }
}
