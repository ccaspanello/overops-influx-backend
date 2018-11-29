package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.event.Location;
import com.takipi.api.client.data.event.Stats;
import com.takipi.api.client.data.metrics.Graph;
import com.takipi.api.client.data.metrics.Graph.GraphPoint;
import com.takipi.api.client.data.metrics.Graph.GraphPointContributor;
import com.takipi.api.client.data.transaction.Transaction;
import com.takipi.api.client.data.view.SummarizedView;
import com.takipi.api.client.request.TimeframeRequest;
import com.takipi.api.client.request.ViewTimeframeRequest;
import com.takipi.api.client.request.event.EventsRequest;
import com.takipi.api.client.request.event.EventsSlimVolumeRequest;
import com.takipi.api.client.request.metrics.GraphRequest;
import com.takipi.api.client.request.transaction.TransactionsVolumeRequest;
import com.takipi.api.client.request.view.ViewsRequest;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.event.EventSlimResult;
import com.takipi.api.client.result.event.EventsResult;
import com.takipi.api.client.result.event.EventsSlimVolumeResult;
import com.takipi.api.client.result.event.EventsVolumeResult;
import com.takipi.api.client.result.metrics.GraphResult;
import com.takipi.api.client.result.transaction.TransactionsVolumeResult;
import com.takipi.api.client.result.view.ViewsResult;
import com.takipi.api.client.util.validation.ValidationUtil.GraphType;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.input.BaseGraphInput;
import com.takipi.integrations.grafana.input.EnvironmentsFilterInput;
import com.takipi.integrations.grafana.input.EnvironmentsInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.ViewInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.GrafanaSettings;
import com.takipi.integrations.grafana.settings.GroupSettings.GroupFilter;
import com.takipi.integrations.grafana.util.ApiCache;
import com.takipi.integrations.grafana.util.TimeUtil;

public abstract class GrafanaFunction
{
	
	private static int MAX_COMBINE_SERVICES = 3;
	
	public interface FunctionFactory
	{
		public GrafanaFunction create(ApiClient apiClient);
		
		public Class<?> getInputClass();
		
		public String getName();
	}
	
	protected static final String TIMER = "Timer";
	protected static final String RESOVED = "Resolved";
	protected static final String HIDDEN = "Hidden";
	
	protected static final String SERIES_NAME = "events";
	protected static final String EMPTY_NAME = "";
	
	protected static final String SUM_COLUMN = "sum";
	protected static final String TIME_COLUMN = "time";
	protected static final String KEY_COLUMN = "key";
	protected static final String VALUE_COLUMN = "value";
	
	public static final String GRAFANA_SEPERATOR_RAW = "|";
	public static final String GRAFANA_SEPERATOR = Pattern.quote(GRAFANA_SEPERATOR_RAW);
	public static final String ARRAY_SEPERATOR = Pattern.quote(",");
	public static final String SERVICE_SEPERATOR = ": ";
	public static final String GRAFANA_VAR_PREFIX = "$";
	
	public static final String ALL = "all";
	public static final List<String> VAR_ALL = Arrays.asList(new String[] { "*", ALL });
	
	protected static final char QUALIFIED_DELIM = '.';
	protected static final char INTERNAL_DELIM = '/';
	protected static final String TRANS_DELIM = "#";
	
	private static final DateTimeFormatter fmt = ISODateTimeFormat.dateTime().withZoneUTC();
	
	protected static final Map<String, String> TYPES_MAP;
	
	static
	{
		TYPES_MAP = new HashMap<String, String>();
		
		TYPES_MAP.put("Logged Error", "ERR");
		TYPES_MAP.put("Logged Warning", "WRN");
		TYPES_MAP.put("Caught Exception", "CEX");
		TYPES_MAP.put("Uncaught Exception", "UNC");
		TYPES_MAP.put("Swallowed Exception", "SWL");
		TYPES_MAP.put("Timer", "TMR");
		TYPES_MAP.put("HTTP_ERROR", "HTTP");
		
	}
	
	protected final ApiClient apiClient;
	
	public GrafanaFunction(ApiClient apiClient)
	{
		this.apiClient = apiClient;
	}
	
	public static String toQualified(String value)
	{
		return value.replace(INTERNAL_DELIM, QUALIFIED_DELIM);
	}
	
	protected String toTransactionName(Location location)
	{
		String transactionName =
				location.class_name + TRANS_DELIM + location.method_name + TRANS_DELIM + location.method_desc;
		return transactionName;
	}
	
	protected static Pair<String, String> getTransactionNameAndMethod(String name)
	{
		
		String[] parts = name.split(TRANS_DELIM);
		
		if (parts.length == 1)
		{
			return Pair.of(getSimpleClassName(toQualified(name)), null);
		}
		else
		{
			return Pair.of(getSimpleClassName(toQualified((parts[0]))), parts[1]);
		}
	}
	
	protected static Pair<String, String> getFullNameAndMethod(String name)
	{
		
		String[] parts = name.split(TRANS_DELIM);
		
		if (parts.length == 1)
		{
			return Pair.of(toQualified(name), null);
		}
		else
		{
			return Pair.of(toQualified((parts[0])), parts[1]);
		}
	}
	
	protected static String getTransactionName(String name, boolean includeMethod)
	{
		
		Pair<String, String> nameAndMethod = getTransactionNameAndMethod(name);
		
		if ((includeMethod) && (nameAndMethod.getSecond() != null))
		{
			return nameAndMethod.getFirst() + QUALIFIED_DELIM + nameAndMethod.getSecond();
		}
		else
		{
			return nameAndMethod.getFirst();
		}
	}
	
	public static String getSimpleClassName(String className)
	{
		String qualified = toQualified(className);
		int sepIdex = Math.max(qualified.lastIndexOf(QUALIFIED_DELIM) + 1, 0);
		String result = qualified.substring(sepIdex, qualified.length());
		return result;
	}
	
	protected void validateResponse(Response<?> response)
	{
		if ((response.isBadResponse()) || (response.data == null))
		{
			System.err.println("EventsResult code " + response.responseCode);
		}
		
	}
	
	protected static String formatLocation(Location location)
	{
		return getSimpleClassName(location.class_name) + QUALIFIED_DELIM + location.method_name;
	}
	
	protected Collection<String> getServiceIds(EnvironmentsInput input)
	{
		
		List<String> serviceIds = input.getServiceIds();
		
		if (serviceIds.size() == 0)
		{
			return Collections.emptyList();
		}
		
		List<String> result = serviceIds.subList(0, Math.min(MAX_COMBINE_SERVICES, serviceIds.size()));
		
		return result;
	}
	
	private void applyBuilder(ViewTimeframeRequest.Builder builder, String serviceId, String viewId,
			Pair<String, String> timeSpan, ViewInput request)
	{
		
		builder.setViewId(viewId).setServiceId(serviceId).setFrom(timeSpan.getFirst()).setTo(timeSpan.getSecond());
		
		applyFilters(request, serviceId, builder);
	}
	
	public static boolean filterTransaction(GroupFilter filter, String className, String methodName) {
		
		if ((filter == null) || ((filter.values.size() == 0) && (filter.patterns.size() == 0))) {
			return false;
		}
		
		String simpleClassName = getSimpleClassName(className);
		String simpleClassAndMethod;
		
		if (methodName != null) {
			simpleClassAndMethod = simpleClassName + QUALIFIED_DELIM + methodName;
		} else {
			simpleClassAndMethod = null;
		}
				
		for (String value : filter.values)
		{
			if ((simpleClassAndMethod != null) && (value.equals(simpleClassAndMethod))) {
				return false;
			}
			
			if (value.equals(simpleClassName)) {
				return false;
			}
			
		}
		
		String fullName = className + QUALIFIED_DELIM + methodName;
		
		for (Pattern pattern : filter.patterns)
		{
			if (pattern.matcher(fullName).find())
			{
				return false;
			}
		}
		
		return true;
	}
	
	protected Collection<Transaction> getTransactions(String serviceId, String viewId,
			Pair<DateTime, DateTime> timeSpan,
			ViewInput input)
	{
		
		Pair<String, String> fromTo = TimeUtil.toTimespan(timeSpan);
		
		TransactionsVolumeRequest.Builder builder = TransactionsVolumeRequest.newBuilder().setServiceId(serviceId)
				.setViewId(viewId).setFrom(fromTo.getFirst()).setTo(fromTo.getSecond());
		
		applyFilters(input, serviceId, builder);
		
		Response<TransactionsVolumeResult> response = ApiCache.getTransactionsVolume(apiClient, serviceId,
				input, builder.build());
		
		if (response.isBadResponse())
		{
			System.err.println("Transnaction volume for service " + serviceId + " code: " + response.responseCode);
		}
		
		if ((response.data == null) || (response.data.transactions == null))
		{
			return null;
		}
		
		Collection<Transaction> result;
		
		if (input.hasTransactions())
		{
			
			result = new ArrayList<Transaction>(response.data.transactions.size());
			Collection<String> transactions = input.getTransactions(serviceId);
			
			GroupFilter transactionsFilter = GrafanaSettings.getServiceSettings(apiClient, serviceId).getTransactionsFilter(transactions);

			for (Transaction transaction : response.data.transactions)
			{
				Pair<String, String> nameAndMethod = getFullNameAndMethod(transaction.name);
				
				if (filterTransaction(transactionsFilter, nameAndMethod.getFirst(), nameAndMethod.getSecond()))
				{
					continue;
				}
				
				result.add(transaction);
			}
			
		}
		else
		{
			result = response.data.transactions;
		}
		
		return result;
	}
	
	/**
	 * @param seriesName
	 *            - needed by child classes
	 * @param volumeType
	 */
	protected String getSeriesName(BaseGraphInput input, String seriesName, Object volumeType, String serviceId,
			Collection<String> serviceIds)
	{
		
		return getServiceValue(input.deployments, serviceId, serviceIds);
	}
	
	protected Graph getEventsGraph(ApiClient apiClient, String serviceId, String viewId, int pointsCount,
			ViewInput input, VolumeType volumeType, DateTime from, DateTime to) {
		return getEventsGraph(apiClient, serviceId, viewId, pointsCount, input, volumeType, from, to, 0, 0);
	}
	
	protected Graph getEventsGraph(ApiClient apiClient, String serviceId, String viewId, int pointsCount,
			ViewInput input, VolumeType volumeType, DateTime from, DateTime to, int baselineWindow, int activeWindow)
	{
		GraphRequest.Builder builder = GraphRequest.newBuilder().setServiceId(serviceId).setViewId(viewId)
				.setGraphType(GraphType.view).setFrom(from.toString(fmt)).setTo(to.toString(fmt))
				.setVolumeType(volumeType).setWantedPointCount(pointsCount).setRaw(true);
		
		applyFilters(input, serviceId, builder);
		
		Response<GraphResult> graphResponse = ApiCache.getEventGraph(apiClient, serviceId, input, volumeType,
				builder.build(), pointsCount, activeWindow, baselineWindow);
		
		if (graphResponse.isBadResponse())
		{
			return null;
		}
		
		GraphResult graphResult = graphResponse.data;
		
		if (graphResult == null)
		{
			return null;
		}
		
		if (CollectionUtil.safeIsEmpty(graphResult.graphs))
		{
			return null;
		}
		
		Graph result = graphResult.graphs.get(0);
		
		if (!viewId.equals(result.id))
		{
			return null;
		}
		
		if (CollectionUtil.safeIsEmpty(result.points))
		{
			return null;
		}
		
		return result;
	}
	
	private static void appendGraphStats(Map<String, EventResult> eventMap, Graph graph)
	{
		
		for (GraphPoint gp : graph.points)
		{
			
			if (gp.contributors == null)
			{
				continue;
			}
			
			for (GraphPointContributor gpc : gp.contributors)
			{
				
				EventResult event = eventMap.get(gpc.id);
				
				if (event == null)
				{
					continue;
				}
				
				if (event.stats == null)
				{
					event.stats = new Stats();
				}
				
				event.stats.hits += gpc.stats.hits;
				event.stats.invocations += gpc.stats.invocations;
			}
		}
	}
	
	private Collection<EventResult> getEventListFromGraph(String serviceId, String viewId, ViewInput input,
			DateTime from, DateTime to,
			VolumeType volumeType, int pointsCount)
	{
		
		Graph graph = getEventsGraph(apiClient, serviceId, viewId, pointsCount, input, volumeType, from, to);
		
		if (graph == null)
		{
			return null;
		}
		
		Collection<EventResult> events = getEventList(serviceId, viewId, input, from, to);
		
		if (events == null)
		{
			return null;
		}
		
		Map<String, EventResult> eventsMap = getEventsMap(events);
		appendGraphStats(eventsMap, graph);
		
		return eventsMap.values();
	}
	
	protected EventsSlimVolumeResult getEventsVolume(String serviceId, String viewId, ViewInput input, DateTime from,
			DateTime to,
			VolumeType volumeType)
	{	
		EventsSlimVolumeRequest.Builder builder =
				EventsSlimVolumeRequest.newBuilder().setVolumeType(volumeType).setServiceId(serviceId).setViewId(viewId)
						.setFrom(from.toString(fmt)).setTo(to.toString(fmt))
						.setVolumeType(volumeType).setRaw(true);
		
		applyBuilder(builder, serviceId, viewId, TimeUtil.toTimespan(from, to), input);
		Response<EventsSlimVolumeResult> response =
				ApiCache.getEventVolume(apiClient, serviceId, input, volumeType, builder.build());
		validateResponse(response);
		
		if ((response.data == null) || (response.data.events == null))
		{
			return null;
		}
		
		return response.data;
	}
	
	private void applyVolumeToEvents(String serviceId, String viewId, ViewInput input, DateTime from, DateTime to,
			VolumeType volumeType, Map<String, EventResult> eventsMap)
	{
		EventsSlimVolumeResult eventsSlimVolumeResult = getEventsVolume(serviceId, viewId, input, from, to, volumeType);
		
		if (eventsSlimVolumeResult == null) {
			return;
		}
		
		for (EventSlimResult eventSlimResult : eventsSlimVolumeResult.events)
		{
			EventResult event = eventsMap.get(eventSlimResult.id);
			
			if (event == null)
			{
				continue;
			}
			
			event.stats = eventSlimResult.stats;
		}
	}
	
	private Collection<EventResult> getEventList(String serviceId, String viewId, ViewInput input, DateTime from,
			DateTime to)
	{
		
		EventsRequest.Builder builder = EventsRequest.newBuilder().setRaw(true);
		applyBuilder(builder, serviceId, viewId, TimeUtil.toTimespan(from, to), input);
		Response<?> response = ApiCache.getEventList(apiClient, serviceId, input, builder.build());
		validateResponse(response);
		
		List<EventResult> events;
		
		if (response.data instanceof EventsVolumeResult)
		{
			events = ((EventsVolumeResult)(response.data)).events;
		}
		else if (response.data instanceof EventsResult)
		{
			events = ((EventsResult)(response.data)).events;
		}
		else
		{
			return null;
		}
		
		if (events == null)
		{
			return null;
		}
		
		List<EventResult> eventsCopy = new ArrayList<EventResult>(events.size());
		
		try
		{
			for (EventResult event : events)
			{
				EventResult clone = (EventResult)event.clone();
				clone.stats = new Stats();
				eventsCopy.add(clone);
			}
		}
		catch (CloneNotSupportedException e)
		{
			throw new IllegalStateException(e);
			
		}
		
		return eventsCopy;
	}
	
	private static Map<String, EventResult> getEventsMap(Collection<EventResult> events)
	{
		
		Map<String, EventResult> result = new HashMap<String, EventResult>();
		
		for (EventResult event : events)
		{
			result.put(event.id, event);
		}
		
		return result;
	}
	
	protected Map<String, EventResult> getEventMap(String serviceId, ViewInput input, DateTime from, DateTime to,
			VolumeType volumeType)
	{
		return getEventMap(serviceId, input, from, to, volumeType, 0);
	}
	
	protected Map<String, EventResult> getEventMap(String serviceId, ViewInput input, DateTime from, DateTime to,
			VolumeType volumeType, int pointsCount)
	{
		
		String viewId = getViewId(serviceId, input.view);
		
		if (viewId == null)
		{
			return null;
		}
		
		Collection<EventResult> events;
		
		Map<String, EventResult> result = new HashMap<String, EventResult>();
		
		if (volumeType != null)
		{
			if (pointsCount > 0)
			{
				events = getEventListFromGraph(serviceId, viewId, input, from, to, volumeType, pointsCount);
			}
			else
			{
				events = getEventList(serviceId, viewId, input, from, to);
				
				if (events != null) {
					applyVolumeToEvents(serviceId, viewId, input, from, to, 
						volumeType, getEventsMap(events));
				}
			}
		}
		else
		{
			events = getEventList(serviceId, viewId, input, from, to);
		}
		
		if (events == null)
		{
			return null;
			
		}
		
		result = getEventsMap(events);
		
		return result;
	}
	
	protected List<Object> executeTasks(Collection<Callable<Object>> tasks)
	{
		
		CompletionService<Object> completionService = new ExecutorCompletionService<Object>(GrafanaThreadPool.executor);
		
		for (Callable<Object> task : tasks)
		{
			completionService.submit(task);
		}
		
		List<Object> result = new ArrayList<Object>();
		
		int received = 0;
		
		while (received < tasks.size())
		{
			try
			{
				Future<Object> future = completionService.take();
				received++;
				Object asynResult = future.get();
				result.add(asynResult);
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		}
		
		return result;
	}
	
	protected void applyFilters(EnvironmentsFilterInput input, String serviceId, TimeframeRequest.Builder builder)
	{
		applyFilters(this.apiClient, input, serviceId, builder);
	}
	
	private void applyFilters(ApiClient apiClient, EnvironmentsFilterInput input, String serviceId,
			TimeframeRequest.Builder builder)
	{
		
		for (String app : input.getApplications(apiClient, serviceId))
		{
			builder.addApp(app);
		}
		
		for (String dep : input.getDeployments(serviceId))
		{
			builder.addDeployment(dep);
		}
		
		for (String server : input.getServers(serviceId))
		{
			builder.addServer(server);
		}
	}
	
	protected SummarizedView getView(String serviceId, String viewName)
	{
		
		if (viewName.startsWith(GRAFANA_VAR_PREFIX))
		{
			return null;
		}
		
		ViewsRequest request = ViewsRequest.newBuilder().setServiceId(serviceId).setViewName(viewName).build();
		
		Response<ViewsResult> response = ApiCache.getView(apiClient, serviceId, viewName, request);
		
		if ((response.isBadResponse()) ||	(response.data == null) || (response.data.views == null) ||
			(response.data.views.size() == 0))
		{
			return null;
		}
		
		SummarizedView result = response.data.views.get(0);
		
		return result;
	}
	
	protected static String getServiceValue(String value, String serviceId)
	{
		return value + SERVICE_SEPERATOR + serviceId;
	}
	
	protected static String getServiceValue(String value, String serviceId, Collection<String> serviceIds)
	{
		
		if (serviceIds.size() == 1)
		{
			return value;
		}
		else
		{
			return getServiceValue(value, serviceId);
		}
	}
	
	protected String getViewId(String serviceId, String viewName)
	{
		SummarizedView view = getView(serviceId, viewName);
		
		if (view == null)
		{
			return null;
		}
		
		return view.id;
	}
	
	protected List<Series> createSingleStatSeries(Pair<DateTime, DateTime> timespan, Object singleStat)
	{
		
		Series series = new Series();
		
		series.name = SERIES_NAME;
		series.columns = Arrays.asList(new String[] { TIME_COLUMN, SUM_COLUMN });
		
		Long time = Long.valueOf(timespan.getSecond().getMillis());
		series.values = Collections.singletonList(Arrays.asList(new Object[] { time, singleStat }));
		
		return Collections.singletonList(series);
	}
	
	public abstract List<Series> process(FunctionInput functionInput);
}
