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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
//import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zenoss.app.metricservice.api.metric.impl.MetricService;
import org.zenoss.app.metricservice.api.model.MetricSpecification;
import org.zenoss.app.metricservice.buckets.Buckets;
import org.zenoss.app.metricservice.buckets.Value;
import org.zenoss.app.metricservice.calculators.Closure;
import org.zenoss.app.metricservice.calculators.MetricCalculator;
import org.zenoss.app.metricservice.calculators.MetricCalculatorFactory;
import org.zenoss.app.metricservice.calculators.ReferenceProvider;
import org.zenoss.app.metricservice.calculators.UnknownReferenceException;

/**
 * Processes the output stream from the back end metric query storage into
 * buckets including the calculation of any RPN functions and references.
 *
 * @author Zenoss
 */
public class DefaultResultProcessor implements ResultProcessor,
        ReferenceProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultResultProcessor.class);

    private class BucketClosure implements Closure {
        public BucketClosure() {

        }

        public long ts;
        public Buckets<MetricKey, String>.Bucket bucket;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.zenoss.app.metricservice.calculators.ReferenceProvider#lookup
     * (java .lang.String, org.zenoss.app.metricservice.calculators.Closure)
     */
    @Override
    public double lookup(String name, Closure closure)
            throws UnknownReferenceException {
        BucketClosure b = (BucketClosure) closure;

        /**
         * If they are looking for special values like "time" then give them
         * that.
         */
        if ("time".equalsIgnoreCase(name)) {
            return b.ts;
        }

        /**
         * Check for metrics or values in the bucket
         */
        Value v = b.bucket.getValueByShortcut(name);
        if (v == null) {
            throw new UnknownReferenceException(name);
        }
        return v.getValue();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.zenoss.app.metricservice.api.impl.ResultProcessor#processResults(
     * java.io.BufferedReader, java.util.List, long)
     */
    @Override
    public Buckets<MetricKey, String> processResults(BufferedReader reader,
                                                     List<MetricSpecification> queries, long bucketSize)
            throws ClassNotFoundException, UnknownReferenceException,
            IOException {

        Buckets<MetricKey, String> buckets = new Buckets<MetricKey, String>(bucketSize);

        String line;
        String[] terms;
        double val;
        String expr;
        long ts = 0, previousTs = 0;
        Buckets<MetricKey, String>.Bucket previousBucket = null, currentBucket = null;

        MetricKey key;
        MetricCalculator calc = null;
        Map<MetricKey, MetricCalculator> calcs = new HashMap<MetricKey, MetricCalculator>();
        MetricCalculatorFactory calcFactory = new MetricCalculatorFactory();

        // Walk the queries and build up a map of metric name to RPN
        // expressions

        /*
         * key is name + tags ... It has to be repeatable, each time generated
         * the same way
         */
        MetricKeyCache keyCache = new MetricKeyCache();
        for (MetricSpecification spec : queries) {
            key = keyCache.put(MetricKey.fromValue(spec));
            if ((expr = spec.getExpression()) != null
                    && (expr = expr.trim()).length() > 0) {
                calc = calcFactory.newInstance(expr);
                calc.setReferenceProvider(this);
                calcs.put(key, calc);
            }
        }

        // Get a list of calculated values
        List<MetricSpecification> values = MetricService.valueFilter(queries);
        BucketClosure closure = new BucketClosure();
        Tags curTags = null;
        key = null;

        List<OpenTSDBQueryResult> allResults = new ArrayList<>();
        while ((line = reader.readLine()) != null) {
            log.info("Processing line: {}", line);
            ObjectMapper mapper = new ObjectMapper();
            try {
                List<OpenTSDBQueryResult> resultList = mapper.readValue(line, mapper.getTypeFactory().constructCollectionType(List.class, OpenTSDBQueryResult.class));
                allResults.addAll(resultList);
            } catch (Exception e) {
                log.error("Exception parsing json", e);
            }
        }

        for (OpenTSDBQueryResult result : allResults) {
            log.debug("processing result: {}", result.debugString());
            String metricName = result.metric;
            for (Map.Entry<Long, String> dpValue : result.dps.entrySet()) {
                previousTs = ts;
                val = Double.valueOf(dpValue.getValue());
                ts = dpValue.getKey();
                curTags = Tags.fromOpenTsdbTags(result.tags);
                key = keyCache.get(metricName, curTags);
                if ((calc = calcs.get(key)) != null) {
                    val = calc.evaluate(val);
                }

                buckets.add(key, key.getName(), ts, val);
                previousBucket = currentBucket;
                currentBucket = buckets.getBucket(ts);
                if (previousBucket != null && currentBucket != previousBucket) {
                    for (MetricSpecification value : values) {
                        MetricKey k2 = keyCache.get(value.getName(), curTags);
                        try {
                            if ((calc = calcs.get(k2)) != null) {
                                closure.ts = previousTs;
                                closure.bucket = previousBucket;
                                val = calc.evaluate((Closure) closure);
                                buckets.add(k2, k2.getName(), previousTs, val);
                            }
                        } catch (UnknownReferenceException e) {
                        /*
                         * Just because a reference was not in the same bucket
                         * does not mean a real failure. It is legitimate.
                         */
                        }
                    }
                }
            }
        }
        for (MetricSpecification value : values) {
            key = keyCache.get(value.getName(), curTags);

            try {
                if ((calc = calcs.get(value.getName())) != null) {
                    closure.ts = previousTs;
                    closure.bucket = previousBucket;
                    val = calc.evaluate((Closure) closure);
                    buckets.add(key, key.getName(), previousTs, val);
                }
            } catch (Exception e) {
                /*
                 * Just because a reference was not in the same bucket does not
                 * mean a real failure. It is legitimate.
                 */
            }
        }
        return buckets;
    }

}
