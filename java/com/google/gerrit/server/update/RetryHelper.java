// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.update;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.github.rholder.retry.Attempt;
import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.RetryListener;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.github.rholder.retry.WaitStrategy;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.git.LockFailureException;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Counter2;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.ExceptionHook;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.RequestId;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Config;

@Singleton
public class RetryHelper {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @FunctionalInterface
  public interface ChangeAction<T> {
    T call(BatchUpdate.Factory batchUpdateFactory) throws Exception;
  }

  @FunctionalInterface
  public interface Action<T> {
    T call() throws Exception;
  }

  public enum ActionType {
    ACCOUNT_UPDATE,
    CHANGE_UPDATE,
    GROUP_UPDATE,
    INDEX_QUERY,
    PLUGIN_UPDATE,
    REST_REQUEST,
  }

  /**
   * Options for retrying a single operation.
   *
   * <p>This class is similar in function to upstream's {@link RetryerBuilder}, but it exists as its
   * own class in Gerrit for several reasons:
   *
   * <ul>
   *   <li>Gerrit needs to support defaults for some of the options, such as a default timeout.
   *       {@code RetryerBuilder} doesn't support calling the same setter multiple times, so doing
   *       this with {@code RetryerBuilder} directly would not be easy.
   *   <li>Gerrit explicitly does not want callers to have full control over all possible options,
   *       so this class exposes a curated subset.
   * </ul>
   */
  @AutoValue
  public abstract static class Options {
    @Nullable
    abstract RetryListener listener();

    @Nullable
    abstract Duration timeout();

    abstract Optional<String> caller();

    abstract Optional<Predicate<Throwable>> retryWithTrace();

    abstract Optional<Consumer<String>> onAutoTrace();

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder listener(RetryListener listener);

      public abstract Builder timeout(Duration timeout);

      public abstract Builder caller(String caller);

      public abstract Builder retryWithTrace(Predicate<Throwable> exceptionPredicate);

      public abstract Builder onAutoTrace(Consumer<String> traceIdConsumer);

      public abstract Options build();
    }
  }

  @VisibleForTesting
  @Singleton
  public static class Metrics {
    final Counter1<ActionType> attemptCounts;
    final Counter1<ActionType> timeoutCount;
    final Counter2<ActionType, String> autoRetryCount;
    final Counter2<ActionType, String> failuresOnAutoRetryCount;

    @Inject
    Metrics(MetricMaker metricMaker) {
      Field<ActionType> actionTypeField =
          Field.ofEnum(ActionType.class, "action_type", Metadata.Builder::actionType).build();
      attemptCounts =
          metricMaker.newCounter(
              "action/retry_attempt_count",
              new Description(
                      "Number of retry attempts made by RetryHelper to execute an action"
                          + " (0 == single attempt, no retry)")
                  .setCumulative()
                  .setUnit("attempts"),
              actionTypeField);
      timeoutCount =
          metricMaker.newCounter(
              "action/retry_timeout_count",
              new Description(
                      "Number of action executions of RetryHelper that ultimately timed out")
                  .setCumulative()
                  .setUnit("timeouts"),
              actionTypeField);
      autoRetryCount =
          metricMaker.newCounter(
              "action/auto_retry_count",
              new Description("Number of automatic retries with tracing")
                  .setCumulative()
                  .setUnit("retries"),
              actionTypeField,
              Field.ofString("operation_name", Metadata.Builder::operationName)
                  .description("The name of the operation that was retried.")
                  .build());
      failuresOnAutoRetryCount =
          metricMaker.newCounter(
              "action/failures_on_auto_retry_count",
              new Description("Number of failures on auto retry")
                  .setCumulative()
                  .setUnit("failures"),
              actionTypeField,
              Field.ofString("operation_name", Metadata.Builder::operationName)
                  .description("The name of the operation that was retried.")
                  .build());
    }
  }

  public static Options.Builder options() {
    return new AutoValue_RetryHelper_Options.Builder();
  }

  private static Options defaults() {
    return options().build();
  }

  private final Metrics metrics;
  private final BatchUpdate.Factory updateFactory;
  private final PluginSetContext<ExceptionHook> exceptionHooks;
  private final Map<ActionType, Duration> defaultTimeouts;
  private final WaitStrategy waitStrategy;
  @Nullable private final Consumer<RetryerBuilder<?>> overwriteDefaultRetryerStrategySetup;
  private final boolean retryWithTraceOnFailure;

  @Inject
  RetryHelper(
      @GerritServerConfig Config cfg,
      Metrics metrics,
      PluginSetContext<ExceptionHook> exceptionHooks,
      BatchUpdate.Factory updateFactory) {
    this(cfg, metrics, updateFactory, exceptionHooks, null);
  }

  @VisibleForTesting
  public RetryHelper(
      @GerritServerConfig Config cfg,
      Metrics metrics,
      BatchUpdate.Factory updateFactory,
      PluginSetContext<ExceptionHook> exceptionHooks,
      @Nullable Consumer<RetryerBuilder<?>> overwriteDefaultRetryerStrategySetup) {
    this.metrics = metrics;
    this.updateFactory = updateFactory;
    this.exceptionHooks = exceptionHooks;

    Duration defaultTimeout =
        Duration.ofMillis(
            cfg.getTimeUnit("retry", null, "timeout", SECONDS.toMillis(20), MILLISECONDS));
    this.defaultTimeouts = Maps.newEnumMap(ActionType.class);
    Arrays.stream(ActionType.values())
        .forEach(
            at ->
                defaultTimeouts.put(
                    at,
                    Duration.ofMillis(
                        cfg.getTimeUnit(
                            "retry",
                            at.name(),
                            "timeout",
                            SECONDS.toMillis(defaultTimeout.getSeconds()),
                            MILLISECONDS))));

    this.waitStrategy =
        WaitStrategies.join(
            WaitStrategies.exponentialWait(
                cfg.getTimeUnit("retry", null, "maxWait", SECONDS.toMillis(5), MILLISECONDS),
                MILLISECONDS),
            WaitStrategies.randomWait(50, MILLISECONDS));
    this.overwriteDefaultRetryerStrategySetup = overwriteDefaultRetryerStrategySetup;
    this.retryWithTraceOnFailure = cfg.getBoolean("retry", "retryWithTraceOnFailure", false);
  }

  public Duration getDefaultTimeout(ActionType actionType) {
    return defaultTimeouts.get(actionType);
  }

  public <T> T execute(
      ActionType actionType, Action<T> action, Predicate<Throwable> exceptionPredicate)
      throws Exception {
    return execute(actionType, action, defaults(), exceptionPredicate);
  }

  public <T> T execute(
      ActionType actionType,
      Action<T> action,
      Options opts,
      Predicate<Throwable> exceptionPredicate)
      throws Exception {
    try {
      return executeWithAttemptAndTimeoutCount(actionType, action, opts, exceptionPredicate);
    } catch (Throwable t) {
      Throwables.throwIfUnchecked(t);
      Throwables.throwIfInstanceOf(t, Exception.class);
      throw new IllegalStateException(t);
    }
  }

  public <T> T execute(ChangeAction<T> changeAction) throws RestApiException, UpdateException {
    return execute(changeAction, defaults());
  }

  public <T> T execute(ChangeAction<T> changeAction, Options opts)
      throws RestApiException, UpdateException {
    try {
      return execute(
          ActionType.CHANGE_UPDATE,
          () -> changeAction.call(updateFactory),
          opts,
          t -> {
            if (t instanceof UpdateException) {
              t = t.getCause();
            }
            return t instanceof LockFailureException;
          });
    } catch (Throwable t) {
      Throwables.throwIfUnchecked(t);
      Throwables.throwIfInstanceOf(t, UpdateException.class);
      Throwables.throwIfInstanceOf(t, RestApiException.class);
      throw new UpdateException(t);
    }
  }

  /**
   * Executes an action and records the number of attempts and the timeout as metrics.
   *
   * @param actionType the type of the action
   * @param action the action which should be executed and retried on failure
   * @param opts options for retrying the action on failure
   * @param exceptionPredicate predicate to control on which exception the action should be retried
   * @return the result of executing the action
   * @throws Throwable any error or exception that made the action fail, callers are expected to
   *     catch and inspect this Throwable to decide carefully whether it should be re-thrown
   */
  private <T> T executeWithAttemptAndTimeoutCount(
      ActionType actionType,
      Action<T> action,
      Options opts,
      Predicate<Throwable> exceptionPredicate)
      throws Throwable {
    MetricListener listener = new MetricListener();
    try (TraceContext traceContext = TraceContext.open()) {
      RetryerBuilder<T> retryerBuilder =
          createRetryerBuilder(
              actionType,
              opts,
              t -> {
                // exceptionPredicate checks for temporary errors for which the operation should be
                // retried (e.g. LockFailure). The retry has good chances to succeed.
                if (exceptionPredicate.test(t)) {
                  return true;
                }

                // Exception hooks may identify additional exceptions for retry.
                if (exceptionHooks.stream().anyMatch(h -> h.shouldRetry(t))) {
                  return true;
                }

                // A non-recoverable failure occurred. Check if we should retry to capture a trace
                // of the failure. If a trace was already done there is no need to retry.
                if (retryWithTraceOnFailure
                    && opts.retryWithTrace().isPresent()
                    && opts.retryWithTrace().get().test(t)) {
                  String caller = opts.caller().orElse("N/A");
                  if (!traceContext.isTracing()) {
                    String traceId = "retry-on-failure-" + new RequestId();
                    traceContext.addTag(RequestId.Type.TRACE_ID, traceId).forceLogging();
                    opts.onAutoTrace().ifPresent(c -> c.accept(traceId));
                    logger.atFine().withCause(t).log(
                        "AutoRetry: %s failed, retry with tracing enabled", caller);
                    metrics.autoRetryCount.increment(actionType, caller);
                    return true;
                  }

                  // A non-recoverable failure occurred. We retried the operation with tracing
                  // enabled and it failed again. Log the failure so that admin can see if it
                  // differs from the failure that triggered the retry.
                  logger.atFine().withCause(t).log(
                      "AutoRetry: auto-retry of %s has failed", caller);
                  metrics.failuresOnAutoRetryCount.increment(actionType, caller);
                  return false;
                }

                return false;
              });
      retryerBuilder.withRetryListener(listener);
      return executeWithTimeoutCount(actionType, action, retryerBuilder.build());
    } finally {
      if (listener.getAttemptCount() > 1) {
        logger.atFine().log("%s was attempted %d times", actionType, listener.getAttemptCount());
        metrics.attemptCounts.incrementBy(actionType, listener.getAttemptCount() - 1);
      }
    }
  }

  /**
   * Executes an action and records the timeout as metric.
   *
   * @param actionType the type of the action
   * @param action the action which should be executed and retried on failure
   * @param retryer the retryer
   * @return the result of executing the action
   * @throws Throwable any error or exception that made the action fail, callers are expected to
   *     catch and inspect this Throwable to decide carefully whether it should be re-thrown
   */
  private <T> T executeWithTimeoutCount(ActionType actionType, Action<T> action, Retryer<T> retryer)
      throws Throwable {
    try {
      return retryer.call(action::call);
    } catch (ExecutionException | RetryException e) {
      if (e instanceof RetryException) {
        metrics.timeoutCount.increment(actionType);
      }
      if (e.getCause() != null) {
        throw e.getCause();
      }
      throw e;
    }
  }

  private <O> RetryerBuilder<O> createRetryerBuilder(
      ActionType actionType, Options opts, Predicate<Throwable> exceptionPredicate) {
    RetryerBuilder<O> retryerBuilder =
        RetryerBuilder.<O>newBuilder().retryIfException(exceptionPredicate::test);
    if (opts.listener() != null) {
      retryerBuilder.withRetryListener(opts.listener());
    }

    if (overwriteDefaultRetryerStrategySetup != null) {
      overwriteDefaultRetryerStrategySetup.accept(retryerBuilder);
      return retryerBuilder;
    }

    return retryerBuilder
        .withStopStrategy(
            StopStrategies.stopAfterDelay(
                firstNonNull(opts.timeout(), getDefaultTimeout(actionType)).toMillis(),
                MILLISECONDS))
        .withWaitStrategy(waitStrategy);
  }

  private static class MetricListener implements RetryListener {
    private long attemptCount;

    MetricListener() {
      attemptCount = 1;
    }

    @Override
    public <V> void onRetry(Attempt<V> attempt) {
      attemptCount = attempt.getAttemptNumber();
    }

    long getAttemptCount() {
      return attemptCount;
    }
  }
}
