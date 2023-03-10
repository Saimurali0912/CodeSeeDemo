/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.support;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.util.concurrent.BaseFuture;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.common.util.concurrent.UncategorizedExecutionException;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.TimeValue;

import java.util.concurrent.TimeUnit;

public class PlainActionFuture<T> extends BaseFuture<T> implements ActionFuture<T>, ActionListener<T> {

    public static <T> PlainActionFuture<T> newFuture() {
        return new PlainActionFuture<>();
    }

    @Override
    public T actionGet() {
        try {
            return FutureUtils.get(this);
        } catch (ElasticsearchException e) {
            throw unwrapEsException(e);
        }
    }

    @Override
    public T actionGet(String timeout) {
        return actionGet(TimeValue.parseTimeValue(timeout, null, getClass().getSimpleName() + ".actionGet.timeout"));
    }

    @Override
    public T actionGet(long timeoutMillis) {
        return actionGet(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public T actionGet(TimeValue timeout) {
        return actionGet(timeout.millis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public T actionGet(long timeout, TimeUnit unit) {
        try {
            return FutureUtils.get(this, timeout, unit);
        } catch (ElasticsearchException e) {
            throw unwrapEsException(e);
        }
    }

    @Override
    public void onResponse(T result) {
        set(result);
    }

    @Override
    public void onFailure(Exception e) {
        setException(e);
    }

    private static RuntimeException unwrapEsException(ElasticsearchException esEx) {
        Throwable root = esEx.unwrapCause();
        if (root instanceof RuntimeException) {
            return (RuntimeException) root;
        }
        return new UncategorizedExecutionException("Failed execution", root);
    }

    public static <T, E extends Exception> T get(CheckedConsumer<PlainActionFuture<T>, E> e) throws E {
        PlainActionFuture<T> fut = newFuture();
        e.accept(fut);
        return fut.actionGet();
    }

    public static <T, E extends Exception> T get(CheckedConsumer<PlainActionFuture<T>, E> e, long timeout, TimeUnit unit) throws E {
        PlainActionFuture<T> fut = newFuture();
        e.accept(fut);
        return fut.actionGet(timeout, unit);
    }
}
