package com.xishuang.es.dao;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.xishuang.es.EsClientSingleton;
import com.xishuang.es.domain.EsGroupByResult;
import com.xishuang.es.domain.EsCommonResult;
import com.xishuang.es.domain.EsGroupByTopNResult;
import com.xishuang.es.domain.Result;
import org.apache.http.HttpHost;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.elasticsearch.search.aggregations.bucket.composite.CompositeAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.composite.CompositeValuesSourceBuilder;
import org.elasticsearch.search.aggregations.bucket.composite.ParsedComposite;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.ParsedTopHits;
import org.elasticsearch.search.aggregations.metrics.TopHitsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class EsDao {
    private static final Logger LOGGER = LogManager.getLogger(EsDao.class);
    private final RestHighLevelClient client;
    private long defaultSearchTimeoutMs = 30_000; // 小于等于0则代表不限制
    private final Boolean defaultAllowPartialSearchResults = true; // null则代表不设置

    public EsDao(HttpHost... hosts) {
        LOGGER.info("init [{}]", this.getClass().getName());
        this.client = EsClientSingleton.getInstance(hosts);
    }

    /**
     * 单纯查询索引列表
     *
     * @param index               索引
     * @param searchSourceBuilder 查询语句
     */
    public EsCommonResult queryList(String index, SearchSourceBuilder searchSourceBuilder, Result<List<?>> result) {
        LOGGER.info("listToQueryResult => [{}]/[{}]", index, searchSourceBuilder.toString());

        if (result.isDebug()) {
            result.setSearch(JSONObject.parseObject(searchSourceBuilder.toString()));
            return new EsCommonResult();
        }

        StopWatch clock = new StopWatch();
        clock.start();
        SearchRequest searchRequest = setDefaultSearchParam(new SearchRequest(index).source(searchSourceBuilder));
        IndicesOptions fromOptions = IndicesOptions.fromOptions(true, true, true, false);
        fromOptions.ignoreUnavailable();
        searchRequest.indicesOptions(fromOptions);
        RequestOptions options = RequestOptions.DEFAULT;
        SearchResponse response;
        try {
            response = client.search(searchRequest, options);
            if (!checkResponse(response)) {
                LOGGER.error("search failed with response status[{}]", response.status().name());
                return null;
            }
            SearchHits hits = response.getHits();
            List<Map<String, Object>> beans = new LinkedList<>();
            Object[] lastSortValues = null;
            for (SearchHit hit : hits) {
//                String bean = hit.getSourceAsString();
                Map<String, Object> bean = hit.getSourceAsMap();
                lastSortValues = hit.getSortValues();
                beans.add(bean);
            }

            EsCommonResult esCommonResult = new EsCommonResult();
            esCommonResult.setAmounts(hits.getTotalHits().value);
            esCommonResult.setBeans(beans);
            esCommonResult.setLastSortValues(lastSortValues);
            LOGGER.info("status is " + response.status().name() + ", took " + clock.totalTime() + "ms, timeout is " + response.isTimedOut() + ", hits.value is " +
                    hits.getTotalHits().relation + " " + esCommonResult.getAmounts() + ", bean amount is " + esCommonResult.getBeans().size());
            return esCommonResult;
        } catch (Exception e) {
            LOGGER.error("search es cause an exception", e);
            return null;
        }
    }

    /**
     * 多字段分组聚合，count() group by语句进行处理，不排序
     */
    public List<EsGroupByResult> groupByNoSort(String index, QueryBuilder queryBuilder, List<CompositeValuesSourceBuilder<?>> sources, Result<List<?>> result) {
        LOGGER.info("aggregate term all => [{}]/[{}][{}]", index, queryBuilder.toString(), sources);

        final int batchSize = 10000;
        Map<String, Object> afterKey = null;
        List<EsGroupByResult> aggregationResults = new ArrayList<>();
        while (true) {
            CompositeAggregationBuilder composite = AggregationBuilders.composite("my_buckets", sources);
            composite.size(batchSize).aggregateAfter(afterKey);
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
                    .query(queryBuilder)
                    .aggregation(composite);

            Aggregation aggregate = aggregate(index, searchSourceBuilder, result);
            if (aggregate == null) {
                return new ArrayList<>();
            }
            ParsedComposite parsedComposite = (ParsedComposite) aggregate;
            afterKey = parsedComposite.afterKey();
            List<ParsedComposite.ParsedBucket> buckets = parsedComposite.getBuckets();
            for (ParsedComposite.ParsedBucket bucket : buckets) {
                EsGroupByResult groupByResult = new EsGroupByResult();
                groupByResult.setKey(bucket.getKeyAsString());
                groupByResult.setValue(bucket.getDocCount());
                aggregationResults.add(groupByResult);
            }
            if (buckets.size() < batchSize) {
                break;
            }
        }
        return aggregationResults;
    }

    /**
     * 单字段分组聚合
     */
    public List<EsGroupByResult> groupBy(@Nullable String index, QueryBuilder queryBuilder, AggregationBuilder aggregationBuilder, Result<List<?>> result) {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(queryBuilder)
                .trackTotalHits(false)
                .size(0)
                .aggregation(aggregationBuilder);
        Aggregation aggregate = aggregate(index, searchSourceBuilder, result);
        if (aggregate == null) {
            return new ArrayList<>();
        }
        MultiBucketsAggregation multiBucketsAggregation = (MultiBucketsAggregation) aggregate;

        List<EsGroupByResult> aggregationResults = new ArrayList<>();
        for (MultiBucketsAggregation.Bucket b : multiBucketsAggregation.getBuckets()) {
            EsGroupByResult groupByResult = new EsGroupByResult();
            groupByResult.setKey(b.getKeyAsString());
            groupByResult.setValue(b.getDocCount());
            aggregationResults.add(groupByResult);
        }
        return aggregationResults;
    }

    protected Aggregation aggregate(String index, SearchSourceBuilder searchSourceBuilder, Result<List<?>> result) {
        LOGGER.info("aggregate => [{}]/[{}]", index, searchSourceBuilder);
        if (searchSourceBuilder.aggregations().count() == 0) {
            LOGGER.error("searchSourceBuilder.aggregations() can't empty");
            return null;
        }
        StopWatch clock = new StopWatch();
        clock.start();
        searchSourceBuilder.trackTotalHits(false).size(0).trackTotalHitsUpTo(10);
        SearchRequest searchRequest = setDefaultSearchParam(new SearchRequest(index).source(searchSourceBuilder));
        IndicesOptions fromOptions = IndicesOptions.fromOptions(true, true, true, false);
        fromOptions.ignoreUnavailable();
        searchRequest.indicesOptions(fromOptions);
        RequestOptions options = RequestOptions.DEFAULT;
        SearchResponse response;

        if (result.isDebug()) {
            result.setSearch(JSONObject.parseObject(searchSourceBuilder.toString()));
            return null;
        }

        try {
            response = client.search(searchRequest, options);
            if (!"OK".equals(response.status().name())) {
                LOGGER.error("search failed with response status[{}]", response.status().name());
                return null;
            }
            Aggregations agg = response.getAggregations();
            if (agg == null) {
                return null;
            }
            List<Aggregation> aggregations = agg.asList();
            if (aggregations.isEmpty()) {
                return null;
            }
            Aggregation aggregation = aggregations.get(0);
            checkResponse(response);
            clock.stop();
            LOGGER.info("status is " + response.status().name() + ", took " + clock.totalTime() + "ms, timeout is " + response.isTimedOut() + ", bucket amount is " + ((MultiBucketsAggregation) aggregation).getBuckets().size());
            return aggregation;
        } catch (Exception e) {
            LOGGER.error("search es cause an exception", e);
            return null;
        }
    }

    /**
     * 分组top n计算:aggregate+topHit方式
     */
    public List<EsGroupByTopNResult> topNGroupByAgg(@Nullable String index,
                                                    QueryBuilder queryBuilder,
                                                    TermsAggregationBuilder termsAggregationBuilder,
                                                    TopHitsAggregationBuilder topHitsAggregationBuilder,
                                                    Result<List<?>> result) {

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(queryBuilder)
                .trackTotalHits(false)
                .size(0)
                .aggregation(termsAggregationBuilder.subAggregation(topHitsAggregationBuilder));
        Aggregation aggregate = aggregate(index, searchSourceBuilder, result);
        if (aggregate == null) {
            return new ArrayList<>();
        }
        Terms terms = (Terms) aggregate;
        List<EsGroupByTopNResult> groups = new ArrayList<>();
        for (org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket bucket : terms.getBuckets()) {
            EsGroupByTopNResult group = new EsGroupByTopNResult();
            group.setValue(bucket.getKeyAsString());
            ParsedTopHits parsedTopHits = (ParsedTopHits) (bucket.getAggregations().get(topHitsAggregationBuilder.getName()));
            SearchHits hits = parsedTopHits.getHits();
            List<Map<String, Object>> beans = new ArrayList<>();
            for (SearchHit hit : hits) {
                beans.add(hit.getSourceAsMap());
            }
            group.setBeans(beans);
            group.setAmounts(hits.getTotalHits().value);
            groups.add(group);
        }
        return groups;
    }

    /**
     * 分组top n计算:collapse方式
     */
    public List<EsGroupByTopNResult> topNGroupByCollapse(@Nullable String index, SearchSourceBuilder searchSourceBuilder, Result<List<?>> result) {
        LOGGER.info("listWithFieldCollapse => [{}]/[{}]", index, searchSourceBuilder.toString());
        if (searchSourceBuilder.collapse() == null) {
            LOGGER.error("collapse must be set");
            return null;
        }
        StopWatch clock = new StopWatch();
        clock.start();
        SearchRequest searchRequest = setDefaultSearchParam(new SearchRequest(index).source(searchSourceBuilder));
        RequestOptions options = RequestOptions.DEFAULT;
        SearchResponse response;

        if (result.isDebug()) {
            result.setSearch(JSONObject.parseObject(searchSourceBuilder.toString()));
            return null;
        }

        try {
            response = client.search(searchRequest, options);
            if (!checkResponse(response)) {
                LOGGER.error("search failed with response status[{}]", response.status().name());
                return null;
            }
            SearchHits hits = response.getHits();
            List<EsGroupByTopNResult> resultList = new ArrayList<>();
            for (SearchHit hit : hits) {
                List<Map<String, Object>> beans = new ArrayList<>();
                EsGroupByTopNResult group = new EsGroupByTopNResult();
                for (DocumentField collapseField : hit.getFields().values()) {
                    group.setValue(collapseField.getValue().toString()); // note：目前只处理设置一个collapse field的情况
                }
                group.setBeans(beans);
                resultList.add(group);
                for (SearchHits innerhits : hit.getInnerHits().values()) {
                    group.setAmounts(innerhits.getTotalHits().value); // 当没有设置innerhits时，group.amounts为空
                    for (SearchHit innerHit : innerhits) {
                        beans.add(hit.getSourceAsMap());
                    }
                    // note：目前只处理设置一个innerhits的情况
                    break;
                }
                // 当没有设置innerhits或者innerhits.size=0时，补充首文档
                if (beans.isEmpty()) {
                    beans.add(hit.getSourceAsMap());
                }
            }
            clock.stop();
            LOGGER.info("status is " + response.status().name() + ", took " + clock.totalTime() + "ms, timeout is " + response.isTimedOut() +
                    ", hits.value is " + hits.getTotalHits().relation + " " + hits.getTotalHits().value + ", bucket amount is " + resultList.size());
            return resultList;
        } catch (Exception e) {
            LOGGER.error("search es cause an exception", e);
            return null;
        }
    }

    private boolean checkResponse(SearchResponse response) {
        String statusName = response.status().name();
        return "CREATE".equals(statusName) || "OK".equals(statusName) || "CREATED".equals(statusName);
    }

    private SearchRequest setDefaultSearchParam(SearchRequest searchRequest) {
        SearchSourceBuilder searchSourceBuilder = searchRequest.source();
        if (searchSourceBuilder.timeout() == null && defaultSearchTimeoutMs > 0) {
            searchSourceBuilder.timeout(new TimeValue(defaultSearchTimeoutMs));
        }
        if (searchRequest.allowPartialSearchResults() == null && defaultAllowPartialSearchResults != null) {
            searchRequest.allowPartialSearchResults(defaultAllowPartialSearchResults);
        }
        return searchRequest;
    }
}
