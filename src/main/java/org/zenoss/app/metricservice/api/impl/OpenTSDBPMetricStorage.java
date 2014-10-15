/*
 * Copyright (c) 2013, Zenoss and/or its affiliates. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Zenoss or the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.zenoss.app.metricservice.api.impl;

import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.zenoss.app.annotations.API;
import org.zenoss.app.metricservice.MetricServiceAppConfiguration;
import org.zenoss.app.metricservice.api.model.MetricSpecification;
import org.zenoss.app.metricservice.api.model.ReturnSet;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


@API
@Configuration
@Profile({"default", "prod"})
public class OpenTSDBPMetricStorage implements MetricStorageAPI {

    private static final Logger log = LoggerFactory.getLogger(OpenTSDBPMetricStorage.class);

    private static final String SOURCE_ID = "OpenTSDB";

    /*
     * (non-Javadoc)
     *
     * @see
     * org.zenoss.app.query.api.impl.MetricStorageAPI#getReader(org.zenoss.app
     * .query.QueryAppConfiguration, java.lang.String, java.lang.String,
     * java.lang.String, java.lang.Boolean, java.lang.Boolean, java.util.List)
     */
    @Override
    public BufferedReader getReader(MetricServiceAppConfiguration config,
                                    String id, String startTime, String endTime, ReturnSet returnset,
                                    Boolean series, String downsample, double downsampleMultiplier,
                                    Map<String, List<String>> globalTags,
                                    List<MetricSpecification> queries) throws IOException {

        OpenTSDBQuery query = new OpenTSDBQuery();

        // This could maybe be better - for now, it works : end time defaults to 'now', start time does not default.
        query.start = startTime;
        if (!Utils.NOW.equals(endTime)) {
            query.end = endTime;
        }

        String appliedDownsample = createModifiedDownsampleRequest(downsample, downsampleMultiplier);
        log.info("Specified Downsample = {}, Specified Multiplier = {}, Applied Downsample = {}.", downsample, downsampleMultiplier, appliedDownsample);

        for (MetricSpecification metricSpecification : queries) {
            String oldDownsample = metricSpecification.getDownsample();
            if (null != oldDownsample && !oldDownsample.isEmpty()) {
                log.info("Overriding specified series downsample ({}) with global specification of {}", oldDownsample, appliedDownsample);
            }
            metricSpecification.setDownsample(appliedDownsample);
            query.addSubQuery(openTSDBSubQueryFromMetricSpecification(metricSpecification));
        }

        Collection<OpenTSDBQueryResult> responses = runQueries(query.asSeparateQueries(), config);
        InputStream responseStream = aggregateResponses(responses);

        return new BufferedReader(new InputStreamReader(responseStream, "UTF-8"));
    }

    private InputStream aggregateResponses(Collection<OpenTSDBQueryResult> responses) {
        String aggregatedResponse;
        aggregatedResponse = Utils.jsonStringFromObject(responses);
        return new ByteArrayInputStream(aggregatedResponse.getBytes(StandardCharsets.UTF_8));
    }

    private String getOpenTSDBApiQueryUrl(MetricServiceAppConfiguration passedConfig) {
        String result = String.format("%s/api/query", passedConfig.getMetricServiceConfig().getOpenTsdbUrl());
        log.info("getOpenTSDBApiQueryUrl(): Returning {}", result);
        return result;
    }

    private static OpenTSDBSubQuery openTSDBSubQueryFromMetricSpecification(MetricSpecification metricSpecification) {
        OpenTSDBSubQuery result = null;
        if (null != metricSpecification) {
            result = new OpenTSDBSubQuery();
            result.aggregator = metricSpecification.getAggregator();
            result.downsample = metricSpecification.getDownsample();
            result.metric = metricSpecification.getMetric();
            result.rate = metricSpecification.getRate();
            result.rateOptions = new OpenTSDBRateOption(metricSpecification.getRateOptions());
            Map<String, List<String>> tags = metricSpecification.getTags();
            if (null != tags) {
                for (Map.Entry<String, List<String>> tagEntry : tags.entrySet()) {
                    for (String tagValue : tagEntry.getValue()) {
                        //apply metric-consumer sanitization to tags in query
                        result.addTag( Tags.sanitize(tagEntry.getKey()), Tags.sanitize(tagValue));
                    }
                }
            }
        }
        return result;
    }


    /*
     * (non-Javadoc)
     *
     * @see org.zenoss.app.query.api.impl.MetricStorageAPI#getSourceId()
     */
    @Override
    public String getSourceId() {
        return SOURCE_ID;
    }

    private static String parseAggregation(String v) {
        String result = "";
        int dashPosition = v.indexOf('-');
        if (dashPosition > 0 && dashPosition < v.length()) {
            result = v.substring(dashPosition + 1);
        }
        return result;
    }

    private static String createModifiedDownsampleRequest(String downsample, double downsampleMultiplier) {
        if (null == downsample || downsample.isEmpty() || downsampleMultiplier <= 0.0) {
            log.warn("Bad downsample or multiplier. Returning original downsample value of {}.", downsample);
            return downsample;
        }
        long duration = Utils.parseDuration(downsample);
        String aggregation = parseAggregation(downsample);
        long newDuration = (long)(duration / downsampleMultiplier);
        if (newDuration <= 0) {
            log.warn("Applying value {} of downsampleMultiplier to downsample value of {} would result in a request with resolution finer than 1 sec. returning 1 second.", downsampleMultiplier, downsample);
            newDuration = 1;
        }
        return String.format("%ds-%s", newDuration, aggregation);
    }

    private List<OpenTSDBQueryResult> runQueries(List<OpenTSDBQuery> queries, MetricServiceAppConfiguration passedConfig) {
        List<OpenTSDBQueryResult> results = new ArrayList<>();

        if (null == queries) {
            log.warn("Null query list passed to runQueries. Returning empty results list.");
            return results;
        }

        List<Callable<OpenTSDBQueryResult>> executors = getExecutors(queries, passedConfig);
        List<Future<OpenTSDBQueryResult>> futures = invokeExecutors(executors, passedConfig);
        log.debug("{} futures returned.", futures.size());
        getResultsFromFutures(results, futures);
        log.debug("{} results returned.", results.size());
        return results;
    }

    private List<Future<OpenTSDBQueryResult>> invokeExecutors(List<Callable<OpenTSDBQueryResult>> executors, MetricServiceAppConfiguration passedConfig) {
        int executorThreadPoolSize = passedConfig.getMetricServiceConfig().getExecutorThreadPoolSize();
        log.info("Setting up executor pool with {} threads.", executorThreadPoolSize);
        ExecutorService executorService = Executors.newFixedThreadPool(executorThreadPoolSize); // Number of threads in pool
        List<Future<OpenTSDBQueryResult>> futures = new ArrayList<>();
        try {
            log.debug("invoking {} executors...", executors.size());
            futures = executorService.invokeAll(executors); // throws: InterruptedException (checked), NullPointerException/RejectedExecutionException (unchecked)
        } catch (InterruptedException | NullPointerException | RejectedExecutionException e) {
            log.error("Query execution was unsuccessful: {}", e.getMessage());
        } finally {
            executorService.shutdown();
        }
        return futures;
    }

    private void getResultsFromFutures(List<OpenTSDBQueryResult> results, List<Future<OpenTSDBQueryResult>> futures) {
        for (Future<OpenTSDBQueryResult>  future : futures) {
            try {
                OpenTSDBQueryResult result  = future.get(); // Throws InterruptedException, ExecutionException (checked); CancellationException (unchecked)
                results.add(result);
            } catch (InterruptedException | ExecutionException | CancellationException e) {
                // On exception, return an empty result, with the queryStatus set to indicate the problem.
                OpenTSDBQueryResult result = new OpenTSDBQueryResult();
                QueryStatus queryStatus = new QueryStatus(QueryStatus.QueryStatusEnum.ERROR, e.getMessage());
                result.setStatus(queryStatus);
                log.error("{} exception getting result from future: {}", e.getClass().getName(), e.getMessage());
            }
        }
    }

    private List<Callable<OpenTSDBQueryResult>> getExecutors(List<OpenTSDBQuery> queries, MetricServiceAppConfiguration passedConfig) {
        DefaultHttpClient httpClient = getHttpClient(passedConfig);

        List<Callable<OpenTSDBQueryResult>> executors = new ArrayList<>();

        for (OpenTSDBQuery query : queries) {
            try {
                CallableQueryExecutor executor = new CallableQueryExecutor(httpClient, query, getOpenTSDBApiQueryUrl(passedConfig));
                executors.add(executor);
            } catch (IllegalArgumentException e) {
                log.warn("Unable to create request from query", e);
            }
        }
        return executors;
    }

    private DefaultHttpClient getHttpClient(MetricServiceAppConfiguration passedConfig) {
        PoolingClientConnectionManager cm = new PoolingClientConnectionManager();
        int maxTotalPoolConnections = passedConfig.getMetricServiceConfig().getMaxTotalPoolConnections();
        int maxPoolConnectionsPerRoute = passedConfig.getMetricServiceConfig().getMaxPoolConnectionsPerRoute();
        log.debug("Setting up pool with {} total connections and {} max connections per route.", maxTotalPoolConnections, maxPoolConnectionsPerRoute);
        cm.setMaxTotal(maxTotalPoolConnections);
        cm.setDefaultMaxPerRoute(maxPoolConnectionsPerRoute);
        return new DefaultHttpClient(cm);
    }
}

