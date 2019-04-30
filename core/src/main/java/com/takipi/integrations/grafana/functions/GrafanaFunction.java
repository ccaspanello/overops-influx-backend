package com.takipi.integrations.grafana.functions;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.ocpsoft.prettytime.PrettyTime;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.event.Location;
import com.takipi.api.client.data.event.MainEventStats;
import com.takipi.api.client.data.event.Stats;
import com.takipi.api.client.data.metrics.Graph;
import com.takipi.api.client.data.metrics.Graph.GraphPoint;
import com.takipi.api.client.data.metrics.Graph.GraphPointContributor;
import com.takipi.api.client.data.process.Jvm;
import com.takipi.api.client.data.transaction.Transaction;
import com.takipi.api.client.data.transaction.TransactionGraph;
import com.takipi.api.client.data.view.SummarizedView;
import com.takipi.api.client.request.TimeframeRequest;
import com.takipi.api.client.request.ViewTimeframeRequest;
import com.takipi.api.client.request.event.EventsRequest;
import com.takipi.api.client.request.event.EventsSlimVolumeRequest;
import com.takipi.api.client.request.metrics.GraphRequest;
import com.takipi.api.client.request.transaction.TransactionsGraphRequest;
import com.takipi.api.client.request.transaction.TransactionsVolumeRequest;
import com.takipi.api.client.request.view.ViewsRequest;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.event.EventSlimResult;
import com.takipi.api.client.result.event.EventsResult;
import com.takipi.api.client.result.event.EventsSlimVolumeResult;
import com.takipi.api.client.result.metrics.GraphResult;
import com.takipi.api.client.result.process.JvmsResult;
import com.takipi.api.client.result.transaction.TransactionsGraphResult;
import com.takipi.api.client.result.transaction.TransactionsVolumeResult;
import com.takipi.api.client.result.view.ViewsResult;
import com.takipi.api.client.util.infra.Categories;
import com.takipi.api.client.util.infra.Categories.Category;
import com.takipi.api.client.util.infra.Categories.CategoryType;
import com.takipi.api.client.util.infra.InfraUtil;
import com.takipi.api.client.util.performance.PerformanceUtil;
import com.takipi.api.client.util.performance.calc.PerformanceScore;
import com.takipi.api.client.util.performance.calc.PerformanceState;
import com.takipi.api.client.util.performance.transaction.DirectStatsPerformanceCalculator;
import com.takipi.api.client.util.performance.transaction.GraphPerformanceCalculator;
import com.takipi.api.client.util.regression.RegressionInput;
import com.takipi.api.client.util.regression.RegressionUtil.RegressionWindow;
import com.takipi.api.client.util.settings.GeneralSettings;
import com.takipi.api.client.util.settings.GroupSettings;
import com.takipi.api.client.util.settings.GroupSettings.GroupFilter;
import com.takipi.api.client.util.settings.RegressionSettings;
import com.takipi.api.client.util.settings.ServiceSettingsData;
import com.takipi.api.client.util.settings.SlowdownSettings;
import com.takipi.api.client.util.transaction.TransactionUtil;
import com.takipi.api.client.util.validation.ValidationUtil.GraphResolution;
import com.takipi.api.client.util.validation.ValidationUtil.GraphType;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.functions.ReliabilityReportFunction.DeterminantKey;
import com.takipi.integrations.grafana.input.BaseEnvironmentsInput;
import com.takipi.integrations.grafana.input.BaseEventVolumeInput;
import com.takipi.integrations.grafana.input.BaseGraphInput;
import com.takipi.integrations.grafana.input.EnvironmentsFilterInput;
import com.takipi.integrations.grafana.input.EventFilterInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.VariableInput;
import com.takipi.integrations.grafana.input.ViewInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.GrafanaSettings;
import com.takipi.integrations.grafana.settings.ServiceSettings;
import com.takipi.integrations.grafana.util.ApiCache;
import com.takipi.integrations.grafana.util.TimeUtil;
import com.takipi.integrations.grafana.util.TimeUtil.Interval;

public abstract class GrafanaFunction {
	public interface FunctionFactory {
		
		public GrafanaFunction create(ApiClient apiClient);
		public Class<?> getInputClass();
		public String getName();
	}
	
	public static final DecimalFormat singleDigitFormatter = new DecimalFormat("#.#");
	public static final DecimalFormat doubleDigitFormatter = new DecimalFormat("#.##");

	protected static final PrettyTime prettyTime = new PrettyTime();
	
	protected static final String ALL_EVENTS = "All Events";
	
	protected static final String RESOLVED = "Resolved";
	protected static final String HIDDEN = "Hidden";
	
	protected static final String SERIES_NAME = "events";
	protected static final String EMPTY_NAME = "";
	
	protected static final String SUM_COLUMN = "sum";
	protected static final String TIME_COLUMN = "time";
	protected static final String KEY_COLUMN = "key";
	protected static final String VALUE_COLUMN = "value";
	
	public static final String GRAFANA_SEPERATOR_RAW = "|";
	public static final String ARRAY_SEPERATOR_RAW = ServiceSettingsData.ARRAY_SEPERATOR_RAW;

	public static final String GRAFANA_VAR_ADD = "And";

	public static final String GRAFANA_SEPERATOR = Pattern.quote(GRAFANA_SEPERATOR_RAW);
	public static final String ARRAY_SEPERATOR = Pattern.quote(ARRAY_SEPERATOR_RAW);
	
	public static final String SERVICE_SEPERATOR_RAW = ":";
	public static final String SERVICE_SEPERATOR = SERVICE_SEPERATOR_RAW + " ";
	public static final String GRAFANA_VAR_PREFIX = "$";
	
	public static final String ALL = "All";
	public static final String NONE = "None";
	public static final List<String> VAR_ALL = Arrays.asList(new String[] { "*", ALL,
			ALL.toLowerCase(), NONE, NONE.toLowerCase() });
	
	protected static final char QUALIFIED_DELIM = '.';
	protected static final char INTERNAL_DELIM = '/';
	protected static final String TRANS_DELIM = "#";
	protected static final String EMPTY_POSTFIX = ".";
	
	protected static final String QUALIFIED_DELIM_PATTERN = Pattern.quote(String.valueOf(GrafanaFunction.QUALIFIED_DELIM));
	
	protected static final String REGEX_STARTS_WITH = "^";
	protected static boolean FILTER_TX_BY_APP_LABEL = false;
	
	private static final long H8_THRESHOLD = TimeUnit.DAYS.toMillis(7);
	private static final long H1_THRESHOLD = TimeUnit.HOURS.toMillis(24);
	private static final long M5_THRESHOLD = TimeUnit.HOURS.toMillis(3);

	protected static final String HTTP = "http://";
	protected static final String HTTPS = "https://";
	
	protected static final DateTimeFormatter dateTimeFormatter = ISODateTimeFormat.dateTime().withZoneUTC();
	protected static final DecimalFormat decimalFormat = new DecimalFormat("#.##");

	protected static final Map<String, String> TYPES_MAP;
	
	protected static final String LOGGED_ERROR = "Logged Error";
	protected static final String LOGGED_WARNING = "Logged Warning";
	protected static final String CAUGHT_EXCEPTION = "Caught Exception";
	protected static final String UNCAUGHT_EXCEPTION = "Uncaught Exception";
	protected static final String SWALLOWED_EXCEPTION = "Swallowed Exception";
	protected static final String TIMER = "Timer";
	protected static final String HTTP_ERROR = "HTTP Error";

	public static final int TOP_TRANSACTIONS_MAX = 3;
	public static final int GET_EVENT_LIST_MAX_RETRIES = 5;
	public static final String TOP_ERRORING_TRANSACTIONS = String.format("Top %d Failing", TOP_TRANSACTIONS_MAX);
	public static final String SLOWEST_TRANSACTIONS = String.format("Top %d Slowest", TOP_TRANSACTIONS_MAX);
	public static final String SLOWING_TRANSACTIONS = String.format("Top %d Slowing", TOP_TRANSACTIONS_MAX);
	public static final String HIGHEST_VOLUME_TRANSACTIONS = String.format("Top %d Volume", TOP_TRANSACTIONS_MAX);
		
	public static final List<String> TOP_TRANSACTION_FILTERS = Arrays.asList(new String[] {
			TOP_ERRORING_TRANSACTIONS, 	SLOWEST_TRANSACTIONS, SLOWING_TRANSACTIONS, HIGHEST_VOLUME_TRANSACTIONS});
	
	protected static final List<String> TYPE_RANK = Arrays.asList(new String[] {
			TIMER, CAUGHT_EXCEPTION, SWALLOWED_EXCEPTION, 
			LOGGED_WARNING, LOGGED_ERROR,  
			HTTP_ERROR, UNCAUGHT_EXCEPTION
			});
	
	protected static final List<String> EXCEPTION_TYPES = Arrays.asList(new String[] {
			 CAUGHT_EXCEPTION, SWALLOWED_EXCEPTION, UNCAUGHT_EXCEPTION
			});
	
	static {
		TYPES_MAP = new HashMap<String, String>();
		
		TYPES_MAP.put(LOGGED_ERROR, "ERR");
		TYPES_MAP.put(LOGGED_WARNING, "WRN");
		TYPES_MAP.put(CAUGHT_EXCEPTION, "CEX");
		TYPES_MAP.put(UNCAUGHT_EXCEPTION, "UNC");
		TYPES_MAP.put(SWALLOWED_EXCEPTION, "SWL");
		TYPES_MAP.put(TIMER, "TMR");
		TYPES_MAP.put(HTTP_ERROR, "HTTP");			
	}
		
	protected final ApiClient apiClient;
	protected volatile Map<String, ServiceSettings> settingsMaps;
	
	protected class GraphSliceTaskResult {
		
		GraphSliceTask task;
		List<Graph> graphs;
		
		protected GraphSliceTaskResult(GraphSliceTask task, List<Graph> graphs) {
			this.task = task;
			this.graphs = graphs;
		}
	}
	
	protected class GraphSliceTask extends BaseAsyncTask implements Callable<Object> {

		protected String serviceId;
		protected String viewId;
		protected ViewInput input;
		protected VolumeType volumeType;
		protected DateTime from;
		protected DateTime to;
		protected int baselineWindow;
		protected int activeWindow;
		protected int windowSlice;
		protected boolean cache;
		protected GraphRequest.Builder builder;
		
		protected GraphSliceTask(GraphRequest.Builder builder, String serviceId, String viewId, 
				ViewInput input, VolumeType volumeType, DateTime from, DateTime to,
				int baselineWindow, int activeWindow, int windowSlice, boolean cache) {
			
			this.builder = builder;
			this.serviceId = serviceId;
			this.viewId = viewId;
			this.input = input;
			this.volumeType = volumeType;
			this.from = from;
			this.to = to;
			this.baselineWindow = baselineWindow;
			this.activeWindow = activeWindow;
			this.windowSlice = windowSlice;
			this.cache = cache;
		}
		
		@Override
		public Object call() throws Exception {		
			
			Response<GraphResult> response = ApiCache.getEventGraph(apiClient, 
				serviceId, input, getSettingsData(serviceId),
				volumeType, builder.build(), 
				baselineWindow, activeWindow, windowSlice,
				Pair.of(from, to), cache);
			
			if (response.isBadResponse()) {
				return null;
			}
			
			GraphResult graphResult = response.data;
			
			if (graphResult == null) {
				return null;
			}
			
			if (CollectionUtil.safeIsEmpty(graphResult.graphs)) {
				return null;
			}
			
			Graph graph = graphResult.graphs.get(0);
			
			if (graph == null) {
				return null;
			}
			
			if (!viewId.equals(graph.id)) {
				return null;
			}
			
			if (CollectionUtil.safeIsEmpty(graph.points)) {
				return null;
			}
			
			return new GraphSliceTaskResult(this, graphResult.graphs);
		}
	}
	
	protected class SliceRequest {
		
		protected DateTime from;
		protected DateTime to;
		protected boolean cache;		
		
		protected SliceRequest(DateTime from, DateTime to, 
			boolean cache) {
			this.from = from;
			this.to = to;
			this.cache = cache;
		}
	}
	
	protected class TransactionData {
		protected com.takipi.api.client.data.transaction.Stats stats;
		protected TransactionGraph graph;
		protected TransactionGraph baselineGraph;
		protected TransactionGraph baselineAndActiveGraph;
		protected long timersHits;
		protected long errorsHits;
		protected List<EventResult> errors;
		protected EventResult currTimer;
		protected PerformanceState state;
		protected double score;
		protected com.takipi.api.client.data.transaction.Stats baselineStats;
		protected long baselineErrors;
		
		@Override
		public String toString() {
			return graph.name;
		}
	}
	
	public static class TransactionKey {
		
		public String className;
		public String methodName;
		
		public static TransactionKey of(String className, String methodName) {
			
			TransactionKey result = new TransactionKey();
			result.className = className;
			result.methodName = methodName;
			
			return result;
		}
		
		
		@Override
		public boolean equals(Object obj) {
			
			if (!(obj instanceof TransactionKey)) {
				return false;
			}
			
			TransactionKey other = (TransactionKey)obj;
			
			if (!Objects.equal(className, other.className)) {
				return false;
			}
			
			if (!Objects.equal(methodName, other.methodName)) {
				return false;
			}
			
			return true;
		}
		
		@Override
		public int hashCode() {
			
			if (methodName == null) {
				return className.hashCode();
			}
			
			return className.hashCode() ^ methodName.hashCode();
		}
		
		@Override
		public String toString() {
			return className + "." + methodName;
		}
	}
	
	public static class TransactionDataResult {
		
		public Map<TransactionKey, TransactionData> items;
		public RegressionInput regressionInput;
		public RegressionWindow regressionWindow;
	}
	
	protected abstract class TopTransactionProcessor {
		
		protected abstract Comparator<Map.Entry<TransactionKey, TransactionData>> 
			getComparator(TransactionDataResult transactionDataResult);
		
		protected boolean updateBaseline() {
			return false;
		}
		
		protected boolean updateEvents() {
			return false;
		}
		
		/**
		 * @param key - needed for children
		 * @param data 
		 */
		protected boolean includeTransaction(TransactionKey key, TransactionData data) {
			return true;
		}
		
		protected Collection<String> getTransactions(TransactionDataResult transactionDataResult) {
			
			if ((transactionDataResult == null) || (transactionDataResult.items.size() == 0)) {
				return Collections.emptyList();
			}
			
			Comparator<Map.Entry<TransactionKey, TransactionData>> comparator = getComparator(transactionDataResult);
			
			List<Map.Entry<TransactionKey, TransactionData>> sortedTransactionDatas = 
					new ArrayList<Map.Entry<TransactionKey, TransactionData>>(transactionDataResult.items.entrySet());
				
			sortedTransactionDatas.sort(comparator);
			int size = Math.min(sortedTransactionDatas.size(), TOP_TRANSACTIONS_MAX);
			
			List<String> result = new ArrayList<String>(size);
			
			for (Map.Entry<TransactionKey, TransactionData> entry : sortedTransactionDatas) {
				
				if (!includeTransaction(entry.getKey(), entry.getValue())) {
					continue;
				}
				
				result.add(getSimpleClassName(entry.getKey().className));
				
				if (result.size() >= size) {
					break;
				}
			}
	
			return result;
		}
	}
	
	protected class TopErrorTransactionProcessor extends TopTransactionProcessor {
	
		@Override
		protected boolean updateEvents() {
			return true;
		}
		
		@Override
		protected Comparator<Map.Entry<TransactionKey, TransactionData>> getComparator(TransactionDataResult transactionDataResult) {
						
			return new Comparator<Map.Entry<TransactionKey,TransactionData>>() {
	
				@Override
				public int compare(Entry<TransactionKey, TransactionData> o1, Entry<TransactionKey, TransactionData> o2) {
					
					long v1 =  o1.getValue().errorsHits;
					long v2 =  o2.getValue().errorsHits;
					
					return Long.compare(v2, v1);
				}
			};
		}
	}
	
	protected class TopVolumeTransactionProcessor extends TopTransactionProcessor {
		
		@Override
		protected Comparator<Map.Entry<TransactionKey, TransactionData>> getComparator(TransactionDataResult transactionDataResult) {
				
			return new Comparator<Map.Entry<TransactionKey,TransactionData>>() {
	
				@Override
				public int compare(Entry<TransactionKey, TransactionData> o1, Entry<TransactionKey, TransactionData> o2) {
					
					long v1 =  o1.getValue().stats.invocations;
					long v2 =  o2.getValue().stats.invocations;
					
					return Long.compare(v2, v1);
				}
			};
		}
	}
	
	protected class TopSlowestTransactionProcessor extends TopTransactionProcessor {
		
		@Override
		protected Comparator<Map.Entry<TransactionKey, TransactionData>> getComparator(TransactionDataResult transactionDataResult) {
			
			return new Comparator<Map.Entry<TransactionKey,TransactionData>>() {
	
				@Override
				public int compare(Entry<TransactionKey, TransactionData> o1, Entry<TransactionKey, TransactionData> o2){
					
					double v1 = o1.getValue().stats.avg_time;
					double v2 = o2.getValue().stats.avg_time;
					
					return Double.compare(v2, v1);
				}
			};
		}
	}
	
	protected class TopSlowingTransactionProcessor extends TopTransactionProcessor {
		
		@Override
		protected Collection<String> getTransactions(TransactionDataResult transactionDataResult) {
			
			if ((transactionDataResult == null) 
			|| (transactionDataResult.items.size() == 0)) {
				return Collections.emptyList();
			}
			
			boolean hasSlowdown = false;
			
			for (TransactionData transactionData : transactionDataResult.items.values()) {
				
				if ((transactionData.state == PerformanceState.SLOWING) 
				|| (transactionData.state == PerformanceState.CRITICAL)) {
					hasSlowdown = true;
					break;
				}
			}
			
			if (!hasSlowdown) {
				return null; 
			}
			
			return super.getTransactions(transactionDataResult);
		}
		
		@Override
		protected boolean updateBaseline() {
			return true;
		}
		
		
		@Override
		protected boolean includeTransaction(TransactionKey key, TransactionData data) {
			
			if ((data.state == PerformanceState.CRITICAL) 
			|| (data.state == PerformanceState.SLOWING)) {
				return true;
			}
			
			return false;
		}
		
		@Override
		protected Comparator<Map.Entry<TransactionKey, TransactionData>> getComparator(TransactionDataResult transactionDataResult) {
			
			return new Comparator<Map.Entry<TransactionKey,TransactionData>>() {
	
				@Override
				public int compare(Entry<TransactionKey, TransactionData> o1, Entry<TransactionKey, TransactionData> o2) {
					
					int v1 =  o1.getValue().state.ordinal();
					int v2 =  o2.getValue().state.ordinal();
					
					if (v2 - v1 > 0) {
						return 1;
					}
					
					if (v2 - v1 < 0) {
						return -1;
					}
					
					double d1 =  o1.getValue().score;
					double d2 =  o2.getValue().score;
					
					return Double.compare(d2, d1);
				}
			};
		}
	}
	
	public GrafanaFunction(ApiClient apiClient, Map<String, ServiceSettings> settingsMaps) {
		this.apiClient = apiClient;
		this.settingsMaps = settingsMaps;
	}
	
	public GrafanaFunction(ApiClient apiClient) {
		this(apiClient, null);
	}
			
	public ServiceSettingsData getSettingsData(String serviceId) {
		return getSettings(serviceId).getData();
	}

	
	public ServiceSettings getSettings(String serviceId) {
		
		ServiceSettings result = null;
		
		if (settingsMaps != null) {
			
			result = settingsMaps.get(serviceId);
			
			if (result != null) {
				return result;
			}
		} else {
			synchronized (this) {
				
				if (settingsMaps == null) {
					settingsMaps = Collections.synchronizedMap(new TreeMap<String, ServiceSettings>());
					result = doGetSettings(serviceId);
				}		
			}		
		}
		
		if (result == null) {
			result = doGetSettings(serviceId);
		}
		
		return result;
	}
	
	public Collection<String> getTypes(String serviceId, EventFilterInput eventFilterInput) {
		return getTypes(serviceId, true, eventFilterInput);
	}
	
	private Collection<String> getCriticalExceptions(String serviceId) {
		
		List<String> result = new ArrayList<String>();

		RegressionSettings regressionSettings = getSettingsData(serviceId).regression;
		
		if (regressionSettings != null) {
			Collection<String> criticalExceptionTypes = regressionSettings.getCriticalExceptionTypes();
			
			for (String criticalExceptionType : criticalExceptionTypes) {
				result.add(EventFilter.toExceptionFilter(criticalExceptionType));
			}
		}
		
		return result;
	}
	
	private Collection<String> getTransactionFailures(String serviceId) {
		
		List<String> result = new ArrayList<String>();

		String transactionFailures = getSettingsData(serviceId).general.transaction_failures;
		
		if (transactionFailures != null) {

			String[] failureTypes = transactionFailures.split(ServiceSettingsData.ARRAY_SEPERATOR);

			for (String failureType : failureTypes) {
				
				if (EventFilter.CRITICAL_EXCEPTIONS.equals(failureType)) {
					result.addAll(getCriticalExceptions(serviceId));
				} else {
					result.add(failureType);
				}
			}
		}
		
		return result;
	}

	public Collection<String> getTypes(String serviceId, boolean expandCriticalTypes,
		EventFilterInput eventFilterInput) {
		
		if (!VariableInput.hasFilter(eventFilterInput.types)) {
			return null;
		}
		
		String value = eventFilterInput.types.replace(GrafanaFunction.ARRAY_SEPERATOR_RAW,
				GrafanaFunction.GRAFANA_SEPERATOR_RAW);
		
		List<String> result = new ArrayList<String>();
		Collection<String> types = VariableInput.getServiceFilters(value, null, true);
		
		for (String type : types) {
			
			if (expandCriticalTypes) {
				
				if (EventFilter.CRITICAL_EXCEPTIONS.equals(type)) {
					result.addAll(getCriticalExceptions(serviceId));	
				} else if (EventFilter.TRANSACTION_FAILURES.equals(type)) {
					result.addAll(getTransactionFailures(serviceId));		
				} else {
					result.add(type);
				}	
			} else {
				result.add(type);
			}
		}
		
		return result;
	}
	
	private ServiceSettings doGetSettings(String serviceId) {
		
		ServiceSettings result = GrafanaSettings.getServiceSettings(apiClient, serviceId);
		settingsMaps.put(serviceId, result);
		
		return result;
	}
	
	public static String toQualified(String value) {
		return value.replace(INTERNAL_DELIM, QUALIFIED_DELIM);
	}
	
	protected String toTransactionName(Location location)
	{
		String transactionName =
				location.class_name + TRANS_DELIM + location.method_name + TRANS_DELIM + location.method_desc;
		return transactionName;
	}
	
	protected Object getTimeValue(long value, FunctionInput input) {
		
		switch (input.getTimeFormat()) {
			
			case EPOCH: return Long.valueOf(value);
			case ISO_UTC: return TimeUtil.getDateTimeFromEpoch(value);
		}
		
		throw new IllegalStateException();
	}
	
	protected static Object getTimeValue(Long value, FunctionInput input) {
		
		switch (input.getTimeFormat()) {
			
			case EPOCH: return value;
			case ISO_UTC: return TimeUtil.getDateTimeFromEpoch(value.longValue());
		}
		
		throw new IllegalStateException();
	}
	
	protected Pair<Object, Object> getTimeFilterPair(Pair<DateTime, DateTime> timeSpan, String timeFilter) {
		
		Object from;
		Object to;
		
		String timeUnit = TimeUtil.getTimeUnit(timeFilter);
		
		if (timeUnit != null) {
			from = "now-" + timeUnit;
			to = "now";
		} else {
			from = timeSpan.getFirst().getMillis();
			to = timeSpan.getSecond().getMillis();
		}
		
		return Pair.of(from, to);
	}

	protected static Pair<String, String> getTransactionNameAndMethod(String name, 
		boolean fullyQualified) {
		
		String[] parts = name.split(TRANS_DELIM);
		
		if (parts.length == 1) {
			if (fullyQualified) {
				return Pair.of(toQualified(name), null);
			} else {
				return Pair.of(getSimpleClassName(toQualified(name)), null);
			}
			
		} else {
			if (fullyQualified) {
				return Pair.of(toQualified((parts[0])), parts[1]);		
			} else {
				return Pair.of(getSimpleClassName(toQualified((parts[0]))), parts[1]);
			}
		}
	}
	
	protected static Pair<String, String> getFullNameAndMethod(String name) {
		
		String[] parts = name.split(TRANS_DELIM);
		
		if (parts.length == 1) {
			return Pair.of(toQualified(name), null);
		} else {
			return Pair.of(toQualified((parts[0])), parts[1]);
		}
	}
	
	protected static String getTransactionName(String name, boolean includeMethod) {
		
		Pair<String, String> nameAndMethod = getTransactionNameAndMethod(name, false);
		
		if ((includeMethod) && (nameAndMethod.getSecond() != null)){
			return nameAndMethod.getFirst() + QUALIFIED_DELIM + nameAndMethod.getSecond();
		} else {
			return nameAndMethod.getFirst();
		}
	}
	
	public static String getSimpleClassName(String className) {
		String qualified = toQualified(className);
		int sepIdex = Math.max(qualified.lastIndexOf(QUALIFIED_DELIM) + 1, 0);
		String result = qualified.substring(sepIdex, qualified.length());
		return result;
	}
	
	protected void validateResponse(Response<?> response) {
		if ((response.isBadResponse()) || (response.data == null)) {
			System.err.println("EventsResult code " + response.responseCode);
		}	
	}
	
	protected static String formatLocation(String className, String method) {
		return getSimpleClassName(className) + QUALIFIED_DELIM + method;
	}
	
	protected static String formatLocation(Location location) {
		
		if (location == null) {
			return null;
		}
		
		return formatLocation(getSimpleClassName(location.class_name), 
			location.method_name);
	}
	
	protected Collection<String> getServiceIds(BaseEnvironmentsInput input) {
		
		List<String> serviceIds = input.getServiceIds();
		
		if (serviceIds.size() == 0) {
			return Collections.emptyList();
		}
		
		List<String> result;
		
		if (input.unlimited) {
			result = serviceIds;
		} else {
			result = serviceIds.subList(0, Math.min(BaseEnvironmentsInput.MAX_COMBINE_SERVICES, 
				serviceIds.size()));
		}
		
		result.remove(NONE);
		
		return result;
	}
	
	private void applyBuilder(ViewTimeframeRequest.Builder builder, String serviceId, String viewId,
			Pair<String, String> timeSpan, ViewInput request) {
		
		builder.setViewId(viewId).setServiceId(serviceId).
			setFrom(timeSpan.getFirst()).setTo(timeSpan.getSecond());
		
		applyFilters(request, serviceId, builder);
	}
	
	public static boolean filterTransaction(GroupFilter filter, String searchText,
		String className, String methodName) {
		
		String searchTextLower;
		
		if (searchText != null) {
			searchTextLower = searchText.toLowerCase();
		} else {
			searchTextLower = null;
		}
		
		boolean hasSearchText = (searchText != null) && (!searchText.equals(EventFilter.TERM));
		
		String simpleClassName = getSimpleClassName(className);
		String simpleClassAndMethod;
		
		if (methodName != null) {
			simpleClassAndMethod = simpleClassName + QUALIFIED_DELIM + methodName;
			
			if ((hasSearchText) && (!simpleClassAndMethod.toLowerCase().contains(searchTextLower))) {
				return true;
			}
		} else {
			simpleClassAndMethod = null;
			
			if ((hasSearchText) && (!simpleClassName.toLowerCase().contains(searchTextLower))) {
				return true;
			}
		}
	
		if ((filter == null) || ((filter.values.size() == 0) 
		&& (filter.patterns.size() == 0))) {		
			return false;
		}
				
		for (String value : filter.values) {
			if ((simpleClassAndMethod != null) 
			&& (value.equals(simpleClassAndMethod))) {				
				return false;
			}
			
			if (value.equals(simpleClassName)) {				
				return false;
			}
			
		}
		
		String fullName;
		
		if (methodName != null) {
			fullName = className + QUALIFIED_DELIM + methodName;
		} else {
			fullName = className;
		}
		
		for (Pattern pattern : filter.patterns) {
			if (pattern.matcher(fullName).find()) {
				return false;
			}
		}
		
		return true;
	}
	
	private BaseEventVolumeInput getBaselineInput(BaseEventVolumeInput input) {
		
		BaseEventVolumeInput result;
		
		if ((input.hasDeployments()) || (input.hasTransactions())) {
			Gson gson = new Gson();
			String json = gson.toJson(input);
			result = gson.fromJson(json, input.getClass());
			result.deployments = null;
			result.transactions = null;
		} else {
			result = input;
		}
		
		return result;
	}
	
	protected Collection<TransactionGraph> getBaselineTransactionGraphs(
		String serviceId, String viewId, BaseEventVolumeInput input, 
		Pair<DateTime, DateTime> timeSpan, RegressionInput regressionInput, 
		RegressionWindow regressionWindow) {
		
		BaseEventVolumeInput baselineInput = getBaselineInput(input);
		
		DateTime baselineStart = regressionWindow.activeWindowStart.minusMinutes(regressionInput.baselineTimespan);

		Collection<TransactionGraph> result = getTransactionGraphs(baselineInput, serviceId, 
				viewId, Pair.of(baselineStart, timeSpan.getSecond()), 
				null, regressionWindow.activeTimespan, regressionInput.baselineTimespan);
		
		return result;
	}
	
	private Collection<Transaction> getBaselineTransactions(
			String serviceId, String viewId, BaseEventVolumeInput input, 
			@SuppressWarnings("unused") Pair<DateTime, DateTime> timeSpan, RegressionInput regressionInput, 
			RegressionWindow regressionWindow) {
			
		BaseEventVolumeInput baselineInput = getBaselineInput(input);
			
		DateTime baselineStart = regressionWindow.activeWindowStart.minusMinutes(regressionInput.baselineTimespan);

		Collection<Transaction> result = getTransactions(serviceId, 
					viewId, Pair.of(baselineStart, regressionWindow.activeWindowStart), 
					baselineInput, baselineInput.getSearchText(),
					0, regressionInput.baselineTimespan);
			
		return result;
	}
	
	protected TransactionData getEventTransactionData(Map<TransactionKey, TransactionData> transactions, EventResult event) {
	
		TransactionKey classOnlyKey = TransactionKey.of(event.entry_point.class_name, null);
		TransactionData result = transactions.get(classOnlyKey);
	
		if (result == null) {
			
			TransactionKey classAndMethodKey = TransactionKey.of(event.entry_point.class_name, 
				event.entry_point.method_name);
			
			result = transactions.get(classAndMethodKey);
		}
		
		return result;
	}
	
	/**
	 * @param queryBaselineGraphs - skipped for now 
	 */
	private void updateTransactionPerformance(String serviceId, String viewId, 
			Pair<DateTime, DateTime> timeSpan, BaseEventVolumeInput input, 
			RegressionInput regressionInput, RegressionWindow regressionWindow, 
			Map<TransactionKey, TransactionData> transactionDatas, boolean queryBaselineGraphs) {		
		
		SlowdownSettings slowdownSettings = getSettingsData(serviceId).slowdown;
		
		if (slowdownSettings == null) {
			throw new IllegalStateException("Missing slowdown settings for " + serviceId);
		}
		
		
		if (queryBaselineGraphs) {
			
			Collection<TransactionGraph> baselineAndActiveGraphs = getBaselineTransactionGraphs(serviceId, 
				viewId, input, timeSpan, regressionInput, regressionWindow);
		
			Collection<TransactionGraph> baselineGraphs = getBaselineGraphs(baselineAndActiveGraphs, 
				regressionWindow.activeWindowStart);
			
			updateTransactionGraphPerformance(baselineGraphs, baselineAndActiveGraphs, 
				transactionDatas, slowdownSettings);
		}// else {
		//*/
			Collection<Transaction> baselineTransactions = getBaselineTransactions(serviceId,
				viewId, input, timeSpan, regressionInput, regressionWindow);
			
			if (baselineTransactions != null) {
				updateTransactionPerformance(baselineTransactions, 
					transactionDatas, slowdownSettings);
			} else {
				for (TransactionData transactionData : transactionDatas.values()) {
					transactionData.state = PerformanceState.NO_DATA;
					transactionData.stats = TransactionUtil.aggregateGraph(transactionData.graph);
				}
			}
		//}		
	}
	
	private TransactionKey getTransactionKey(String transactionName) {
		
		Pair<String, String> classMethodNamesPair = getTransactionNameAndMethod(transactionName, true);
		TransactionKey key = TransactionKey.of(classMethodNamesPair.getFirst(), classMethodNamesPair.getSecond());
		
		return key;
	}
	
	private Collection<TransactionGraph> getBaselineGraphs(
			Collection<TransactionGraph> graphs, DateTime activeWindowStart) {
		
		List<TransactionGraph> result = new ArrayList<TransactionGraph>(graphs.size());
		
		for (TransactionGraph graph : graphs) {
			
			TransactionGraph baselineGraph = new TransactionGraph();
			
			baselineGraph.class_name = graph.class_name;
			baselineGraph.method_name = graph.method_name;
			baselineGraph.method_desc = graph.method_desc;
			baselineGraph.name = graph.name;
			
			baselineGraph.points = new ArrayList<com.takipi.api.client.data.transaction.TransactionGraph.GraphPoint>();
			
			if (!CollectionUtil.safeIsEmpty(graph.points)) {
				
				for (com.takipi.api.client.data.transaction.TransactionGraph.GraphPoint gp : graph.points) {
					
					DateTime gpTime = TimeUtil.getDateTime(gp.time);
					
					if (gpTime.isAfter(activeWindowStart)) {
						continue;
					}
					
					baselineGraph.points.add(gp);
				}
			}
			
			result.add(baselineGraph);
		}
		
		return result;
	}
	
	protected void updateTransactionPerformance(
			Collection<Transaction> baselineTransactions, 
			Map<TransactionKey, TransactionData> transactionDatas,
			SlowdownSettings slowdownSettings) {
		
		DirectStatsPerformanceCalculator calc = DirectStatsPerformanceCalculator.of(
				slowdownSettings.active_invocations_threshold, slowdownSettings.baseline_invocations_threshold,
				slowdownSettings.min_delta_threshold,
				slowdownSettings.over_avg_slowing_percentage, slowdownSettings.over_avg_critical_percentage,
				slowdownSettings.std_dev_factor);
	
		Map<String, TransactionGraph> activeGraphsMap = getTransactionsMap(transactionDatas);
		
		Map<String, com.takipi.api.client.data.transaction.Stats> baselineStats = 
			new HashMap<String, com.takipi.api.client.data.transaction.Stats>();
		
		for (Transaction transaction : baselineTransactions) {
			baselineStats.put(transaction.name, transaction.stats);
		}
		
		Map<TransactionGraph, PerformanceScore> performanceScores = 
				PerformanceUtil.getPerformanceStates(
				activeGraphsMap, baselineStats, calc);
		
		for (Map.Entry<String, com.takipi.api.client.data.transaction.Stats> entry : baselineStats.entrySet()) {
			
			String transactionName = entry.getKey();
			
			TransactionKey transactionKey = getTransactionKey(transactionName);
			TransactionData transactionData = transactionDatas.get(transactionKey);	
			
			if (transactionData != null) {
				
				transactionData.baselineStats = entry.getValue();
			}		
		}
		
		Map<String, PerformanceScore> performanceStats = new HashMap<String, PerformanceScore>();
		
		for (Map.Entry<TransactionGraph, PerformanceScore> entry : performanceScores.entrySet()) {
			performanceStats.put(entry.getKey().name, entry.getValue());
		}
		
		updateTransactionStats(performanceStats, transactionDatas);		
	}
	
	private Map<String, TransactionGraph> getTransactionsMap(Map<TransactionKey, TransactionData> transactionDatas) {
		
		Map<String, TransactionGraph> result = new HashMap<String, TransactionGraph>();
		
		for (TransactionData transactionData : transactionDatas.values()) {
			result.put(transactionData.graph.name, transactionData.graph);
		}
		
		return result;
		
	}
	
	protected static class RegressionPeriodData {
		protected Graph activeGraph;
		protected Graph baselineGraph;
		protected Map<String, EventResult> eventMap;
	}
	
	protected static RegressionPeriodData cropGraphByPeriod(Graph graph, Pair<DateTime, DateTime> period,
															int baselineWindow, Map<String, EventResult> eventMap) {
		
		RegressionPeriodData result = new RegressionPeriodData();
		
		result.activeGraph = new Graph();
		result.baselineGraph = new Graph();
		result.eventMap = new HashMap<String, EventResult>();
		
		result.activeGraph.id = graph.id;
		result.activeGraph.type = graph.type;
		result.activeGraph.points = new ArrayList<GraphPoint>();
		
		result.activeGraph.application_name = graph.deployment_name;
		result.activeGraph.application_name = graph.machine_name;
		result.activeGraph.application_name = graph.application_name;
								
		result.baselineGraph.id = graph.id;
		result.baselineGraph.type = graph.type;
		result.baselineGraph.points = new ArrayList<GraphPoint>();
		
		result.baselineGraph.deployment_name = graph.deployment_name;
		result.baselineGraph.machine_name = graph.machine_name;
		result.baselineGraph.application_name = graph.application_name;
		
		DateTime baselineEnd = period.getFirst();
		DateTime baselineStart = baselineEnd.minusMinutes(baselineWindow);
		
		for (GraphPoint gp : graph.points) {
			
			if (gp.contributors == null) {
				continue;
			}
			
			DateTime gpTime = TimeUtil.getDateTime(gp.time);
			
			if (timespanContains(period.getFirst(), period.getSecond(), gpTime)) {
				
				result.activeGraph.points.add(gp);
				
				for (GraphPointContributor gpc : gp.contributors) {
					if (eventMap != null)
					{
						EventResult event = eventMap.get(gpc.id);
						
						if (event != null)
						{
							result.eventMap.put(event.id, event);
						}
					}
				}
			}
			
			if (timespanContains(baselineStart, baselineEnd, gpTime)) {
				result.baselineGraph.points.add(gp);
			}
		}
		
		return result;
	}
	
	protected void updateTransactionGraphPerformance(
			Collection<TransactionGraph> baselineGraphs,
			Collection<TransactionGraph> baselineAndActiveGraphs, 
			Map<TransactionKey, TransactionData> transactionDatas,
			SlowdownSettings slowdownSettings) {

		GraphPerformanceCalculator calc = GraphPerformanceCalculator.of(
				slowdownSettings.active_invocations_threshold, slowdownSettings.baseline_invocations_threshold,
				slowdownSettings.min_delta_threshold,
				slowdownSettings.over_avg_slowing_percentage, slowdownSettings.over_avg_critical_percentage,
				slowdownSettings.std_dev_factor);			
				
		Map<String, TransactionGraph> baselineGraphsMap = TransactionUtil.getTransactionGraphsMap(baselineGraphs);
		Map<String, TransactionGraph> baselineAndActiveGraphsMap = TransactionUtil.getTransactionGraphsMap(baselineAndActiveGraphs);	
		
		Map<String, TransactionGraph> activeGraphsMap = getTransactionsMap(transactionDatas);
		
		Map<TransactionGraph, PerformanceScore> performanceScores = 
				PerformanceUtil.getPerformanceStates(
				activeGraphsMap, baselineGraphsMap, calc);
		
		for (Map.Entry<String, TransactionGraph> entry : activeGraphsMap.entrySet()) {
			
			String transactionName = entry.getKey();
			
			TransactionKey transactionKey = getTransactionKey(transactionName);
			TransactionData transactionData = transactionDatas.get(transactionKey);	
			
			if (transactionData != null) {
				
				transactionData.baselineGraph = baselineGraphsMap.get(transactionName);
				
				if (transactionData.baselineGraph != null) {
					transactionData.baselineStats =  TransactionUtil.aggregateGraph(transactionData.baselineGraph);
				}
				
				transactionData.baselineAndActiveGraph = baselineAndActiveGraphsMap.get(transactionName);				
			}		
		}
		
		Map<String, PerformanceScore> performanceStats = new HashMap<String, PerformanceScore>();
		
		for (Map.Entry<TransactionGraph, PerformanceScore> entry : performanceScores.entrySet()) {
			performanceStats.put(entry.getKey().name, entry.getValue());
		}
		
		updateTransactionStats(performanceStats, transactionDatas);		
	}
	
	private void updateTransactionStats(Map<String, PerformanceScore> performanceScores,
			Map<TransactionKey, TransactionData> transactionDatas) {
		
		for (Map.Entry<String, PerformanceScore> entry : performanceScores.entrySet()) {
			
			String transactionName = entry.getKey();
			PerformanceScore performanceScore = entry.getValue();
			
			TransactionKey transactionKey = getTransactionKey(transactionName);
			TransactionData transactionData = transactionDatas.get(transactionKey);
			
			if (transactionData == null) {
				continue;
			}
			
			transactionData.state = performanceScore.state;
			transactionData.score = performanceScore.score;
			transactionData.stats = TransactionUtil.aggregateGraph(transactionData.graph);
		}
	}
	
	private void updateTransactionBaselineEvents(String serviceId, Pair<DateTime, DateTime> timeSpan,
		BaseEventVolumeInput input, RegressionInput regressionInput, 
		RegressionWindow regressionWindow, Map<TransactionKey, TransactionData> transactions) {
		
		BaseEventVolumeInput eventsInput;
		
		if (input.deployments != null) {
			Gson gson = new Gson();
			String json = gson.toJson(input);
			eventsInput = gson.fromJson(json, input.getClass()); 
			eventsInput.deployments = null;
		} else {
			eventsInput = input;
		}
		
		Graph graph = getEventsGraph(serviceId, input, 
			VolumeType.hits, timeSpan.getFirst(), timeSpan.getSecond(), 
			regressionInput.baselineTimespan, regressionWindow.activeTimespan, true);
		
		if ((graph == null ) || (graph.points == null)) {
			return;
		}
		
		Map<String, TransactionData> eventTransactionMap = new HashMap<String, TransactionData>();
		
		for (TransactionData transactionData : transactions.values()) {
			
			if (transactionData.errors == null) {
				continue;
			}
			
			for (EventResult eventResult : transactionData.errors) {
				eventTransactionMap.put(eventResult.id, transactionData);
			}
			
		}
		
		for (GraphPoint gp : graph.points) {
			
			if (gp.contributors == null) {
				return;
			}
			
			for (GraphPointContributor gpc : gp.contributors) {
				
				TransactionData transactionData = eventTransactionMap.get(gpc.id);
				
				if (transactionData != null) {
					transactionData.baselineErrors += gpc.stats.hits;
				}
			}
			
		}
		
	}
	
	private void updateTransactionEvents(String serviceId, Pair<DateTime, DateTime> timeSpan,
		BaseEventVolumeInput input, Map<TransactionKey, TransactionData> transactions) {
				
		Map<String, EventResult> eventsMap = getEventMap(serviceId, input, timeSpan.getFirst(),
			timeSpan.getSecond(), VolumeType.hits);
		
		if (eventsMap == null) {
			return;
		}

		EventFilter eventFilter = getEventFilter(serviceId, input, timeSpan);
		
		if (eventFilter == null) {
			return;
		}
				
		for (EventResult event : eventsMap.values()) {
			
			if (event.entry_point == null) {
				continue;
			}
				
			TransactionData transaction = getEventTransactionData(transactions, event);
			
			if (transaction == null) {
				continue;
			}
			
			if (event.type.equals(TIMER)) {
				  
				transaction.timersHits += event.stats.hits;

				if (transaction.currTimer == null) {
					transaction.currTimer = event;
				} else {
					DateTime eventFirstSeen = TimeUtil.getDateTime(event.first_seen);
					DateTime timerFirstSeen = TimeUtil.getDateTime(transaction.currTimer.first_seen);
					
					long eventDelta = timeSpan.getSecond().getMillis() - eventFirstSeen.getMillis();
					long timerDelta = timeSpan.getSecond().getMillis() - timerFirstSeen.getMillis();

					if (eventDelta < timerDelta) {
						transaction.currTimer = event;
					}				
				}	
			} else {

				if (eventFilter.filter(event)) {
					continue;
				}
				
				transaction.errorsHits += event.stats.hits;
				
				if (transaction.errors == null) {
					transaction.errors = new ArrayList<EventResult>();
				}
				
				transaction.errors.add(event);
			}
		}
	}
	
	private Map<TransactionKey, TransactionData> getTransactionDatas(Collection<TransactionGraph> transactionGraphs) {
		
		Map<TransactionKey, TransactionData> result = new HashMap<TransactionKey, TransactionData>();
				
		for (TransactionGraph transactionGraph : transactionGraphs) {	
			TransactionData transactionData = new TransactionData();	
			transactionData.graph = transactionGraph;
			Pair<String, String> pair = getTransactionNameAndMethod(transactionGraph.name, true);
			TransactionKey key = TransactionKey.of(pair.getFirst(), pair.getSecond());
			result.put(key, transactionData);
		}
		
		return result;
		
	}
	
	protected Map<TransactionKey, TransactionData> getTransactionDatas(String serviceId,
			Collection<TransactionGraph> transactionGraphs,
			Collection<TransactionGraph> baselineGraphs) {
		
		Map<TransactionKey, TransactionData> result = getTransactionDatas(transactionGraphs);
		
		SlowdownSettings slowdownSettings = getSettingsData(serviceId).slowdown;
		
		if (slowdownSettings == null) {
			throw new IllegalStateException("Missing slowdown settings for " + serviceId);
		}
		
		updateTransactionGraphPerformance(baselineGraphs, null, result, slowdownSettings);
		
		return result;
	}
	

	protected TransactionDataResult getTransactionDatas(Collection<TransactionGraph> transactionGraphs,
			String serviceId, String viewId, Pair<DateTime, DateTime> timeSpan,
			BaseEventVolumeInput input, 
			boolean updateBaselne, boolean updateEvents, boolean updateBaselineEvents,
			boolean queryBaselineGraphs) {
				
		if (transactionGraphs == null) {
			return null;
		}
		
		TransactionDataResult result = new TransactionDataResult();
		result.items = getTransactionDatas(transactionGraphs);
		
		if (updateEvents) {
			updateTransactionEvents(serviceId, timeSpan, input, result.items);
		}
			
		RegressionFunction regressionFunction = new RegressionFunction(apiClient, settingsMaps);
		
		Pair<RegressionInput, RegressionWindow> regPair = regressionFunction.getRegressionInput(serviceId, 
				viewId, input, timeSpan, false);
		
		if (regPair != null) {
			
			result.regressionInput = regPair.getFirst();
			result.regressionWindow = regPair.getSecond();
				
			if (updateBaselne) {
				
				updateTransactionPerformance(serviceId, viewId, timeSpan, input, 
					result.regressionInput, result.regressionWindow,
					result.items, queryBaselineGraphs);	
			}
			
			if (updateBaselineEvents) {
				updateTransactionBaselineEvents(serviceId, timeSpan, input, 
					result.regressionInput, result.regressionWindow, result.items);
			}
		}
		
		return result;

	}
	
	private static Collection<String> toArray(String value)
	{
		if (value == null) {
			return Collections.emptyList();
		}
		
		String[] types = value.split(ARRAY_SEPERATOR);
		return Arrays.asList(types);
		
	} 
	
	protected TransactionDataResult getTransactionDatas(String serviceId, Pair<DateTime, DateTime> timeSpan,
			BaseEventVolumeInput input, boolean updateEvents, boolean updateBaselineEvents,
			boolean queryBaselineGraphs) {
		
		return getTransactionDatas(serviceId, timeSpan, input, 
			updateEvents, true, updateBaselineEvents,
			queryBaselineGraphs);
	}
	
	protected TransactionDataResult getTransactionDatas(String serviceId, Pair<DateTime, DateTime> timeSpan,
			BaseEventVolumeInput input, boolean updateEvents, boolean updateBaselne,
			boolean updateEventsBaseline, boolean queryBaselineGraphs) {
		
		String viewId = getViewId(serviceId, input.view);
		
		if (viewId == null) {
			return null;
		}
		
		Collection<TransactionGraph> transactionGraphs = getTransactionGraphs(input,
				serviceId, viewId, timeSpan, input.getSearchText());
		
		return getTransactionDatas(transactionGraphs, serviceId, viewId, timeSpan,
			input, updateBaselne, updateEvents, updateEventsBaseline,
			queryBaselineGraphs);
	}
	
	private Collection<String> getTopTransactions(String serviceId, BaseEventVolumeInput input,
		Pair<DateTime, DateTime> timespan, TopTransactionProcessor processor) {
		
		Gson gson = new Gson();
		String json = gson.toJson(input);
		BaseEventVolumeInput cleanInput = gson.fromJson(json, input.getClass());
		cleanInput.transactions = null;
		
		TransactionDataResult transactionDataResult = getTransactionDatas(serviceId, 
			timespan, cleanInput, processor.updateEvents(), processor.updateBaseline(), true);
		
		Collection<String> result = processor.getTransactions(transactionDataResult);
		
		return result;
	}
	
	
	protected GroupFilter getTransactionsFilter(String serviceId, BaseEventVolumeInput input,
			Pair<DateTime, DateTime> timespan, boolean filterByAppTier) {
		
		Collection<String> transactions = input.getTransactions(serviceId);
		return getTransactionsFilter(serviceId, input, timespan, transactions, filterByAppTier);
	}
		
	protected GroupFilter getTransactionsFilter(String serviceId, BaseEventVolumeInput input,
		Pair<DateTime, DateTime> timespan, Collection<String> transactions,
		boolean filterByAppTier) {
		
		GroupFilter result;
		
		Collection<String> targetTransactions;
		
		if (transactions != null) {
			
			List<String> transactionsList = new ArrayList<String>();
			
			for (String transaction : transactions) {
				
				TopTransactionProcessor processor;
				
				if (transaction.equals(TOP_ERRORING_TRANSACTIONS)) {
					processor = new TopErrorTransactionProcessor();
				} else if (transaction.equals(SLOWEST_TRANSACTIONS)) {
					processor = new TopSlowestTransactionProcessor();
				} else if (transaction.equals(SLOWING_TRANSACTIONS)) {
					processor = new TopSlowingTransactionProcessor();
				} else if (transaction.equals(HIGHEST_VOLUME_TRANSACTIONS)) {
					processor = new TopVolumeTransactionProcessor();
				} else {
					processor = null;
				}
				
				if (processor != null) {
					
					Collection<String> topTransactions = getTopTransactions(serviceId, input, 
							timespan, processor);
					
					if (topTransactions == null) {
						return null;
					}
					
					transactionsList.addAll(topTransactions);
				} else {
					transactionsList.add(transaction);
				}
			}
			
			targetTransactions = transactionsList;			
		} else {
			targetTransactions = null;
		}
						
		result = getTransactionsFilter(serviceId, input, targetTransactions, filterByAppTier);
		
		return result;
	}
	
	private Collection<String> getAppTierPatterns(String serviceId, BaseEventVolumeInput input) {
		
		if (!FILTER_TX_BY_APP_LABEL) {
			return null;
		}
		
		Collection<String> apps = input.getApplications(apiClient,
				getSettingsData(serviceId), serviceId, true, true);
		
		if (CollectionUtil.safeIsEmpty(apps)) {
			return null;
		}
		
		List<Category> tiers = getSettingsData(serviceId).tiers;

		if (CollectionUtil.safeIsEmpty(tiers)) { 
			return null;
		}
		
		List<String> result = new ArrayList<String>();
		
		for (String app : apps) {
				
			if (!EnvironmentsFilterInput.isLabelApp(app)) {
				continue;
			}
				
			String name = EnvironmentsFilterInput.getLabelAppName(app);
				
			for (Category tier : tiers) {
				
				if (CollectionUtil.safeIsEmpty(tier.names)) {
					continue;
				}
				
				if (!CollectionUtil.safeContains(tier.labels, name)) {
					continue;
				}
				
				for (String tierClass : tier.names) {
					
					String regex = GroupSettings.REGEX_ESCAPE + REGEX_STARTS_WITH  
						+ tierClass + GroupSettings.REGEX_ESCAPE;
					
					result.add(regex);
				}
			}
		} 
		
		return result;
	}
	
	private GroupFilter getTransactionsFilter(String serviceId, 
			BaseEventVolumeInput input, Collection<String> transactions
			, boolean filterByTiers) {
		
		GroupFilter result;
		
		Collection<String> tierPatterns;
		
		if (filterByTiers) {
			tierPatterns = getAppTierPatterns(serviceId, input);
		} else {
			tierPatterns = null;
		}
		
		if ((!CollectionUtil.safeIsEmpty(transactions)) 
		|| (!CollectionUtil.safeIsEmpty(tierPatterns))) {
			
			GroupSettings transactionGroups = getSettingsData(serviceId).transactions;
			
			List<String> filterValues = new ArrayList<String>();
			
			if (tierPatterns != null) {
				filterValues.addAll(tierPatterns);
			} 
			
			if (transactions != null) {	
				if (transactionGroups != null) {
					filterValues.addAll(transactionGroups.expandList(transactions));
				} else {
					filterValues.addAll(transactions);
				}
			} 
			
			result = GroupFilter.from(filterValues);
		} else {
			result = null;
		}
		
		return result;
	}
	
	protected EventFilter getEventFilter(String serviceId, BaseEventVolumeInput input, 
		Pair<DateTime, DateTime> timespan) {
		
		GroupFilter transactionsFilter = getTransactionsFilter(serviceId, 
			input, timespan, false);
		
		Categories categories = getSettings(serviceId).getCategories();	

		Collection<String> allowedTypes;
		
		if (input.allowedTypes != null) {
			if (GrafanaFunction.VAR_ALL.contains(input.allowedTypes)) {
				allowedTypes = Collections.emptyList();
			} else {
				allowedTypes = toArray(input.allowedTypes);
			}
		} else {
			GeneralSettings generalSettings = getSettingsData(serviceId).general;
			
			if (generalSettings != null) {
				allowedTypes = generalSettings.getDefaultTypes();
			} else {
				allowedTypes = Collections.emptyList();
			}
		}
		
		Set<String> labels = new HashSet<String>(input.geLabels(serviceId));
		
		Collection<String> apps = input.getApplications(apiClient, getSettingsData(serviceId), serviceId,
			true, true);	
		
		for (String app : apps) {
			if (EnvironmentsFilterInput.isLabelApp(app)) {
				String appLabel = EnvironmentsFilterInput.getLabelAppName(app);
				String appLabelName = InfraUtil.toTierLabelName(appLabel, CategoryType.app);
				labels.add(appLabelName);
			}
		}
		
		Collection<String> eventLocations = VariableInput.getServiceFilters(input.eventLocations, 
			serviceId, true);
	
		return EventFilter.of(getTypes(serviceId, input), allowedTypes, 
				input.getIntroducedBy(serviceId), eventLocations, transactionsFilter,
				labels, input.labelsRegex, input.firstSeen, categories, input.searchText, 
				input.transactionSearchText);
	}
	
	protected Collection<Transaction> getTransactions(String serviceId, String viewId,
			Pair<DateTime, DateTime> timeSpan,
			BaseEventVolumeInput input, String searchText) {
		return getTransactions(serviceId, viewId, timeSpan, input, searchText, 0, 0);	
	}
	
	protected Collection<TransactionGraph> getTransactionGraphs(BaseEventVolumeInput input, String serviceId, String viewId, 
			Pair<DateTime, DateTime> timeSpan, String searchText) {
		
		return getTransactionGraphs(input, serviceId, viewId, timeSpan, 
			searchText, 0, 0);
	}
	
	protected GraphResolution getResolution(Pair<DateTime, DateTime> timeSpan) {
				
		long delta = timeSpan.getSecond().getMillis() - timeSpan.getFirst().getMillis();
		
		if (delta > H8_THRESHOLD) { 
			return GraphResolution.H8;
		} 
		
		if (delta > H1_THRESHOLD) { 
			return GraphResolution.H1;
		} 
		
		if (delta > M5_THRESHOLD) {
			return GraphResolution.M5;
		}
		
		return GraphResolution.M1;	
	}
	
	protected Collection<TransactionGraph> getTransactionGraphs(BaseEventVolumeInput input, String serviceId, String viewId, 
			Pair<DateTime, DateTime> timeSpan, String searchText,
			int activeTimespan, int baselineTimespan) {
		
		GroupFilter transactionsFilter = null;
		
		if (input.hasTransactions()) {			
			transactionsFilter = getTransactionsFilter(serviceId, input, timeSpan, true);

			if (transactionsFilter == null) {
				return Collections.emptyList();
			}
		}
		
		Pair<String, String> fromTo = TimeUtil.toTimespan(timeSpan);
		GraphResolution graphResolution = getResolution(timeSpan);
		
		TransactionsGraphRequest.Builder builder = TransactionsGraphRequest.newBuilder().setServiceId(serviceId)
			.setViewId(viewId).setFrom(fromTo.getFirst()).setTo(fromTo.getSecond())
				.setResolution(graphResolution);
				
		applyFilters(input, serviceId, builder);

		Response<TransactionsGraphResult> response = ApiCache.getTransactionsGraph(apiClient, 
			serviceId, input, getSettingsData(serviceId), baselineTimespan, activeTimespan, builder.build());
				
		validateResponse(response);
		
		if ((response.data == null) || (response.data.graphs == null)) { 

			return Collections.emptyList();
		}
		
		Collection<TransactionGraph> result;
		
		if ((input.hasTransactions() || (searchText != null))) {
			result = new ArrayList<TransactionGraph>(response.data.graphs.size());
			
			for (TransactionGraph transaction : response.data.graphs) {
				Pair<String, String> nameAndMethod = getFullNameAndMethod(transaction.name);
				
				if (filterTransaction(transactionsFilter, searchText, 
					nameAndMethod.getFirst(), nameAndMethod.getSecond())) {
					continue;
				}
				
				result.add(transaction);
			}
			
		} else {
			result = response.data.graphs;
		}
		
		return result;
	}
	
	protected Collection<Transaction> getTransactions(String serviceId, String viewId,
		Pair<DateTime, DateTime> timeSpan,
		BaseEventVolumeInput input, String searchText, 
		int activeTimespan, int baselineTimespan) {
		
		Pair<String, String> fromTo = TimeUtil.toTimespan(timeSpan);
		
		TransactionsVolumeRequest.Builder builder = TransactionsVolumeRequest.newBuilder().setServiceId(serviceId)
				.setViewId(viewId).setFrom(fromTo.getFirst()).setTo(fromTo.getSecond()).setRaw(true);
		
		applyFilters(input, serviceId, builder);
		
		Response<TransactionsVolumeResult> response = ApiCache.getTransactionsVolume(apiClient, serviceId,
				input, getSettingsData(serviceId),
				activeTimespan, baselineTimespan, builder.build());
		
		if (response.isBadResponse()) {
			System.err.println("Transnaction volume for service " + serviceId + " code: " + response.responseCode);
		}
		
		if ((response.data == null) || (response.data.transactions == null)) {
			return null;
		}
		
		Collection<Transaction> result;
		
		if ((input.hasTransactions() || (searchText != null))) {	
			
			result = new ArrayList<Transaction>(response.data.transactions.size());
			
			GroupFilter transactionsFilter = getTransactionsFilter(serviceId,
				input, timeSpan, true);

			for (Transaction transaction : response.data.transactions) {
				Pair<String, String> nameAndMethod = getFullNameAndMethod(transaction.name);
				
				if (filterTransaction(transactionsFilter, 
					searchText, nameAndMethod.getFirst(), nameAndMethod.getSecond())) {
					continue;
				}
				
				result.add(transaction);
			}
			
		} else {
			result = response.data.transactions;
		}
		
		return result;
	}
	
	/**
	 * @param seriesName
	 *            - needed by child classes
	 * @param volumeType
	 */
	protected String getSeriesName(BaseGraphInput input, String seriesName, String serviceId,
			Collection<String> serviceIds) {
		
		return getServiceValue(input.view, serviceId, serviceIds);
	}
	
	protected void sortSeriesValues(List<List<Object>> seriesValues,
			Collection<List<List<Object>>> servicesValues) {
			
		seriesValues.sort(new Comparator<List<Object>>() {

			@Override
			public int compare(List<Object> o1, List<Object> o2)
			{
				int o1Index = -1;
				int o2Index = -1;
					
				for (List<List<Object>> serviceValues : servicesValues) {
						
					if (o1Index == -1) {
						o1Index =  serviceValues.indexOf(o1);
					}
					
					if (o2Index == -1) {
						o2Index =  serviceValues.indexOf(o2);
					}
				}
				
				return Integer.compare(o1Index, o2Index);
			}
		});
	}
	
	protected void sortApplicationsByProcess(String serviceId, List<String> apps, 
		Collection<String> serversFilter, Collection<String> deploymentsFilter) {
		
		Response<JvmsResult> response =  ApiCache.getProcesses(apiClient, serviceId, true);
		
		if ((response == null) || (response.isBadResponse()) || (response.data == null) ||
			(response.data.clients == null)) {
			return;
		}
		
		Map<String, Integer> processMap = new HashMap<String, Integer>();
		
		for (Jvm jvm : response.data.clients) {
			
			if (jvm.pids == null) {
				continue;
			}
			
			if ((!CollectionUtil.safeIsEmpty(serversFilter)) &&
				(!serversFilter.contains(jvm.machine_name))) {
				continue;
			}
			
			if ((!CollectionUtil.safeIsEmpty(deploymentsFilter)) &&
			(!deploymentsFilter.contains(jvm.deployment_name))) {
					continue;
			}
			
			Integer newValue;
			Integer existingValue = processMap.get(jvm.application_name);
			
			if (existingValue != null) {
				newValue = existingValue.intValue() + jvm.pids.size();
			} else {
				newValue = jvm.pids.size();
			}
			
			processMap.put(jvm.application_name, newValue);
		}
		
		apps.sort(new Comparator<String>() {

			@Override
			public int compare(String o1, String o2) {
				Integer a1 = processMap.get(o1);
				Integer a2 = processMap.get(o2);
				
				int i1;
				int i2;
				
				if (a1 != null) {
					i1 = a1.intValue();
				} else {
					i1 = 0;
				}
				
				if (a2 != null) {
					i2 = a2.intValue();
				} else {
					i2 = 0;
				}
				
				int delta = i2 -i1;
				
				if (delta != 0) {
					return delta;
				}
					
				return o1.compareTo(o2);
			}
		});
		
	}
	
	protected int compareEvents(EventResult o1, EventResult o2, 
		double o1RateDelta, double o2RateDelta,
		List<String> criticalExceptionList, int minThreshold) {
		
		boolean iso1Uncaught = o1.type.equals(UNCAUGHT_EXCEPTION); 
		boolean iso2Uncaught = o2.type.equals(UNCAUGHT_EXCEPTION); 

		int uncaughtDelta = Boolean.compare(iso2Uncaught, iso1Uncaught);
		
		if (uncaughtDelta != 0) {
			return uncaughtDelta;
		}
		
		int o1ExRank = criticalExceptionList.indexOf(o1.name); 
		int o2ExRank = criticalExceptionList.indexOf(o2.name); 
		
		int exRankDelta = Integer.compare(o2ExRank, o1ExRank);						

		if (exRankDelta != 0) {
			return exRankDelta;
		}
			
		int o1TypeRank = TYPE_RANK.indexOf(o1.type); 
		int o2TypeRank = TYPE_RANK.indexOf(o2.type); 

		int typeRankDelta = Integer.compare(o2TypeRank, o1TypeRank);
		
		if (typeRankDelta != 0) {
			return typeRankDelta;
		}	
		
		DateTime newThreshold = TimeUtil.now().minusDays(3);
		
		DateTime o1FirstSeen = TimeUtil.getDateTime(o1.first_seen);
		DateTime o2FirstSeen = TimeUtil.getDateTime(o2.first_seen);
		
		boolean o1IsNew = o1FirstSeen.isAfter(newThreshold);
		boolean o2IsNew = o2FirstSeen.isAfter(newThreshold);

		int newDelta = Boolean.compare(o2IsNew, o1IsNew);

		if (newDelta != 0) {
			return newDelta;
		}
		
		boolean o1Threshold = o1.stats.hits > minThreshold;
		boolean o2Threshold = o2.stats.hits > minThreshold;

		int thresholdDelta = Boolean.compare(o2Threshold, o1Threshold);

		if (thresholdDelta != 0) {
			return thresholdDelta;
		}
		
		int rateDelta = Double.compare(o2RateDelta, o1RateDelta);
		
		if (rateDelta != 0) {
			return rateDelta;
		}	
	
		int volDelta = Long.compare(o2.stats.hits, o1.stats.hits);
			
		return volDelta;
	}
	
	protected Graph getEventsGraph(String serviceId, String viewId, 
			ViewInput input, VolumeType volumeType, DateTime from, DateTime to) {
		
		return getEventsGraph(serviceId, viewId,  
			input, volumeType, from, to, 0, 0);
	}
	
	protected Graph getEventsGraph(String serviceId, String viewId,
			ViewInput input, VolumeType volumeType, DateTime from, DateTime to, 
			int baselineWindow, int activeWindow) {
		
		return getEventsGraph(serviceId, viewId,  
			input, volumeType, from, to, baselineWindow, activeWindow, true);
	}
	
	protected GraphSliceTask createGraphAsyncTask(String serviceId, String viewId, 
			ViewInput input, VolumeType volumeType, 
			DateTime from, DateTime to, int baselineWindow, int activeWindow, 
			int windowSlice, boolean cache) {
		
		GraphResolution graphResolution;
		
		if (windowSlice == ApiCache.NO_GRAPH_SLICE) {
			graphResolution = getResolution(Pair.of(from, to));
		} else {
			graphResolution = GraphResolution.H8;
		}
		
		GraphRequest.Builder builder = GraphRequest.newBuilder().setServiceId(serviceId).setViewId(viewId)
				.setGraphType(GraphType.view).setFrom(from.toString(dateTimeFormatter)).setTo(to.toString(dateTimeFormatter))
				.setVolumeType(volumeType).setRaw(true).setResolution(graphResolution).setBreakdown(true);
				//setWantedPointCount(pointsCount);
		
		applyFilters(input, serviceId, builder);
		
		GraphSliceTask task = new GraphSliceTask(builder, serviceId, viewId, 
			input, volumeType, from, to, baselineWindow, activeWindow,
			windowSlice, cache);
		
		return task;
	}
	
	protected Graph getEventsGraph(String serviceId,
			ViewInput input, VolumeType volumeType, DateTime from, DateTime to,
			int baselineWindow, int activeWindow, boolean bestRes) {	
		
		String viewId = getViewId(serviceId, input.view);
		
		if (viewId == null) {
			return null;
		}
		
		return getEventsGraph(serviceId, viewId, input,
			volumeType, from, to, baselineWindow, activeWindow, bestRes);
	}
	
	protected Graph getEventsGraph(String serviceId, String viewId, 
		ViewInput input, VolumeType volumeType, DateTime from, DateTime to,
		int baselineWindow, int activeWindow, boolean bestRes) {		
		
		Collection<GraphSliceTask> tasks = getGraphTasks(serviceId, viewId, 
			input, volumeType, from, to, baselineWindow, activeWindow, bestRes);

		Collection<GraphSliceTaskResult> graphTaskResults = executeGraphTasks(tasks, false);
		
		ArrayList<Graph> graphs = Lists.newArrayList();
		
		for (GraphSliceTaskResult graphSliceTaskResult : graphTaskResults) {
			graphs.addAll(graphSliceTaskResult.graphs);
		}
		
		Graph result = mergeGraphs(graphs);
					
		return result;
	}
	
	protected static void printGraph(Graph graph) {
		
		for (GraphPoint gp : graph.points) {
			System.out.println(gp.time + ", " + gp.stats.hits + ", " + gp.stats.invocations);
		}
		
	}
	
	protected Collection<GraphSliceTask> getGraphTasks(String serviceId, String viewId, 
			ViewInput input, VolumeType volumeType, DateTime from, DateTime to, 
			int baselineWindow, int activeWindow, boolean dynamicRes) {
		
		Pair<DateTime, DateTime> timespan = Pair.of(from, to);

		boolean sliceGraph;
		
		if (dynamicRes) {
			sliceGraph = TimeUtil.getTimespanMill(timespan) > H8_THRESHOLD;
		} else {
			sliceGraph = true;
		}
		
		List<SliceRequest> sliceRequests;
				
		if ((ApiCache.SLICE_GRAPHS) && (sliceGraph)
		&& (TimeUtil.getTimespanMill(timespan) > TimeUnit.DAYS.toMillis(2))) {
		
			Pair<DateTime, Integer> periodStart = TimeUtil.getPeriodStart(timespan, Interval.Day);
			
			Collection<Pair<DateTime, DateTime>> periods = TimeUtil.getTimespanPeriods(timespan,
				periodStart.getFirst(), periodStart.getSecond(), baselineWindow == 0);
			
			sliceRequests = new ArrayList<SliceRequest>(periods.size());
			
			int index = 0;
			
			for (Pair<DateTime, DateTime> period :periods) {
				
				boolean cache = (index > 0) && (index < periods.size() - 1);
				
				SliceRequest slice = new SliceRequest(period.getFirst(),
					period.getSecond(), cache);
				
				sliceRequests.add(slice);
				
				index++;
			}	
		} else {
			sliceRequests = Collections.singletonList(new SliceRequest(from, to, false));	
		}
		
		List<GraphSliceTask> tasks = new ArrayList<GraphSliceTask>(sliceRequests.size());
		
		int index = 0;
		
		for (SliceRequest sliceRequest : sliceRequests) {
			
			int sliceIndex;
			
			if (sliceGraph) {
				sliceIndex = index;
			} else {
				sliceIndex = ApiCache.NO_GRAPH_SLICE;
			}
				
			GraphSliceTask task = createGraphAsyncTask(serviceId, viewId,
				input, volumeType, 
				sliceRequest.from, sliceRequest.to, 
				baselineWindow, activeWindow, sliceIndex, sliceRequest.cache);
				
			index++;
			
			tasks.add(task);
		}
		
		return tasks;
	}
	
	/*
	private List<SliceRequest> getTimeSlices(DateTime from, DateTime to, int days, int pointCount) {
		
		List<SliceRequest> result = Lists.newArrayList();
		
		// First partial day (<2018-11-22T12:23:38.418+02:00, 2018-11-22T23:59:00.000+02:00>)
		
		result.add(new SliceRequest(from, from.plusDays(1).withTimeAtStartOfDay().minusMinutes(1), END_SLICE_POINT_COUNT));

		// Only full days (<2018-11-23T00:00:00.000+02:00, 2018-11-23T23:59:00.000+02:00>)
		for (int i = 1; i < days; i++)
		{
			DateTime fullDayStart = from.plusDays(i).withTimeAtStartOfDay(); 
			DateTime fullDayEnd = fullDayStart.plusDays(1).withTimeAtStartOfDay().minusMinutes(1);
			
			result.add(new SliceRequest(fullDayStart, fullDayEnd, pointCount));
		}
		
		// Last partial day (<2018-11-29T00:00:00.000+02:00, 2018-11-29T12:23:38.418+02:00>)
		
		result.add(new SliceRequest(to.withTimeAtStartOfDay(), to, END_SLICE_POINT_COUNT));
		
		return result;
	}
	*/
	
	protected static boolean timespanContains(DateTime start, DateTime end, DateTime value) {
		
		if ((value.getMillis() > start.getMillis()) 
		&& (value.getMillis() <= end.getMillis())) {

			return true;
		}
		
		return false;
	}
	
	protected Graph mergeGraphs(Collection<Graph> graphs) {
		
		if (graphs.size() == 0) {
			return null;
		}
		
		if (graphs.size() == 1) {
			return graphs.iterator().next();
		}
		
		Graph result = new Graph();
		Map<Long, GraphPoint> graphPoints = new TreeMap<Long, GraphPoint>();
		
		for (Graph graph : graphs) {
			
			if (graph == null) {
				continue;
			}
			
			if (result.id == null) {
				result.id = graph.id;
				result.type = graph.type;
			}
			
			for (GraphPoint gp : graph.points) {
				DateTime dateTime = TimeUtil.getDateTime(gp.time);
				graphPoints.put(Long.valueOf(dateTime.getMillis()), gp);
			}
		}
		
		result.points = new ArrayList<GraphPoint>(graphPoints.values());
		
		return result;
	}
	
	protected Collection<GraphSliceTaskResult> executeGraphTasks(Collection<GraphSliceTask> slices, boolean sync) {
		
		List<Callable<Object>> tasks = Lists.newArrayList(slices);
		
		Collection<Object> taskResults;
		
		if ((sync) || (tasks.size() == 1)) {
			
			taskResults = Lists.newArrayList();
			
			for (Callable<Object> task : tasks) {
				try {
					taskResults.add(task.call());
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		} else {	
			taskResults = executeTasks(tasks, true);	
		}
		
		List<GraphSliceTaskResult> result = Lists.newArrayList();
		
		for (Object taskResult : taskResults) {
			
			if (taskResult == null) {
				continue;
			}
			
			result.add((GraphSliceTaskResult)taskResult);
		}
			
		return result;
	}
	
	protected static void appendGraphStats(Map<String, EventResult> eventMap, Graph graph) {
		
		for (GraphPoint gp : graph.points) {
			
			if (gp.contributors == null) {
				continue;
			}
			
			for (GraphPointContributor gpc : gp.contributors) {
				
				EventResult event = eventMap.get(gpc.id);
				
				if (event == null) {
					continue;
				}
				
				if (event.stats == null) {
					event.stats = new MainEventStats();
				}
				
				event.stats.hits += gpc.stats.hits;
				event.stats.invocations += gpc.stats.invocations;
			}
		}
	}
	
	protected Pair<Map<String, EventResult>, Long> filterEvents(String serviceId, 
		Pair<DateTime, DateTime> timespan, BaseEventVolumeInput input, 
		Collection<EventResult> events) {
	
		EventFilter eventFilter = getEventFilter(serviceId, input, timespan);
		
		if (eventFilter == null) {
			return Pair.of(Collections.emptyMap(), Long.valueOf(0l));
		}
		
		long volume = 0;
		
		Map<String, EventResult> filteredEvents = new HashMap<String, EventResult>();
		
		for (EventResult event : events) {	
			
			if (eventFilter.filter(event)) {
				continue;
			}
			
			filteredEvents.put(event.id, event);
			volume += event.stats.hits;
		}
		
		Pair<Map<String, EventResult>, Long> result = Pair.of(filteredEvents, Long.valueOf(volume));		
		
		return result;
	}
	
	protected long applyGraphToEvents(Map<String, EventResult> eventListMap, 
		Graph graph, Pair<DateTime, DateTime> timespan) {
		
		long result = 0;
		
		for (GraphPoint gp : graph.points) {
			
			if (gp.contributors == null) {
				continue;
			}
			
			if (timespan != null) {
				
				DateTime gpTime = TimeUtil.getDateTime(gp.time);
				
				if (!timespanContains(timespan.getFirst(), timespan.getSecond(), gpTime)) {
					continue;
				}
			}
	
			for (GraphPointContributor gpc : gp.contributors) {		
				
				EventResult event = eventListMap.get(gpc.id);
				
				if (event != null) {
					event.stats.invocations += gpc.stats.invocations;
					event.stats.hits += gpc.stats.hits;
					result += gpc.stats.hits;
				}
			}
		}
		
		return result;
	}
		
	private Collection<EventResult> getEventListFromGraph(String serviceId, String viewId, ViewInput input,
		DateTime from, DateTime to, VolumeType volumeType) {
		
		Graph graph = getEventsGraph(serviceId, viewId, input, volumeType, from, to);
		
		if (graph == null) {
			return null;
		}
		
		Collection<EventResult> events = getEventList(serviceId, viewId, input, from, to);
		
		if (events == null) {
			return null;
		}
		
		Map<String, EventResult> eventsMap = getEventsMap(events);
		appendGraphStats(eventsMap, graph);
		
		return eventsMap.values();
	}
	
	protected EventsSlimVolumeResult getEventsVolume(String serviceId, String viewId, ViewInput input, DateTime from,
		DateTime to, VolumeType volumeType) {	
		
		EventsSlimVolumeRequest.Builder builder =
				EventsSlimVolumeRequest.newBuilder().setVolumeType(volumeType).setServiceId(serviceId).setViewId(viewId)
						.setFrom(from.toString(dateTimeFormatter)).setTo(to.toString(dateTimeFormatter))
						.setVolumeType(volumeType).setRaw(true);
		
		applyBuilder(builder, serviceId, viewId, TimeUtil.toTimespan(from, to), input);
		
		Response<EventsSlimVolumeResult> response =
				ApiCache.getEventVolume(apiClient, serviceId, input, 
				getSettingsData(serviceId), volumeType, builder.build());
		
		validateResponse(response);
		
		if ((response.data == null) || (response.data.events == null)) {
			return null;
		}
		
		return response.data;
	}
	
	private void applyVolumeToEvents(String serviceId, String viewId, ViewInput input, DateTime from, DateTime to,
			VolumeType volumeType, Map<String, EventResult> eventsMap) {
		
		EventsSlimVolumeResult eventsSlimVolumeResult = getEventsVolume(serviceId, viewId, input, from, to, volumeType);
		
		if (eventsSlimVolumeResult == null) {
			return;
		}
		
		for (EventSlimResult eventSlimResult : eventsSlimVolumeResult.events) {
			
			EventResult event = eventsMap.get(eventSlimResult.id);
			
			if (event == null) {
				continue;
			}
			
			event.stats.hits = eventSlimResult.stats.hits;
			event.stats.invocations = eventSlimResult.stats.invocations;
		}
	}
	
	public Collection<EventResult> getEventList(String serviceId, String viewId, ViewInput input, DateTime from,
			DateTime to)
	{
		return getEventList(serviceId, viewId, input, from, to, false);
	}
	
	public Collection<EventResult> getEventList(String serviceId, String viewId, ViewInput input, DateTime from,
			DateTime to, boolean copyStats) {
		
		EventsRequest.Builder builder = EventsRequest.newBuilder().setRaw(true).setBreakdown(true);
		applyBuilder(builder, serviceId, viewId, TimeUtil.toTimespan(from, to), input);
		
		Response<?> response = ApiCache.getEventList(apiClient, serviceId, input,
			getSettingsData(serviceId), builder.build());
		
		validateResponse(response);
		
		List<EventResult> events;
		
		if (response.data instanceof EventsResult) {
			events = ((EventsResult)(response.data)).events;
		} else {
			return null;
		}
		
		if (events == null) {
			return null;
		}
		
		return cloneEvents(events, copyStats);
		
	}
	
	protected Collection<EventResult> cloneEvents(Collection<EventResult> events, boolean copyStats) {
		
		List<EventResult> result = new ArrayList<EventResult>(events.size());
		
		for (EventResult event : events) {
			
			EventResult clone = (EventResult)event.clone();
			
			if (!copyStats) {
				clone.stats = new MainEventStats();
			}
			
			result.add(clone);
		}
		
		return result;
	}
	
	
	public static Map<DeterminantKey, Map<String, EventResult>> getEventsMapByKey(Collection<EventResult> events) {
		Map<DeterminantKey, Map<String, EventResult>> keyToEventMap = Maps.newHashMap();
		
		for (EventResult event : events) {
			if (CollectionUtil.safeIsEmpty(event.stats.contributors)) {
				safePutEventToKeysMap(keyToEventMap, DeterminantKey.Empty, event);
			}
			
			for (Stats contributor : event.stats.contributors) {
				
				DeterminantKey determinantKey = new DeterminantKey(contributor.machine_name,
						contributor.application_name, contributor.deployment_name);
				
				EventResult contributorEventResult = (EventResult) event.clone();
				
				contributorEventResult.stats = new MainEventStats();
				
				safePutEventToKeysMap(keyToEventMap, determinantKey, contributorEventResult);
			}
		}
		
		return keyToEventMap;
	}
	
	private static void safePutEventToKeysMap(Map<DeterminantKey, Map<String, EventResult>> keyToEventMap,
			DeterminantKey determinantKey, EventResult event) {
		
		Map<String, EventResult> currentDeterminant = keyToEventMap.get(determinantKey);
		
		if (currentDeterminant == null) {
			currentDeterminant = Maps.newHashMap();
			
			keyToEventMap.put(determinantKey, currentDeterminant);
		}
		
		currentDeterminant.put(event.id, event);
	}
	
	protected static Map<String, EventResult> getEventsMap(Collection<EventResult> events) {
		return getEventsMap(events, true);
	}
	
	protected static Map<String, EventResult> getEventsMap(Collection<EventResult> events, boolean allowNoHits) {
		
		Map<String, EventResult> result = new HashMap<String, EventResult>();
		
		for (EventResult event : events) {
			if ((allowNoHits) || (event.stats.hits > 0)) {
				result.put(event.id, event);
			}
		}
		
		return result;
	}
	
	protected Map<String, EventResult> getEventMap(String serviceId, ViewInput input, DateTime from, DateTime to,
			VolumeType volumeType) {
		return getEventMap(serviceId, input, from, to, volumeType, false);
	}
	
	protected Map<String, EventResult> getEventMap(String serviceId, ViewInput input, DateTime from, DateTime to,
			VolumeType volumeType, boolean useGraph) {
		
		String viewId = getViewId(serviceId, input.view);
		
		if (viewId == null) {
			return null;
		}
		
		Collection<EventResult> events;
		
		Map<String, EventResult> result = new HashMap<String, EventResult>();
		
		if (volumeType != null) {
			
			if (useGraph) {
				events = getEventListFromGraph(serviceId, viewId, input, from, to, volumeType);	
			} else {
				events = getEventList(serviceId, viewId, input, from, to);
				
				if (events != null) {
					applyVolumeToEvents(serviceId, viewId, input, from, to, 
						volumeType, getEventsMap(events));
				}
			}
			
		} else {
			events = getEventList(serviceId, viewId, input, from, to);
		}
		
		if (events == null) {
			return null;	
		}
		
		result = getEventsMap(events);
		
		return result;
	}
	
	protected List<Object> executeTasks(Collection<Callable<Object>> tasks, boolean queryPool) {	
		Executor executor;
		
		if (queryPool) {
			executor = GrafanaThreadPool.getQueryExecutor(apiClient);
		} else {
			executor  = GrafanaThreadPool.getFunctionExecutor(apiClient);
		} 
		
		CompletionService<Object> completionService = new ExecutorCompletionService<Object>(executor);
		
		for (Callable<Object> task : tasks)	{
			completionService.submit(task);
		}
		
		List<Object> result = new ArrayList<Object>();
		
		int received = 0;
		
		while (received < tasks.size()) {
			try {
				Future<Object> future = completionService.take();
				
				received++;
				Object asynResult = future.get();
				result.add(asynResult);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		
		return result;
	}
	
	protected void applyFilters(EnvironmentsFilterInput input, String serviceId,
			TimeframeRequest.Builder builder) {
		
		Collection<String> apps = input.getApplications(apiClient,
			getSettingsData(serviceId), serviceId, true, false);
		
		for (String app : apps) {
			builder.addApp(app);
		}
		
		Collection<String> deps = input.getDeployments(serviceId, apiClient);
		
		for (String dep : deps) {
			builder.addDeployment(dep);
		}
		
		Collection<String> servers = input.getServers(serviceId); 
		
		for (String server : servers) {
			builder.addServer(server);
		}
	}
	
	protected SummarizedView getView(String serviceId, String viewName) {
		
		if ((viewName.length() == 0) || (viewName.startsWith(GRAFANA_VAR_PREFIX))) {
			return null;
		}
		
		ViewsRequest request = ViewsRequest.newBuilder().setServiceId(serviceId).setViewName(viewName).build();
		
		Response<ViewsResult> response = ApiCache.getView(apiClient, serviceId, viewName, request);
		
		if ((response.isBadResponse()) ||	(response.data == null) || (response.data.views == null) ||
			(response.data.views.size() == 0)) {
			return null;
		}
		
		SummarizedView result = response.data.views.get(0);
		
		return result;
	}
	
	protected static String getServiceValue(String value, String serviceId) {
		return value + SERVICE_SEPERATOR + serviceId;
	}
	
	protected static String getServiceValue(String value, String serviceId, Collection<String> serviceIds) {
		
		if (serviceIds.size() == 1) {
			return value;
		} else {
			return getServiceValue(value, serviceId);
		}
	}
	
	public static String getViewName(String name) {
	
		String result;
		
		if ((name != null) && (!name.isEmpty() && (!name.startsWith("$")))) { 
			result = name;
		} else {
			result = ALL_EVENTS;
		}
		
		return result;
		
	}
	
	protected String getViewId(String serviceId, String name) {
		
		String viewName = getViewName(name);
		
		SummarizedView view = getView(serviceId, viewName);
		
		if (view == null)
		{
			return null;
		}
		
		return view.id;
	}
	
	protected ViewInput getInput(ViewInput input) {
						
		if (!input.varTimeFilter) {
			return input;
		}
		
		Gson gson = new Gson();
		String json = gson.toJson(input);
		
		ViewInput result = gson.fromJson(json, input.getClass());
		
		if (input.timeFilter != null) {
			Pair<DateTime, DateTime> timespan = TimeUtil.getTimeFilter(input.timeFilter);
			result.timeFilter = TimeUtil.getTimeFilter(timespan);	
		} 
		
		return result;
		
	}
	
	protected static String formatLongValue(long value) {
		
		if (value > 1000000000) {
			return singleDigitFormatter.format(value / 1000000000.0) + "B";
		}
		
		if (value > 1000000) {
			return singleDigitFormatter.format(value / 1000000.0) + "M";
		}
		
		if (value > 1000) {
			return singleDigitFormatter.format(value / 1000.0) + "K";
		}
			
		return String.valueOf(value);
	}
	
	protected String formatMilli(Double mill) {
		return singleDigitFormatter.format(mill.doubleValue()) + "ms";
	}
	
	protected static String formatRate(double value, boolean doubleDigit) {
		
		DecimalFormat df;
		
		if (doubleDigit) {
			df = doubleDigitFormatter;
		} else {
			df = singleDigitFormatter; 
		}
		
		String result;
		String strValue = df.format(value * 100) + "%";
		
		if (strValue.startsWith("0.")) {
			result = strValue.substring(1);
		} else {
			result = strValue;
		}
		
		return result;
	} 
	
	protected Series createGraphSeries(String name, long volume) {
		return createGraphSeries(name, volume, new ArrayList<List<Object>>());
	}	
	
	protected Series createGraphSeries(String name, long volume, List<List<Object>> values) {
		
		Series result = new Series();
		
		String seriesName;

		if (volume > 0) {
			seriesName = String.format("%s (%s)", name, formatLongValue(volume));
		} else {
			seriesName = name;
		}
		
		result.name = EMPTY_NAME;
		result.columns = Arrays.asList(new String[] { TIME_COLUMN, seriesName });
		result.values = values;
		
		return result;
	}

	protected List<Series> createSingleStatSeries(Pair<DateTime, DateTime> timespan, Object singleStat) {
		
		Series series = new Series();
		
		series.name = SERIES_NAME;
		series.columns = Arrays.asList(new String[] { TIME_COLUMN, SUM_COLUMN });
		
		Long time = Long.valueOf(timespan.getSecond().getMillis());
		series.values = Collections.singletonList(Arrays.asList(new Object[] { time, singleStat }));
		
		return Collections.singletonList(series);
	}
	
	public abstract List<Series> process(FunctionInput functionInput);
}
