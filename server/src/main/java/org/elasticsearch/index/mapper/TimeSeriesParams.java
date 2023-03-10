/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.function.Function;

/**
 * Utility functions for time series related mapper parameters
 */
public final class TimeSeriesParams {

    public static final String TIME_SERIES_METRIC_PARAM = "time_series_metric";
    public static final String TIME_SERIES_DIMENSION_PARAM = "time_series_dimension";

    private TimeSeriesParams() {}

    public enum MetricType {
        GAUGE(new String[] { "max", "min", "value_count", "sum" }),
        COUNTER(new String[] { "last_value" });

        private final String[] supportedAggs;

        MetricType(String[] supportedAggs) {
            this.supportedAggs = supportedAggs;
        }

        public String[] supportedAggs() {
            return supportedAggs;
        }

        @Override
        public final String toString() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static MetricType fromString(String value) {
            for (MetricType metricType : values()) {
                if (metricType.toString().equals(value)) {
                    return metricType;
                }
            }
            throw new IllegalArgumentException("No enum constant MetricType." + value);
        }
    }

    public static FieldMapper.Parameter<MetricType> metricParam(Function<FieldMapper, MetricType> initializer, MetricType... values) {
        assert values.length > 0;
        EnumSet<MetricType> acceptedValues = EnumSet.noneOf(MetricType.class);
        acceptedValues.addAll(Arrays.asList(values));
        return FieldMapper.Parameter.restrictedEnumParam(
            TIME_SERIES_METRIC_PARAM,
            false,
            initializer,
            null,
            MetricType.class,
            acceptedValues
        ).acceptsNull();
    }

    public static FieldMapper.Parameter<Boolean> dimensionParam(Function<FieldMapper, Boolean> initializer) {
        return FieldMapper.Parameter.boolParam(TIME_SERIES_DIMENSION_PARAM, false, initializer, false);
    }

}
