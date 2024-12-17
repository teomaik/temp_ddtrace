package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.*;
import static datadog.trace.api.DDTags.*;
import static datadog.trace.api.DDTags.PROFILING_ENABLED;
import static datadog.trace.api.config.AppSecConfig.*;
import static datadog.trace.api.config.CiVisibilityConfig.*;
import static datadog.trace.api.config.CrashTrackingConfig.*;
import static datadog.trace.api.config.CwsConfig.CWS_ENABLED;
import static datadog.trace.api.config.CwsConfig.CWS_TLS_REFRESH;
import static datadog.trace.api.config.DebuggerConfig.*;
import static datadog.trace.api.config.GeneralConfig.*;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.IastConfig.*;
import static datadog.trace.api.config.JmxFetchConfig.*;
import static datadog.trace.api.config.ProfilingConfig.*;
import static datadog.trace.api.config.RemoteConfigConfig.*;
import static datadog.trace.api.config.TraceInstrumentationConfig.*;
import static datadog.trace.api.config.TracerConfig.*;
import static datadog.trace.api.iast.IastDetectionMode.DEFAULT;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;
import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;

import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.IastDetectionMode;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.api.profiling.ProfilingEnablement;
import datadog.trace.bootstrap.config.provider.CapturedEnvironmentConfigSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import datadog.trace.util.PidHelper;
import datadog.trace.util.Strings;
import datadog.trace.util.throwable.FatalAgentMisconfigurationError;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config reads values with the following priority:
 *
 * <p>1) system properties
 *
 * <p>2) environment variables,
 *
 * <p>3) optional configuration file
 *
 * <p>4) platform dependant properties. It also includes default values to ensure a valid config.
 *
 * <p>System properties are {@link Config#PREFIX}'ed. Environment variables are the same as the
 * system property, but uppercased and '.' is replaced with '_'.
 *
 * @see ConfigProvider for details on how configs are processed
 * @see InstrumenterConfig for pre-instrumentation configurations
 * @see DynamicConfig for configuration that can be dynamically updated via remote-config
 */
public class Config {

  private static final Logger log = LoggerFactory.getLogger(Config.class);

  private static final Pattern COLON = Pattern.compile(":");

  private final InstrumenterConfig instrumenterConfig;

  private final long startTimeMillis = System.currentTimeMillis();
  private final boolean timelineEventsEnabled;

  /**
   * this is a random UUID that gets generated on JVM start up and is attached to every root span
   * and every JMX metric that is sent out.
   */
  static class RuntimeIdHolder {
    static final String runtimeId = UUID.randomUUID().toString();
  }

  static class HostNameHolder {
    static final String hostName = initHostName();

    public static String getHostName() {
      return hostName;
    }
  }

  private final boolean runtimeIdEnabled;

  /** This is the version of the runtime, ex: 1.8.0_332, 11.0.15, 17.0.3 */
  private final String runtimeVersion;

  private final String applicationKey;
  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting. If CI Visibility is used with agentless mode, api key is used when
   * sending data (including traces) to backend
   */
  private final String apiKey;
  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting.
   */
  private final String site;

  private final String serviceName;
  private final boolean serviceNameSetByUser;
  private final String rootContextServiceName;
  private final boolean integrationSynapseLegacyOperationName;
  private final String writerType;
  private final boolean injectBaggageAsTagsEnabled;
  private final boolean agentConfiguredUsingDefault;
  private final String agentUrl;
  private final String agentHost;
  private final int agentPort;
  private final String agentUnixDomainSocket;
  private final String agentNamedPipe;
  private final int agentTimeout;
  private final Set<String> noProxyHosts;
  private final boolean prioritySamplingEnabled;
  private final String prioritySamplingForce;
  private final boolean traceResolverEnabled;
  private final int spanAttributeSchemaVersion;
  private final boolean peerServiceDefaultsEnabled;
  private final Map<String, String> peerServiceComponentOverrides;
  private final boolean removeIntegrationServiceNamesEnabled;
  private final Map<String, String> peerServiceMapping;
  private final Map<String, String> serviceMapping;
  private final Map<String, String> tags;
  private final Map<String, String> spanTags;
  private final Map<String, String> jmxTags;
  private final Map<String, String> requestHeaderTags;
  private final Map<String, String> responseHeaderTags;
  private final Map<String, String> baggageMapping;
  private final boolean requestHeaderTagsCommaAllowed;
  private final BitSet httpServerErrorStatuses;
  private final BitSet httpClientErrorStatuses;
  private final boolean httpServerTagQueryString;
  private final boolean httpServerRawQueryString;
  private final boolean httpServerRawResource;
  private final boolean httpServerDecodedResourcePreserveSpaces;
  private final boolean httpServerRouteBasedNaming;
  private final Map<String, String> httpServerPathResourceNameMapping;
  private final Map<String, String> httpClientPathResourceNameMapping;
  private final boolean httpResourceRemoveTrailingSlash;
  private final boolean httpClientTagQueryString;
  private final boolean httpClientTagHeaders;
  private final boolean httpClientSplitByDomain;
  private final boolean dbClientSplitByInstance;
  private final boolean dbClientSplitByInstanceTypeSuffix;
  private final boolean dbClientSplitByHost;
  private final Set<String> splitByTags;
  private final boolean jeeSplitByDeployment;
  private final int scopeDepthLimit;
  private final boolean scopeStrictMode;
  private final int scopeIterationKeepAlive;
  private final int partialFlushMinSpans;
  private final int traceKeepLatencyThreshold;
  private final boolean traceKeepLatencyThresholdEnabled;
  private final boolean traceStrictWritesEnabled;
  private final boolean logExtractHeaderNames;
  private final Set<PropagationStyle> propagationStylesToExtract;
  private final Set<PropagationStyle> propagationStylesToInject;
  private final boolean tracePropagationStyleB3PaddingEnabled;
  private final Set<TracePropagationStyle> tracePropagationStylesToExtract;
  private final Set<TracePropagationStyle> tracePropagationStylesToInject;
  private final boolean tracePropagationExtractFirst;
  private final int clockSyncPeriod;
  private final boolean logsInjectionEnabled;

  private final String dogStatsDNamedPipe;
  private final int dogStatsDStartDelay;

  private final Integer statsDClientQueueSize;
  private final Integer statsDClientSocketBuffer;
  private final Integer statsDClientSocketTimeout;

  private final boolean runtimeMetricsEnabled;
  private final boolean jmxFetchEnabled;
  private final String jmxFetchConfigDir;
  private final List<String> jmxFetchConfigs;
  @Deprecated private final List<String> jmxFetchMetricsConfigs;
  private final Integer jmxFetchCheckPeriod;
  private final Integer jmxFetchInitialRefreshBeansPeriod;
  private final Integer jmxFetchRefreshBeansPeriod;
  private final String jmxFetchStatsdHost;
  private final Integer jmxFetchStatsdPort;
  private final boolean jmxFetchMultipleRuntimeServicesEnabled;
  private final int jmxFetchMultipleRuntimeServicesLimit;

  // These values are default-ed to those of jmx fetch values as needed
  private final boolean healthMetricsEnabled;
  private final String healthMetricsStatsdHost;
  private final Integer healthMetricsStatsdPort;
  private final boolean perfMetricsEnabled;

  private final boolean tracerMetricsEnabled;
  private final boolean tracerMetricsBufferingEnabled;
  private final int tracerMetricsMaxAggregates;
  private final int tracerMetricsMaxPending;

  private final boolean reportHostName;

  private final boolean traceAnalyticsEnabled;
  private final String traceClientIpHeader;
  private final boolean traceClientIpResolverEnabled;

  private final boolean traceGitMetadataEnabled;

  private final Map<String, String> traceSamplingServiceRules;
  private final Map<String, String> traceSamplingOperationRules;
  private final String traceSamplingRules;
  private final Double traceSampleRate;
  private final int traceRateLimit;
  private final String spanSamplingRules;
  private final String spanSamplingRulesFile;

  private final ProfilingEnablement profilingEnabled;
  private final boolean profilingAgentless;
  private final boolean isDatadogProfilerEnabled;
  @Deprecated private final String profilingUrl;
  private final Map<String, String> profilingTags;
  private final int profilingStartDelay;
  private final boolean profilingStartForceFirst;
  private final int profilingUploadPeriod;
  private final String profilingTemplateOverrideFile;
  private final int profilingUploadTimeout;
  private final String profilingUploadCompression;
  private final String profilingProxyHost;
  private final int profilingProxyPort;
  private final String profilingProxyUsername;
  private final String profilingProxyPassword;
  private final int profilingExceptionSampleLimit;
  private final int profilingBackPressureSampleLimit;
  private final boolean profilingBackPressureEnabled;
  private final int profilingDirectAllocationSampleLimit;
  private final int profilingExceptionHistogramTopItems;
  private final int profilingExceptionHistogramMaxCollectionSize;
  private final boolean profilingExcludeAgentThreads;
  private final boolean profilingUploadSummaryOn413Enabled;
  private final boolean profilingRecordExceptionMessage;

  private final boolean crashTrackingAgentless;
  private final Map<String, String> crashTrackingTags;

  private final boolean clientIpEnabled;

  private final boolean appSecReportingInband;
  private final String appSecRulesFile;
  private final int appSecReportMinTimeout;
  private final int appSecReportMaxTimeout;
  private final int appSecTraceRateLimit;
  private final boolean appSecWafMetrics;
  private final int appSecWafTimeout;
  private final String appSecObfuscationParameterKeyRegexp;
  private final String appSecObfuscationParameterValueRegexp;
  private final String appSecHttpBlockedTemplateHtml;
  private final String appSecHttpBlockedTemplateJson;
  private final UserIdCollectionMode appSecUserIdCollectionMode;
  private final Boolean appSecScaEnabled;
  private final boolean appSecRaspEnabled;
  private final boolean appSecStackTraceEnabled;
  private final int appSecMaxStackTraces;
  private final int appSecMaxStackTraceDepth;
  private final boolean appSecStandaloneEnabled;
  private final boolean apiSecurityEnabled;
  private final float apiSecurityRequestSampleRate;

  private final IastDetectionMode iastDetectionMode;
  private final int iastMaxConcurrentRequests;
  private final int iastVulnerabilitiesPerRequest;
  private final float iastRequestSampling;
  private final boolean iastDebugEnabled;
  private final Verbosity iastTelemetryVerbosity;
  private final boolean iastRedactionEnabled;
  private final String iastRedactionNamePattern;
  private final String iastRedactionValuePattern;
  private final int iastMaxRangeCount;
  private final int iastTruncationMaxValueLength;
  private final boolean iastStacktraceLeakSuppress;
  private final IastContext.Mode iastContextMode;
  private final boolean iastHardcodedSecretEnabled;
  private final boolean iastAnonymousClassesEnabled;
  private final boolean iastSourceMappingEnabled;
  private final int iastSourceMappingMaxSize;
  private final boolean iastStackTraceEnabled;
  private final boolean iastExperimentalPropagationEnabled;

  private final boolean ciVisibilityTraceSanitationEnabled;
  private final boolean ciVisibilityAgentlessEnabled;
  private final String ciVisibilityAgentlessUrl;

  private final boolean ciVisibilitySourceDataEnabled;
  private final boolean ciVisibilityBuildInstrumentationEnabled;
  private final String ciVisibilityAgentJarUri;
  private final boolean ciVisibilityAutoConfigurationEnabled;
  private final String ciVisibilityAdditionalChildProcessJvmArgs;
  private final boolean ciVisibilityCompilerPluginAutoConfigurationEnabled;
  private final boolean ciVisibilityCodeCoverageEnabled;
  private final Boolean ciVisibilityCoverageLinesEnabled;
  private final String ciVisibilityCodeCoverageReportDumpDir;
  private final String ciVisibilityCompilerPluginVersion;
  private final String ciVisibilityJacocoPluginVersion;
  private final boolean ciVisibilityJacocoPluginVersionProvided;
  private final List<String> ciVisibilityCodeCoverageIncludes;
  private final List<String> ciVisibilityCodeCoverageExcludes;
  private final String[] ciVisibilityCodeCoverageIncludedPackages;
  private final String[] ciVisibilityCodeCoverageExcludedPackages;
  private final List<String> ciVisibilityJacocoGradleSourceSets;
  private final Integer ciVisibilityDebugPort;
  private final boolean ciVisibilityGitUploadEnabled;
  private final boolean ciVisibilityGitUnshallowEnabled;
  private final boolean ciVisibilityGitUnshallowDefer;
  private final long ciVisibilityGitCommandTimeoutMillis;
  private final String ciVisibilityGitRemoteName;
  private final long ciVisibilityBackendApiTimeoutMillis;
  private final long ciVisibilityGitUploadTimeoutMillis;
  private final String ciVisibilitySignalServerHost;
  private final int ciVisibilitySignalServerPort;
  private final int ciVisibilitySignalClientTimeoutMillis;
  private final boolean ciVisibilityItrEnabled;
  private final boolean ciVisibilityTestSkippingEnabled;
  private final boolean ciVisibilityCiProviderIntegrationEnabled;
  private final boolean ciVisibilityRepoIndexDuplicateKeyCheckEnabled;
  private final int ciVisibilityExecutionSettingsCacheSize;
  private final int ciVisibilityJvmInfoCacheSize;
  private final int ciVisibilityCoverageRootPackagesLimit;
  private final String ciVisibilityInjectedTracerVersion;
  private final List<String> ciVisibilityResourceFolderNames;
  private final boolean ciVisibilityFlakyRetryEnabled;
  private final boolean ciVisibilityFlakyRetryOnlyKnownFlakes;
  private final int ciVisibilityFlakyRetryCount;
  private final int ciVisibilityTotalFlakyRetryCount;
  private final boolean ciVisibilityEarlyFlakeDetectionEnabled;
  private final int ciVisibilityEarlyFlakeDetectionLowerLimit;
  private final String ciVisibilitySessionName;
  private final String ciVisibilityModuleName;
  private final String ciVisibilityTestCommand;
  private final boolean ciVisibilityTelemetryEnabled;
  private final long ciVisibilityRumFlushWaitMillis;
  private final boolean ciVisibilityAutoInjected;
  private final String ciVisibilityRemoteEnvVarsProviderUrl;
  private final String ciVisibilityRemoteEnvVarsProviderKey;
  private final String ciVisibilityTestOrder;

  private final boolean remoteConfigEnabled;
  private final boolean remoteConfigIntegrityCheckEnabled;
  private final String remoteConfigUrl;
  private final float remoteConfigPollIntervalSeconds;
  private final long remoteConfigMaxPayloadSize;
  private final String remoteConfigTargetsKeyId;
  private final String remoteConfigTargetsKey;

  private final int remoteConfigMaxExtraServices;

  private final String DBMPropagationMode;
  private final boolean DBMTracePreparedStatements;

  private final boolean debuggerEnabled;
  private final int debuggerUploadTimeout;
  private final int debuggerUploadFlushInterval;
  private final boolean debuggerClassFileDumpEnabled;
  private final int debuggerPollInterval;
  private final int debuggerDiagnosticsInterval;
  private final boolean debuggerMetricEnabled;
  private final String debuggerProbeFileLocation;
  private final int debuggerUploadBatchSize;
  private final long debuggerMaxPayloadSize;
  private final boolean debuggerVerifyByteCode;
  private final boolean debuggerInstrumentTheWorld;
  private final String debuggerExcludeFiles;
  private final String debuggerIncludeFiles;
  private final int debuggerCaptureTimeout;
  private final String debuggerRedactedIdentifiers;
  private final Set<String> debuggerRedactionExcludedIdentifiers;
  private final String debuggerRedactedTypes;
  private final boolean debuggerSymbolEnabled;
  private final boolean debuggerSymbolForceUpload;
  private final String debuggerSymbolIncludes;
  private final int debuggerSymbolFlushThreshold;
  private final boolean debuggerSymbolCompressed;
  private final boolean debuggerExceptionEnabled;
  private final int debuggerMaxExceptionPerSecond;
  @Deprecated private final boolean debuggerExceptionOnlyLocalRoot;
  private final boolean debuggerExceptionCaptureIntermediateSpansEnabled;
  private final int debuggerExceptionMaxCapturedFrames;
  private final int debuggerExceptionCaptureInterval;
  private final boolean debuggerCodeOriginEnabled;
  private final int debuggerCodeOriginMaxUserFrames;

  private final Set<String> debuggerThirdPartyIncludes;
  private final Set<String> debuggerThirdPartyExcludes;

  private final boolean awsPropagationEnabled;
  private final boolean sqsPropagationEnabled;
  private final boolean sqsBodyPropagationEnabled;

  private final boolean kafkaClientPropagationEnabled;
  private final Set<String> kafkaClientPropagationDisabledTopics;
  private final boolean kafkaClientBase64DecodingEnabled;

  private final boolean jmsPropagationEnabled;
  private final Set<String> jmsPropagationDisabledTopics;
  private final Set<String> jmsPropagationDisabledQueues;
  private final int jmsUnacknowledgedMaxAge;

  private final boolean rabbitPropagationEnabled;
  private final Set<String> rabbitPropagationDisabledQueues;
  private final Set<String> rabbitPropagationDisabledExchanges;

  private final boolean rabbitIncludeRoutingKeyInResource;

  private final boolean messageBrokerSplitByDestination;

  private final boolean hystrixTagsEnabled;
  private final boolean hystrixMeasuredEnabled;

  private final boolean igniteCacheIncludeKeys;

  private final String obfuscationQueryRegexp;

  // TODO: remove at a future point.
  private final boolean playReportHttpStatus;

  private final boolean servletPrincipalEnabled;
  private final boolean servletAsyncTimeoutError;

  private final boolean springDataRepositoryInterfaceResourceName;

  private final int xDatadogTagsMaxLength;

  private final boolean traceAgentV05Enabled;

  private final String logLevel;
  private final boolean debugEnabled;
  private final boolean triageEnabled;
  private final String triageReportTrigger;
  private final String triageReportDir;

  private final boolean startupLogsEnabled;
  private final String configFileStatus;

  private final IdGenerationStrategy idGenerationStrategy;

  private final boolean secureRandom;

  private final boolean trace128bitTraceIdGenerationEnabled;

  private final Set<String> grpcIgnoredInboundMethods;
  private final Set<String> grpcIgnoredOutboundMethods;
  private final boolean grpcServerTrimPackageResource;
  private final BitSet grpcServerErrorStatuses;
  private final BitSet grpcClientErrorStatuses;

  private final boolean cwsEnabled;
  private final int cwsTlsRefresh;

  private final boolean dataJobsEnabled;
  private final String dataJobsCommandPattern;

  private final boolean dataStreamsEnabled;
  private final float dataStreamsBucketDurationSeconds;

  private final Set<String> iastWeakHashAlgorithms;

  private final Pattern iastWeakCipherAlgorithms;

  private final boolean iastDeduplicationEnabled;

  private final float telemetryHeartbeatInterval;
  private final long telemetryExtendedHeartbeatInterval;
  private final float telemetryMetricsInterval;
  private final boolean isTelemetryDependencyServiceEnabled;
  private final boolean telemetryMetricsEnabled;
  private final boolean isTelemetryLogCollectionEnabled;
  private final int telemetryDependencyResolutionQueueSize;

  private final boolean azureAppServices;
  private final String traceAgentPath;
  private final List<String> traceAgentArgs;
  private final String dogStatsDPath;
  private final List<String> dogStatsDArgs;

  private String env;
  private String version;
  private final String primaryTag;

  private final ConfigProvider configProvider;

  private final boolean longRunningTraceEnabled;
  private final long longRunningTraceInitialFlushInterval;
  private final long longRunningTraceFlushInterval;
  private final boolean couchbaseInternalSpansEnabled;
  private final boolean elasticsearchBodyEnabled;
  private final boolean elasticsearchParamsEnabled;
  private final boolean elasticsearchBodyAndParamsEnabled;
  private final boolean sparkTaskHistogramEnabled;
  private final boolean sparkAppNameAsService;
  private final boolean jaxRsExceptionAsErrorsEnabled;

  private final boolean axisPromoteResourceName;
  private final float traceFlushIntervalSeconds;
  private final long tracePostProcessingTimeout;

  private final boolean telemetryDebugRequestsEnabled;

  private final boolean agentlessLogSubmissionEnabled;
  private final int agentlessLogSubmissionQueueSize;
  private final String agentlessLogSubmissionLevel;
  private final String agentlessLogSubmissionUrl;
  private final String agentlessLogSubmissionProduct;

  private final Set<String> cloudPayloadTaggingServices;
  @Nullable private final List<String> cloudRequestPayloadTagging;
  @Nullable private final List<String> cloudResponsePayloadTagging;
  private final int cloudPayloadTaggingMaxDepth;
  private final int cloudPayloadTaggingMaxTags;

  // Read order: System Properties -> Env Variables, [-> properties file], [-> default value]
  private Config() {
    this(ConfigProvider.createDefault());
  }

  private Config(final ConfigProvider configProvider) {
    this(configProvider, new InstrumenterConfig(configProvider));
  }

  private Config(final ConfigProvider configProvider, final InstrumenterConfig instrumenterConfig) {
    this.configProvider = configProvider;
    this.instrumenterConfig = instrumenterConfig;
    configFileStatus = configProvider.getConfigFileStatus();
    runtimeIdEnabled = configProvider.getBoolean(RUNTIME_ID_ENABLED, true);
    runtimeVersion = System.getProperty("java.version", "unknown");

    // Note: We do not want APiKey to be loaded from property for security reasons
    // Note: we do not use defined default here
    // FIXME: We should use better authentication mechanism
    final String apiKeyFile = configProvider.getString(API_KEY_FILE);
    String tmpApiKey =
        configProvider.getStringExcludingSource(API_KEY, null, SystemPropertiesConfigSource.class);
    if (apiKeyFile != null) {
      try {
        tmpApiKey =
            new String(Files.readAllBytes(Paths.get(apiKeyFile)), StandardCharsets.UTF_8).trim();
      } catch (final IOException e) {
        log.error(
            "Cannot read API key from file {}, skipping. Exception {}", apiKeyFile, e.getMessage());
      }
    }
    site = configProvider.getString(SITE, DEFAULT_SITE);

    String tmpApplicationKey =
        configProvider.getStringExcludingSource(
            APPLICATION_KEY, null, SystemPropertiesConfigSource.class);
    String applicationKeyFile = configProvider.getString(APPLICATION_KEY_FILE);
    if (applicationKeyFile != null) {
      try {
        tmpApplicationKey =
            new String(Files.readAllBytes(Paths.get(applicationKeyFile)), StandardCharsets.UTF_8)
                .trim();
      } catch (final IOException e) {
        log.error("Cannot read API key from file {}, skipping", applicationKeyFile, e);
      }
    }
    applicationKey = tmpApplicationKey;

    String userProvidedServiceName =
        configProvider.getStringExcludingSource(
            SERVICE, null, CapturedEnvironmentConfigSource.class, SERVICE_NAME);

    if (userProvidedServiceName == null) {
      serviceNameSetByUser = false;
      serviceName = configProvider.getString(SERVICE, DEFAULT_SERVICE_NAME, SERVICE_NAME);
    } else {
      serviceNameSetByUser = true;
      serviceName = userProvidedServiceName;
    }

    rootContextServiceName =
        configProvider.getString(
            SERVLET_ROOT_CONTEXT_SERVICE_NAME, DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME);

    integrationSynapseLegacyOperationName =
        configProvider.getBoolean(INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME, false);
    writerType = configProvider.getString(WRITER_TYPE, DEFAULT_AGENT_WRITER_TYPE);
    injectBaggageAsTagsEnabled =
        configProvider.getBoolean(WRITER_BAGGAGE_INJECT, DEFAULT_WRITER_BAGGAGE_INJECT);
    String lambdaInitType = getEnv("AWS_LAMBDA_INITIALIZATION_TYPE");
    if (lambdaInitType != null && lambdaInitType.equals("snap-start")) {
      secureRandom = true;
    } else {
      secureRandom = configProvider.getBoolean(SECURE_RANDOM, DEFAULT_SECURE_RANDOM);
    }
    couchbaseInternalSpansEnabled =
        configProvider.getBoolean(
            COUCHBASE_INTERNAL_SPANS_ENABLED, DEFAULT_COUCHBASE_INTERNAL_SPANS_ENABLED);
    elasticsearchBodyEnabled =
        configProvider.getBoolean(ELASTICSEARCH_BODY_ENABLED, DEFAULT_ELASTICSEARCH_BODY_ENABLED);
    elasticsearchParamsEnabled =
        configProvider.getBoolean(
            ELASTICSEARCH_PARAMS_ENABLED, DEFAULT_ELASTICSEARCH_PARAMS_ENABLED);
    elasticsearchBodyAndParamsEnabled =
        configProvider.getBoolean(
            ELASTICSEARCH_BODY_AND_PARAMS_ENABLED, DEFAULT_ELASTICSEARCH_BODY_AND_PARAMS_ENABLED);
    String strategyName = configProvider.getString(ID_GENERATION_STRATEGY);
    trace128bitTraceIdGenerationEnabled =
        configProvider.getBoolean(
            TRACE_128_BIT_TRACEID_GENERATION_ENABLED,
            DEFAULT_TRACE_128_BIT_TRACEID_GENERATION_ENABLED);
    if (secureRandom) {
      strategyName = "SECURE_RANDOM";
    }
    if (strategyName == null) {
      strategyName = "RANDOM";
    }
    IdGenerationStrategy strategy =
        IdGenerationStrategy.fromName(strategyName, trace128bitTraceIdGenerationEnabled);
    if (strategy == null) {
      log.warn(
          "*** you are trying to use an unknown id generation strategy {} - falling back to RANDOM",
          strategyName);
      strategyName = "RANDOM";
      strategy = IdGenerationStrategy.fromName(strategyName, trace128bitTraceIdGenerationEnabled);
    }
    if (!strategyName.equals("RANDOM") && !strategyName.equals("SECURE_RANDOM")) {
      log.warn(
          "*** you are using an unsupported id generation strategy {} - this can impact correctness of traces",
          strategyName);
    }
    idGenerationStrategy = strategy;

    String agentHostFromEnvironment = null;
    int agentPortFromEnvironment = -1;
    String unixSocketFromEnvironment = null;
    boolean rebuildAgentUrl = false;

    final String agentUrlFromEnvironment = configProvider.getString(TRACE_AGENT_URL);
    if (agentUrlFromEnvironment != null) {
      try {
        final URI parsedAgentUrl = new URI(agentUrlFromEnvironment);
        agentHostFromEnvironment = parsedAgentUrl.getHost();
        agentPortFromEnvironment = parsedAgentUrl.getPort();
        if ("unix".equals(parsedAgentUrl.getScheme())) {
          unixSocketFromEnvironment = parsedAgentUrl.getPath();
        }
      } catch (URISyntaxException e) {
        log.warn("{} not configured correctly: {}. Ignoring", TRACE_AGENT_URL, e.getMessage());
      }
    }

    // avoid merging in supplementary host/port settings when dealing with unix: URLs
    if (unixSocketFromEnvironment == null) {
      if (agentHostFromEnvironment == null) {
        agentHostFromEnvironment = configProvider.getString(AGENT_HOST);
        rebuildAgentUrl = true;
      }
      if (agentPortFromEnvironment < 0) {
        agentPortFromEnvironment =
            configProvider.getInteger(TRACE_AGENT_PORT, -1, AGENT_PORT_LEGACY);
        rebuildAgentUrl = true;
      }
    }

    if (agentHostFromEnvironment == null) {
      agentHost = DEFAULT_AGENT_HOST;
    } else {
      agentHost = agentHostFromEnvironment;
    }

    if (agentPortFromEnvironment < 0) {
      agentPort = DEFAULT_TRACE_AGENT_PORT;
    } else {
      agentPort = agentPortFromEnvironment;
    }

    if (rebuildAgentUrl) {
      agentUrl = "http://" + agentHost + ":" + agentPort;
    } else {
      agentUrl = agentUrlFromEnvironment;
    }

    if (unixSocketFromEnvironment == null) {
      unixSocketFromEnvironment = configProvider.getString(AGENT_UNIX_DOMAIN_SOCKET);
      String unixPrefix = "unix://";
      // handle situation where someone passes us a unix:// URL instead of a socket path
      if (unixSocketFromEnvironment != null && unixSocketFromEnvironment.startsWith(unixPrefix)) {
        unixSocketFromEnvironment = unixSocketFromEnvironment.substring(unixPrefix.length());
      }
    }

    agentUnixDomainSocket = unixSocketFromEnvironment;

    agentNamedPipe = configProvider.getString(AGENT_NAMED_PIPE);

    agentConfiguredUsingDefault =
        agentHostFromEnvironment == null
            && agentPortFromEnvironment < 0
            && unixSocketFromEnvironment == null
            && agentNamedPipe == null;

    agentTimeout = configProvider.getInteger(AGENT_TIMEOUT, DEFAULT_AGENT_TIMEOUT);

    // DD_PROXY_NO_PROXY is specified as a space-separated list of hosts
    noProxyHosts = tryMakeImmutableSet(configProvider.getSpacedList(PROXY_NO_PROXY));

    prioritySamplingEnabled =
        configProvider.getBoolean(PRIORITY_SAMPLING, DEFAULT_PRIORITY_SAMPLING_ENABLED);
    prioritySamplingForce =
        configProvider.getString(PRIORITY_SAMPLING_FORCE, DEFAULT_PRIORITY_SAMPLING_FORCE);

    traceResolverEnabled =
        configProvider.getBoolean(TRACE_RESOLVER_ENABLED, DEFAULT_TRACE_RESOLVER_ENABLED);
    serviceMapping = configProvider.getMergedMap(SERVICE_MAPPING);

    {
      final Map<String, String> tags = new HashMap<>(configProvider.getMergedMap(GLOBAL_TAGS));
      tags.putAll(configProvider.getMergedMap(TRACE_TAGS, TAGS));
      this.tags = getMapWithPropertiesDefinedByEnvironment(tags, ENV, VERSION);
    }

    spanTags = configProvider.getMergedMap(SPAN_TAGS);
    jmxTags = configProvider.getMergedMap(JMX_TAGS);

    primaryTag = configProvider.getString(PRIMARY_TAG);

    if (isEnabled(false, HEADER_TAGS, ".legacy.parsing.enabled")) {
      requestHeaderTags = configProvider.getMergedMap(HEADER_TAGS);
      responseHeaderTags = Collections.emptyMap();
      if (configProvider.isSet(REQUEST_HEADER_TAGS)) {
        logIgnoredSettingWarning(REQUEST_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
      if (configProvider.isSet(RESPONSE_HEADER_TAGS)) {
        logIgnoredSettingWarning(RESPONSE_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
    } else {
      requestHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.request.headers.", true, HEADER_TAGS, REQUEST_HEADER_TAGS);
      responseHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.response.headers.", true, HEADER_TAGS, RESPONSE_HEADER_TAGS);
    }
    requestHeaderTagsCommaAllowed =
        configProvider.getBoolean(REQUEST_HEADER_TAGS_COMMA_ALLOWED, true);

    baggageMapping = configProvider.getMergedMapWithOptionalMappings(null, true, BAGGAGE_MAPPING);

    spanAttributeSchemaVersion = schemaVersionFromConfig();

    // following two only used in v0.
    // in v1+ defaults are always calculated regardless this feature flag
    peerServiceDefaultsEnabled =
        configProvider.getBoolean(TRACE_PEER_SERVICE_DEFAULTS_ENABLED, false);
    peerServiceComponentOverrides =
        configProvider.getMergedMap(TRACE_PEER_SERVICE_COMPONENT_OVERRIDES);
    // feature flag to remove fake services in v0
    removeIntegrationServiceNamesEnabled =
        configProvider.getBoolean(TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED, false);

    peerServiceMapping = configProvider.getMergedMap(TRACE_PEER_SERVICE_MAPPING);

    httpServerPathResourceNameMapping =
        configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING);

    httpClientPathResourceNameMapping =
        configProvider.getOrderedMap(TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING);

    httpResourceRemoveTrailingSlash =
        configProvider.getBoolean(
            TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH,
            DEFAULT_TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH);

    httpServerErrorStatuses =
        configProvider.getIntegerRange(
            TRACE_HTTP_SERVER_ERROR_STATUSES,
            DEFAULT_HTTP_SERVER_ERROR_STATUSES,
            HTTP_SERVER_ERROR_STATUSES);

    httpClientErrorStatuses =
        configProvider.getIntegerRange(
            TRACE_HTTP_CLIENT_ERROR_STATUSES,
            DEFAULT_HTTP_CLIENT_ERROR_STATUSES,
            HTTP_CLIENT_ERROR_STATUSES);

    httpServerTagQueryString =
        configProvider.getBoolean(
            HTTP_SERVER_TAG_QUERY_STRING, DEFAULT_HTTP_SERVER_TAG_QUERY_STRING);

    httpServerRawQueryString = configProvider.getBoolean(HTTP_SERVER_RAW_QUERY_STRING, true);

    httpServerRawResource = configProvider.getBoolean(HTTP_SERVER_RAW_RESOURCE, false);

    httpServerDecodedResourcePreserveSpaces =
        configProvider.getBoolean(HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES, true);

    httpServerRouteBasedNaming =
        configProvider.getBoolean(
            HTTP_SERVER_ROUTE_BASED_NAMING, DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING);

    httpClientTagQueryString =
        configProvider.getBoolean(
            TRACE_HTTP_CLIENT_TAG_QUERY_STRING,
            DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING,
            HTTP_CLIENT_TAG_QUERY_STRING);

    httpClientTagHeaders = configProvider.getBoolean(HTTP_CLIENT_TAG_HEADERS, true);

    httpClientSplitByDomain =
        configProvider.getBoolean(
            HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN);

    dbClientSplitByInstance =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE);

    dbClientSplitByInstanceTypeSuffix =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX,
            DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX);

    dbClientSplitByHost =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_HOST, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_HOST);

    DBMPropagationMode =
        configProvider.getString(
            DB_DBM_PROPAGATION_MODE_MODE, DEFAULT_DB_DBM_PROPAGATION_MODE_MODE);

    DBMTracePreparedStatements =
        configProvider.getBoolean(
            DB_DBM_TRACE_PREPARED_STATEMENTS, DEFAULT_DB_DBM_TRACE_PREPARED_STATEMENTS);

    splitByTags = tryMakeImmutableSet(configProvider.getList(SPLIT_BY_TAGS));

    jeeSplitByDeployment =
        configProvider.getBoolean(
            EXPERIMENTATAL_JEE_SPLIT_BY_DEPLOYMENT, DEFAULT_EXPERIMENTATAL_JEE_SPLIT_BY_DEPLOYMENT);

    springDataRepositoryInterfaceResourceName =
        configProvider.getBoolean(SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME, true);

    scopeDepthLimit = configProvider.getInteger(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);

    scopeStrictMode = configProvider.getBoolean(SCOPE_STRICT_MODE, false);

    scopeIterationKeepAlive =
        configProvider.getInteger(SCOPE_ITERATION_KEEP_ALIVE, DEFAULT_SCOPE_ITERATION_KEEP_ALIVE);

    boolean partialFlushEnabled = configProvider.getBoolean(PARTIAL_FLUSH_ENABLED, true);
    partialFlushMinSpans =
        !partialFlushEnabled
            ? 0
            : configProvider.getInteger(PARTIAL_FLUSH_MIN_SPANS, DEFAULT_PARTIAL_FLUSH_MIN_SPANS);

    traceKeepLatencyThreshold =
        configProvider.getInteger(
            TRACE_KEEP_LATENCY_THRESHOLD_MS, DEFAULT_TRACE_KEEP_LATENCY_THRESHOLD_MS);

    traceKeepLatencyThresholdEnabled = !partialFlushEnabled && (traceKeepLatencyThreshold > 0);

    traceStrictWritesEnabled = configProvider.getBoolean(TRACE_STRICT_WRITES_ENABLED, false);

    logExtractHeaderNames =
        configProvider.getBoolean(
            PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED,
            DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED);

    tracePropagationStyleB3PaddingEnabled =
        isEnabled(true, TRACE_PROPAGATION_STYLE, ".b3.padding.enabled");
    {
      // The dd.propagation.style.(extract|inject) settings have been deprecated in
      // favor of dd.trace.propagation.style(|.extract|.inject) settings.
      // The different propagation settings when set will be applied in the following order of
      // precedence, and warnings will be logged for both deprecation and overrides.
      // * dd.trace.propagation.style.(extract|inject)
      // * dd.trace.propagation.style
      // * dd.propagation.style.(extract|inject)
      Set<PropagationStyle> deprecatedExtract =
          getSettingsSetFromEnvironment(
              PROPAGATION_STYLE_EXTRACT, PropagationStyle::valueOfConfigName, true);
      Set<PropagationStyle> deprecatedInject =
          getSettingsSetFromEnvironment(
              PROPAGATION_STYLE_INJECT, PropagationStyle::valueOfConfigName, true);
      Set<TracePropagationStyle> common =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE, TracePropagationStyle::valueOfDisplayName, false);
      Set<TracePropagationStyle> extract =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE_EXTRACT, TracePropagationStyle::valueOfDisplayName, false);
      Set<TracePropagationStyle> inject =
          getSettingsSetFromEnvironment(
              TRACE_PROPAGATION_STYLE_INJECT, TracePropagationStyle::valueOfDisplayName, false);
      String extractOrigin = TRACE_PROPAGATION_STYLE_EXTRACT;
      String injectOrigin = TRACE_PROPAGATION_STYLE_INJECT;
      // Check if we should use the common setting for extraction
      if (extract.isEmpty()) {
        extract = common;
        extractOrigin = TRACE_PROPAGATION_STYLE;
      } else if (!common.isEmpty()) {
        // The more specific settings will override the common setting, so log a warning
        logOverriddenSettingWarning(
            TRACE_PROPAGATION_STYLE, TRACE_PROPAGATION_STYLE_EXTRACT, extract);
      }
      // Check if we should use the common setting for injection
      if (inject.isEmpty()) {
        inject = common;
        injectOrigin = TRACE_PROPAGATION_STYLE;
      } else if (!common.isEmpty()) {
        // The more specific settings will override the common setting, so log a warning
        logOverriddenSettingWarning(
            TRACE_PROPAGATION_STYLE, TRACE_PROPAGATION_STYLE_INJECT, inject);
      }
      // Check if we should use the deprecated setting for extraction
      if (extract.isEmpty()) {
        // If we don't have a new setting, we convert the deprecated one
        extract = convertSettingsSet(deprecatedExtract, PropagationStyle::getNewStyles);
        if (!extract.isEmpty()) {
          logDeprecatedConvertedSetting(
              PROPAGATION_STYLE_EXTRACT,
              deprecatedExtract,
              TRACE_PROPAGATION_STYLE_EXTRACT,
              extract);
        }
      } else if (!deprecatedExtract.isEmpty()) {
        // If we have a new setting, we log a warning
        logOverriddenDeprecatedSettingWarning(PROPAGATION_STYLE_EXTRACT, extractOrigin, extract);
      }
      // Check if we should use the deprecated setting for injection
      if (inject.isEmpty()) {
        // If we don't have a new setting, we convert the deprecated one
        inject = convertSettingsSet(deprecatedInject, PropagationStyle::getNewStyles);
        if (!inject.isEmpty()) {
          logDeprecatedConvertedSetting(
              PROPAGATION_STYLE_INJECT, deprecatedInject, TRACE_PROPAGATION_STYLE_INJECT, inject);
        }
      } else if (!deprecatedInject.isEmpty()) {
        // If we have a new setting, we log a warning
        logOverriddenDeprecatedSettingWarning(PROPAGATION_STYLE_INJECT, injectOrigin, inject);
      }
      // Now we can check if we should pick the default injection/extraction
      tracePropagationStylesToExtract =
          extract.isEmpty() ? DEFAULT_TRACE_PROPAGATION_STYLE : extract;
      tracePropagationStylesToInject = inject.isEmpty() ? DEFAULT_TRACE_PROPAGATION_STYLE : inject;
      // These setting are here for backwards compatibility until they can be removed in a major
      // release of the tracer
      propagationStylesToExtract =
          deprecatedExtract.isEmpty() ? DEFAULT_PROPAGATION_STYLE : deprecatedExtract;
      propagationStylesToInject =
          deprecatedInject.isEmpty() ? DEFAULT_PROPAGATION_STYLE : deprecatedInject;
    }

    tracePropagationExtractFirst =
        configProvider.getBoolean(
            TRACE_PROPAGATION_EXTRACT_FIRST, DEFAULT_TRACE_PROPAGATION_EXTRACT_FIRST);

    clockSyncPeriod = configProvider.getInteger(CLOCK_SYNC_PERIOD, DEFAULT_CLOCK_SYNC_PERIOD);

    logsInjectionEnabled =
        configProvider.getBoolean(
            LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED, LOGS_INJECTION);

    dogStatsDNamedPipe = configProvider.getString(DOGSTATSD_NAMED_PIPE);

    dogStatsDStartDelay =
        configProvider.getInteger(
            DOGSTATSD_START_DELAY, DEFAULT_DOGSTATSD_START_DELAY, JMX_FETCH_START_DELAY);

    statsDClientQueueSize = configProvider.getInteger(STATSD_CLIENT_QUEUE_SIZE);
    statsDClientSocketBuffer = configProvider.getInteger(STATSD_CLIENT_SOCKET_BUFFER);
    statsDClientSocketTimeout = configProvider.getInteger(STATSD_CLIENT_SOCKET_TIMEOUT);

    runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);

    jmxFetchEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(JMX_FETCH_ENABLED, DEFAULT_JMX_FETCH_ENABLED);
    jmxFetchConfigDir = configProvider.getString(JMX_FETCH_CONFIG_DIR);
    jmxFetchConfigs = tryMakeImmutableList(configProvider.getList(JMX_FETCH_CONFIG));
    jmxFetchMetricsConfigs =
        tryMakeImmutableList(configProvider.getList(JMX_FETCH_METRICS_CONFIGS));
    jmxFetchCheckPeriod = configProvider.getInteger(JMX_FETCH_CHECK_PERIOD);
    jmxFetchInitialRefreshBeansPeriod =
        configProvider.getInteger(JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD);
    jmxFetchRefreshBeansPeriod = configProvider.getInteger(JMX_FETCH_REFRESH_BEANS_PERIOD);

    jmxFetchStatsdPort = configProvider.getInteger(JMX_FETCH_STATSD_PORT, DOGSTATSD_PORT);
    jmxFetchStatsdHost =
        configProvider.getString(
            JMX_FETCH_STATSD_HOST,
            // default to agent host if an explicit port has been set
            null != jmxFetchStatsdPort && jmxFetchStatsdPort > 0 ? agentHost : null,
            DOGSTATSD_HOST);

    jmxFetchMultipleRuntimeServicesEnabled =
        configProvider.getBoolean(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED);
    jmxFetchMultipleRuntimeServicesLimit =
        configProvider.getInteger(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT);

    // Writer.Builder createMonitor will use the values of the JMX fetch & agent to fill-in defaults
    healthMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(HEALTH_METRICS_ENABLED, DEFAULT_HEALTH_METRICS_ENABLED);
    healthMetricsStatsdHost = configProvider.getString(HEALTH_METRICS_STATSD_HOST);
    healthMetricsStatsdPort = configProvider.getInteger(HEALTH_METRICS_STATSD_PORT);
    perfMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(PERF_METRICS_ENABLED, DEFAULT_PERF_METRICS_ENABLED);

    tracerMetricsEnabled = configProvider.getBoolean(TRACER_METRICS_ENABLED, false);
    tracerMetricsBufferingEnabled =
        configProvider.getBoolean(TRACER_METRICS_BUFFERING_ENABLED, false);
    tracerMetricsMaxAggregates = configProvider.getInteger(TRACER_METRICS_MAX_AGGREGATES, 2048);
    tracerMetricsMaxPending = configProvider.getInteger(TRACER_METRICS_MAX_PENDING, 2048);

    reportHostName =
        configProvider.getBoolean(TRACE_REPORT_HOSTNAME, DEFAULT_TRACE_REPORT_HOSTNAME);

    traceAgentV05Enabled =
        configProvider.getBoolean(ENABLE_TRACE_AGENT_V05, DEFAULT_TRACE_AGENT_V05_ENABLED);

    traceAnalyticsEnabled =
        configProvider.getBoolean(TRACE_ANALYTICS_ENABLED, DEFAULT_TRACE_ANALYTICS_ENABLED);

    String traceClientIpHeader = configProvider.getString(TRACE_CLIENT_IP_HEADER);
    if (traceClientIpHeader == null) {
      traceClientIpHeader = configProvider.getString(APPSEC_IP_ADDR_HEADER);
    }
    if (traceClientIpHeader != null) {
      traceClientIpHeader = traceClientIpHeader.toLowerCase(Locale.ROOT);
    }
    this.traceClientIpHeader = traceClientIpHeader;

    traceClientIpResolverEnabled =
        configProvider.getBoolean(TRACE_CLIENT_IP_RESOLVER_ENABLED, true);

    traceGitMetadataEnabled = configProvider.getBoolean(TRACE_GIT_METADATA_ENABLED, true);

    traceSamplingServiceRules = configProvider.getMergedMap(TRACE_SAMPLING_SERVICE_RULES);
    traceSamplingOperationRules = configProvider.getMergedMap(TRACE_SAMPLING_OPERATION_RULES);
    traceSamplingRules = configProvider.getString(TRACE_SAMPLING_RULES);
    traceSampleRate = configProvider.getDouble(TRACE_SAMPLE_RATE);
    traceRateLimit = configProvider.getInteger(TRACE_RATE_LIMIT, DEFAULT_TRACE_RATE_LIMIT);
    spanSamplingRules = configProvider.getString(SPAN_SAMPLING_RULES);
    spanSamplingRulesFile = configProvider.getString(SPAN_SAMPLING_RULES_FILE);

    // For the native image 'instrumenterConfig.isProfilingEnabled()' value will be 'baked-in' based
    // on whether
    // the profiler was enabled at build time or not.
    // Otherwise just do the standard config lookup by key.
    // An extra step is needed to properly handle the 'auto' value for profiling enablement via SSI.
    String value =
        configProvider.getString(
            ProfilingConfig.PROFILING_ENABLED,
            String.valueOf(instrumenterConfig.isProfilingEnabled()));
    // Run a validator that will emit a warning if the value is not a valid ProfilingEnablement
    // We don't want it to run in each call to ProfilingEnablement.of(value) not to flood the logs
    ProfilingEnablement.validate(value);
    profilingEnabled = ProfilingEnablement.of(value);
    profilingAgentless =
        configProvider.getBoolean(PROFILING_AGENTLESS, PROFILING_AGENTLESS_DEFAULT);
    isDatadogProfilerEnabled =
        !isDatadogProfilerEnablementOverridden()
            && configProvider.getBoolean(
                PROFILING_DATADOG_PROFILER_ENABLED, isDatadogProfilerSafeInCurrentEnvironment())
            && !(Platform.isNativeImageBuilder() || Platform.isNativeImage());
    profilingUrl = configProvider.getString(PROFILING_URL);

    if (tmpApiKey == null) {
      final String oldProfilingApiKeyFile = configProvider.getString(PROFILING_API_KEY_FILE_OLD);
      tmpApiKey = getEnv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_OLD));
      if (oldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(oldProfilingApiKeyFile)), StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          log.error("Cannot read API key from file {}, skipping", oldProfilingApiKeyFile, e);
        }
      }
    }
    if (tmpApiKey == null) {
      final String veryOldProfilingApiKeyFile =
          configProvider.getString(PROFILING_API_KEY_FILE_VERY_OLD);
      tmpApiKey = getEnv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_VERY_OLD));
      if (veryOldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(veryOldProfilingApiKeyFile)),
                      StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          log.error("Cannot read API key from file {}, skipping", veryOldProfilingApiKeyFile, e);
        }
      }
    }

    profilingTags = configProvider.getMergedMap(PROFILING_TAGS);
    int profilingStartDelayValue =
        configProvider.getInteger(PROFILING_START_DELAY, PROFILING_START_DELAY_DEFAULT);
    boolean profilingStartForceFirstValue =
        configProvider.getBoolean(PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);
    if (profilingEnabled == ProfilingEnablement.AUTO
        || profilingEnabled == ProfilingEnablement.INJECTED) {
      if (profilingStartDelayValue != PROFILING_START_DELAY_DEFAULT) {
        log.info(
            "Profiling start delay is set to {}s, but profiling enablement is set to auto. Using the default delay of {}s.",
            profilingStartDelayValue,
            PROFILING_START_DELAY_DEFAULT);
      }
      if (profilingStartForceFirstValue != PROFILING_START_FORCE_FIRST_DEFAULT) {
        log.info(
            "Profiling is requested to start immediately, but profiling enablement is set to auto. Profiling will be started with delay of {}s.",
            PROFILING_START_DELAY_DEFAULT);
      }
      profilingStartDelayValue = PROFILING_START_DELAY_DEFAULT;
      profilingStartForceFirstValue = PROFILING_START_FORCE_FIRST_DEFAULT;
    }
    profilingStartDelay = profilingStartDelayValue;
    profilingStartForceFirst = profilingStartForceFirstValue;
    profilingUploadPeriod =
        configProvider.getInteger(PROFILING_UPLOAD_PERIOD, PROFILING_UPLOAD_PERIOD_DEFAULT);
    profilingTemplateOverrideFile = configProvider.getString(PROFILING_TEMPLATE_OVERRIDE_FILE);
    profilingUploadTimeout =
        configProvider.getInteger(PROFILING_UPLOAD_TIMEOUT, PROFILING_UPLOAD_TIMEOUT_DEFAULT);
    profilingUploadCompression =
        configProvider.getString(
            PROFILING_UPLOAD_COMPRESSION, PROFILING_UPLOAD_COMPRESSION_DEFAULT);
    profilingProxyHost = configProvider.getString(PROFILING_PROXY_HOST);
    profilingProxyPort =
        configProvider.getInteger(PROFILING_PROXY_PORT, PROFILING_PROXY_PORT_DEFAULT);
    profilingProxyUsername = configProvider.getString(PROFILING_PROXY_USERNAME);
    profilingProxyPassword = configProvider.getString(PROFILING_PROXY_PASSWORD);

    profilingExceptionSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    profilingBackPressureSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, PROFILING_BACKPRESSURE_SAMPLE_LIMIT_DEFAULT);
    profilingBackPressureEnabled =
        configProvider.getBoolean(
            PROFILING_BACKPRESSURE_SAMPLING_ENABLED,
            PROFILING_BACKPRESSURE_SAMPLING_ENABLED_DEFAULT);
    profilingDirectAllocationSampleLimit =
        configProvider.getInteger(
            PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT,
            PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT_DEFAULT);
    profilingExceptionHistogramTopItems =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT);
    profilingExceptionHistogramMaxCollectionSize =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT);

    profilingExcludeAgentThreads = configProvider.getBoolean(PROFILING_EXCLUDE_AGENT_THREADS, true);

    profilingRecordExceptionMessage =
        configProvider.getBoolean(
            PROFILING_EXCEPTION_RECORD_MESSAGE, PROFILING_EXCEPTION_RECORD_MESSAGE_DEFAULT);

    profilingUploadSummaryOn413Enabled =
        configProvider.getBoolean(
            PROFILING_UPLOAD_SUMMARY_ON_413, PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT);

    crashTrackingAgentless =
        configProvider.getBoolean(CRASH_TRACKING_AGENTLESS, CRASH_TRACKING_AGENTLESS_DEFAULT);
    crashTrackingTags = configProvider.getMergedMap(CRASH_TRACKING_TAGS);

    float telemetryInterval =
        configProvider.getFloat(TELEMETRY_HEARTBEAT_INTERVAL, DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL);
    if (telemetryInterval < 0.1 || telemetryInterval > 3600) {
      log.warn(
          "Invalid Telemetry heartbeat interval: {}. The value must be in range 0.1-3600",
          telemetryInterval);
      telemetryInterval = DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
    }
    telemetryHeartbeatInterval = telemetryInterval;

    telemetryExtendedHeartbeatInterval =
        configProvider.getLong(
            TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL, DEFAULT_TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL);

    telemetryInterval =
        configProvider.getFloat(TELEMETRY_METRICS_INTERVAL, DEFAULT_TELEMETRY_METRICS_INTERVAL);
    if (telemetryInterval < 0.1 || telemetryInterval > 3600) {
      log.warn(
          "Invalid Telemetry metrics interval: {}. The value must be in range 0.1-3600",
          telemetryInterval);
      telemetryInterval = DEFAULT_TELEMETRY_METRICS_INTERVAL;
    }
    telemetryMetricsInterval = telemetryInterval;

    telemetryMetricsEnabled =
        configProvider.getBoolean(GeneralConfig.TELEMETRY_METRICS_ENABLED, true);

    isTelemetryLogCollectionEnabled =
        instrumenterConfig.isTelemetryEnabled()
            && configProvider.getBoolean(
                TELEMETRY_LOG_COLLECTION_ENABLED, DEFAULT_TELEMETRY_LOG_COLLECTION_ENABLED);

    isTelemetryDependencyServiceEnabled =
        configProvider.getBoolean(
            TELEMETRY_DEPENDENCY_COLLECTION_ENABLED,
            DEFAULT_TELEMETRY_DEPENDENCY_COLLECTION_ENABLED);
    telemetryDependencyResolutionQueueSize =
        configProvider.getInteger(
            TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE,
            DEFAULT_TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE);
    clientIpEnabled = configProvider.getBoolean(CLIENT_IP_ENABLED, DEFAULT_CLIENT_IP_ENABLED);

    appSecReportingInband =
        configProvider.getBoolean(APPSEC_REPORTING_INBAND, DEFAULT_APPSEC_REPORTING_INBAND);
    appSecRulesFile = configProvider.getString(APPSEC_RULES_FILE, null);

    // Default AppSec report timeout min=5, max=60
    appSecReportMaxTimeout = configProvider.getInteger(APPSEC_REPORT_TIMEOUT_SEC, 60);
    appSecReportMinTimeout = Math.min(appSecReportMaxTimeout, 5);

    appSecTraceRateLimit =
        configProvider.getInteger(APPSEC_TRACE_RATE_LIMIT, DEFAULT_APPSEC_TRACE_RATE_LIMIT);

    appSecWafMetrics = configProvider.getBoolean(APPSEC_WAF_METRICS, DEFAULT_APPSEC_WAF_METRICS);

    appSecWafTimeout = configProvider.getInteger(APPSEC_WAF_TIMEOUT, DEFAULT_APPSEC_WAF_TIMEOUT);

    appSecObfuscationParameterKeyRegexp =
        configProvider.getString(APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, null);
    appSecObfuscationParameterValueRegexp =
        configProvider.getString(APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, null);

    appSecHttpBlockedTemplateHtml =
        configProvider.getString(APPSEC_HTTP_BLOCKED_TEMPLATE_HTML, null);
    appSecHttpBlockedTemplateJson =
        configProvider.getString(APPSEC_HTTP_BLOCKED_TEMPLATE_JSON, null);
    appSecUserIdCollectionMode =
        UserIdCollectionMode.fromString(
            configProvider.getStringNotEmpty(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, null),
            configProvider.getStringNotEmpty(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, null));
    appSecScaEnabled = configProvider.getBoolean(APPSEC_SCA_ENABLED);
    appSecStandaloneEnabled = configProvider.getBoolean(APPSEC_STANDALONE_ENABLED, false);
    appSecRaspEnabled = configProvider.getBoolean(APPSEC_RASP_ENABLED, DEFAULT_APPSEC_RASP_ENABLED);
    appSecStackTraceEnabled =
        configProvider.getBoolean(APPSEC_STACK_TRACE_ENABLED, DEFAULT_APPSEC_STACK_TRACE_ENABLED);
    appSecMaxStackTraces =
        configProvider.getInteger(APPSEC_MAX_STACK_TRACES, DEFAULT_APPSEC_MAX_STACK_TRACES);
    appSecMaxStackTraceDepth =
        configProvider.getInteger(
            APPSEC_MAX_STACK_TRACE_DEPTH, DEFAULT_APPSEC_MAX_STACK_TRACE_DEPTH);
    apiSecurityEnabled =
        configProvider.getBoolean(
            API_SECURITY_ENABLED, DEFAULT_API_SECURITY_ENABLED, API_SECURITY_ENABLED_EXPERIMENTAL);
    apiSecurityRequestSampleRate =
        configProvider.getFloat(
            API_SECURITY_REQUEST_SAMPLE_RATE, DEFAULT_API_SECURITY_REQUEST_SAMPLE_RATE);

    iastDebugEnabled = configProvider.getBoolean(IAST_DEBUG_ENABLED, DEFAULT_IAST_DEBUG_ENABLED);

    iastContextMode =
        configProvider.getEnum(IAST_CONTEXT_MODE, IastContext.Mode.class, IastContext.Mode.REQUEST);
    iastDetectionMode =
        configProvider.getEnum(IAST_DETECTION_MODE, IastDetectionMode.class, DEFAULT);
    iastMaxConcurrentRequests = iastDetectionMode.getIastMaxConcurrentRequests(configProvider);
    iastVulnerabilitiesPerRequest =
        iastDetectionMode.getIastVulnerabilitiesPerRequest(configProvider);
    iastRequestSampling = iastDetectionMode.getIastRequestSampling(configProvider);
    iastDeduplicationEnabled = iastDetectionMode.isIastDeduplicationEnabled(configProvider);
    iastWeakHashAlgorithms =
        tryMakeImmutableSet(
            configProvider.getSet(IAST_WEAK_HASH_ALGORITHMS, DEFAULT_IAST_WEAK_HASH_ALGORITHMS));
    iastWeakCipherAlgorithms =
        getPattern(
            DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS,
            configProvider.getString(IAST_WEAK_CIPHER_ALGORITHMS));
    iastTelemetryVerbosity =
        configProvider.getEnum(IAST_TELEMETRY_VERBOSITY, Verbosity.class, Verbosity.INFORMATION);
    iastRedactionEnabled =
        configProvider.getBoolean(IAST_REDACTION_ENABLED, DEFAULT_IAST_REDACTION_ENABLED);
    iastRedactionNamePattern =
        configProvider.getString(IAST_REDACTION_NAME_PATTERN, DEFAULT_IAST_REDACTION_NAME_PATTERN);
    iastRedactionValuePattern =
        configProvider.getString(
            IAST_REDACTION_VALUE_PATTERN, DEFAULT_IAST_REDACTION_VALUE_PATTERN);
    iastTruncationMaxValueLength =
        configProvider.getInteger(
            IAST_TRUNCATION_MAX_VALUE_LENGTH, DEFAULT_IAST_TRUNCATION_MAX_VALUE_LENGTH);
    iastMaxRangeCount = iastDetectionMode.getIastMaxRangeCount(configProvider);
    iastStacktraceLeakSuppress =
        configProvider.getBoolean(
            IAST_STACKTRACE_LEAK_SUPPRESS, DEFAULT_IAST_STACKTRACE_LEAK_SUPPRESS);
    iastHardcodedSecretEnabled =
        configProvider.getBoolean(
            IAST_HARDCODED_SECRET_ENABLED, DEFAULT_IAST_HARDCODED_SECRET_ENABLED);
    iastAnonymousClassesEnabled =
        configProvider.getBoolean(
            IAST_ANONYMOUS_CLASSES_ENABLED, DEFAULT_IAST_ANONYMOUS_CLASSES_ENABLED);
    iastSourceMappingEnabled = configProvider.getBoolean(IAST_SOURCE_MAPPING_ENABLED, false);
    iastSourceMappingMaxSize = configProvider.getInteger(IAST_SOURCE_MAPPING_MAX_SIZE, 1000);
    iastStackTraceEnabled =
        configProvider.getBoolean(IAST_STACK_TRACE_ENABLED, DEFAULT_IAST_STACK_TRACE_ENABLED);
    iastExperimentalPropagationEnabled =
        configProvider.getBoolean(IAST_EXPERIMENTAL_PROPAGATION_ENABLED, false);

    ciVisibilityTraceSanitationEnabled =
        configProvider.getBoolean(CIVISIBILITY_TRACE_SANITATION_ENABLED, true);

    ciVisibilityAgentlessEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_AGENTLESS_ENABLED, DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED);

    ciVisibilitySourceDataEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_SOURCE_DATA_ENABLED, DEFAULT_CIVISIBILITY_SOURCE_DATA_ENABLED);

    ciVisibilityBuildInstrumentationEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED,
            DEFAULT_CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED);

    final String ciVisibilityAgentlessUrlStr = configProvider.getString(CIVISIBILITY_AGENTLESS_URL);
    URI parsedCiVisibilityUri = null;
    if (ciVisibilityAgentlessUrlStr != null && !ciVisibilityAgentlessUrlStr.isEmpty()) {
      try {
        parsedCiVisibilityUri = new URL(ciVisibilityAgentlessUrlStr).toURI();
      } catch (MalformedURLException | URISyntaxException ex) {
        log.error(
            "Cannot parse CI Visibility agentless URL '{}', skipping", ciVisibilityAgentlessUrlStr);
      }
    }
    if (parsedCiVisibilityUri != null) {
      ciVisibilityAgentlessUrl = ciVisibilityAgentlessUrlStr;
    } else {
      ciVisibilityAgentlessUrl = null;
    }

    ciVisibilityAgentJarUri = configProvider.getString(CIVISIBILITY_AGENT_JAR_URI);
    ciVisibilityAutoConfigurationEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_AUTO_CONFIGURATION_ENABLED,
            DEFAULT_CIVISIBILITY_AUTO_CONFIGURATION_ENABLED);
    ciVisibilityAdditionalChildProcessJvmArgs =
        configProvider.getString(CIVISIBILITY_ADDITIONAL_CHILD_PROCESS_JVM_ARGS);
    ciVisibilityCompilerPluginAutoConfigurationEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED,
            DEFAULT_CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED);
    ciVisibilityCodeCoverageEnabled =
        configProvider.getBoolean(CIVISIBILITY_CODE_COVERAGE_ENABLED, true);
    ciVisibilityCoverageLinesEnabled =
        configProvider.getBoolean(CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED);
    ciVisibilityCodeCoverageReportDumpDir =
        configProvider.getString(CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR);
    ciVisibilityCompilerPluginVersion =
        configProvider.getString(
            CIVISIBILITY_COMPILER_PLUGIN_VERSION, DEFAULT_CIVISIBILITY_COMPILER_PLUGIN_VERSION);
    ciVisibilityJacocoPluginVersion =
        configProvider.getString(
            CIVISIBILITY_JACOCO_PLUGIN_VERSION, DEFAULT_CIVISIBILITY_JACOCO_PLUGIN_VERSION);
    ciVisibilityJacocoPluginVersionProvided =
        configProvider.getString(CIVISIBILITY_JACOCO_PLUGIN_VERSION) != null;
    ciVisibilityCodeCoverageIncludes =
        Arrays.asList(
            COLON.split(configProvider.getString(CIVISIBILITY_CODE_COVERAGE_INCLUDES, ":")));
    ciVisibilityCodeCoverageExcludes =
        Arrays.asList(
            COLON.split(
                configProvider.getString(
                    CIVISIBILITY_CODE_COVERAGE_EXCLUDES,
                    DEFAULT_CIVISIBILITY_JACOCO_PLUGIN_EXCLUDES)));
    ciVisibilityCodeCoverageIncludedPackages =
        convertJacocoExclusionFormatToPackagePrefixes(ciVisibilityCodeCoverageIncludes);
    ciVisibilityCodeCoverageExcludedPackages =
        convertJacocoExclusionFormatToPackagePrefixes(ciVisibilityCodeCoverageExcludes);
    ciVisibilityJacocoGradleSourceSets =
        configProvider.getList(CIVISIBILITY_GRADLE_SOURCE_SETS, Arrays.asList("main", "test"));
    ciVisibilityDebugPort = configProvider.getInteger(CIVISIBILITY_DEBUG_PORT);
    ciVisibilityGitUploadEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_GIT_UPLOAD_ENABLED, DEFAULT_CIVISIBILITY_GIT_UPLOAD_ENABLED);
    ciVisibilityGitUnshallowEnabled =
        configProvider.getBoolean(
            CIVISIBILITY_GIT_UNSHALLOW_ENABLED, DEFAULT_CIVISIBILITY_GIT_UNSHALLOW_ENABLED);
    ciVisibilityGitUnshallowDefer =
        configProvider.getBoolean(CIVISIBILITY_GIT_UNSHALLOW_DEFER, true);
    ciVisibilityGitCommandTimeoutMillis =
        configProvider.getLong(
            CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS,
            DEFAULT_CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS);
    ciVisibilityBackendApiTimeoutMillis =
        configProvider.getLong(
            CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS,
            DEFAULT_CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS);
    ciVisibilityGitUploadTimeoutMillis =
        configProvider.getLong(
            CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS, DEFAULT_CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS);
    ciVisibilityGitRemoteName =
        configProvider.getString(
            CIVISIBILITY_GIT_REMOTE_NAME, DEFAULT_CIVISIBILITY_GIT_REMOTE_NAME);
    ciVisibilitySignalServerHost =
        configProvider.getString(
            CIVISIBILITY_SIGNAL_SERVER_HOST, DEFAULT_CIVISIBILITY_SIGNAL_SERVER_HOST);
    ciVisibilitySignalServerPort =
        configProvider.getInteger(
            CIVISIBILITY_SIGNAL_SERVER_PORT, DEFAULT_CIVISIBILITY_SIGNAL_SERVER_PORT);
    ciVisibilitySignalClientTimeoutMillis =
        configProvider.getInteger(CIVISIBILITY_SIGNAL_CLIENT_TIMEOUT_MILLIS, 10_000);
    ciVisibilityItrEnabled = configProvider.getBoolean(CIVISIBILITY_ITR_ENABLED, true);
    ciVisibilityTestSkippingEnabled =
        configProvider.getBoolean(CIVISIBILITY_TEST_SKIPPING_ENABLED, true);
    ciVisibilityCiProviderIntegrationEnabled =
        configProvider.getBoolean(CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED, true);
    ciVisibilityRepoIndexDuplicateKeyCheckEnabled =
        configProvider.getBoolean(CIVISIBILITY_REPO_INDEX_DUPLICATE_KEY_CHECK_ENABLED, true);
    ciVisibilityExecutionSettingsCacheSize =
        configProvider.getInteger(CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE, 16);
    ciVisibilityJvmInfoCacheSize = configProvider.getInteger(CIVISIBILITY_JVM_INFO_CACHE_SIZE, 8);
    ciVisibilityCoverageRootPackagesLimit =
        configProvider.getInteger(CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT, 50);
    ciVisibilityInjectedTracerVersion =
        configProvider.getString(CIVISIBILITY_INJECTED_TRACER_VERSION);
    ciVisibilityResourceFolderNames =
        configProvider.getList(
            CIVISIBILITY_RESOURCE_FOLDER_NAMES, DEFAULT_CIVISIBILITY_RESOURCE_FOLDER_NAMES);
    ciVisibilityFlakyRetryEnabled =
        configProvider.getBoolean(CIVISIBILITY_FLAKY_RETRY_ENABLED, true);
    ciVisibilityFlakyRetryOnlyKnownFlakes =
        configProvider.getBoolean(CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES, false);
    ciVisibilityEarlyFlakeDetectionEnabled =
        configProvider.getBoolean(CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED, true);
    ciVisibilityEarlyFlakeDetectionLowerLimit =
        configProvider.getInteger(CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT, 30);
    ciVisibilityFlakyRetryCount = configProvider.getInteger(CIVISIBILITY_FLAKY_RETRY_COUNT, 5);
    ciVisibilityTotalFlakyRetryCount =
        configProvider.getInteger(CIVISIBILITY_TOTAL_FLAKY_RETRY_COUNT, 1000);
    ciVisibilitySessionName = configProvider.getString(TEST_SESSION_NAME);
    ciVisibilityModuleName = configProvider.getString(CIVISIBILITY_MODULE_NAME);
    ciVisibilityTestCommand = configProvider.getString(CIVISIBILITY_TEST_COMMAND);
    ciVisibilityTelemetryEnabled = configProvider.getBoolean(CIVISIBILITY_TELEMETRY_ENABLED, true);
    ciVisibilityRumFlushWaitMillis =
        configProvider.getLong(CIVISIBILITY_RUM_FLUSH_WAIT_MILLIS, 500);
    ciVisibilityAutoInjected =
        Strings.isNotBlank(configProvider.getString(CIVISIBILITY_AUTO_INSTRUMENTATION_PROVIDER));
    ciVisibilityRemoteEnvVarsProviderUrl =
        configProvider.getString(CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_URL);
    ciVisibilityRemoteEnvVarsProviderKey =
        configProvider.getString(CIVISIBILITY_REMOTE_ENV_VARS_PROVIDER_KEY);
    ciVisibilityTestOrder = configProvider.getString(CIVISIBILITY_TEST_ORDER);

    remoteConfigEnabled =
        configProvider.getBoolean(
            REMOTE_CONFIGURATION_ENABLED, DEFAULT_REMOTE_CONFIG_ENABLED, REMOTE_CONFIG_ENABLED);
    remoteConfigIntegrityCheckEnabled =
        configProvider.getBoolean(
            REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED, DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED);
    remoteConfigUrl = configProvider.getString(REMOTE_CONFIG_URL);
    remoteConfigPollIntervalSeconds =
        configProvider.getFloat(
            REMOTE_CONFIG_POLL_INTERVAL_SECONDS, DEFAULT_REMOTE_CONFIG_POLL_INTERVAL_SECONDS);
    remoteConfigMaxPayloadSize =
        configProvider.getInteger(
                REMOTE_CONFIG_MAX_PAYLOAD_SIZE, DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE)
            * 1024;
    remoteConfigTargetsKeyId =
        configProvider.getString(
            REMOTE_CONFIG_TARGETS_KEY_ID, DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID);
    remoteConfigTargetsKey =
        configProvider.getString(REMOTE_CONFIG_TARGETS_KEY, DEFAULT_REMOTE_CONFIG_TARGETS_KEY);

    remoteConfigMaxExtraServices =
        configProvider.getInteger(
            REMOTE_CONFIG_MAX_EXTRA_SERVICES, DEFAULT_REMOTE_CONFIG_MAX_EXTRA_SERVICES);

    debuggerEnabled = configProvider.getBoolean(DEBUGGER_ENABLED, DEFAULT_DEBUGGER_ENABLED);
    debuggerUploadTimeout =
        configProvider.getInteger(DEBUGGER_UPLOAD_TIMEOUT, DEFAULT_DEBUGGER_UPLOAD_TIMEOUT);
    debuggerUploadFlushInterval =
        configProvider.getInteger(
            DEBUGGER_UPLOAD_FLUSH_INTERVAL, DEFAULT_DEBUGGER_UPLOAD_FLUSH_INTERVAL);
    debuggerClassFileDumpEnabled =
        configProvider.getBoolean(
            DEBUGGER_CLASSFILE_DUMP_ENABLED, DEFAULT_DEBUGGER_CLASSFILE_DUMP_ENABLED);
    debuggerPollInterval =
        configProvider.getInteger(DEBUGGER_POLL_INTERVAL, DEFAULT_DEBUGGER_POLL_INTERVAL);
    debuggerDiagnosticsInterval =
        configProvider.getInteger(
            DEBUGGER_DIAGNOSTICS_INTERVAL, DEFAULT_DEBUGGER_DIAGNOSTICS_INTERVAL);
    debuggerMetricEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(
                DEBUGGER_METRICS_ENABLED, DEFAULT_DEBUGGER_METRICS_ENABLED);
    debuggerProbeFileLocation = configProvider.getString(DEBUGGER_PROBE_FILE_LOCATION);
    debuggerUploadBatchSize =
        configProvider.getInteger(DEBUGGER_UPLOAD_BATCH_SIZE, DEFAULT_DEBUGGER_UPLOAD_BATCH_SIZE);
    debuggerMaxPayloadSize =
        configProvider.getInteger(DEBUGGER_MAX_PAYLOAD_SIZE, DEFAULT_DEBUGGER_MAX_PAYLOAD_SIZE)
            * 1024;
    debuggerVerifyByteCode =
        configProvider.getBoolean(DEBUGGER_VERIFY_BYTECODE, DEFAULT_DEBUGGER_VERIFY_BYTECODE);
    debuggerInstrumentTheWorld =
        configProvider.getBoolean(
            DEBUGGER_INSTRUMENT_THE_WORLD, DEFAULT_DEBUGGER_INSTRUMENT_THE_WORLD);
    debuggerExcludeFiles = configProvider.getString(DEBUGGER_EXCLUDE_FILES);
    debuggerIncludeFiles = configProvider.getString(DEBUGGER_INCLUDE_FILES);
    debuggerCaptureTimeout =
        configProvider.getInteger(DEBUGGER_CAPTURE_TIMEOUT, DEFAULT_DEBUGGER_CAPTURE_TIMEOUT);
    debuggerRedactedIdentifiers = configProvider.getString(DEBUGGER_REDACTED_IDENTIFIERS, null);
    debuggerRedactionExcludedIdentifiers =
        tryMakeImmutableSet(configProvider.getList(DEBUGGER_REDACTION_EXCLUDED_IDENTIFIERS));
    debuggerRedactedTypes = configProvider.getString(DEBUGGER_REDACTED_TYPES, null);
    debuggerSymbolEnabled =
        configProvider.getBoolean(DEBUGGER_SYMBOL_ENABLED, DEFAULT_DEBUGGER_SYMBOL_ENABLED);
    debuggerSymbolForceUpload =
        configProvider.getBoolean(
            DEBUGGER_SYMBOL_FORCE_UPLOAD, DEFAULT_DEBUGGER_SYMBOL_FORCE_UPLOAD);
    debuggerSymbolIncludes = configProvider.getString(DEBUGGER_SYMBOL_INCLUDES, null);
    debuggerSymbolFlushThreshold =
        configProvider.getInteger(
            DEBUGGER_SYMBOL_FLUSH_THRESHOLD, DEFAULT_DEBUGGER_SYMBOL_FLUSH_THRESHOLD);
    debuggerSymbolCompressed =
        configProvider.getBoolean(DEBUGGER_SYMBOL_COMPRESSED, DEFAULT_DEBUGGER_SYMBOL_COMPRESSED);
    debuggerExceptionEnabled =
        configProvider.getBoolean(
            DEBUGGER_EXCEPTION_ENABLED,
            DEFAULT_DEBUGGER_EXCEPTION_ENABLED,
            EXCEPTION_REPLAY_ENABLED);
    debuggerCodeOriginEnabled =
        configProvider.getBoolean(
            CODE_ORIGIN_FOR_SPANS_ENABLED, DEFAULT_CODE_ORIGIN_FOR_SPANS_ENABLED);
    debuggerCodeOriginMaxUserFrames =
        configProvider.getInteger(CODE_ORIGIN_MAX_USER_FRAMES, DEFAULT_CODE_ORIGIN_MAX_USER_FRAMES);
    debuggerMaxExceptionPerSecond =
        configProvider.getInteger(
            DEBUGGER_MAX_EXCEPTION_PER_SECOND, DEFAULT_DEBUGGER_MAX_EXCEPTION_PER_SECOND);
    debuggerExceptionOnlyLocalRoot =
        configProvider.getBoolean(
            DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT, DEFAULT_DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT);
    debuggerExceptionCaptureIntermediateSpansEnabled =
        configProvider.getBoolean(
            DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED,
            DEFAULT_DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED);
    debuggerExceptionMaxCapturedFrames =
        configProvider.getInteger(
            DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES,
            DEFAULT_DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES,
            DEBUGGER_EXCEPTION_CAPTURE_MAX_FRAMES);
    debuggerExceptionCaptureInterval =
        configProvider.getInteger(
            DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS,
            DEFAULT_DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS);

    debuggerThirdPartyIncludes = tryMakeImmutableSet(configProvider.getList(THIRD_PARTY_INCLUDES));
    debuggerThirdPartyExcludes = tryMakeImmutableSet(configProvider.getList(THIRD_PARTY_EXCLUDES));

    awsPropagationEnabled = isPropagationEnabled(true, "aws", "aws-sdk");
    sqsPropagationEnabled = isPropagationEnabled(true, "sqs");
    sqsBodyPropagationEnabled = configProvider.getBoolean(SQS_BODY_PROPAGATION_ENABLED, false);

    kafkaClientPropagationEnabled = isPropagationEnabled(true, "kafka", "kafka.client");
    kafkaClientPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS));
    kafkaClientBase64DecodingEnabled =
        configProvider.getBoolean(KAFKA_CLIENT_BASE64_DECODING_ENABLED, false);
    jmsPropagationEnabled = isPropagationEnabled(true, "jms");
    jmsPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_TOPICS));
    jmsPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_QUEUES));
    jmsUnacknowledgedMaxAge = configProvider.getInteger(JMS_UNACKNOWLEDGED_MAX_AGE, 3600);

    rabbitPropagationEnabled = isPropagationEnabled(true, "rabbit", "rabbitmq");
    rabbitPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_QUEUES));
    rabbitPropagationDisabledExchanges =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_EXCHANGES));
    rabbitIncludeRoutingKeyInResource =
        configProvider.getBoolean(RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE, true);

    messageBrokerSplitByDestination =
        configProvider.getBoolean(MESSAGE_BROKER_SPLIT_BY_DESTINATION, false);

    grpcIgnoredInboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_INBOUND_METHODS));
    final List<String> tmpGrpcIgnoredOutboundMethods = new ArrayList<>();
    tmpGrpcIgnoredOutboundMethods.addAll(configProvider.getList(GRPC_IGNORED_OUTBOUND_METHODS));
    // When tracing shadowing will be possible we can instrument the stubs to silent tracing
    // starting from interception points
    if (InstrumenterConfig.get()
        .isIntegrationEnabled(Collections.singleton("google-pubsub"), true)) {
      tmpGrpcIgnoredOutboundMethods.addAll(
          configProvider.getList(
              GOOGLE_PUBSUB_IGNORED_GRPC_METHODS,
              Arrays.asList(
                  "google.pubsub.v1.Subscriber/ModifyAckDeadline",
                  "google.pubsub.v1.Subscriber/Acknowledge",
                  "google.pubsub.v1.Subscriber/Pull",
                  "google.pubsub.v1.Subscriber/StreamingPull",
                  "google.pubsub.v1.Publisher/Publish")));
    }
    grpcIgnoredOutboundMethods = tryMakeImmutableSet(tmpGrpcIgnoredOutboundMethods);
    grpcServerTrimPackageResource =
        configProvider.getBoolean(GRPC_SERVER_TRIM_PACKAGE_RESOURCE, false);
    grpcServerErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_SERVER_ERROR_STATUSES, DEFAULT_GRPC_SERVER_ERROR_STATUSES);
    grpcClientErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_CLIENT_ERROR_STATUSES, DEFAULT_GRPC_CLIENT_ERROR_STATUSES);

    hystrixTagsEnabled = configProvider.getBoolean(HYSTRIX_TAGS_ENABLED, false);
    hystrixMeasuredEnabled = configProvider.getBoolean(HYSTRIX_MEASURED_ENABLED, false);

    igniteCacheIncludeKeys = configProvider.getBoolean(IGNITE_CACHE_INCLUDE_KEYS, false);

    obfuscationQueryRegexp =
        configProvider.getString(
            OBFUSCATION_QUERY_STRING_REGEXP, null, "obfuscation.query.string.regexp");

    playReportHttpStatus = configProvider.getBoolean(PLAY_REPORT_HTTP_STATUS, false);

    servletPrincipalEnabled = configProvider.getBoolean(SERVLET_PRINCIPAL_ENABLED, false);

    xDatadogTagsMaxLength =
        configProvider.getInteger(
            TRACE_X_DATADOG_TAGS_MAX_LENGTH, DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);

    servletAsyncTimeoutError = configProvider.getBoolean(SERVLET_ASYNC_TIMEOUT_ERROR, true);

    logLevel = configProvider.getString(LOG_LEVEL);
    debugEnabled = configProvider.getBoolean(TRACE_DEBUG, false);
    triageEnabled = configProvider.getBoolean(TRACE_TRIAGE, instrumenterConfig.isTriageEnabled());
    triageReportTrigger = configProvider.getString(TRIAGE_REPORT_TRIGGER);
    if (null != triageReportTrigger) {
      triageReportDir = configProvider.getString(TRIAGE_REPORT_DIR, getProp("java.io.tmpdir"));
    } else {
      triageReportDir = null;
    }

    startupLogsEnabled =
        configProvider.getBoolean(STARTUP_LOGS_ENABLED, DEFAULT_STARTUP_LOGS_ENABLED);

    cwsEnabled = configProvider.getBoolean(CWS_ENABLED, DEFAULT_CWS_ENABLED);
    cwsTlsRefresh = configProvider.getInteger(CWS_TLS_REFRESH, DEFAULT_CWS_TLS_REFRESH);

    dataJobsEnabled = configProvider.getBoolean(DATA_JOBS_ENABLED, DEFAULT_DATA_JOBS_ENABLED);
    dataJobsCommandPattern = configProvider.getString(DATA_JOBS_COMMAND_PATTERN);

    dataStreamsEnabled =
        configProvider.getBoolean(DATA_STREAMS_ENABLED, DEFAULT_DATA_STREAMS_ENABLED);
    dataStreamsBucketDurationSeconds =
        configProvider.getFloat(
            DATA_STREAMS_BUCKET_DURATION_SECONDS, DEFAULT_DATA_STREAMS_BUCKET_DURATION);

    azureAppServices = configProvider.getBoolean(AZURE_APP_SERVICES, false);
    traceAgentPath = configProvider.getString(TRACE_AGENT_PATH);
    String traceAgentArgsString = configProvider.getString(TRACE_AGENT_ARGS);
    if (traceAgentArgsString == null) {
      traceAgentArgs = Collections.emptyList();
    } else {
      traceAgentArgs =
          Collections.unmodifiableList(
              new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(traceAgentArgsString)));
    }

    dogStatsDPath = configProvider.getString(DOGSTATSD_PATH);
    String dogStatsDArgsString = configProvider.getString(DOGSTATSD_ARGS);
    if (dogStatsDArgsString == null) {
      dogStatsDArgs = Collections.emptyList();
    } else {
      dogStatsDArgs =
          Collections.unmodifiableList(
              new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(dogStatsDArgsString)));
    }

    // Setting this last because we have a few places where this can come from
    apiKey = tmpApiKey;

    boolean longRunningEnabled =
        configProvider.getBoolean(
            TracerConfig.TRACE_LONG_RUNNING_ENABLED,
            ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_ENABLED);
    long longRunningTraceInitialFlushInterval =
        configProvider.getLong(
            TracerConfig.TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL,
            DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL);
    long longRunningTraceFlushInterval =
        configProvider.getLong(
            TracerConfig.TRACE_LONG_RUNNING_FLUSH_INTERVAL,
            ConfigDefaults.DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL);

    if (longRunningEnabled
        && (longRunningTraceInitialFlushInterval < 10
            || longRunningTraceInitialFlushInterval > 450)) {
      log.warn(
          "Provided long running trace initial flush interval of {} seconds. It should be between 10 seconds and 7.5 minutes."
              + "Setting the flush interval to the default value of {} seconds .",
          longRunningTraceInitialFlushInterval,
          DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL);
      longRunningTraceInitialFlushInterval = DEFAULT_TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL;
    }
    if (longRunningEnabled
        && (longRunningTraceFlushInterval < 20 || longRunningTraceFlushInterval > 450)) {
      log.warn(
          "Provided long running trace flush interval of {} seconds. It should be between 20 seconds and 7.5 minutes."
              + "Setting the flush interval to the default value of {} seconds .",
          longRunningTraceFlushInterval,
          DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL);
      longRunningTraceFlushInterval = DEFAULT_TRACE_LONG_RUNNING_FLUSH_INTERVAL;
    }
    this.longRunningTraceEnabled = longRunningEnabled;
    this.longRunningTraceInitialFlushInterval = longRunningTraceInitialFlushInterval;
    this.longRunningTraceFlushInterval = longRunningTraceFlushInterval;

    this.sparkTaskHistogramEnabled =
        configProvider.getBoolean(
            SPARK_TASK_HISTOGRAM_ENABLED, ConfigDefaults.DEFAULT_SPARK_TASK_HISTOGRAM_ENABLED);

    this.sparkAppNameAsService =
        configProvider.getBoolean(
            SPARK_APP_NAME_AS_SERVICE, ConfigDefaults.DEFAULT_SPARK_APP_NAME_AS_SERVICE);

    this.jaxRsExceptionAsErrorsEnabled =
        configProvider.getBoolean(
            JAX_RS_EXCEPTION_AS_ERROR_ENABLED,
            ConfigDefaults.DEFAULT_JAX_RS_EXCEPTION_AS_ERROR_ENABLED);

    axisPromoteResourceName = configProvider.getBoolean(AXIS_PROMOTE_RESOURCE_NAME, false);

    this.traceFlushIntervalSeconds =
        configProvider.getFloat(
            TracerConfig.TRACE_FLUSH_INTERVAL, ConfigDefaults.DEFAULT_TRACE_FLUSH_INTERVAL);
    if (profilingAgentless && apiKey == null) {
      log.warn(
          "Agentless profiling activated but no api key provided. Profile uploading will likely fail");
    }

    this.tracePostProcessingTimeout =
        configProvider.getLong(
            TRACE_POST_PROCESSING_TIMEOUT, ConfigDefaults.DEFAULT_TRACE_POST_PROCESSING_TIMEOUT);

    if (isCiVisibilityEnabled()
        && ciVisibilityAgentlessEnabled
        && (apiKey == null || apiKey.isEmpty())) {
      throw new FatalAgentMisconfigurationError(
          "Attempt to start in Agentless mode without API key. "
              + "Please ensure that either an API key is configured, or the tracer is set up to work with the Agent");
    }

    this.telemetryDebugRequestsEnabled =
        configProvider.getBoolean(
            GeneralConfig.TELEMETRY_DEBUG_REQUESTS_ENABLED,
            ConfigDefaults.DEFAULT_TELEMETRY_DEBUG_REQUESTS_ENABLED);

    this.agentlessLogSubmissionEnabled =
        configProvider.getBoolean(GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED, false);
    this.agentlessLogSubmissionQueueSize =
        configProvider.getInteger(GeneralConfig.AGENTLESS_LOG_SUBMISSION_QUEUE_SIZE, 1024);
    this.agentlessLogSubmissionLevel =
        configProvider.getString(GeneralConfig.AGENTLESS_LOG_SUBMISSION_LEVEL, "INFO");
    this.agentlessLogSubmissionUrl =
        configProvider.getString(GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL);
    this.agentlessLogSubmissionProduct = isCiVisibilityEnabled() ? "citest" : "apm";

    this.cloudPayloadTaggingServices =
        configProvider.getSet(
            TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_SERVICES,
            ConfigDefaults.DEFAULT_TRACE_CLOUD_PAYLOAD_TAGGING_SERVICES);
    this.cloudRequestPayloadTagging =
        configProvider.getList(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, null);
    this.cloudResponsePayloadTagging =
        configProvider.getList(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, null);
    this.cloudPayloadTaggingMaxDepth =
        configProvider.getInteger(TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_MAX_DEPTH, 10);
    this.cloudPayloadTaggingMaxTags =
        configProvider.getInteger(TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_MAX_TAGS, 758);

    timelineEventsEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED,
            ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED_DEFAULT);

    if (appSecScaEnabled != null
        && appSecScaEnabled
        && (!isTelemetryEnabled() || !isTelemetryDependencyServiceEnabled())) {
      log.warn(
          SEND_TELEMETRY,
          "AppSec SCA is enabled but telemetry is disabled. AppSec SCA will not work.");
    }

    log.debug("New instance: {}", this);
  }

  /**
   * Converts a list of packages in Jacoco exclusion format ({@code
   * my.package.*,my.other.package.*}) to list of package prefixes suitable for use with ASM ({@code
   * my/package/,my/other/package/})
   */
  public static String[] convertJacocoExclusionFormatToPackagePrefixes(List<String> packages) {
    return packages.stream()
        .map(s -> (s.endsWith("*") ? s.substring(0, s.length() - 1) : s).replace('.', '/'))
        .toArray(String[]::new);
  }

  public ConfigProvider configProvider() {
    return configProvider;
  }

  public long getStartTimeMillis() {
    return startTimeMillis;
  }

  public String getRuntimeId() {
    return runtimeIdEnabled ? RuntimeIdHolder.runtimeId : "";
  }

  public Long getProcessId() {
    return PidHelper.getPidAsLong();
  }

  public String getRuntimeVersion() {
    return runtimeVersion;
  }

  public String getApiKey() {
    return apiKey;
  }

  public String getApplicationKey() {
    return applicationKey;
  }

  public String getSite() {
    return site;
  }

  public String getHostName() {
    return HostNameHolder.hostName;
  }

  public Supplier<String> getHostNameSupplier() {
    return HostNameHolder::getHostName;
  }

  public String getServiceName() {
    return serviceName;
  }

  public boolean isServiceNameSetByUser() {
    return serviceNameSetByUser;
  }

  public String getRootContextServiceName() {
    return rootContextServiceName;
  }

  public boolean isTraceEnabled() {
    return instrumenterConfig.isTraceEnabled();
  }

  public boolean isLongRunningTraceEnabled() {
    return longRunningTraceEnabled;
  }

  public long getLongRunningTraceInitialFlushInterval() {
    return longRunningTraceInitialFlushInterval;
  }

  public long getLongRunningTraceFlushInterval() {
    return longRunningTraceFlushInterval;
  }

  public float getTraceFlushIntervalSeconds() {
    return traceFlushIntervalSeconds;
  }

  public long getTracePostProcessingTimeout() {
    return tracePostProcessingTimeout;
  }

  public boolean isIntegrationSynapseLegacyOperationName() {
    return integrationSynapseLegacyOperationName;
  }

  public String getWriterType() {
    return writerType;
  }

  public boolean isInjectBaggageAsTagsEnabled() {
    return injectBaggageAsTagsEnabled;
  }

  public boolean isAgentConfiguredUsingDefault() {
    return agentConfiguredUsingDefault;
  }

  public String getAgentUrl() {
    return agentUrl;
  }

  public String getAgentHost() {
    return agentHost;
  }

  public int getAgentPort() {
    return agentPort;
  }

  public String getAgentUnixDomainSocket() {
    return agentUnixDomainSocket;
  }

  public String getAgentNamedPipe() {
    return agentNamedPipe;
  }

  public int getAgentTimeout() {
    return agentTimeout;
  }

  public Set<String> getNoProxyHosts() {
    return noProxyHosts;
  }

  public boolean isPrioritySamplingEnabled() {
    return prioritySamplingEnabled;
  }

  public String getPrioritySamplingForce() {
    return prioritySamplingForce;
  }

  public boolean isTraceResolverEnabled() {
    return traceResolverEnabled;
  }

  public Set<String> getIastWeakHashAlgorithms() {
    return iastWeakHashAlgorithms;
  }

  public Pattern getIastWeakCipherAlgorithms() {
    return iastWeakCipherAlgorithms;
  }

  public boolean isIastDeduplicationEnabled() {
    return iastDeduplicationEnabled;
  }

  public int getSpanAttributeSchemaVersion() {
    return spanAttributeSchemaVersion;
  }

  public boolean isPeerServiceDefaultsEnabled() {
    return peerServiceDefaultsEnabled;
  }

  public Map<String, String> getPeerServiceComponentOverrides() {
    return peerServiceComponentOverrides;
  }

  public boolean isRemoveIntegrationServiceNamesEnabled() {
    return removeIntegrationServiceNamesEnabled;
  }

  public Map<String, String> getPeerServiceMapping() {
    return peerServiceMapping;
  }

  public Map<String, String> getServiceMapping() {
    return serviceMapping;
  }

  public Map<String, String> getRequestHeaderTags() {
    return requestHeaderTags;
  }

  public Map<String, String> getResponseHeaderTags() {
    return responseHeaderTags;
  }

  public boolean isRequestHeaderTagsCommaAllowed() {
    return requestHeaderTagsCommaAllowed;
  }

  public Map<String, String> getBaggageMapping() {
    return baggageMapping;
  }

  public Map<String, String> getHttpServerPathResourceNameMapping() {
    return httpServerPathResourceNameMapping;
  }

  public Map<String, String> getHttpClientPathResourceNameMapping() {
    return httpClientPathResourceNameMapping;
  }

  public boolean getHttpResourceRemoveTrailingSlash() {
    return httpResourceRemoveTrailingSlash;
  }

  public BitSet getHttpServerErrorStatuses() {
    return httpServerErrorStatuses;
  }

  public BitSet getHttpClientErrorStatuses() {
    return httpClientErrorStatuses;
  }

  public boolean isHttpServerTagQueryString() {
    return httpServerTagQueryString;
  }

  public boolean isHttpServerRawQueryString() {
    return httpServerRawQueryString;
  }

  public boolean isHttpServerRawResource() {
    return httpServerRawResource;
  }

  public boolean isHttpServerDecodedResourcePreserveSpaces() {
    return httpServerDecodedResourcePreserveSpaces;
  }

  public boolean isHttpServerRouteBasedNaming() {
    return httpServerRouteBasedNaming;
  }

  public boolean isHttpClientTagQueryString() {
    return httpClientTagQueryString;
  }

  public boolean isHttpClientTagHeaders() {
    return httpClientTagHeaders;
  }

  public boolean isHttpClientSplitByDomain() {
    return httpClientSplitByDomain;
  }

  public boolean isDbClientSplitByInstance() {
    return dbClientSplitByInstance;
  }

  public boolean isDbClientSplitByInstanceTypeSuffix() {
    return dbClientSplitByInstanceTypeSuffix;
  }

  public boolean isDbClientSplitByHost() {
    return dbClientSplitByHost;
  }

  public Set<String> getSplitByTags() {
    return splitByTags;
  }

  public boolean isJeeSplitByDeployment() {
    return jeeSplitByDeployment;
  }

  public int getScopeDepthLimit() {
    return scopeDepthLimit;
  }

  public boolean isScopeStrictMode() {
    return scopeStrictMode;
  }

  public int getScopeIterationKeepAlive() {
    return scopeIterationKeepAlive;
  }

  public int getPartialFlushMinSpans() {
    return partialFlushMinSpans;
  }

  public int getTraceKeepLatencyThreshold() {
    return traceKeepLatencyThreshold;
  }

  public boolean isTraceKeepLatencyThresholdEnabled() {
    return traceKeepLatencyThresholdEnabled;
  }

  public boolean isTraceStrictWritesEnabled() {
    return traceStrictWritesEnabled;
  }

  public boolean isLogExtractHeaderNames() {
    return logExtractHeaderNames;
  }

  @Deprecated
  public Set<PropagationStyle> getPropagationStylesToExtract() {
    return propagationStylesToExtract;
  }

  @Deprecated
  public Set<PropagationStyle> getPropagationStylesToInject() {
    return propagationStylesToInject;
  }

  public boolean isTracePropagationStyleB3PaddingEnabled() {
    return tracePropagationStyleB3PaddingEnabled;
  }

  public Set<TracePropagationStyle> getTracePropagationStylesToExtract() {
    return tracePropagationStylesToExtract;
  }

  public Set<TracePropagationStyle> getTracePropagationStylesToInject() {
    return tracePropagationStylesToInject;
  }

  public boolean isTracePropagationExtractFirst() {
    return tracePropagationExtractFirst;
  }

  public int getClockSyncPeriod() {
    return clockSyncPeriod;
  }

  public String getDogStatsDNamedPipe() {
    return dogStatsDNamedPipe;
  }

  public int getDogStatsDStartDelay() {
    return dogStatsDStartDelay;
  }

  public Integer getStatsDClientQueueSize() {
    return statsDClientQueueSize;
  }

  public Integer getStatsDClientSocketBuffer() {
    return statsDClientSocketBuffer;
  }

  public Integer getStatsDClientSocketTimeout() {
    return statsDClientSocketTimeout;
  }

  public boolean isRuntimeMetricsEnabled() {
    return runtimeMetricsEnabled;
  }

  public boolean isJmxFetchEnabled() {
    return jmxFetchEnabled;
  }

  public String getJmxFetchConfigDir() {
    return jmxFetchConfigDir;
  }

  public List<String> getJmxFetchConfigs() {
    return jmxFetchConfigs;
  }

  public List<String> getJmxFetchMetricsConfigs() {
    return jmxFetchMetricsConfigs;
  }

  public Integer getJmxFetchCheckPeriod() {
    return jmxFetchCheckPeriod;
  }

  public Integer getJmxFetchRefreshBeansPeriod() {
    return jmxFetchRefreshBeansPeriod;
  }

  public Integer getJmxFetchInitialRefreshBeansPeriod() {
    return jmxFetchInitialRefreshBeansPeriod;
  }

  public String getJmxFetchStatsdHost() {
    return jmxFetchStatsdHost;
  }

  public Integer getJmxFetchStatsdPort() {
    return jmxFetchStatsdPort;
  }

  public boolean isJmxFetchMultipleRuntimeServicesEnabled() {
    return jmxFetchMultipleRuntimeServicesEnabled;
  }

  public int getJmxFetchMultipleRuntimeServicesLimit() {
    return jmxFetchMultipleRuntimeServicesLimit;
  }

  public boolean isHealthMetricsEnabled() {
    return healthMetricsEnabled;
  }

  public String getHealthMetricsStatsdHost() {
    return healthMetricsStatsdHost;
  }

  public Integer getHealthMetricsStatsdPort() {
    return healthMetricsStatsdPort;
  }

  public boolean isPerfMetricsEnabled() {
    return perfMetricsEnabled;
  }

  public boolean isTracerMetricsEnabled() {
    // When ASM Standalone Billing is enabled metrics should be disabled
    return tracerMetricsEnabled && !isAppSecStandaloneEnabled();
  }

  public boolean isTracerMetricsBufferingEnabled() {
    return tracerMetricsBufferingEnabled;
  }

  public int getTracerMetricsMaxAggregates() {
    return tracerMetricsMaxAggregates;
  }

  public int getTracerMetricsMaxPending() {
    return tracerMetricsMaxPending;
  }

  public boolean isLogsInjectionEnabled() {
    return logsInjectionEnabled;
  }

  public boolean isReportHostName() {
    return reportHostName;
  }

  public boolean isTraceAnalyticsEnabled() {
    return traceAnalyticsEnabled;
  }

  public String getTraceClientIpHeader() {
    return traceClientIpHeader;
  }

  // whether to collect headers and run the client ip resolution (also requires appsec to be enabled
  // or clientIpEnabled)
  public boolean isTraceClientIpResolverEnabled() {
    return traceClientIpResolverEnabled;
  }

  public boolean isTraceGitMetadataEnabled() {
    return traceGitMetadataEnabled;
  }

  public Map<String, String> getTraceSamplingServiceRules() {
    return traceSamplingServiceRules;
  }

  public Map<String, String> getTraceSamplingOperationRules() {
    return traceSamplingOperationRules;
  }

  public String getTraceSamplingRules() {
    return traceSamplingRules;
  }

  public Double getTraceSampleRate() {
    return traceSampleRate;
  }

  public int getTraceRateLimit() {
    return traceRateLimit;
  }

  public String getSpanSamplingRules() {
    return spanSamplingRules;
  }

  public String getSpanSamplingRulesFile() {
    return spanSamplingRulesFile;
  }

  public boolean isProfilingEnabled() {
    if (Platform.isNativeImage()) {
      if (!instrumenterConfig.isProfilingEnabled() && profilingEnabled.isActive()) {
        log.warn(
            "Profiling was not enabled during the native image build. "
                + "Please set DD_PROFILING_ENABLED=true in your native image build configuration if you want"
                + "to use profiling.");
      }
    }
    return profilingEnabled.isActive() && instrumenterConfig.isProfilingEnabled();
  }

  public boolean isProfilingTimelineEventsEnabled() {
    return timelineEventsEnabled;
  }

  public boolean isProfilingAgentless() {
    return profilingAgentless;
  }

  public int getProfilingStartDelay() {
    return profilingStartDelay;
  }

  public boolean isProfilingStartForceFirst() {
    return profilingStartForceFirst;
  }

  public int getProfilingUploadPeriod() {
    return profilingUploadPeriod;
  }

  public String getProfilingTemplateOverrideFile() {
    return profilingTemplateOverrideFile;
  }

  public int getProfilingUploadTimeout() {
    return profilingUploadTimeout;
  }

  public String getProfilingUploadCompression() {
    return profilingUploadCompression;
  }

  public String getProfilingProxyHost() {
    return profilingProxyHost;
  }

  public int getProfilingProxyPort() {
    return profilingProxyPort;
  }

  public String getProfilingProxyUsername() {
    return profilingProxyUsername;
  }

  public String getProfilingProxyPassword() {
    return profilingProxyPassword;
  }

  public int getProfilingExceptionSampleLimit() {
    return profilingExceptionSampleLimit;
  }

  public int getProfilingDirectAllocationSampleLimit() {
    return profilingDirectAllocationSampleLimit;
  }

  public int getProfilingBackPressureSampleLimit() {
    return profilingBackPressureSampleLimit;
  }

  public boolean isProfilingBackPressureSamplingEnabled() {
    return profilingBackPressureEnabled;
  }

  public int getProfilingExceptionHistogramTopItems() {
    return profilingExceptionHistogramTopItems;
  }

  public int getProfilingExceptionHistogramMaxCollectionSize() {
    return profilingExceptionHistogramMaxCollectionSize;
  }

  public boolean isProfilingExcludeAgentThreads() {
    return profilingExcludeAgentThreads;
  }

  public boolean isProfilingUploadSummaryOn413Enabled() {
    return profilingUploadSummaryOn413Enabled;
  }

  public boolean isProfilingRecordExceptionMessage() {
    return profilingRecordExceptionMessage;
  }

  public boolean isDatadogProfilerEnabled() {
    return isProfilingEnabled() && isDatadogProfilerEnabled;
  }

  public static boolean isDatadogProfilerEnablementOverridden() {
    // old non-LTS versions without important backports
    // also, we have no windows binaries
    return Platform.isWindows()
        || Platform.isJavaVersion(18)
        || Platform.isJavaVersion(16)
        || Platform.isJavaVersion(15)
        || Platform.isJavaVersion(14)
        || Platform.isJavaVersion(13)
        || Platform.isJavaVersion(12)
        || Platform.isJavaVersion(10)
        || Platform.isJavaVersion(9);
  }

  public static boolean isDatadogProfilerSafeInCurrentEnvironment() {
    // don't want to put this logic (which will evolve) in the public ProfilingConfig, and can't
    // access Platform there
    if (!Platform.isJ9() && Platform.isJavaVersion(8)) {
      String arch = System.getProperty("os.arch");
      if ("aarch64".equalsIgnoreCase(arch) || "arm64".equalsIgnoreCase(arch)) {
        return false;
      }
    }
    if (Platform.isGraalVM()) {
      // let's be conservative about GraalVM and require opt-in from the users
      return false;
    }
    boolean result = false;
    if (Platform.isJ9()) {
      // OpenJ9 will activate only JVMTI GetAllStackTraces based profiling which is safe
      result = true;
    } else {
      // JDK 18 is missing ASGCT fixes, so we can't use it
      if (!Platform.isJavaVersion(18)) {
        result =
            Platform.isJavaVersionAtLeast(17, 0, 5)
                || (Platform.isJavaVersion(11) && Platform.isJavaVersionAtLeast(11, 0, 17))
                || (Platform.isJavaVersion(8) && Platform.isJavaVersionAtLeast(8, 0, 352));
      }
    }
    return result;
  }

  public boolean isCrashTrackingAgentless() {
    return crashTrackingAgentless;
  }

  public boolean isTelemetryEnabled() {
    return instrumenterConfig.isTelemetryEnabled();
  }

  public float getTelemetryHeartbeatInterval() {
    return telemetryHeartbeatInterval;
  }

  public long getTelemetryExtendedHeartbeatInterval() {
    return telemetryExtendedHeartbeatInterval;
  }

  public float getTelemetryMetricsInterval() {
    return telemetryMetricsInterval;
  }

  public boolean isTelemetryDependencyServiceEnabled() {
    return isTelemetryDependencyServiceEnabled;
  }

  public boolean isTelemetryMetricsEnabled() {
    return telemetryMetricsEnabled;
  }

  public boolean isTelemetryLogCollectionEnabled() {
    return isTelemetryLogCollectionEnabled;
  }

  public int getTelemetryDependencyResolutionQueueSize() {
    return telemetryDependencyResolutionQueueSize;
  }

  public boolean isClientIpEnabled() {
    return clientIpEnabled;
  }

  public ProductActivation getAppSecActivation() {
    return instrumenterConfig.getAppSecActivation();
  }

  public boolean isAppSecReportingInband() {
    return appSecReportingInband;
  }

  public int getAppSecReportMinTimeout() {
    return appSecReportMinTimeout;
  }

  public int getAppSecReportMaxTimeout() {
    return appSecReportMaxTimeout;
  }

  public int getAppSecTraceRateLimit() {
    return appSecTraceRateLimit;
  }

  public boolean isAppSecWafMetrics() {
    return appSecWafMetrics;
  }

  // in microseconds
  public int getAppSecWafTimeout() {
    return appSecWafTimeout;
  }

  public String getAppSecObfuscationParameterKeyRegexp() {
    return appSecObfuscationParameterKeyRegexp;
  }

  public String getAppSecObfuscationParameterValueRegexp() {
    return appSecObfuscationParameterValueRegexp;
  }

  public String getAppSecHttpBlockedTemplateHtml() {
    return appSecHttpBlockedTemplateHtml;
  }

  public String getAppSecHttpBlockedTemplateJson() {
    return appSecHttpBlockedTemplateJson;
  }

  public UserIdCollectionMode getAppSecUserIdCollectionMode() {
    return appSecUserIdCollectionMode;
  }

  public boolean isApiSecurityEnabled() {
    return apiSecurityEnabled;
  }

  public float getApiSecurityRequestSampleRate() {
    return apiSecurityRequestSampleRate;
  }

  public ProductActivation getIastActivation() {
    return instrumenterConfig.getIastActivation();
  }

  public boolean isIastDebugEnabled() {
    return iastDebugEnabled;
  }

  public int getIastMaxConcurrentRequests() {
    return iastMaxConcurrentRequests;
  }

  public int getIastVulnerabilitiesPerRequest() {
    return iastVulnerabilitiesPerRequest;
  }

  public float getIastRequestSampling() {
    return iastRequestSampling;
  }

  public Verbosity getIastTelemetryVerbosity() {
    return isTelemetryEnabled() ? iastTelemetryVerbosity : Verbosity.OFF;
  }

  public boolean isIastRedactionEnabled() {
    return iastRedactionEnabled;
  }

  public String getIastRedactionNamePattern() {
    return iastRedactionNamePattern;
  }

  public String getIastRedactionValuePattern() {
    return iastRedactionValuePattern;
  }

  public int getIastTruncationMaxValueLength() {
    return iastTruncationMaxValueLength;
  }

  public int getIastMaxRangeCount() {
    return iastMaxRangeCount;
  }

  public boolean isIastStacktraceLeakSuppress() {
    return iastStacktraceLeakSuppress;
  }

  public IastContext.Mode getIastContextMode() {
    return iastContextMode;
  }

  public boolean isIastHardcodedSecretEnabled() {
    return iastHardcodedSecretEnabled;
  }

  public boolean isIastSourceMappingEnabled() {
    return iastSourceMappingEnabled;
  }

  public int getIastSourceMappingMaxSize() {
    return iastSourceMappingMaxSize;
  }

  public IastDetectionMode getIastDetectionMode() {
    return iastDetectionMode;
  }

  public boolean isIastAnonymousClassesEnabled() {
    return iastAnonymousClassesEnabled;
  }

  public boolean isIastStackTraceEnabled() {
    return iastStackTraceEnabled;
  }

  public boolean isIastExperimentalPropagationEnabled() {
    return iastExperimentalPropagationEnabled;
  }

  public boolean isCiVisibilityEnabled() {
    return instrumenterConfig.isCiVisibilityEnabled();
  }

  public boolean isUsmEnabled() {
    return instrumenterConfig.isUsmEnabled();
  }

  public boolean isCiVisibilityTraceSanitationEnabled() {
    return ciVisibilityTraceSanitationEnabled;
  }

  public boolean isCiVisibilityAgentlessEnabled() {
    return ciVisibilityAgentlessEnabled;
  }

  public String getCiVisibilityAgentlessUrl() {
    return ciVisibilityAgentlessUrl;
  }

  public boolean isCiVisibilitySourceDataEnabled() {
    return ciVisibilitySourceDataEnabled;
  }

  public boolean isCiVisibilityBuildInstrumentationEnabled() {
    return ciVisibilityBuildInstrumentationEnabled;
  }

  public String getCiVisibilityAgentJarUri() {
    return ciVisibilityAgentJarUri;
  }

  public File getCiVisibilityAgentJarFile() {
    if (ciVisibilityAgentJarUri == null || ciVisibilityAgentJarUri.isEmpty()) {
      throw new IllegalArgumentException("Agent JAR URI is not set in config");
    }

    try {
      URI agentJarUri = new URI(ciVisibilityAgentJarUri);
      return new File(agentJarUri);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Malformed agent JAR URI: " + ciVisibilityAgentJarUri, e);
    }
  }

  public boolean isCiVisibilityAutoConfigurationEnabled() {
    return ciVisibilityAutoConfigurationEnabled;
  }

  public String getCiVisibilityAdditionalChildProcessJvmArgs() {
    return ciVisibilityAdditionalChildProcessJvmArgs;
  }

  public boolean isCiVisibilityCompilerPluginAutoConfigurationEnabled() {
    return ciVisibilityCompilerPluginAutoConfigurationEnabled;
  }

  public boolean isCiVisibilityCodeCoverageEnabled() {
    return ciVisibilityCodeCoverageEnabled;
  }

  /** @return {@code true} if code coverage line-granularity is explicitly enabled */
  public boolean isCiVisibilityCoverageLinesEnabled() {
    return ciVisibilityCoverageLinesEnabled != null && ciVisibilityCoverageLinesEnabled;
  }

  /** @return {@code true} if code coverage line-granularity is explicitly disabled */
  public boolean isCiVisibilityCoverageLinesDisabled() {
    return ciVisibilityCoverageLinesEnabled != null && !ciVisibilityCoverageLinesEnabled;
  }

  public String getCiVisibilityCodeCoverageReportDumpDir() {
    return ciVisibilityCodeCoverageReportDumpDir;
  }

  public String getCiVisibilityCompilerPluginVersion() {
    return ciVisibilityCompilerPluginVersion;
  }

  public String getCiVisibilityJacocoPluginVersion() {
    return ciVisibilityJacocoPluginVersion;
  }

  public boolean isCiVisibilityJacocoPluginVersionProvided() {
    return ciVisibilityJacocoPluginVersionProvided;
  }

  public List<String> getCiVisibilityCodeCoverageIncludes() {
    return ciVisibilityCodeCoverageIncludes;
  }

  public List<String> getCiVisibilityCodeCoverageExcludes() {
    return ciVisibilityCodeCoverageExcludes;
  }

  public String[] getCiVisibilityCodeCoverageIncludedPackages() {
    return Arrays.copyOf(
        ciVisibilityCodeCoverageIncludedPackages, ciVisibilityCodeCoverageIncludedPackages.length);
  }

  public String[] getCiVisibilityCodeCoverageExcludedPackages() {
    return Arrays.copyOf(
        ciVisibilityCodeCoverageExcludedPackages, ciVisibilityCodeCoverageExcludedPackages.length);
  }

  public List<String> getCiVisibilityJacocoGradleSourceSets() {
    return ciVisibilityJacocoGradleSourceSets;
  }

  public Integer getCiVisibilityDebugPort() {
    return ciVisibilityDebugPort;
  }

  public boolean isCiVisibilityGitUploadEnabled() {
    return ciVisibilityGitUploadEnabled;
  }

  public boolean isCiVisibilityGitUnshallowEnabled() {
    return ciVisibilityGitUnshallowEnabled;
  }

  public boolean isCiVisibilityGitUnshallowDefer() {
    return ciVisibilityGitUnshallowDefer;
  }

  public long getCiVisibilityGitCommandTimeoutMillis() {
    return ciVisibilityGitCommandTimeoutMillis;
  }

  public long getCiVisibilityBackendApiTimeoutMillis() {
    return ciVisibilityBackendApiTimeoutMillis;
  }

  public long getCiVisibilityGitUploadTimeoutMillis() {
    return ciVisibilityGitUploadTimeoutMillis;
  }

  public String getCiVisibilityGitRemoteName() {
    return ciVisibilityGitRemoteName;
  }

  public int getCiVisibilitySignalServerPort() {
    return ciVisibilitySignalServerPort;
  }

  public int getCiVisibilitySignalClientTimeoutMillis() {
    return ciVisibilitySignalClientTimeoutMillis;
  }

  public String getCiVisibilitySignalServerHost() {
    return ciVisibilitySignalServerHost;
  }

  public boolean isCiVisibilityItrEnabled() {
    return ciVisibilityItrEnabled;
  }

  public boolean isCiVisibilityTestSkippingEnabled() {
    return ciVisibilityTestSkippingEnabled;
  }

  public boolean isCiVisibilityCiProviderIntegrationEnabled() {
    return ciVisibilityCiProviderIntegrationEnabled;
  }

  public boolean isCiVisibilityRepoIndexDuplicateKeyCheckEnabled() {
    return ciVisibilityRepoIndexDuplicateKeyCheckEnabled;
  }

  public int getCiVisibilityExecutionSettingsCacheSize() {
    return ciVisibilityExecutionSettingsCacheSize;
  }

  public int getCiVisibilityJvmInfoCacheSize() {
    return ciVisibilityJvmInfoCacheSize;
  }

  public int getCiVisibilityCoverageRootPackagesLimit() {
    return ciVisibilityCoverageRootPackagesLimit;
  }

  public String getCiVisibilityInjectedTracerVersion() {
    return ciVisibilityInjectedTracerVersion;
  }

  public List<String> getCiVisibilityResourceFolderNames() {
    return ciVisibilityResourceFolderNames;
  }

  public boolean isCiVisibilityFlakyRetryEnabled() {
    return ciVisibilityFlakyRetryEnabled;
  }

  public boolean isCiVisibilityFlakyRetryOnlyKnownFlakes() {
    return ciVisibilityFlakyRetryOnlyKnownFlakes;
  }

  public boolean isCiVisibilityEarlyFlakeDetectionEnabled() {
    return ciVisibilityEarlyFlakeDetectionEnabled;
  }

  public int getCiVisibilityEarlyFlakeDetectionLowerLimit() {
    return ciVisibilityEarlyFlakeDetectionLowerLimit;
  }

  public boolean isCiVisibilityTestRetryEnabled() {
    return ciVisibilityFlakyRetryEnabled || ciVisibilityEarlyFlakeDetectionEnabled;
  }

  public int getCiVisibilityFlakyRetryCount() {
    return ciVisibilityFlakyRetryCount;
  }

  public int getCiVisibilityTotalFlakyRetryCount() {
    return ciVisibilityTotalFlakyRetryCount;
  }

  public String getCiVisibilitySessionName() {
    return ciVisibilitySessionName;
  }

  public String getCiVisibilityModuleName() {
    return ciVisibilityModuleName;
  }

  public String getCiVisibilityTestCommand() {
    return ciVisibilityTestCommand;
  }

  public boolean isCiVisibilityTelemetryEnabled() {
    return ciVisibilityTelemetryEnabled;
  }

  public long getCiVisibilityRumFlushWaitMillis() {
    return ciVisibilityRumFlushWaitMillis;
  }

  public boolean isCiVisibilityAutoInjected() {
    return ciVisibilityAutoInjected;
  }

  public String getCiVisibilityRemoteEnvVarsProviderUrl() {
    return ciVisibilityRemoteEnvVarsProviderUrl;
  }

  public String getCiVisibilityRemoteEnvVarsProviderKey() {
    return ciVisibilityRemoteEnvVarsProviderKey;
  }

  public String getCiVisibilityTestOrder() {
    return ciVisibilityTestOrder;
  }

  public String getAppSecRulesFile() {
    return appSecRulesFile;
  }

  public long getRemoteConfigMaxPayloadSizeBytes() {
    return remoteConfigMaxPayloadSize;
  }

  public boolean isRemoteConfigEnabled() {
    return remoteConfigEnabled;
  }

  public boolean isRemoteConfigIntegrityCheckEnabled() {
    return remoteConfigIntegrityCheckEnabled;
  }

  public String getFinalRemoteConfigUrl() {
    return remoteConfigUrl;
  }

  public float getRemoteConfigPollIntervalSeconds() {
    return remoteConfigPollIntervalSeconds;
  }

  public String getRemoteConfigTargetsKeyId() {
    return remoteConfigTargetsKeyId;
  }

  public String getRemoteConfigTargetsKey() {
    return remoteConfigTargetsKey;
  }

  public int getRemoteConfigMaxExtraServices() {
    return remoteConfigMaxExtraServices;
  }

  public boolean isDebuggerEnabled() {
    return debuggerEnabled;
  }

  public int getDebuggerUploadTimeout() {
    return debuggerUploadTimeout;
  }

  public int getDebuggerUploadFlushInterval() {
    return debuggerUploadFlushInterval;
  }

  public boolean isDebuggerClassFileDumpEnabled() {
    return debuggerClassFileDumpEnabled;
  }

  public int getDebuggerPollInterval() {
    return debuggerPollInterval;
  }

  public int getDebuggerDiagnosticsInterval() {
    return debuggerDiagnosticsInterval;
  }

  public boolean isDebuggerMetricsEnabled() {
    return debuggerMetricEnabled;
  }

  public int getDebuggerUploadBatchSize() {
    return debuggerUploadBatchSize;
  }

  public long getDebuggerMaxPayloadSize() {
    return debuggerMaxPayloadSize;
  }

  public boolean isDebuggerVerifyByteCode() {
    return debuggerVerifyByteCode;
  }

  public boolean isDebuggerInstrumentTheWorld() {
    return debuggerInstrumentTheWorld;
  }

  public String getDebuggerExcludeFiles() {
    return debuggerExcludeFiles;
  }

  public String getDebuggerIncludeFiles() {
    return debuggerIncludeFiles;
  }

  public int getDebuggerCaptureTimeout() {
    return debuggerCaptureTimeout;
  }

  public boolean isDebuggerSymbolEnabled() {
    return debuggerSymbolEnabled;
  }

  public boolean isDebuggerSymbolForceUpload() {
    return debuggerSymbolForceUpload;
  }

  public int getDebuggerSymbolFlushThreshold() {
    return debuggerSymbolFlushThreshold;
  }

  public boolean isDebuggerSymbolCompressed() {
    return debuggerSymbolCompressed;
  }

  public boolean isDebuggerExceptionEnabled() {
    return debuggerExceptionEnabled;
  }

  public int getDebuggerMaxExceptionPerSecond() {
    return debuggerMaxExceptionPerSecond;
  }

  public boolean isDebuggerExceptionOnlyLocalRoot() {
    return debuggerExceptionOnlyLocalRoot;
  }

  public boolean isDebuggerExceptionCaptureIntermediateSpansEnabled() {
    return debuggerExceptionCaptureIntermediateSpansEnabled;
  }

  public int getDebuggerExceptionMaxCapturedFrames() {
    return debuggerExceptionMaxCapturedFrames;
  }

  public int getDebuggerExceptionCaptureInterval() {
    return debuggerExceptionCaptureInterval;
  }

  public boolean isDebuggerCodeOriginEnabled() {
    return debuggerCodeOriginEnabled;
  }

  public int getDebuggerCodeOriginMaxUserFrames() {
    return debuggerCodeOriginMaxUserFrames;
  }

  public Set<String> getThirdPartyIncludes() {
    return debuggerThirdPartyIncludes;
  }

  public Set<String> getThirdPartyExcludes() {
    return debuggerThirdPartyExcludes;
  }

  private String getFinalDebuggerBaseUrl() {
    if (agentUrl.startsWith("unix:")) {
      // provide placeholder agent URL, in practice we'll be tunnelling over UDS
      return "http://" + agentHost + ":" + agentPort;
    } else {
      return agentUrl;
    }
  }

  public String getFinalDebuggerSnapshotUrl() {
    return getFinalDebuggerBaseUrl() + "/debugger/v1/input";
  }

  public String getFinalDebuggerSymDBUrl() {
    return getFinalDebuggerBaseUrl() + "/symdb/v1/input";
  }

  public String getDebuggerProbeFileLocation() {
    return debuggerProbeFileLocation;
  }

  public String getDebuggerRedactedIdentifiers() {
    return debuggerRedactedIdentifiers;
  }

  public Set<String> getDebuggerRedactionExcludedIdentifiers() {
    return debuggerRedactionExcludedIdentifiers;
  }

  public String getDebuggerRedactedTypes() {
    return debuggerRedactedTypes;
  }

  public boolean isAwsPropagationEnabled() {
    return awsPropagationEnabled;
  }

  public boolean isSqsPropagationEnabled() {
    return sqsPropagationEnabled;
  }

  public boolean isSqsBodyPropagationEnabled() {
    return sqsBodyPropagationEnabled;
  }

  public boolean isKafkaClientPropagationEnabled() {
    return kafkaClientPropagationEnabled;
  }

  public boolean isKafkaClientPropagationDisabledForTopic(String topic) {
    return null != topic && kafkaClientPropagationDisabledTopics.contains(topic);
  }

  public boolean isJmsPropagationEnabled() {
    return jmsPropagationEnabled;
  }

  public boolean isJmsPropagationDisabledForDestination(final String queueOrTopic) {
    return null != queueOrTopic
        && (jmsPropagationDisabledQueues.contains(queueOrTopic)
            || jmsPropagationDisabledTopics.contains(queueOrTopic));
  }

  public int getJmsUnacknowledgedMaxAge() {
    return jmsUnacknowledgedMaxAge;
  }

  public boolean isKafkaClientBase64DecodingEnabled() {
    return kafkaClientBase64DecodingEnabled;
  }

  public boolean isRabbitPropagationEnabled() {
    return rabbitPropagationEnabled;
  }

  public boolean isRabbitPropagationDisabledForDestination(final String queueOrExchange) {
    return null != queueOrExchange
        && (rabbitPropagationDisabledQueues.contains(queueOrExchange)
            || rabbitPropagationDisabledExchanges.contains(queueOrExchange));
  }

  public boolean isRabbitIncludeRoutingKeyInResource() {
    return rabbitIncludeRoutingKeyInResource;
  }

  public boolean isMessageBrokerSplitByDestination() {
    return messageBrokerSplitByDestination;
  }

  public boolean isHystrixTagsEnabled() {
    return hystrixTagsEnabled;
  }

  public boolean isHystrixMeasuredEnabled() {
    return hystrixMeasuredEnabled;
  }

  public boolean isIgniteCacheIncludeKeys() {
    return igniteCacheIncludeKeys;
  }

  public String getObfuscationQueryRegexp() {
    return obfuscationQueryRegexp;
  }

  public boolean getPlayReportHttpStatus() {
    return playReportHttpStatus;
  }

  public boolean isServletPrincipalEnabled() {
    return servletPrincipalEnabled;
  }

  public boolean isSpringDataRepositoryInterfaceResourceName() {
    return springDataRepositoryInterfaceResourceName;
  }

  public int getxDatadogTagsMaxLength() {
    return xDatadogTagsMaxLength;
  }

  public boolean isServletAsyncTimeoutError() {
    return servletAsyncTimeoutError;
  }

  public boolean isTraceAgentV05Enabled() {
    return traceAgentV05Enabled;
  }

  public String getLogLevel() {
    return logLevel;
  }

  public boolean isDebugEnabled() {
    return debugEnabled;
  }

  public boolean isTriageEnabled() {
    return triageEnabled;
  }

  public String getTriageReportTrigger() {
    return triageReportTrigger;
  }

  public String getTriageReportDir() {
    return triageReportDir;
  }

  public boolean isStartupLogsEnabled() {
    return startupLogsEnabled;
  }

  public boolean isCwsEnabled() {
    return cwsEnabled;
  }

  public int getCwsTlsRefresh() {
    return cwsTlsRefresh;
  }

  public boolean isAzureAppServices() {
    return azureAppServices;
  }

  public boolean isDataStreamsEnabled() {
    return dataStreamsEnabled;
  }

  public float getDataStreamsBucketDurationSeconds() {
    return dataStreamsBucketDurationSeconds;
  }

  public long getDataStreamsBucketDurationNanoseconds() {
    // Rounds to the nearest millisecond before converting to nanos
    int milliseconds = Math.round(dataStreamsBucketDurationSeconds * 1000);
    return TimeUnit.MILLISECONDS.toNanos(milliseconds);
  }

  public String getTraceAgentPath() {
    return traceAgentPath;
  }

  public List<String> getTraceAgentArgs() {
    return traceAgentArgs;
  }

  public String getDogStatsDPath() {
    return dogStatsDPath;
  }

  public List<String> getDogStatsDArgs() {
    return dogStatsDArgs;
  }

  public String getConfigFileStatus() {
    return configFileStatus;
  }

  public IdGenerationStrategy getIdGenerationStrategy() {
    return idGenerationStrategy;
  }

  public boolean isTrace128bitTraceIdGenerationEnabled() {
    return trace128bitTraceIdGenerationEnabled;
  }

  public Set<String> getGrpcIgnoredInboundMethods() {
    return grpcIgnoredInboundMethods;
  }

  public Set<String> getGrpcIgnoredOutboundMethods() {
    return grpcIgnoredOutboundMethods;
  }

  public boolean isGrpcServerTrimPackageResource() {
    return grpcServerTrimPackageResource;
  }

  public BitSet getGrpcServerErrorStatuses() {
    return grpcServerErrorStatuses;
  }

  public BitSet getGrpcClientErrorStatuses() {
    return grpcClientErrorStatuses;
  }

  public boolean isCouchbaseInternalSpansEnabled() {
    return couchbaseInternalSpansEnabled;
  }

  public boolean isElasticsearchBodyEnabled() {
    return elasticsearchBodyEnabled;
  }

  public boolean isElasticsearchParamsEnabled() {
    return elasticsearchParamsEnabled;
  }

  public boolean isElasticsearchBodyAndParamsEnabled() {
    return elasticsearchBodyAndParamsEnabled;
  }

  public boolean isSparkTaskHistogramEnabled() {
    return sparkTaskHistogramEnabled;
  }

  public boolean useSparkAppNameAsService() {
    return sparkAppNameAsService;
  }

  public boolean isJaxRsExceptionAsErrorEnabled() {
    return jaxRsExceptionAsErrorsEnabled;
  }

  public boolean isAxisPromoteResourceName() {
    return axisPromoteResourceName;
  }

  public boolean isDataJobsEnabled() {
    return dataJobsEnabled;
  }

  public String getDataJobsCommandPattern() {
    return dataJobsCommandPattern;
  }

  /** @return A map of tags to be applied only to the local application root span. */
  public Map<String, Object> getLocalRootSpanTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final Map<String, Object> result = new HashMap<>(runtimeTags.size() + 2);
    result.putAll(runtimeTags);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    result.put(SCHEMA_VERSION_TAG_KEY, SpanNaming.instance().version());
    result.put(PROFILING_ENABLED, isProfilingEnabled() ? 1 : 0);
    if (isAppSecStandaloneEnabled()) {
      result.put(APM_ENABLED, 0);
    }

    if (reportHostName) {
      final String hostName = getHostName();
      if (null != hostName && !hostName.isEmpty()) {
        result.put(INTERNAL_HOST_NAME, hostName);
      }
    }

    if (azureAppServices) {
      result.putAll(getAzureAppServicesTags());
    }

    result.putAll(getProcessIdTag());

    return Collections.unmodifiableMap(result);
  }

  public WellKnownTags getWellKnownTags() {
    return new WellKnownTags(
        getRuntimeId(),
        reportHostName ? getHostName() : "",
        getEnv(),
        serviceName,
        getVersion(),
        LANGUAGE_TAG_VALUE);
  }

  public CiVisibilityWellKnownTags getCiVisibilityWellKnownTags() {
    return new CiVisibilityWellKnownTags(
        getRuntimeId(),
        getEnv(),
        LANGUAGE_TAG_VALUE,
        System.getProperty("java.runtime.name"),
        System.getProperty("java.version"),
        System.getProperty("java.vendor"),
        System.getProperty("os.arch"),
        System.getProperty("os.name"),
        System.getProperty("os.version"));
  }

  public String getPrimaryTag() {
    return primaryTag;
  }

  public Set<String> getMetricsIgnoredResources() {
    return tryMakeImmutableSet(configProvider.getList(TRACER_METRICS_IGNORED_RESOURCES));
  }

  public String getEnv() {
    // intentionally not thread safe
    if (env == null) {
      env = getMergedSpanTags().get("env");
      if (env == null) {
        env = "";
      }
    }

    return env;
  }

  public String getVersion() {
    // intentionally not thread safe
    if (version == null) {
      version = getMergedSpanTags().get("version");
      if (version == null) {
        version = "";
      }
    }

    return version;
  }

  public Map<String, String> getMergedSpanTags() {
    // Do not include runtimeId into span tags: we only want that added to the root span
    final Map<String, String> result = newHashMap(getGlobalTags().size() + spanTags.size());
    result.putAll(getGlobalTags());
    result.putAll(spanTags);
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedJmxTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size() + jmxTags.size() + runtimeTags.size() + 1 /* for serviceName */);
    result.putAll(getGlobalTags());
    result.putAll(jmxTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    // Additionally, infra/JMX metrics require `service` rather than APM's `service.name` tag
    result.put(SERVICE_TAG, serviceName);
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedProfilingTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final String host = getHostName();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size()
                + profilingTags.size()
                + runtimeTags.size()
                + 4 /* for serviceName and host and language and runtime_version */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(getGlobalTags());
    result.putAll(profilingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    result.put(SERVICE_TAG, serviceName);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    result.put(RUNTIME_VERSION_TAG, runtimeVersion);
    if (azureAppServices) {
      result.putAll(getAzureAppServicesTags());
    }
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedCrashTrackingTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final String host = getHostName();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size()
                + crashTrackingTags.size()
                + runtimeTags.size()
                + 3 /* for serviceName and host and language */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(getGlobalTags());
    result.putAll(crashTrackingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    result.put(SERVICE_TAG, serviceName);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    return Collections.unmodifiableMap(result);
  }

  /**
   * Returns the sample rate for the specified instrumentation or {@link
   * ConfigDefaults#DEFAULT_ANALYTICS_SAMPLE_RATE} if none specified.
   */
  public float getInstrumentationAnalyticsSampleRate(final String... aliases) {
    for (final String alias : aliases) {
      final String configKey = alias + ".analytics.sample-rate";
      final Float rate = configProvider.getFloat("trace." + configKey, configKey);
      if (null != rate) {
        return rate;
      }
    }
    return DEFAULT_ANALYTICS_SAMPLE_RATE;
  }

  /**
   * Provide 'global' tags, i.e. tags set everywhere. We have to support old (dd.trace.global.tags)
   * version of this setting if new (dd.tags) version has not been specified.
   */
  public Map<String, String> getGlobalTags() {
    return tags;
  }

  /**
   * Return a map of tags required by the datadog backend to link runtime metrics (i.e. jmx) and
   * traces.
   *
   * <p>These tags must be applied to every runtime metrics and placed on the root span of every
   * trace.
   *
   * @return A map of tag-name -> tag-value
   */
  private Map<String, String> getRuntimeTags() {
    return Collections.singletonMap(RUNTIME_ID_TAG, getRuntimeId());
  }

  private Map<String, Long> getProcessIdTag() {
    return Collections.singletonMap(PID_TAG, getProcessId());
  }

  private Map<String, String> getAzureAppServicesTags() {
    // These variable names and derivations are copied from the dotnet tracer
    // See
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/PlatformHelpers/AzureAppServices.cs
    // and
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/TraceContext.cs#L207
    Map<String, String> aasTags = new HashMap<>();

    /// The site name of the site instance in Azure where the traced application is running.
    String siteName = getEnv("WEBSITE_SITE_NAME");
    if (siteName != null) {
      aasTags.put("aas.site.name", siteName);
    }

    // The kind of application instance running in Azure.
    // Possible values: app, api, mobileapp, app_linux, app_linux_container, functionapp,
    // functionapp_linux, functionapp_linux_container

    // The type of application instance running in Azure.
    // Possible values: app, function
    if (getEnv("FUNCTIONS_WORKER_RUNTIME") != null
        || getEnv("FUNCTIONS_EXTENSIONS_VERSION") != null) {
      aasTags.put("aas.site.kind", "functionapp");
      aasTags.put("aas.site.type", "function");
    } else {
      aasTags.put("aas.site.kind", "app");
      aasTags.put("aas.site.type", "app");
    }

    //  The resource group of the site instance in Azure App Services
    String resourceGroup = getEnv("WEBSITE_RESOURCE_GROUP");
    if (resourceGroup != null) {
      aasTags.put("aas.resource.group", resourceGroup);
    }

    // Example: 8c500027-5f00-400e-8f00-60000000000f+apm-dotnet-EastUSwebspace
    // Format: {subscriptionId}+{planResourceGroup}-{hostedInRegion}
    String websiteOwner = getEnv("WEBSITE_OWNER_NAME");
    int plusIndex = websiteOwner == null ? -1 : websiteOwner.indexOf("+");

    // The subscription ID of the site instance in Azure App Services
    String subscriptionId = null;
    if (plusIndex > 0) {
      subscriptionId = websiteOwner.substring(0, plusIndex);
      aasTags.put("aas.subscription.id", subscriptionId);
    }

    if (subscriptionId != null && siteName != null && resourceGroup != null) {
      // The resource ID of the site instance in Azure App Services
      String resourceId =
          "/subscriptions/"
              + subscriptionId
              + "/resourcegroups/"
              + resourceGroup
              + "/providers/microsoft.web/sites/"
              + siteName;
      resourceId = resourceId.toLowerCase(Locale.ROOT);
      aasTags.put("aas.resource.id", resourceId);
    } else {
      log.warn(
          "Unable to generate resource id subscription id: {}, site name: {}, resource group {}",
          subscriptionId,
          siteName,
          resourceGroup);
    }

    // The instance ID in Azure
    String instanceId = getEnv("WEBSITE_INSTANCE_ID");
    instanceId = instanceId == null ? "unknown" : instanceId;
    aasTags.put("aas.environment.instance_id", instanceId);

    // The instance name in Azure
    String instanceName = getEnv("COMPUTERNAME");
    instanceName = instanceName == null ? "unknown" : instanceName;
    aasTags.put("aas.environment.instance_name", instanceName);

    // The operating system in Azure
    String operatingSystem = getEnv("WEBSITE_OS");
    operatingSystem = operatingSystem == null ? "unknown" : operatingSystem;
    aasTags.put("aas.environment.os", operatingSystem);

    // The version of the extension installed
    String siteExtensionVersion = getEnv("DD_AAS_JAVA_EXTENSION_VERSION");
    siteExtensionVersion = siteExtensionVersion == null ? "unknown" : siteExtensionVersion;
    aasTags.put("aas.environment.extension_version", siteExtensionVersion);

    aasTags.put("aas.environment.runtime", getProp("java.vm.name", "unknown"));

    return aasTags;
  }

  private int schemaVersionFromConfig() {
    String versionStr =
        configProvider.getString(TRACE_SPAN_ATTRIBUTE_SCHEMA, "v" + SpanNaming.SCHEMA_MIN_VERSION);
    Matcher matcher = Pattern.compile("^v?(0|[1-9]\\d*)$").matcher(versionStr);
    int parsedVersion = -1;
    if (matcher.matches()) {
      parsedVersion = Integer.parseInt(matcher.group(1));
    }
    if (parsedVersion < SpanNaming.SCHEMA_MIN_VERSION
        || parsedVersion > SpanNaming.SCHEMA_MAX_VERSION) {
      log.warn(
          "Invalid attribute schema version {} invalid or out of range [v{}, v{}]. Defaulting to v{}",
          versionStr,
          SpanNaming.SCHEMA_MIN_VERSION,
          SpanNaming.SCHEMA_MAX_VERSION,
          SpanNaming.SCHEMA_MIN_VERSION);
      parsedVersion = SpanNaming.SCHEMA_MIN_VERSION;
    }
    return parsedVersion;
  }

  public String getFinalProfilingUrl() {
    if (profilingUrl != null) {
      // when profilingUrl is set we use it regardless of apiKey/agentless config
      return profilingUrl;
    } else if (profilingAgentless) {
      // when agentless profiling is turned on we send directly to our intake
      return "https://intake.profile." + site + "/api/v2/profile";
    } else {
      // when profilingUrl and agentless are not set we send to the dd trace agent running locally
      return "http://" + agentHost + ":" + agentPort + "/profiling/v1/input";
    }
  }

  public String getFinalCrashTrackingTelemetryUrl() {
    if (crashTrackingAgentless) {
      // when agentless crashTracking is turned on we send directly to our intake
      return "https://all-http-intake.logs." + site + "/api/v2/apmtelemetry";
    } else {
      // when agentless are not set we send to the dd trace agent running locally
      return "http://" + agentHost + ":" + agentPort + "/telemetry/proxy/api/v2/apmtelemetry";
    }
  }

  public boolean isJmxFetchIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return configProvider.isEnabled(integrationNames, "jmxfetch.", ".enabled", defaultEnabled);
  }

  public boolean isRuleEnabled(final String name) {
    return isRuleEnabled(name, true);
  }

  public boolean isRuleEnabled(final String name, boolean defaultEnabled) {
    boolean enabled = configProvider.getBoolean("trace." + name + ".enabled", defaultEnabled);
    boolean lowerEnabled =
        configProvider.getBoolean(
            "trace." + name.toLowerCase(Locale.ROOT) + ".enabled", defaultEnabled);
    return defaultEnabled ? enabled && lowerEnabled : enabled || lowerEnabled;
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isJmxFetchIntegrationEnabled(Iterable, boolean)}.
   */
  public static boolean jmxFetchIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return Config.get().isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isEndToEndDurationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".e2e.duration.enabled", defaultEnabled);
  }

  public boolean isPropagationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".propagation.enabled", defaultEnabled);
  }

  public boolean isLegacyTracingEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".legacy.tracing.enabled", defaultEnabled);
  }

  public boolean isSqsLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "sqs");
  }

  public boolean isAwsLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(false, "aws-sdk");
  }

  public boolean isJmsLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "jms");
  }

  public boolean isKafkaLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "kafka");
  }

  public boolean isGooglePubSubLegacyTracingEnabled() {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && isLegacyTracingEnabled(true, "google-pubsub");
  }

  public boolean isTimeInQueueEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return SpanNaming.instance().namingSchema().allowInferredServices()
        && configProvider.isEnabled(
            Arrays.asList(integrationNames), "", ".time-in-queue.enabled", defaultEnabled);
  }

  public boolean isEnabled(
      final boolean defaultEnabled, final String settingName, String settingSuffix) {
    return configProvider.isEnabled(
        Collections.singletonList(settingName), "", settingSuffix, defaultEnabled);
  }

  public boolean isDBMTracePreparedStatements() {
    return DBMTracePreparedStatements;
  }

  public String getDBMPropagationMode() {
    return DBMPropagationMode;
  }

  private void logIgnoredSettingWarning(
      String setting, String overridingSetting, String overridingSuffix) {
    log.warn(
        "Setting {} ignored since {}{} is enabled.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        overridingSuffix);
  }

  private void logOverriddenSettingWarning(String setting, String overridingSetting, Object value) {
    log.warn(
        "Setting {} is overridden by setting {} with value {}.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        value);
  }

  private void logOverriddenDeprecatedSettingWarning(
      String setting, String overridingSetting, Object value) {
    log.warn(
        "Setting {} is deprecated and overridden by setting {} with value {}.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        value);
  }

  private void logDeprecatedConvertedSetting(
      String deprecatedSetting, Object oldValue, String newSetting, Object newValue) {
    log.warn(
        "Setting {} is deprecated and the value {} has been converted to {} for setting {}.",
        propertyNameToSystemPropertyName(deprecatedSetting),
        oldValue,
        newValue,
        propertyNameToSystemPropertyName(newSetting));
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return configProvider.isEnabled(integrationNames, "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isSamplingMechanismValidationDisabled() {
    return configProvider.getBoolean(TracerConfig.SAMPLING_MECHANISM_VALIDATION_DISABLED, false);
  }

  public <T extends Enum<T>> T getEnumValue(
      final String name, final Class<T> type, final T defaultValue) {
    return configProvider.getEnum(name, type, defaultValue);
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isTraceAnalyticsIntegrationEnabled(SortedSet, boolean)}.
   */
  public static boolean traceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return Config.get().isTraceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isTelemetryDebugRequestsEnabled() {
    return telemetryDebugRequestsEnabled;
  }

  public boolean isAgentlessLogSubmissionEnabled() {
    return agentlessLogSubmissionEnabled;
  }

  public int getAgentlessLogSubmissionQueueSize() {
    return agentlessLogSubmissionQueueSize;
  }

  public String getAgentlessLogSubmissionLevel() {
    return agentlessLogSubmissionLevel;
  }

  public String getAgentlessLogSubmissionUrl() {
    return agentlessLogSubmissionUrl;
  }

  public String getAgentlessLogSubmissionProduct() {
    return agentlessLogSubmissionProduct;
  }

  public Boolean getAppSecScaEnabled() {
    return appSecScaEnabled;
  }

  public boolean isAppSecStandaloneEnabled() {
    return appSecStandaloneEnabled;
  }

  public boolean isAppSecRaspEnabled() {
    return appSecRaspEnabled;
  }

  public boolean isAppSecStackTraceEnabled() {
    return appSecStackTraceEnabled;
  }

  public int getAppSecMaxStackTraces() {
    return appSecMaxStackTraces;
  }

  public int getAppSecMaxStackTraceDepth() {
    return appSecMaxStackTraceDepth;
  }

  public boolean isCloudPayloadTaggingEnabledFor(String serviceName) {
    return cloudPayloadTaggingServices.contains(serviceName);
  }

  public boolean isCloudPayloadTaggingEnabled() {
    return isCloudRequestPayloadTaggingEnabled() || isCloudResponsePayloadTaggingEnabled();
  }

  public List<String> getCloudRequestPayloadTagging() {
    return cloudRequestPayloadTagging == null
        ? Collections.emptyList()
        : cloudRequestPayloadTagging;
  }

  public boolean isCloudRequestPayloadTaggingEnabled() {
    return cloudRequestPayloadTagging != null;
  }

  public List<String> getCloudResponsePayloadTagging() {
    return cloudResponsePayloadTagging == null
        ? Collections.emptyList()
        : cloudResponsePayloadTagging;
  }

  public boolean isCloudResponsePayloadTaggingEnabled() {
    return cloudResponsePayloadTagging != null;
  }

  public int getCloudPayloadTaggingMaxDepth() {
    return cloudPayloadTaggingMaxDepth;
  }

  public int getCloudPayloadTaggingMaxTags() {
    return cloudPayloadTaggingMaxTags;
  }

  private <T> Set<T> getSettingsSetFromEnvironment(
      String name, Function<String, T> mapper, boolean splitOnWS) {
    final String value = configProvider.getString(name, "");
    return convertStringSetToSet(
        name, parseStringIntoSetOfNonEmptyStrings(value, splitOnWS), mapper);
  }

  private <F, T> Set<T> convertSettingsSet(Set<F> fromSet, Function<F, Iterable<T>> mapper) {
    if (fromSet.isEmpty()) {
      return Collections.emptySet();
    }
    Set<T> result = new LinkedHashSet<>(fromSet.size());
    for (F from : fromSet) {
      for (T to : mapper.apply(from)) {
        result.add(to);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  public static final String PREFIX = "dd.";

  /**
   * Converts the property name, e.g. 'service.name' into a public system property name, e.g.
   * `dd.service.name`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing system property name
   */
  @Nonnull
  private static String propertyNameToSystemPropertyName(final String setting) {
    return PREFIX + setting;
  }

  @Nonnull
  private static Map<String, String> newHashMap(final int size) {
    return new HashMap<>(size + 1, 1f);
  }

  /**
   * @param map
   * @param propNames
   * @return new unmodifiable copy of {@param map} where properties are overwritten from environment
   */
  @Nonnull
  private Map<String, String> getMapWithPropertiesDefinedByEnvironment(
      @Nonnull final Map<String, String> map, @Nonnull final String... propNames) {
    final Map<String, String> res = new HashMap<>(map);
    for (final String propName : propNames) {
      final String val = configProvider.getString(propName);
      if (val != null) {
        res.put(propName, val);
      }
    }
    return Collections.unmodifiableMap(res);
  }

  @Nonnull
  private static Set<String> parseStringIntoSetOfNonEmptyStrings(final String str) {
    return parseStringIntoSetOfNonEmptyStrings(str, true);
  }

  @Nonnull
  private static Set<String> parseStringIntoSetOfNonEmptyStrings(
      final String str, boolean splitOnWS) {
    // Using LinkedHashSet to preserve original string order
    final Set<String> result = new LinkedHashSet<>();
    // Java returns single value when splitting an empty string. We do not need that value, so
    // we need to throw it out.
    int start = 0;
    int i = 0;
    for (; i < str.length(); ++i) {
      char c = str.charAt(i);
      if (c == ',' || (splitOnWS && Character.isWhitespace(c))) {
        if (i - start - 1 > 0) {
          result.add(str.substring(start, i));
        }
        start = i + 1;
      }
    }
    if (i - start - 1 > 0) {
      result.add(str.substring(start));
    }
    return Collections.unmodifiableSet(result);
  }

  private static <T> Set<T> convertStringSetToSet(
      String setting, final Set<String> input, Function<String, T> mapper) {
    if (input.isEmpty()) {
      return Collections.emptySet();
    }
    // Using LinkedHashSet to preserve original string order
    final Set<T> result = new LinkedHashSet<>();
    for (final String value : input) {
      try {
        result.add(mapper.apply(value));
      } catch (final IllegalArgumentException e) {
        log.warn(
            "Cannot recognize config string value {} for setting {}",
            value,
            propertyNameToSystemPropertyName(setting));
      }
    }
    return Collections.unmodifiableSet(result);
  }

  /** Returns the detected hostname. First tries locally, then using DNS */
  static String initHostName() {
    String possibleHostname;

    // Try environment variable.  This works in almost all environments
    if (isWindowsOS()) {
      possibleHostname = getEnv("COMPUTERNAME");
    } else {
      possibleHostname = getEnv("HOSTNAME");
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      log.debug("Determined hostname from environment variable");
      return possibleHostname.trim();
    }

    // Try hostname files
    final String[] hostNameFiles = new String[] {"/proc/sys/kernel/hostname", "/etc/hostname"};
    for (final String hostNameFile : hostNameFiles) {
      try {
        final Path hostNamePath = FileSystems.getDefault().getPath(hostNameFile);
        if (Files.isRegularFile(hostNamePath)) {
          byte[] bytes = Files.readAllBytes(hostNamePath);
          possibleHostname = new String(bytes, StandardCharsets.ISO_8859_1);
        }
      } catch (Throwable t) {
        // Ignore
      }
      possibleHostname = Strings.trim(possibleHostname);
      if (!possibleHostname.isEmpty()) {
        log.debug("Determined hostname from file {}", hostNameFile);
        return possibleHostname;
      }
    }

    // Try hostname command
    try (final TraceScope scope = AgentTracer.get().muteTracing();
        final BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(Runtime.getRuntime().exec("hostname").getInputStream()))) {
      possibleHostname = reader.readLine();
    } catch (final Throwable ignore) {
      // Ignore.  Hostname command is not always available
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      log.debug("Determined hostname from hostname command");
      return possibleHostname.trim();
    }

    // From DNS
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (final UnknownHostException e) {
      // If we are not able to detect the hostname we do not throw an exception.
    }

    return null;
  }

  private static boolean isWindowsOS() {
    return getProp("os.name").startsWith("Windows");
  }

  private static String getEnv(String name) {
    String value = System.getenv(name);
    if (value != null) {
      ConfigCollector.get().put(name, value, ConfigOrigin.ENV);
    }
    return value;
  }

  private static Pattern getPattern(String defaultValue, String userValue) {
    try {
      if (userValue != null) {
        return Pattern.compile(userValue);
      }
    } catch (Exception e) {
      log.debug("Cannot create pattern from user value {}", userValue);
    }
    return Pattern.compile(defaultValue);
  }

  private static String getProp(String name) {
    return getProp(name, null);
  }

  private static String getProp(String name, String def) {
    String value = System.getProperty(name, def);
    if (value != null) {
      ConfigCollector.get().put(name, value, ConfigOrigin.JVM_PROP);
    }
    return value;
  }

  // This has to be placed after all other static fields to give them a chance to initialize
  @SuppressFBWarnings("SI_INSTANCE_BEFORE_FINALS_ASSIGNED")
  private static final Config INSTANCE =
      new Config(
          Platform.isNativeImageBuilder()
              ? ConfigProvider.withoutCollector()
              : ConfigProvider.getInstance(),
          InstrumenterConfig.get());

  public static Config get() {
    return INSTANCE;
  }

  /**
   * This method is deprecated since the method of configuration will be changed in the future. The
   * properties instance should instead be passed directly into the DDTracer builder:
   *
   * <pre>
   *   DDTracer.builder().withProperties(new Properties()).build()
   * </pre>
   *
   * <p>Config keys for use in Properties instance construction can be found in {@link
   * GeneralConfig} and {@link TracerConfig}.
   *
   * @deprecated
   */
  @Deprecated
  public static Config get(final Properties properties) {
    if (properties == null || properties.isEmpty()) {
      return INSTANCE;
    } else {
      return new Config(ConfigProvider.withPropertiesOverride(properties));
    }
  }

  @Override
  public String toString() {
    return "Config{"
        + "instrumenterConfig="
        + instrumenterConfig
        + ", runtimeId='"
        + getRuntimeId()
        + '\''
        + ", runtimeVersion='"
        + runtimeVersion
        + ", apiKey="
        + (apiKey == null ? "null" : "****")
        + ", site='"
        + site
        + '\''
        + ", hostName='"
        + getHostName()
        + '\''
        + ", serviceName='"
        + serviceName
        + '\''
        + ", serviceNameSetByUser="
        + serviceNameSetByUser
        + ", rootContextServiceName="
        + rootContextServiceName
        + ", integrationSynapseLegacyOperationName="
        + integrationSynapseLegacyOperationName
        + ", writerType='"
        + writerType
        + '\''
        + ", agentConfiguredUsingDefault="
        + agentConfiguredUsingDefault
        + ", agentUrl='"
        + agentUrl
        + '\''
        + ", agentHost='"
        + agentHost
        + '\''
        + ", agentPort="
        + agentPort
        + ", agentUnixDomainSocket='"
        + agentUnixDomainSocket
        + '\''
        + ", agentTimeout="
        + agentTimeout
        + ", noProxyHosts="
        + noProxyHosts
        + ", prioritySamplingEnabled="
        + prioritySamplingEnabled
        + ", prioritySamplingForce='"
        + prioritySamplingForce
        + '\''
        + ", traceResolverEnabled="
        + traceResolverEnabled
        + ", serviceMapping="
        + serviceMapping
        + ", tags="
        + tags
        + ", spanTags="
        + spanTags
        + ", jmxTags="
        + jmxTags
        + ", requestHeaderTags="
        + requestHeaderTags
        + ", responseHeaderTags="
        + responseHeaderTags
        + ", baggageMapping="
        + baggageMapping
        + ", httpServerErrorStatuses="
        + httpServerErrorStatuses
        + ", httpClientErrorStatuses="
        + httpClientErrorStatuses
        + ", httpServerTagQueryString="
        + httpServerTagQueryString
        + ", httpServerRawQueryString="
        + httpServerRawQueryString
        + ", httpServerRawResource="
        + httpServerRawResource
        + ", httpServerRouteBasedNaming="
        + httpServerRouteBasedNaming
        + ", httpServerPathResourceNameMapping="
        + httpServerPathResourceNameMapping
        + ", httpClientPathResourceNameMapping="
        + httpClientPathResourceNameMapping
        + ", httpClientTagQueryString="
        + httpClientTagQueryString
        + ", httpClientSplitByDomain="
        + httpClientSplitByDomain
        + ", httpResourceRemoveTrailingSlash="
        + httpResourceRemoveTrailingSlash
        + ", dbClientSplitByInstance="
        + dbClientSplitByInstance
        + ", dbClientSplitByInstanceTypeSuffix="
        + dbClientSplitByInstanceTypeSuffix
        + ", dbClientSplitByHost="
        + dbClientSplitByHost
        + ", DBMPropagationMode="
        + DBMPropagationMode
        + ", splitByTags="
        + splitByTags
        + ", jeeSplitByDeployment="
        + jeeSplitByDeployment
        + ", scopeDepthLimit="
        + scopeDepthLimit
        + ", scopeStrictMode="
        + scopeStrictMode
        + ", scopeIterationKeepAlive="
        + scopeIterationKeepAlive
        + ", partialFlushMinSpans="
        + partialFlushMinSpans
        + ", traceKeepLatencyThresholdEnabled="
        + traceKeepLatencyThresholdEnabled
        + ", traceKeepLatencyThreshold="
        + traceKeepLatencyThreshold
        + ", traceStrictWritesEnabled="
        + traceStrictWritesEnabled
        + ", tracePropagationStylesToExtract="
        + tracePropagationStylesToExtract
        + ", tracePropagationStylesToInject="
        + tracePropagationStylesToInject
        + ", tracePropagationExtractFirst="
        + tracePropagationExtractFirst
        + ", clockSyncPeriod="
        + clockSyncPeriod
        + ", jmxFetchEnabled="
        + jmxFetchEnabled
        + ", dogStatsDStartDelay="
        + dogStatsDStartDelay
        + ", jmxFetchConfigDir='"
        + jmxFetchConfigDir
        + '\''
        + ", jmxFetchConfigs="
        + jmxFetchConfigs
        + ", jmxFetchMetricsConfigs="
        + jmxFetchMetricsConfigs
        + ", jmxFetchCheckPeriod="
        + jmxFetchCheckPeriod
        + ", jmxFetchInitialRefreshBeansPeriod="
        + jmxFetchInitialRefreshBeansPeriod
        + ", jmxFetchRefreshBeansPeriod="
        + jmxFetchRefreshBeansPeriod
        + ", jmxFetchStatsdHost='"
        + jmxFetchStatsdHost
        + '\''
        + ", jmxFetchStatsdPort="
        + jmxFetchStatsdPort
        + ", jmxFetchMultipleRuntimeServicesEnabled="
        + jmxFetchMultipleRuntimeServicesEnabled
        + ", jmxFetchMultipleRuntimeServicesLimit="
        + jmxFetchMultipleRuntimeServicesLimit
        + ", healthMetricsEnabled="
        + healthMetricsEnabled
        + ", healthMetricsStatsdHost='"
        + healthMetricsStatsdHost
        + '\''
        + ", healthMetricsStatsdPort="
        + healthMetricsStatsdPort
        + ", perfMetricsEnabled="
        + perfMetricsEnabled
        + ", tracerMetricsEnabled="
        + tracerMetricsEnabled
        + ", tracerMetricsBufferingEnabled="
        + tracerMetricsBufferingEnabled
        + ", tracerMetricsMaxAggregates="
        + tracerMetricsMaxAggregates
        + ", tracerMetricsMaxPending="
        + tracerMetricsMaxPending
        + ", reportHostName="
        + reportHostName
        + ", traceAnalyticsEnabled="
        + traceAnalyticsEnabled
        + ", traceSamplingServiceRules="
        + traceSamplingServiceRules
        + ", traceSamplingOperationRules="
        + traceSamplingOperationRules
        + ", traceSamplingJsonRules="
        + traceSamplingRules
        + ", traceSampleRate="
        + traceSampleRate
        + ", traceRateLimit="
        + traceRateLimit
        + ", spanSamplingRules="
        + spanSamplingRules
        + ", spanSamplingRulesFile="
        + spanSamplingRulesFile
        + ", profilingAgentless="
        + profilingAgentless
        + ", profilingUrl='"
        + profilingUrl
        + '\''
        + ", profilingTags="
        + profilingTags
        + ", profilingStartDelay="
        + profilingStartDelay
        + ", profilingStartForceFirst="
        + profilingStartForceFirst
        + ", profilingUploadPeriod="
        + profilingUploadPeriod
        + ", profilingTemplateOverrideFile='"
        + profilingTemplateOverrideFile
        + '\''
        + ", profilingUploadTimeout="
        + profilingUploadTimeout
        + ", profilingUploadCompression='"
        + profilingUploadCompression
        + '\''
        + ", profilingProxyHost='"
        + profilingProxyHost
        + '\''
        + ", profilingProxyPort="
        + profilingProxyPort
        + ", profilingProxyUsername='"
        + profilingProxyUsername
        + '\''
        + ", profilingProxyPassword="
        + (profilingProxyPassword == null ? "null" : "****")
        + ", profilingExceptionSampleLimit="
        + profilingExceptionSampleLimit
        + ", profilingExceptionHistogramTopItems="
        + profilingExceptionHistogramTopItems
        + ", profilingExceptionHistogramMaxCollectionSize="
        + profilingExceptionHistogramMaxCollectionSize
        + ", profilingExcludeAgentThreads="
        + profilingExcludeAgentThreads
        + ", crashTrackingTags="
        + crashTrackingTags
        + ", crashTrackingAgentless="
        + crashTrackingAgentless
        + ", remoteConfigEnabled="
        + remoteConfigEnabled
        + ", remoteConfigUrl="
        + remoteConfigUrl
        + ", remoteConfigPollIntervalSeconds="
        + remoteConfigPollIntervalSeconds
        + ", remoteConfigMaxPayloadSize="
        + remoteConfigMaxPayloadSize
        + ", remoteConfigIntegrityCheckEnabled="
        + remoteConfigIntegrityCheckEnabled
        + ", debuggerEnabled="
        + debuggerEnabled
        + ", debuggerUploadTimeout="
        + debuggerUploadTimeout
        + ", debuggerUploadFlushInterval="
        + debuggerUploadFlushInterval
        + ", debuggerClassFileDumpEnabled="
        + debuggerClassFileDumpEnabled
        + ", debuggerPollInterval="
        + debuggerPollInterval
        + ", debuggerDiagnosticsInterval="
        + debuggerDiagnosticsInterval
        + ", debuggerMetricEnabled="
        + debuggerMetricEnabled
        + ", debuggerProbeFileLocation="
        + debuggerProbeFileLocation
        + ", debuggerUploadBatchSize="
        + debuggerUploadBatchSize
        + ", debuggerMaxPayloadSize="
        + debuggerMaxPayloadSize
        + ", debuggerVerifyByteCode="
        + debuggerVerifyByteCode
        + ", debuggerInstrumentTheWorld="
        + debuggerInstrumentTheWorld
        + ", debuggerExcludeFiles="
        + debuggerExcludeFiles
        + ", debuggerIncludeFiles="
        + debuggerIncludeFiles
        + ", debuggerCaptureTimeout="
        + debuggerCaptureTimeout
        + ", debuggerRedactIdentifiers="
        + debuggerRedactedIdentifiers
        + ", debuggerRedactTypes="
        + debuggerRedactedTypes
        + ", debuggerSymbolEnabled="
        + debuggerSymbolEnabled
        + ", debuggerSymbolForceUpload="
        + debuggerSymbolForceUpload
        + ", debuggerSymbolFlushThreshold="
        + debuggerSymbolFlushThreshold
        + ", debuggerSymbolIncludes="
        + debuggerSymbolIncludes
        + ", debuggerExceptionEnabled="
        + debuggerExceptionEnabled
        + ", debuggerCodeOriginEnabled="
        + debuggerCodeOriginEnabled
        + ", awsPropagationEnabled="
        + awsPropagationEnabled
        + ", sqsPropagationEnabled="
        + sqsPropagationEnabled
        + ", kafkaClientPropagationEnabled="
        + kafkaClientPropagationEnabled
        + ", kafkaClientPropagationDisabledTopics="
        + kafkaClientPropagationDisabledTopics
        + ", kafkaClientBase64DecodingEnabled="
        + kafkaClientBase64DecodingEnabled
        + ", jmsPropagationEnabled="
        + jmsPropagationEnabled
        + ", jmsPropagationDisabledTopics="
        + jmsPropagationDisabledTopics
        + ", jmsPropagationDisabledQueues="
        + jmsPropagationDisabledQueues
        + ", rabbitPropagationEnabled="
        + rabbitPropagationEnabled
        + ", rabbitPropagationDisabledQueues="
        + rabbitPropagationDisabledQueues
        + ", rabbitPropagationDisabledExchanges="
        + rabbitPropagationDisabledExchanges
        + ", messageBrokerSplitByDestination="
        + messageBrokerSplitByDestination
        + ", hystrixTagsEnabled="
        + hystrixTagsEnabled
        + ", hystrixMeasuredEnabled="
        + hystrixMeasuredEnabled
        + ", igniteCacheIncludeKeys="
        + igniteCacheIncludeKeys
        + ", servletPrincipalEnabled="
        + servletPrincipalEnabled
        + ", servletAsyncTimeoutError="
        + servletAsyncTimeoutError
        + ", datadogTagsLimit="
        + xDatadogTagsMaxLength
        + ", traceAgentV05Enabled="
        + traceAgentV05Enabled
        + ", logLevel="
        + logLevel
        + ", debugEnabled="
        + debugEnabled
        + ", triageEnabled="
        + triageEnabled
        + ", triageReportDir="
        + triageReportDir
        + ", startLogsEnabled="
        + startupLogsEnabled
        + ", configFile='"
        + configFileStatus
        + '\''
        + ", idGenerationStrategy="
        + idGenerationStrategy
        + ", trace128bitTraceIdGenerationEnabled="
        + trace128bitTraceIdGenerationEnabled
        + ", grpcIgnoredInboundMethods="
        + grpcIgnoredInboundMethods
        + ", grpcIgnoredOutboundMethods="
        + grpcIgnoredOutboundMethods
        + ", grpcServerErrorStatuses="
        + grpcServerErrorStatuses
        + ", grpcClientErrorStatuses="
        + grpcClientErrorStatuses
        + ", clientIpEnabled="
        + clientIpEnabled
        + ", appSecReportingInband="
        + appSecReportingInband
        + ", appSecRulesFile='"
        + appSecRulesFile
        + "'"
        + ", appSecHttpBlockedTemplateHtml="
        + appSecHttpBlockedTemplateHtml
        + ", appSecWafTimeout="
        + appSecWafTimeout
        + " us, appSecHttpBlockedTemplateJson="
        + appSecHttpBlockedTemplateJson
        + ", apiSecurityEnabled="
        + apiSecurityEnabled
        + ", apiSecurityRequestSampleRate="
        + apiSecurityRequestSampleRate
        + ", cwsEnabled="
        + cwsEnabled
        + ", cwsTlsRefresh="
        + cwsTlsRefresh
        + ", longRunningTraceEnabled="
        + longRunningTraceEnabled
        + ", longRunningTraceInitialFlushInterval="
        + longRunningTraceInitialFlushInterval
        + ", longRunningTraceFlushInterval="
        + longRunningTraceFlushInterval
        + ", couchbaseInternalSpansEnabled="
        + couchbaseInternalSpansEnabled
        + ", elasticsearchBodyEnabled="
        + elasticsearchBodyEnabled
        + ", elasticsearchParamsEnabled="
        + elasticsearchParamsEnabled
        + ", elasticsearchBodyAndParamsEnabled="
        + elasticsearchBodyAndParamsEnabled
        + ", traceFlushInterval="
        + traceFlushIntervalSeconds
        + ", injectBaggageAsTagsEnabled="
        + injectBaggageAsTagsEnabled
        + ", logsInjectionEnabled="
        + logsInjectionEnabled
        + ", sparkTaskHistogramEnabled="
        + sparkTaskHistogramEnabled
        + ", sparkAppNameAsService="
        + sparkAppNameAsService
        + ", jaxRsExceptionAsErrorsEnabled="
        + jaxRsExceptionAsErrorsEnabled
        + ", axisPromoteResourceName="
        + axisPromoteResourceName
        + ", peerServiceDefaultsEnabled="
        + peerServiceDefaultsEnabled
        + ", peerServiceComponentOverrides="
        + peerServiceComponentOverrides
        + ", removeIntegrationServiceNamesEnabled="
        + removeIntegrationServiceNamesEnabled
        + ", spanAttributeSchemaVersion="
        + spanAttributeSchemaVersion
        + ", telemetryDebugRequestsEnabled="
        + telemetryDebugRequestsEnabled
        + ", telemetryMetricsEnabled="
        + telemetryMetricsEnabled
        + ", appSecScaEnabled="
        + appSecScaEnabled
        + ", appSecRaspEnabled="
        + appSecRaspEnabled
        + ", dataJobsEnabled="
        + dataJobsEnabled
        + ", dataJobsCommandPattern="
        + dataJobsCommandPattern
        + ", appSecStandaloneEnabled="
        + appSecStandaloneEnabled
        + ", cloudRequestPayloadTagging="
        + cloudRequestPayloadTagging
        + ", cloudResponsePayloadTagging="
        + cloudResponsePayloadTagging
        + '}';
  }
}