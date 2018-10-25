package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.takipi.common.api.ApiClient;
import com.takipi.common.api.data.event.Location;
import com.takipi.common.api.data.metrics.Graph;
import com.takipi.common.api.data.service.SummarizedService;
import com.takipi.common.api.data.transaction.Transaction;
import com.takipi.common.api.data.view.SummarizedView;
import com.takipi.common.api.request.TimeframeRequest;
import com.takipi.common.api.request.ViewTimeframeRequest;
import com.takipi.common.api.request.event.EventsRequest;
import com.takipi.common.api.request.event.EventsVolumeRequest;
import com.takipi.common.api.request.metrics.GraphRequest;
import com.takipi.common.api.request.transaction.TransactionsVolumeRequest;
import com.takipi.common.api.result.event.EventResult;
import com.takipi.common.api.result.event.EventsResult;
import com.takipi.common.api.result.event.EventsVolumeResult;
import com.takipi.common.api.result.metrics.GraphResult;
import com.takipi.common.api.result.transaction.TransactionsVolumeResult;
import com.takipi.common.api.url.UrlClient.Response;
import com.takipi.common.api.util.CollectionUtil;
import com.takipi.common.api.util.Pair;
import com.takipi.common.api.util.ValidationUtil.GraphType;
import com.takipi.common.api.util.ValidationUtil.VolumeType;
import com.takipi.common.udf.util.ApiFilterUtil;
import com.takipi.common.udf.util.ApiViewUtil;
import com.takipi.integrations.grafana.input.EnvironmentsInput;
import com.takipi.integrations.grafana.input.FilterInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.ViewInput;
import com.takipi.integrations.grafana.output.Series;

public abstract class GrafanaFunction {

	public interface FunctionFactory {
		public GrafanaFunction create(ApiClient apiClient);
		public Class<?> getInputClass();
		public String getName();
	}

	protected static final String SERIES_NAME = "events";
	protected static final String EMPTY_NAME = "";

	protected static final String SUM_COLUMN = "sum";
	protected static final String TIME_COLUMN = "time";
	protected static final String KEY_COLUMN = "key";
	protected static final String VALUE_COLUMN = "value";

	public static final String GRAFANA_SEPERATOR = Pattern.quote("|");
	public static final String ARRAY_SEPERATOR = Pattern.quote(",");
	public static final String SERVICE_SEPERATOR = ": ";
	public static final String VAR_ALL = "*";

	private static final DateTimeFormatter fmt = ISODateTimeFormat.dateTime().withZoneUTC();
	
	protected final ApiClient apiClient;

	public GrafanaFunction(ApiClient apiClient) {
		this.apiClient = apiClient;
	}

	public static String toQualified(String value) {
		return value.replace('/', '.');
	}

	public static String getSimpleClassName(String className) {
		String qualified = toQualified(className);
		int sepIdex = Math.max(qualified.lastIndexOf('.') + 1, 0);
		String result = qualified.substring(sepIdex, qualified.length());
		return result;
	}

	protected void validateResponse(Response<?> response) {
		if ((response.isBadResponse()) || (response.data == null)) {
			throw new IllegalStateException("EventsResult code " + response.responseCode);
		}

	}

	protected static String formatLocation(Location location) {
		return getSimpleClassName(location.class_name) + "." + location.method_name;
	}

	protected String[] getServiceIds(EnvironmentsInput input) {
		String[] serviceIds = input.getServiceIds();

		if (serviceIds.length != 0) {
			return serviceIds;
		}

		List<SummarizedService> services = ApiFilterUtil.getEnvironments(apiClient);

		String[] result = new String[services.size()];

		for (int i = 0; i < services.size(); i++) {
			result[i] = services.get(i).id;
		}

		return result;
	}

	private void applyBuilder(ViewTimeframeRequest.Builder builder, String serviceId, String viewId,
			Pair<String, String> timeSpan, ViewInput request) {

		builder.setViewId(viewId).setServiceId(serviceId).setFrom(timeSpan.getFirst()).setTo(timeSpan.getSecond());

		applyFilters(request, serviceId, builder);
	}

	protected Collection<Transaction> getTransactions(String serviceId, String viewId, Pair<String, String> timeSpan,
			FilterInput input) {
		
		TransactionsVolumeRequest.Builder builder = TransactionsVolumeRequest.newBuilder().setServiceId(serviceId)
				.setViewId(viewId).setFrom(timeSpan.getFirst()).setTo(timeSpan.getSecond());

		applyFilters(input, serviceId, builder);

		Response<TransactionsVolumeResult> response = apiClient.get(builder.build());

		if (response.isBadResponse()) {
			throw new IllegalStateException(
					"Transnaction volume for service " + serviceId + " code: " + response.responseCode);
		}

		if ((response.data == null) || (response.data.transactions == null)) {
			return null;
		}

		Collection<Transaction> result;

		if (input.transactions != null) {

			result = new ArrayList<Transaction>(response.data.transactions.size());
			Collection<String> transactions = input.getTransactions(serviceId);

			for (Transaction transaction : response.data.transactions) {

				String entryPoint = getSimpleClassName(transaction.name);

				if ((transactions != null) && (!transactions.contains(entryPoint))) {
					continue;
				}

				result.add(transaction);
			}

		} else {
			result = response.data.transactions;
		}

		return result;
	}
	
	protected static Graph getEventsGraph(ApiClient apiClient, String serviceId, String viewId, int pointsCount,
			ViewInput input, VolumeType volumeType, DateTime from, DateTime to) {
		
		GraphRequest.Builder builder = GraphRequest.newBuilder().setServiceId(serviceId).setViewId(viewId)
				.setGraphType(GraphType.view).setFrom(from.toString(fmt)).setTo(to.toString(fmt))
				.setVolumeType(volumeType).setWantedPointCount(pointsCount);

		applyFilters(input, serviceId, builder);
		
		Response<GraphResult> graphResponse = apiClient.get(builder.build());

		if (graphResponse.isBadResponse()) {
			return null;
		}

		GraphResult graphResult = graphResponse.data;

		if (graphResult == null) {
			return null;
		}

		if (CollectionUtil.safeIsEmpty(graphResult.graphs)) {
			return null;
		}

		Graph graph = graphResult.graphs.get(0);

		if (!viewId.equals(graph.id)) {
			return null;
		}

		if (CollectionUtil.safeIsEmpty(graph.points)) {
			return null;
		}

		return graph;
	}
	
	protected Collection<EventResult> getEventList(String serviceId, ViewInput input, Pair<String, String> timeSpan,
			VolumeType volumeType) {
		 
		Map<String, EventResult> result = getEventMap(serviceId, input, timeSpan, volumeType);
		 
		if (result != null) {
			return result.values();
		} else {
			return null;
		}
	}
	
	protected Map<String, EventResult> getEventMap(String serviceId, ViewInput input, Pair<String, String> timeSpan,
			VolumeType volumeType) {

		String viewId = getViewId(serviceId, input.view);

		if (viewId == null) {
			return null;
		}

		List<EventResult> events;
		
		Map<String, EventResult> result = new HashMap<String, EventResult>();

		if (volumeType != null) {
			EventsVolumeRequest.Builder builder = EventsVolumeRequest.newBuilder().setVolumeType(volumeType);
			applyBuilder(builder, serviceId, viewId, timeSpan, input);
			Response<EventsVolumeResult> volumeResponse = apiClient.get(builder.build());
			validateResponse(volumeResponse);
			events = volumeResponse.data.events;
		} else {
			EventsRequest.Builder builder = EventsRequest.newBuilder();
			applyBuilder(builder, serviceId, viewId, timeSpan, input);
			Response<EventsResult> eventResponse = apiClient.get(builder.build());
			validateResponse(eventResponse);
			events = eventResponse.data.events;
		}
		
		if (events == null) {
			return null;
		}

		for (EventResult event: events) {
			result.put(event.id, event);
		}
		
		return result;
	}

	public static void applyFilters(FilterInput request, String serviceId, TimeframeRequest.Builder builder) {

		for (String app : request.getApplications(serviceId)) {
			builder.addApp(app);
		}

		for (String dep : request.getDeployments(serviceId)) {
			builder.addDeployment(dep);
		}

		for (String server : request.getServers(serviceId)) {
			builder.addServer(server);
		}
	}

	protected SummarizedView getView(String serviceId, String viewName) {
		SummarizedView view = ApiViewUtil.getServiceViewByName(apiClient, serviceId, viewName);
		return view;
	}
	
	protected static String getServiceValue(String value, String serviceId) {
		return value + SERVICE_SEPERATOR + serviceId;
	}
	
	protected static String getServiceValue(String value, String serviceId, String[] serviceIds) {
		
		if (serviceIds.length == 1) {
			return value;
		} else {
			return getServiceValue(value, serviceId);
		}
	}

	protected String getViewId(String serviceId, String viewName) {
		SummarizedView view = getView(serviceId, viewName);

		if (view == null) {
			return null;
		}

		return view.id;
	}

	public abstract List<Series> process(FunctionInput functionInput);
}
