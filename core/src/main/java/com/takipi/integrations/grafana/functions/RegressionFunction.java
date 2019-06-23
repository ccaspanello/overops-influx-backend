package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.google.common.base.Objects;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.deployment.SummarizedDeployment;
import com.takipi.api.client.data.metrics.Graph;
import com.takipi.api.client.result.deployment.DeploymentsResult;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.metrics.GraphResult;
import com.takipi.api.client.request.event.BreakdownType;
import com.takipi.api.client.util.regression.DeterminantKey;
import com.takipi.api.client.util.regression.RateRegression;
import com.takipi.api.client.util.regression.RegressionInput;
import com.takipi.api.client.util.regression.RegressionResult;
import com.takipi.api.client.util.regression.RegressionStringUtil;
import com.takipi.api.client.util.regression.RegressionUtil;
import com.takipi.api.client.util.regression.RegressionUtil.RegressionWindow;
import com.takipi.api.client.util.settings.RegressionSettings;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.input.BaseEventVolumeInput;
import com.takipi.integrations.grafana.input.EventFilterInput;
import com.takipi.integrations.grafana.input.EventsInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.RegressionsInput;
import com.takipi.integrations.grafana.input.RegressionsInput.RegressionType;
import com.takipi.integrations.grafana.input.ViewInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.ServiceSettings;
import com.takipi.integrations.grafana.util.ApiCache;
import com.takipi.integrations.grafana.util.EventLinkEncoder;
import com.takipi.integrations.grafana.util.TimeUtil;

public class RegressionFunction extends EventsFunction {	

	public static class Factory implements FunctionFactory {
		
		@Override
		public GrafanaFunction create(ApiClient apiClient) {
			return new RegressionFunction(apiClient);
		}
		
		@Override
		public Class<?> getInputClass() {
			return RegressionsInput.class;
		}
		
		@Override
		public String getName() {
			return "regressions";
		}
	}
	
	public static final int P1 = 2;
	public static final int P2 = 1;
	
	protected class RegressionData extends EventData {
		
		protected RegressionType type;
		protected RegressionResult regression;
		protected RateRegression regResult;
		protected RegressionInput input;
		protected Set<String> mergedIds;
		
		protected RegressionData(RateRegression regResult, RegressionInput input,
				EventResult event, RegressionType type) {
			super(event);
			this.type = type;
			this.regResult = regResult;
			this.input = input;
			this.regression = null;
			this.mergedIds = new HashSet<String>();
		}
			
		protected RegressionData(RateRegression regResult, RegressionInput input,
				RegressionResult regression, RegressionType type) {
			this(regResult, input, regression.getEvent(), type);
			this.regression = regression;
		}
		
		protected int getDelta() {
			
			if (regression == null) {
				return 0;
			}
			
			double baselineRate = (double) regression.getBaselineHits() 
					/ (double) regression.getBaselineInvocations()  * 100;
				
			double activeRate = (double) event.stats.hits
					/ (double) event.stats.invocations  * 100;
				
			double delta = activeRate - baselineRate;
					
			return (int)(delta);
		}
		
		public String getDescription() {
			
			String ratio;
			
			if (event.stats.invocations > 0) {
				ratio = (decimalFormat.format((double)event.stats.hits / (double)event.stats.invocations));
			} else {
				ratio = "N/A";
			}
			
			String description;
			
			switch (type) {
				case NewIssues:
					
					if (event.stats.hits < input.minVolumeThreshold) {
						description = String.format("Non severe error: volume %d < %d", 
								event.stats.hits, input.minVolumeThreshold);	
					} else {
						description = String.format("Non severe error: rate %s < %.2f", 
								ratio, input.minErrorRateThreshold);
					}
					
					break;
					
				case SevereNewIssues:
					
					if (regResult.getCriticalNewEvents().containsKey(event.id)) {

						if (event.type.equals(UNCAUGHT_EXCEPTION)) {
							description = String.format("Severe error: event is uncaught exception");					
						} else {
							description = String.format("Severe error: event type " + 
								event.name + " is defined as a critical exception type");	
						}
					} else {
						description = String.format("Severe error: (volume  %d > %s) AND (rate %s > %.2f)",
							event.stats.hits, input.minVolumeThreshold, ratio,  input.minErrorRateThreshold);
					}
					
					break;

					
				case Regressions:
					
					description = String.format("Increase Warning : (volume %s > %d) AND (rate %s > %.2f) AND (rate change from baseline %.2f > %.2f)",
							formatLongValue(event.stats.hits), input.minVolumeThreshold,
							ratio, input.minErrorRateThreshold,
							getRateDelta(), input.regressionDelta);
					break;
					
				case SevereRegressions:
					
					description = String.format("Severe Increase: (volume %s > %d) AND (rate %s > %.2f) AND (rate change from baseline %.2f > %.2f)",
							formatLongValue(event.stats.hits), input.minVolumeThreshold,
							ratio, input.minErrorRateThreshold,
							getRateDelta(), input.criticalRegressionDelta);
					break;
					
				default: throw new IllegalStateException(String.valueOf(type));
			}
			
			return description;
		}
		
		private double getRateDelta() {
			
			double baselineRate = (double) regression.getBaselineHits() 
				/ (double) regression.getBaselineInvocations();
				
			double activeRate = (double) event.stats.hits
					/ (double) event.stats.invocations;
					
			double result =  (activeRate / baselineRate) - 1;
			
			return result;
		}
		
		@Override
		public boolean equals(Object obj) {
			if (!super.equals(obj)) {
				return false;
			}
			
			RegressionData other = (RegressionData)obj;
			
			if (!Objects.equal(type, other.type)) {
				return false;
			}
			
			return true;
		}
		
		public String getText() {
			
			switch (type) {
				case NewIssues:
					return RegressionStringUtil.NEW_ISSUE;
				case SevereNewIssues:
					return RegressionStringUtil.SEVERE_NEW;
				case Regressions:
					return RegressionStringUtil.REGRESSION;
				case SevereRegressions:
					return RegressionStringUtil.SEVERE_REGRESSION;
			
				default:
					return null;
			}
		}
		
		public int getSeverity() {
			
			if (type == null) {
				return Integer.valueOf(0);
			}
			
			switch (type) {
				
				case NewIssues:
				case Regressions:
					return Integer.valueOf(P2);
					
				case SevereNewIssues:
				case SevereRegressions:
					return Integer.valueOf(P1);
				default:
					break;
			}
			
			return Integer.valueOf(0);
		}
		
		@Override
		public String toString() {
			return String.valueOf(type) + " " + event.toString();
		}
	}
	
	protected static class RegressionFullRateFormatter  extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
			
			RegressionData regData = (RegressionData)eventData;
			
			if (regData.regression != null) {
				return RegressionStringUtil.getRegressedEventRate(regData.regression, true);
			} else {
				return RegressionStringUtil.getEventRate(regData.event);
			}
		}
	}
	
	protected static class RegressionRateFormatter extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
			
			RegressionData regData = (RegressionData)eventData;
			
			if (regData.regression != null) {
				return regData.getDelta();	
			} else {
				return RegressionStringUtil.getEventRate(regData.event);
			}
		}
	}
		
	protected static class RegressionSeverityFormatter extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
						
			RegressionData regData = (RegressionData)eventData;
			return regData.getSeverity();
		}
	}
	
	protected static class RegressionTimeRangeFormatter  extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
			
			if (input.timeFilter == null) {
				return null;
			}
			
			String timeUnit = TimeUtil.getTimeRange(input.timeFilter); 
					
			return timeUnit;
		}
	}
	
	protected class RegressionFromFormatter  extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
			
			if (input.timeFilter == null) {
				return null;
			}
			
			Pair<Object, Object> fromTo = getTimeFilterPair(timeSpan, input.timeFilter);
								
			return fromTo.getFirst();
		}
	}
	
	protected class RegressionToFormatter extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
			
			if (input.timeFilter == null) {
				return null;
			}
			
			Pair<Object, Object> fromTo = getTimeFilterPair(timeSpan, input.timeFilter);
								
			return fromTo.getSecond();
		}
	}
	

	
	protected static class RegressionDescriptionFormatter extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
			RegressionData regData = (RegressionData)eventData;
			String result = regData.getDescription();
			return result;
		}
		
		@Override
		protected Object formatValue(Object value, EventsInput input) {
			return value;
		}
	}
	
	public int expandBaselineTimespan(String serviceId, RegressionWindow activeWindow) {
		RegressionSettings regressionSettings = getRegressionSettings(serviceId);
		
		return expandBaselineTimespan(regressionSettings.baseline_timespan_factor, regressionSettings.min_baseline_timespan, activeWindow);
	}
	
	private int expandBaselineTimespan(int baselineTimespanFactor, int minBaselineTimespan,
			RegressionWindow activeWindow) {
		
		int result;
		double factor = (double)minBaselineTimespan / (double)activeWindow.activeTimespan;
		
		if (factor > baselineTimespanFactor) {
			result = minBaselineTimespan;
		} else {
			result = activeWindow.activeTimespan * baselineTimespanFactor;
		}
		
		return result;
		
	}
	
	protected class RegressionLinkFormatter extends FieldFormatter {
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan) {
			
			RegressionData regData = (RegressionData)eventData;
			
			DateTime from = regData.regResult.getActiveWndowStart().minusMinutes(regData.input.baselineTimespan);
			DateTime to = DateTime.now();
			
			return EventLinkEncoder.encodeLink(apiClient, getSettingsData(serviceId), 
				serviceId, input, eventData.event, from, to);
		}
		
		@Override
		protected Object formatValue(Object value, EventsInput input) {
			return value;
		}
	}
	
	public RegressionFunction(ApiClient apiClient) {
		super(apiClient);
	}
	
	public RegressionFunction(ApiClient apiClient, Map<String, ServiceSettings> settingsMaps) {
		super(apiClient, settingsMaps);
	}
	
	public static int compareRegressions(RegressionData r1, RegressionData r2, 
		List<String> criticalExceptionList, int minThreshold ) {
		
		int typeDelta = r1.type.ordinal() - r2.type.ordinal();
		
		if (typeDelta != 0) {
			return typeDelta;
		}
		
		if ((r1.type == RegressionType.SevereNewIssues) ||
			(r1.type == RegressionType.NewIssues)) {
					
			return compareEvents(r1.event, r2.event, 0, 0, 
				criticalExceptionList, minThreshold);
		}			
		
		if ((r1.type == RegressionType.SevereRegressions) ||
			(r1.type == RegressionType.Regressions)) {
			return r2.getDelta() - r1.getDelta();		
		}		
		
		throw new IllegalStateException(String.valueOf(r1.type));
		
	}
	
	private void sortRegressions(String serviceId, List<EventData> eventData) {	
		
		RegressionSettings regressionSettings = getSettingsData(serviceId).regression;
		
		int minThreshold = regressionSettings.error_min_volume_threshold;
		List<String> criticalExceptionList = new ArrayList<String>(regressionSettings.getCriticalExceptionTypes());
		
		eventData.sort(new Comparator<EventData>() {
			
			@Override
			public int compare(EventData o1, EventData o2) {
				
				return compareRegressions((RegressionData)o1, (RegressionData)o2, 
					criticalExceptionList, minThreshold);
			}
		});
	}
	
	private List<EventData> getUniqueEventData(List<EventData> eventDatas, Map<String, EventResult> eventListMap) {
		
		List<EventData> result = new ArrayList<EventData>();
		
		Map<String, EventData> eventDataMap = new HashMap<String, EventData>(eventDatas.size());
		
		for (EventData eventData : eventDatas) {
			eventDataMap.put(eventData.event.id, eventData);
		}
		
		for (EventData eventData : eventDatas) {
			
			RegressionData regData = (RegressionData)eventData;
			
			if (regData.regression != null) {
				result.add(regData);
			} else {
				
				boolean found = false;
						
				for (EventResult eventResult : eventListMap.values()) {
					
					if (eventResult.id.equals(regData.event.id)) {
						continue;
					}
					
					if (compareEvents(regData.event, eventResult)) {
						
						EventData matchingNewEvent = eventDataMap.get(eventResult.id);
						
						if (matchingNewEvent == null) {
							found = true;
							break;
						}
					}
				}
				
				if (!found) {
					result.add(regData);
				}
			}
		}
		
		return result;
	}
	
	public List<EventData> processRegression(String serviceId, EventFilterInput functionInput, RegressionInput input,
			RateRegression rateRegression, Map<String, EventResult> eventListMap,
			boolean includeNew, boolean includeRegressions) {
		
		List<EventData> result;
		List<EventData> eventDatas = processRegressionData(serviceId, input, rateRegression, includeNew, includeRegressions);
				
		if (functionInput.hasTransactions()) {
			result = eventDatas;
		} else {
			List<EventData> uniqueEventDatas = getUniqueEventData(eventDatas, eventListMap);
			result = doMergeSimilarEvents(input.serviceId, false, uniqueEventDatas);
		}
		
		return result;
	}
	
	private List<EventData> processRegressionData(String serviceId, RegressionInput input,
			RateRegression rateRegression, boolean includeNew, boolean includeRegressions) {
		
		List<EventData> result = new ArrayList<EventData>();
		
		if (includeNew) {
			
			for (EventResult event : rateRegression.getSortedCriticalNewEvents()) {
				result.add(new RegressionData(rateRegression, input, event, RegressionType.SevereNewIssues));
			}
			
			for (EventResult event : rateRegression.getSortedExceededNewEvents()) {
				result.add(new RegressionData(rateRegression, input, event, RegressionType.SevereNewIssues));
			}
			
			for (EventResult event : rateRegression.getSortedAllNewEvents()) {
				
				if (rateRegression.getExceededNewEvents().containsKey(event.id)) {
					continue;
				}
				
				if (rateRegression.getCriticalNewEvents().containsKey(event.id)) {
					continue;
				}
				
				result.add(new RegressionData(rateRegression, input, event, RegressionType.NewIssues));	
			}
		}
		
		if (includeRegressions) {
			
			for (RegressionResult regressionResult : rateRegression.getSortedCriticalRegressions()) {
				result.add(new RegressionData(rateRegression, input, regressionResult, RegressionType.SevereRegressions));
			}
			
			for (RegressionResult regressionResult : rateRegression.getSortedAllRegressions()) {
				
				if (rateRegression.getCriticalRegressions().containsKey(regressionResult.getEvent().id)) {
					continue;
				}
				
				result.add(new RegressionData(rateRegression, input, regressionResult, RegressionType.Regressions));
			}
		}
	
		sortRegressionDatas(serviceId, result);
		
		return result;
	}
	
	private void sortRegressionDatas(String serviceId, List<EventData> eventDatas) {
		
		RegressionSettings regressionSettings = getSettingsData(serviceId).regression;
		
		int minThreshold = regressionSettings.error_min_volume_threshold;
		List<String> criticalExceptionList = new ArrayList<String>(regressionSettings.getCriticalExceptionTypes());
		
		eventDatas.sort(new Comparator<EventData>() {

			@Override
			public int compare(EventData o1, EventData o2) {
				
				RegressionData r1 = (RegressionData)o1;
				RegressionData r2 = (RegressionData)o2;
				
				int typeDelta = Integer.compare(r2.type.ordinal(), r1.type.ordinal());

				if (typeDelta != 0) {
					return typeDelta;
				}
				
				int result = compareEvents(o1.event, o2.event, 
					0, 0, criticalExceptionList, minThreshold);
				
				return result;
			}
		});
		
	}
	
	@Override
	protected FieldFormatter getFormatter(String serviceId, String column) {
		
		if (column.equals(RegressionsInput.REG_DELTA)) {
			return new RegressionRateFormatter();
		}
		
		if (column.equals(RegressionsInput.REGRESSION)) {
			return new RegressionFullRateFormatter();
		}
		
		if (column.equals(RegressionsInput.SEVERITY)) {
			return new RegressionSeverityFormatter();
		}
		
		if (column.equals(EventsInput.LINK)) {
			return new RegressionLinkFormatter();
		}
		
		if (column.equals(ViewInput.TIME_RANGE)) {
			return new RegressionTimeRangeFormatter();
		}
		
		if (column.equals(ViewInput.FROM)) {
			return new RegressionFromFormatter();
		}
		
		if (column.equals(ViewInput.TO)) {
			return new RegressionToFormatter();
		}
		
		if (column.equals(RegressionsInput.REG_DESC)) {
			return new RegressionDescriptionFormatter();
		}
		
		return super.getFormatter(serviceId, column);
	}
	
	public static class RegressionOutput {	
		
		public static final RegressionOutput emptyOutput = new RegressionOutput(true);
		
		public boolean empty;
		
		public BaseEventVolumeInput input;
		public RegressionInput regressionInput;
		public RegressionWindow regressionWindow;
		public RateRegression rateRegression;
		public Graph baseVolumeGraph;
		public Graph activeVolumeGraph;
		public Map<String, EventResult> eventListMap;
		public List<EventData> eventDatas;
		
		protected double score;
		protected long volume;
		
		protected int severeNewIssues;
		protected int newIssues;
		
		protected int criticalRegressions;
		protected int regressions;
		
		public RegressionOutput(boolean empty) {
			this.empty = empty;
		}
		
		public double getStat(RegressionType type) {
			
			switch (type) {	
				case NewIssues:
					return newIssues;
				
				case SevereNewIssues:
					return severeNewIssues;
				
				case Regressions:
					return regressions;
				
				case SevereRegressions:
					return criticalRegressions;
					
				default:
					return 0;
			}
		}
	}
	
	protected class DeterminantGraphsLists {
		public List<Graph> baselineGraph = new ArrayList<Graph>();
		public List<Graph> activeWindowGraph = new ArrayList<Graph>();
	}
	
	protected class GraphSliceResult {
		public boolean isBaselineGraph;
		public Graph graph;
		
		public GraphSliceResult(boolean isBaselineGraph, Graph graph) {
			this.isBaselineGraph = isBaselineGraph;
			this.graph = graph;
		}
	}
	
	protected class RegressionGraphDeterminantResult {
		public Graph baselineGraph;
		public Graph activeWindowGraph;
		public RegressionWindow activeWindow;
		
		public RegressionGraphDeterminantResult(Graph activeWindowGraph, Graph baselineGraph, RegressionWindow activeWindow) {
			
			this.activeWindowGraph = activeWindowGraph;
			this.baselineGraph = baselineGraph;
			this.activeWindow = activeWindow;
		}
	}
	
	public static class RegressionApiResult {
		public DeterminantKey determinantKey;
		
		public Graph baselineGraph;
		public Graph activeWindowGraph;
		public RegressionWindow activeWindow;
		public Map<String, EventResult> eventResultsMap;
		public Set<BreakdownType> queryBreakdownTypes;
		public Set<BreakdownType> determinantBreakdownTypes;
		
		public RegressionApiResult(DeterminantKey determinantKey, Graph baselineGraph, Graph activeWindowGraph,
				RegressionWindow activeWindow, Map<String, EventResult> eventResultsMap, Set<BreakdownType> queryBreakdownTypes,
				Set<BreakdownType> determinantBreakdownTypes) {
			
			this.determinantKey = determinantKey;
			this.baselineGraph = baselineGraph;
			this.activeWindowGraph = activeWindowGraph;
			this.activeWindow = activeWindow;
			this.eventResultsMap = eventResultsMap;
			this.queryBreakdownTypes = queryBreakdownTypes;
			this.determinantBreakdownTypes = determinantBreakdownTypes;
		}
	}
	
	private RegressionSettings getRegressionSettings(String serviceId) {
		
		RegressionSettings regressionSettings = getSettingsData(serviceId).regression;
		
		if (regressionSettings == null) {
			throw new IllegalStateException("Missing regression settings for " + serviceId);
		}
		
		return regressionSettings;
	}
	
	public Pair<RegressionInput, RegressionWindow> getRegressionInput(String serviceId, String viewId,
			EventFilterInput input,
			Pair<DateTime, DateTime> timeSpan, boolean newOnly) {
		return getRegressionInput(serviceId, viewId, input, null, timeSpan, newOnly);
	}
	
	public Pair<RegressionInput, RegressionWindow> getRegressionInput(String serviceId, String viewId,
		EventFilterInput input, RegressionWindow window,
		Pair<DateTime, DateTime> timeSpan, boolean newOnly) {
		
		RegressionSettings regressionSettings = getRegressionSettings(serviceId);
		
		RegressionInput regressionInput = new RegressionInput();
		
		regressionInput.serviceId = serviceId;
		regressionInput.viewId = viewId;
		regressionInput.deployments = input.getDeployments(serviceId, apiClient);
		
		regressionInput.activeTimespan = (int)TimeUnit.MILLISECONDS
				.toMinutes(timeSpan.getSecond().getMillis() - timeSpan.getFirst().getMillis());
		
		if ((CollectionUtil.safeIsEmpty(regressionInput.deployments))) {
			regressionInput.activeWindowStart = timeSpan.getFirst();
		}
		
		regressionInput.baselineTimespan = regressionSettings.min_baseline_timespan;
		
		RegressionWindow regressionWindow;

		if (window == null) {
			regressionWindow = ApiCache.getRegressionWindow(apiClient, regressionInput);
		} else {
			regressionWindow = window;
		}
		
		if ((!CollectionUtil.safeIsEmpty(regressionInput.deployments)) 
		&& (!regressionWindow.deploymentFound)) {
			return null;
		}
		
		int expandedBaselineTimespan = expandBaselineTimespan(regressionSettings.baseline_timespan_factor,
				regressionSettings.min_baseline_timespan,
				regressionWindow);
		
		regressionInput.activeWindowStart = regressionWindow.activeWindowStart;
		regressionInput.activeTimespan = regressionWindow.activeTimespan;
		regressionInput.baselineTimespan = expandedBaselineTimespan;
		
		regressionInput.applictations = input.getApplications(apiClient,
			getSettingsData(serviceId), serviceId, true, false);
		
		regressionInput.servers = input.getServers(serviceId);
		
		Collection<String> criticalExceptionTypes = regressionSettings.getCriticalExceptionTypes();
		
		regressionInput.criticalExceptionTypes = criticalExceptionTypes;
		
		regressionInput.minVolumeThreshold = regressionSettings.error_min_volume_threshold;
		regressionInput.minErrorRateThreshold = regressionSettings.error_min_rate_threshold;
		
		if (!newOnly) {
			regressionInput.regressionDelta = regressionSettings.error_regression_delta;
			regressionInput.criticalRegressionDelta = regressionSettings.error_critical_regression_delta;
			regressionInput.applySeasonality = regressionSettings.apply_seasonality;
		}
		
		return Pair.of(regressionInput, regressionWindow);
		
	}
	
	public Set<GraphSliceResult> getRegressionGraphs(String serviceId, String viewId,
			RegressionInput regressionInput, RegressionWindow regressionWindow,
			BaseEventVolumeInput input, boolean newOnly, Set<BreakdownType> queryBreakdownTypes,
			Set<BreakdownType> determinantBreakdownTypes) {
		
		EventFilterInput baselineInput;
		DateTime baselineStart = regressionWindow.activeWindowStart.minusMinutes(regressionInput.baselineTimespan);
		DateTime baselineEnd = regressionWindow.activeWindowStart;
		
		DateTime activeStart = regressionWindow.activeWindowStart;
		DateTime activeEnd = regressionWindow.activeWindowStart.plusMinutes(regressionWindow.activeTimespan);
		
		Set<BreakdownType> baselineQueryBreakdownTypes = queryBreakdownTypes;
		
		if (CollectionUtil.safeContains(determinantBreakdownTypes, BreakdownType.Deployment)) {
			// for deployments baseline graph will start baseline timespan before the first deployment
			// and finish at the end of current deployment the cache therefore should return the results quickly for
			// the actual active window. Each deployment baseline will be calculated afterwards in code
			baselineInput = gson.fromJson(gson.toJson(input), input.getClass());
			baselineEnd = activeEnd;
			
			//deployments by definition nature do not have their own baseline - 
			//they are compared against the general baseline (all prev deps)
			baselineInput.deployments = null;
			baselineQueryBreakdownTypes = null;
		} else {
			baselineInput = input;
		}
		
		Collection<GraphSliceTask> baselineGraphTasks;
		
		if (!newOnly) {
			baselineGraphTasks = getGraphTasks(serviceId, viewId, baselineInput, 
				VolumeType.all, baselineStart, baselineEnd,
				regressionInput.baselineTimespan, regressionWindow.activeTimespan, false, baselineQueryBreakdownTypes);
		} else {
			baselineGraphTasks = null;
		}
		
		int graphActiveTimespan;
		
		if (input.hasDeployments()) {
			graphActiveTimespan = regressionWindow.activeTimespan;
		} else {
			graphActiveTimespan = 0;
		}
		
		Collection<GraphSliceTask> activeGraphTasks = getGraphTasks(serviceId, viewId, 
			input, VolumeType.all, activeStart, activeEnd, 0, graphActiveTimespan, false, queryBreakdownTypes);
		
		List<GraphSliceTask> graphTasks = new ArrayList<GraphSliceTask>(); 
		
		if (baselineGraphTasks != null) {
			graphTasks.addAll(baselineGraphTasks);
		}
		
		graphTasks.addAll(activeGraphTasks);
		
		Collection<GraphSliceTaskResult> graphSliceTaskResults = executeGraphTasks(graphTasks, false);
		
		Set<GraphSliceResult> result = getGraphSliceResultData(baselineGraphTasks, graphSliceTaskResults);
		
		return result;
	}
	
	public Map<DeterminantKey, RegressionGraphDeterminantResult> getGraphsDeterminantMap(String serviceId, Set<GraphSliceResult> graphSliceResults,
			RegressionWindow activeWindow, Map<String, Collection<String>> applicationGroupsMap, Set<BreakdownType> determinantBreakdownTypes) {
		
		Map<DeterminantKey, DeterminantGraphsLists> determinantGraphsMap = divideGraphsByDeterminant(graphSliceResults,
				applicationGroupsMap, determinantBreakdownTypes);
		
		Map<DeterminantKey, RegressionGraphDeterminantResult> result = new HashMap<DeterminantKey, RegressionGraphDeterminantResult>();
		
		DeterminantGraphsLists emptyDeterminantGraphs = determinantGraphsMap.get(DeterminantKey.Empty);
		
		List<Graph> allBaselineGraphs = new ArrayList<Graph>();
		
		if (emptyDeterminantGraphs != null) {
			allBaselineGraphs = emptyDeterminantGraphs.baselineGraph;
		}
		
		Map<DeterminantKey, Pair<DateTime, DateTime>> determinantToDeploymentsTimespan = new HashMap<DeterminantKey, Pair<DateTime, DateTime>>();
		Graph mergedBaselineGraph = null;
		
		if (CollectionUtil.safeContains(determinantBreakdownTypes, BreakdownType.Deployment)) {
			mergedBaselineGraph = mergeGraphs(allBaselineGraphs);
			
			determinantToDeploymentsTimespan = getDeploymentsTimespan(serviceId, determinantGraphsMap);
		}
		
		for (DeterminantKey determinantGraphListsMapKey : determinantGraphsMap.keySet()) {
			if (shouldExcludeEmptyDetreminantGraph(determinantBreakdownTypes, determinantGraphListsMapKey)) {
				continue;
			}
			
			DeterminantGraphsLists determinantGraphsLists = determinantGraphsMap.get(determinantGraphListsMapKey);
			
			Pair<DateTime, DateTime> deploymentsTimespan = determinantToDeploymentsTimespan.get(determinantGraphListsMapKey);
			
			RegressionGraphDeterminantResult determinantGraphs = getDeterminantGraphs(serviceId, activeWindow, mergedBaselineGraph,
					determinantGraphsLists.baselineGraph, determinantGraphsLists.activeWindowGraph, deploymentsTimespan, determinantBreakdownTypes);
			
			result.put(determinantGraphListsMapKey, determinantGraphs);
		}
		
		return result;
	}
	
	public Map<DeterminantKey, Pair<DateTime, DateTime>> getDeploymentsTimespan(String serviceId,
			Map<DeterminantKey, DeterminantGraphsLists> determinantGraphsMap) {
		
		Map<DeterminantKey, Pair<DateTime, DateTime>> result = new HashMap<DeterminantKey, Pair<DateTime, DateTime>>();
		
		Map<DeterminantKey, Pair<DateTime, DateTime>> activeTimespanFromCache = getActiveTimespanFromCache(serviceId, true, determinantGraphsMap);
		
		if (activeTimespanFromCache != null) {
			result = activeTimespanFromCache;
			
			return result;
		}
		
		Map<DeterminantKey, Pair<DateTime, DateTime>> nonActiveTimespanFromCache = getActiveTimespanFromCache(serviceId, false, determinantGraphsMap);
		
		if (nonActiveTimespanFromCache != null) {
			result = nonActiveTimespanFromCache;
			
			return result;
		}
		
		return result;
	}
	
	public Map<DeterminantKey, Pair<DateTime, DateTime>> getActiveTimespanFromCache(String serviceId, boolean active,
			Map<DeterminantKey, DeterminantGraphsLists> determinantGraphsMap) {
		
		Response<DeploymentsResult> deployments = ApiCache.getDeployments(apiClient, serviceId, active, null);
		
		Map<DeterminantKey, Pair<DateTime, DateTime>> result = null;
		
		if (deployments.data != null) {
			Map<DeterminantKey, Pair<DateTime, DateTime>> deploymentsTimespan = getDeploymentsTimespan(deployments.data.deployments, determinantGraphsMap);
			
			if (deploymentsTimespan != null) {
				result = deploymentsTimespan;
				
				return result;
			}
		}
		
		return result;
	}
	
	private Map<DeterminantKey, Pair<DateTime, DateTime>> getDeploymentsTimespan(List<SummarizedDeployment> summarizedDeployments,
			Map<DeterminantKey, DeterminantGraphsLists> determinantGraphsMap) {
		
		Map<DeterminantKey, Pair<DateTime, DateTime>> result = new HashMap<DeterminantKey, Pair<DateTime, DateTime>>();
		
		for (SummarizedDeployment summarizedDeployment : summarizedDeployments) {
			
			DeterminantKey determinantKey = DeterminantKey.create("", "", summarizedDeployment.name);
			if (determinantGraphsMap.get(determinantKey) != null) {
				DateTimeFormatter dateTimeFormatter = ISODateTimeFormat.dateTime().withZoneUTC();
				
				DateTime start = dateTimeFormatter.parseDateTime(summarizedDeployment.first_seen);
				DateTime end = null;
				
				if (summarizedDeployment.last_seen != null) {
					end = dateTimeFormatter.parseDateTime(summarizedDeployment.last_seen);
				}
				
				result.put(determinantKey, Pair.of(start, end));
			}
		}
		
		Set<DeterminantKey> difference = new HashSet<DeterminantKey>(determinantGraphsMap.keySet());
		difference.removeAll(result.keySet());
		
		if (!difference.equals(Collections.singleton(DeterminantKey.Empty))) {
			
			return null;
		}
		
		return result;
	}
	
	public RegressionGraphDeterminantResult getDeterminantGraphs(String serviceId, RegressionWindow activeWindow, Graph mergedBaselineGraph,
			List<Graph> baselineGraphs, List<Graph> activeWindowGraphs, Pair<DateTime, DateTime> deploymentsTimespan, Set<BreakdownType> determinantBreakdownTypes) {
		
		Graph baselineGraph = new Graph();
		baselineGraph.points = new ArrayList<>();
		
		Graph activeWindowGraph = null;
		
		boolean hasBaselineGraphs = (!CollectionUtil.safeIsEmpty(baselineGraphs));
		
		if (hasBaselineGraphs) {
			baselineGraph = mergeGraphs(baselineGraphs);
		}
		
		if (!CollectionUtil.safeIsEmpty(activeWindowGraphs)) {
			activeWindowGraph = mergeGraphs(activeWindowGraphs);
		}
		
		RegressionWindow resultActiveWindow = activeWindow.clone();
		
		if (activeWindowGraph != null) {
			GraphResult activeGraphResult = new GraphResult();
			
			activeGraphResult.graphs = Collections.singletonList(activeWindowGraph);
			
			if (CollectionUtil.safeContains(determinantBreakdownTypes, BreakdownType.Deployment)) {
				
				resultActiveWindow = getActiveWindowFromTimespan(deploymentsTimespan);
				
				baselineGraph = adjustDeploymentsBaselineGraph(serviceId, resultActiveWindow, mergedBaselineGraph, deploymentsTimespan);
			}
		}
		
		RegressionGraphDeterminantResult result = new RegressionGraphDeterminantResult(activeWindowGraph, baselineGraph, resultActiveWindow);
		
		return result;
	}
	
	private Graph adjustDeploymentsBaselineGraph(String serviceId,
			RegressionWindow activeWindow, Graph baselineGraph, Pair<DateTime, DateTime> deploymentsTimespan) {
		
		Graph result = baselineGraph;
		
		if (deploymentsTimespan != null) {
			int baselineTimespan = expandBaselineTimespan(serviceId, activeWindow);
			
			RegressionPeriodData deploymentsRegressionGraphs = cropGraphByPeriod(baselineGraph,
					deploymentsTimespan, baselineTimespan,
					null);
			
			result = deploymentsRegressionGraphs.baselineGraph;
		}
		
		return result;
	}
	
	private RegressionWindow getActiveWindowFromTimespan(Pair<DateTime, DateTime> deploymentsTimespan) {
		RegressionWindow activeWindow = new RegressionWindow();
		
		activeWindow.activeWindowStart = deploymentsTimespan.getFirst();
		DateTime deploymentActiveWindowEnd = deploymentsTimespan.getSecond();
		
		activeWindow.activeTimespan = (int) TimeUnit.MILLISECONDS
				.toMinutes(deploymentActiveWindowEnd.minus(
						activeWindow.activeWindowStart.getMillis()).getMillis());
		
		return activeWindow;
	}
	
	
	private Set<GraphSliceResult> getGraphSliceResultData(Collection<GraphSliceTask> baselineGraphTasks, Collection<GraphSliceTaskResult> graphSliceTaskResults) {
		
		Set<GraphSliceResult> result = new HashSet<GraphSliceResult>();
		
		for (GraphSliceTaskResult graphSliceTaskResult : graphSliceTaskResults) {
			boolean baselineGraphSlice = CollectionUtil.safeContains(baselineGraphTasks, graphSliceTaskResult.task);
			
			for (Graph graph : graphSliceTaskResult.graphs) {
				result.add(new GraphSliceResult(baselineGraphSlice, graph));
			}
		}
		
		return result;
	}
	
	public boolean shouldExcludeEmptyDetreminantGraph(Set<BreakdownType> determinantBreakdownTypes, DeterminantKey determinantGraphListsMapKey) {
		// Take empty determinant graph only if determinant breakdown was requested
		boolean result = ((determinantGraphListsMapKey.equals(DeterminantKey.Empty)) && (!CollectionUtil.safeIsEmpty(determinantBreakdownTypes)));
		
		return result;
	}
	
	private Map<DeterminantKey, DeterminantGraphsLists> divideGraphsByDeterminant(Set<GraphSliceResult> graphSliceResultSet,
			Map<String, Collection<String>> applicationGroupsMap, Set<BreakdownType> determinantBreakdownTypes) {
		
		Map<DeterminantKey, DeterminantGraphsLists> result = new HashMap<DeterminantKey, DeterminantGraphsLists>();
		
		for (GraphSliceResult graphSliceResult : graphSliceResultSet) {
			
			Set<DeterminantKey> graphsKeys = new HashSet<DeterminantKey>();
			
			Graph graph = graphSliceResult.graph;
			
			DeterminantKey determinantKey = DeterminantKey.create(determinantBreakdownTypes, "",
					graph.application_name, graph.deployment_name);
			
			graphsKeys.add(determinantKey);
			
			if (CollectionUtil.safeContains(determinantBreakdownTypes, BreakdownType.App) &&
				(!CollectionUtil.safeIsEmpty(applicationGroupsMap))) {
				
				Collection<String> appGroups = applicationGroupsMap.get(graph.application_name);
				
				if (!CollectionUtil.safeIsEmpty(appGroups)) {
					for (String appGroup : appGroups) {
						graphsKeys.add(DeterminantKey.create("", appGroup, ""));
					}
				}
			}
			
			for (DeterminantKey graphKey : graphsKeys) {
				DeterminantGraphsLists determinantGraphsLists = result.get(graphKey);
				
				if (determinantGraphsLists == null) {
					determinantGraphsLists = new DeterminantGraphsLists();
					
					result.put(graphKey, determinantGraphsLists);
				}
				
				if (graphSliceResult.isBaselineGraph) {
					determinantGraphsLists.baselineGraph.add(graph);
				} else {
					if (shouldExcludeEmptyDetreminantGraph(determinantBreakdownTypes, graphKey)) {
						// The relevant apps for the filter does not exist
						continue;
					}
					
					determinantGraphsLists.activeWindowGraph.add(graph);
				}
			}
		}
		
		return result;
	}
	
	protected RegressionOutput createRegressionOutput(String serviceId,
			BaseEventVolumeInput input, RegressionInput regressionInput, RegressionWindow regressionWindow,
			RateRegression rateRegression, Map<String, EventResult> eventListMap,
			Graph baseVolumeGraph, Graph activeVolumeGraph, long volume,
			boolean allowEmpty) {
		
		RegressionOutput result = new RegressionOutput(false);
		
		result.input = input;
		result.regressionInput = regressionInput;
		result.regressionWindow = regressionWindow;
		result.rateRegression = rateRegression;
		result.baseVolumeGraph = baseVolumeGraph;
		result.activeVolumeGraph = activeVolumeGraph;
		result.eventListMap = eventListMap;
		result.volume = volume;
		
		if ((regressionInput != null) && (rateRegression != null)) {
			
			result.eventDatas = processRegression(serviceId, input, regressionInput, 
				rateRegression, eventListMap, true, true);
			
			for (EventData eventData : result.eventDatas) {
				
				RegressionData regData = (RegressionData)eventData;
				
				if ((!allowEmpty) && (regData.event.stats.hits == 0))  {
					continue;
				}
				
				switch (regData.type) {
					case NewIssues:
						result.newIssues++;
						break;
					case SevereNewIssues:
						result.severeNewIssues++;
						break;
					case Regressions:
						result.regressions++;
						break;
					case SevereRegressions:
						result.criticalRegressions++;
						break;
					
					default:
				}
			}
		}
		
		return result;
	}
	
	public RegressionOutput executeRegression(String serviceId, RegressionsInput regressionsInput, boolean newOnly,
			RegressionInput regressionInput, RegressionWindow activeWindow, Map<String, EventResult>  eventResultMap,
			Graph baselineGraph, Graph activeWindowGraph) {
		
		return getRegressionOutput(serviceId, regressionsInput, newOnly,
				TimeUtil.getTimeFilter(regressionsInput.timeFilter), regressionInput,
				activeWindow, eventResultMap, baselineGraph, activeWindowGraph);
	}
	
	public RegressionOutput executeRegression(String serviceId, 
			BaseEventVolumeInput input, boolean newOnly) {
		
		String viewId = getViewId(serviceId, input.view);
		
		if (viewId == null) {
			return RegressionOutput.emptyOutput;
		}
		
		Pair<DateTime, DateTime> timespan = TimeUtil.getTimeFilter(input.timeFilter);
		
		Pair<RegressionInput, RegressionWindow> regressionInputs =
				getRegressionInput(serviceId, viewId, input, timespan, newOnly);
		
		if (regressionInputs == null) {
			return RegressionOutput.emptyOutput;
		}
		
		RegressionInput regressionInput = regressionInputs.getFirst();
		RegressionWindow regressionWindow = regressionInputs.getSecond();
		
		DateTime from = regressionWindow.activeWindowStart;
		DateTime to = regressionWindow.activeWindowStart.plusMinutes(regressionInput.activeTimespan);
		
		Map<String, EventResult> eventListMap = getEventMap(serviceId, input,
			from, to, null);
			
		if (eventListMap == null) {
			return RegressionOutput.emptyOutput;
		}
		
		Set<GraphSliceResult> graphSliceResults = getRegressionGraphs(serviceId,
				viewId, regressionInput, regressionWindow, input, newOnly,
				null, null);
		
		Collection<RegressionGraphDeterminantResult> regressionGraphsCollection = getGraphsDeterminantMap(serviceId, graphSliceResults,
				regressionWindow, null, null).values();
		
		if (CollectionUtil.safeIsEmpty(regressionGraphsCollection)) {
			return RegressionOutput.emptyOutput;
		}
		
		Set<Graph> baselineGraphs = new HashSet<Graph>();
		Set<Graph> activeWindowGraphs = new HashSet<Graph>();
		
		for (RegressionGraphDeterminantResult graphPairs : regressionGraphsCollection) {
			baselineGraphs.add(graphPairs.baselineGraph);
			activeWindowGraphs.add(graphPairs.activeWindowGraph);
		}
		
		Pair<Graph, Graph> regressionGraphs = Pair.of(mergeGraphs(baselineGraphs), mergeGraphs(activeWindowGraphs));
			
		Graph baselineGraph = regressionGraphs.getFirst();
		Graph activeWindowGraph = regressionGraphs.getSecond();
		
		return getRegressionOutput(serviceId, input, newOnly, timespan, regressionInput,
				regressionWindow, eventListMap, baselineGraph, activeWindowGraph);
	}
	
	public RegressionOutput getRegressionOutput(String serviceId, BaseEventVolumeInput input, boolean newOnly,
				Pair<DateTime, DateTime> timespan, RegressionInput regressionInput, RegressionWindow regressionWindow,
				Map<String, EventResult> eventListMap, Graph baselineGraph, Graph activeWindowGraph) {
		if ((activeWindowGraph == null) ||(activeWindowGraph.points == null)) {
			return RegressionOutput.emptyOutput;
		}
		
		if ((!newOnly) && (((baselineGraph == null) || (baselineGraph.points == null)))) {
			return RegressionOutput.emptyOutput;
		}
		
		Pair<Map<String, EventResult>, Long> filteredResult = filterEvents(serviceId,
			timespan, input, eventListMap.values());
		
		Map<String, EventResult> filteredMap = filteredResult.getFirst();
		
		long volume = applyGraphToEvents(filteredMap, activeWindowGraph, timespan);
		
		regressionInput.events = filteredMap.values();
		regressionInput.baselineGraph = baselineGraph;
		
		RegressionOutput result = executeRegression(serviceId, input, regressionInput, 
			regressionWindow, eventListMap, volume, baselineGraph, activeWindowGraph, false);
		
		return result;
	}
	
	/**
	 * @param allowEmpty - skipped for now 
	 */
	public RegressionOutput executeRegression(String serviceId, BaseEventVolumeInput input, 
			RegressionInput regressionInput, RegressionWindow regressionWindow, 
			Map<String, EventResult> eventListMap, long volume,
			Graph baselineGraph, Graph activeWindowGraph, boolean allowEmpty) {
		
		regressionInput.validate();
		
		RegressionWindow regressionInputWindow = ApiCache.getRegressionWindow(apiClient, regressionInput);
		
		RateRegression rateRegression = RegressionUtil.calculateRateRegressions(apiClient, regressionInput, regressionInputWindow,
				null, false);
		
		RegressionOutput result = createRegressionOutput(serviceId,
				input, regressionInput, regressionWindow,
				rateRegression, eventListMap,
				baselineGraph, activeWindowGraph, volume, true);//allowEmpty);
		
		return result;
	}
	
	private Map<RegressionType, List<EventData>> getRegressionMap(List<EventData> eventDatas) {
		
		Map<RegressionType, List<EventData>> result = new HashMap<RegressionType, List<EventData>>();
		
		for (EventData eventData : eventDatas) {
			RegressionData regData = (RegressionData)eventData;
			List<EventData> typeEvents = result.get(regData.type);
			
			if (typeEvents == null) {
				typeEvents = new ArrayList<EventData>();
				result.put(regData.type, typeEvents);
			}
			
			typeEvents.add(regData);
		}
			
		return result;
	}
	
	private EventData mergeRegressionsOfType(List<EventData> eventDatas) {
		
		RegressionData first = (RegressionData)(eventDatas.get(0));
		List<EventData> merged = super.mergeEventDatas(eventDatas);
		
		RegressionData result = new RegressionData(first.regResult, first.input, merged.get(0).event, first.type);
		
		long baselineHits = 0;
		long baselineInvocations = 0;
			
		for (EventData eventData : eventDatas) {	
			
			RegressionData regressionData = (RegressionData)eventData;
				
			if (regressionData.regression != null) {
				baselineHits += regressionData.regression.getBaselineHits();
				baselineInvocations += regressionData.regression.getBaselineInvocations();
			}
				
			result.mergedIds.add(eventData.event.id);
				
			if (eventData.event.similar_event_ids != null) {
				result.mergedIds.addAll(eventData.event.similar_event_ids);
			}
		}
				
		if (first.regression != null) {
			result.regression = RegressionResult.of(result.event, baselineHits, baselineInvocations);
		}
		
		return result;
	}
	
	@Override
	protected List<EventData> mergeEventDatas(List<EventData> eventDatas) {
		
		List<EventData> result = new ArrayList<EventData>();
		Map<RegressionType, List<EventData>> regressionMap = getRegressionMap(eventDatas);
		
		for (List<EventData> typeEvents : regressionMap.values()) {
			result.add(mergeRegressionsOfType(typeEvents));
		}
		
		return result;
	}
	
	private List<EventData> doMergeSimilarEvents(String serviceId,
		boolean skipGrouping, List<EventData> eventDatas) {
		
		List<EventData> result = new ArrayList<EventData>(super.mergeSimilarEvents(serviceId,
			skipGrouping, eventDatas));
		sortRegressions(serviceId, result);
		
		return result;
	}
	
	@Override
	protected void sortEventDatas(String serviceId, List<EventData> eventDatas) {
		//use regression sorting instead of normal event ranking
	}
	
	@Override
	protected List<EventData> mergeSimilarEvents(String serviceId,
		boolean skipGrouping, List<EventData> eventDatas) {
		return eventDatas;
	}
		
	public RegressionOutput runRegression(String serviceId, EventFilterInput regInput, boolean newOnly) {
		RegressionOutput regressionOutput = ApiCache.getRegressionOutput(apiClient, 
			serviceId, regInput, this, newOnly, true);
		return regressionOutput;
	}
	
	@Override
	protected List<EventData> getEventData(String serviceId, EventsInput input,
			Pair<DateTime, DateTime> timeSpan) {
		
		RegressionsInput regInput = (RegressionsInput)input;
				
		RegressionOutput regressionOutput = runRegression(serviceId, regInput, regInput.newOnly());
		
		if ((regressionOutput == null) || (regressionOutput.rateRegression == null) ||
			(regressionOutput.regressionInput == null)) {
			return Collections.emptyList();
		}
		
		List<EventData> result;
		
		if (regInput.regressionTypes != null) {
			
			Collection<RegressionType> types = regInput.getRegressionTypes();
			
			result = new ArrayList<EventData>(regressionOutput.eventDatas.size());
			
			for (EventData eventData : regressionOutput.eventDatas) {
				RegressionData regData = (RegressionData)eventData;
				
				if (types.contains(regData.type)) {
					result.add(regData);
				}
			}
		} else {
			result = regressionOutput.eventDatas;
		}
		
		return result;
	}
	
	public static String getNewIssuesDesc(RegressionOutput regressionOutput, int maxIssuesSize) {
		
		StringBuilder result = new StringBuilder();
		
		int size = 0;
		int issuesSize = regressionOutput.newIssues + regressionOutput.severeNewIssues;
		
		if (!CollectionUtil.safeIsEmpty(regressionOutput.eventDatas)) {
			for (EventData eventData : regressionOutput.eventDatas) {
				
				if (!(eventData instanceof RegressionData)) {
					continue;
				}
				
				RegressionData regressionData = (RegressionData)eventData;
				
				if (regressionData.regression != null) {
					continue;
				}
				
				EventResult newEvent = regressionData.event;
				
				if (newEvent.error_location != null) {
					result.append(newEvent.name);
					result.append(" in ");
					result.append(getSimpleClassName(newEvent.error_location.class_name));
					size++;
				} else {
					continue;
				}
					
				if (size == maxIssuesSize) {
					break;
				} else {
					if (size < issuesSize) {
						result.append(TEXT_SEPERATOR);
					}
				}
			}
		}
		
		int remaining = issuesSize - size;
		
		if (remaining > 0) {
			result.append("\nand ");
			result.append(remaining);
			result.append(" more");
		}
		
		return result.toString();
	}
	
	public static String getRegressionsDesc(RegressionOutput regressionOutput, int maxItems) {
		
		StringBuilder result = new StringBuilder();
		
		int size = 0;
		int regressionsSize = regressionOutput.regressions + regressionOutput.criticalRegressions;
		
		if (!CollectionUtil.safeIsEmpty(regressionOutput.eventDatas)) {
			
			for (EventData eventData : regressionOutput.eventDatas) {
				
				if (!(eventData instanceof RegressionData)) {
					continue;
				}
				
				RegressionData regressionData = (RegressionData)eventData;
				
				if (regressionData.regression == null) {
					continue;
				}
				
				double baseRate = (double) regressionData.regression.getBaselineHits() /
					(double)  regressionData.regression.getBaselineInvocations();
				
				double activeRate = (double) regressionData.event.stats.hits /
					(double) regressionData.event.stats.invocations;
	
				int delta = (int)((activeRate - baseRate) * 100);
				
				if (delta < 1000) {
					result.append("+"); 
					result.append(delta);
				} else {
					result.append(">1000"); 
				}
				
				result.append("% "); 
	
				result.append(regressionData.event.name);
				
				if (regressionData.event.error_location != null) {
					result.append(" in ");
					result.append(getSimpleClassName(regressionData.event.error_location.class_name));
				}
							
				size++;
				
				if (size == maxItems) {
					break;
				} else {
					if (size < regressionsSize) {
						result.append(TEXT_SEPERATOR);
					}
				}
			}
		}
		
		int remaining = regressionsSize - size;
		
		if (remaining > 0) {
			result.append("\nand ");
			result.append(remaining);
			result.append(" more");
		}
		
		return result.toString();
	}
		
	private double getServiceSingleStatCount(String serviceId, RegressionsInput input) {
		RegressionOutput regressionOutput = runRegression(serviceId, input, input.newOnly());
		
		if ((regressionOutput == null) || (regressionOutput.empty)) {
			return 0;
		}
		
		double result = 0;
		Collection<RegressionType> regressionTypes = input.getRegressionTypes();
		
		for (RegressionType regressionType : regressionTypes) {
			result += regressionOutput.getStat(regressionType);
		}
		
		return result;
	}
	
	private double getSingleStatCount(Collection<String> serviceIds, RegressionsInput input) {
		
		double result = 0;
		
		for (String serviceId : serviceIds) {
			result += getServiceSingleStatCount(serviceId, input);
		}
		
		return result;
	}
	
	private List<Series> processSingleStatCount(RegressionsInput input) {
		
		Collection<String> serviceIds = getServiceIds(input);
		
		if (CollectionUtil.safeIsEmpty(serviceIds)) {
			return Collections.emptyList();
		}
		
		Collection<RegressionType> regressionTypes = input.getRegressionTypes();

		if (regressionTypes == null) {
			return Collections.emptyList();
		}
		
		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);
		
		Object singleStatText;
		double singleStatValue = getSingleStatCount(serviceIds, input);
			
		if (input.singleStatFormat != null) {
			
			if (singleStatValue > 0) {
				singleStatText = String.format(input.singleStatFormat, String.valueOf((int)singleStatValue));
			} else {
				singleStatText = EMPTY_POSTFIX;
			}
		}
		else {
			singleStatText = Integer.valueOf((int)singleStatValue);
		}
		
		return createSingleStatSeries(timeSpan, singleStatText);
	}
	
	private List<Series> processSingleStatVolume(RegressionsInput input, boolean textValue) {
		
		Collection<String> serviceIds = getServiceIds(input);
		
		if (CollectionUtil.safeIsEmpty(serviceIds)) {
			return Collections.emptyList();
		}
		
		Collection<RegressionType> regressionTypes = input.getRegressionTypes();

		if (regressionTypes == null) {
			return Collections.emptyList();
		}
		
		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);
		
		long singleStatValue = 0;
		
		for (String serviceId : serviceIds) {
			
			RegressionOutput regressionOutput = runRegression(serviceId, input, input.newOnly());
			
			if ((regressionOutput == null) || (regressionOutput.empty)) {
				continue;
			}
						
			for (EventData eventData : regressionOutput.eventDatas) {
				
				RegressionData regData = (RegressionData)eventData;
				
				if (!regressionTypes.contains(regData.type)) {
					continue;
				}
				
				singleStatValue += regData.event.stats.hits;
			}
			
			singleStatValue += getServiceSingleStatCount(serviceId, input);
		}
			
		Object value;
		
		if (textValue) {
			value = formatLongValue(singleStatValue);
		} else {
			value = 	singleStatValue;
		}
			
		return createSingleStatSeries(timeSpan, value);
	}
	
	private List<Series> processSingleStatDesc(RegressionsInput input) {
		
		Collection<String> serviceIds = getServiceIds(input);
		
		if (CollectionUtil.safeIsEmpty(serviceIds)) {
			return Collections.emptyList();
		}
		
		Collection<RegressionType> regressionTypes = input.getRegressionTypes();

		if (regressionTypes == null) {
			return Collections.emptyList();
		}
		
		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);
		
		StringBuilder result = new StringBuilder();

		for (String serviceId : serviceIds) {
			
			RegressionOutput regressionOutput = runRegression(serviceId, input, input.newOnly());

			String value;
			
			if ((regressionTypes.contains(RegressionType.NewIssues)) 
			|| (regressionTypes.contains(RegressionType.SevereNewIssues))) {
				value = getNewIssuesDesc(regressionOutput, RegressionsInput.MAX_TOOLTIP_ITEMS);
			} else {
				value = getRegressionsDesc(regressionOutput, RegressionsInput.MAX_TOOLTIP_ITEMS);

			}
				
			if (serviceIds.size() > 1) {
				result.append(serviceId);
				result.append(" = ");
			}
				
			result.append(value);
			
			if (serviceIds.size() > 1) {
				result.append(". ");
			}
		}
			
		return createSingleStatSeries(timeSpan, result.toString());
	}
	
	@Override
	public List<Series> process(FunctionInput functionInput) {
		
		if (!(functionInput instanceof RegressionsInput)) {
			throw new IllegalArgumentException("functionInput");
		}
		
		RegressionsInput regInput = (RegressionsInput)getInput((ViewInput)functionInput);
		
		if (regInput.render == null) {
			throw new IllegalStateException("Missing render mode");
		}
		
		switch (regInput.render) {
			
			case Grid:
				return super.process(functionInput);
			
			case Graph:
				throw new IllegalStateException("Graph not supported. Use RegressionGraph");
				
			case SingleStat:
				return processSingleStatCount(regInput);
				
			case SingleStatDesc:
				return processSingleStatDesc(regInput);
				
			case SingleStatCount:
				return processSingleStatCount(regInput);
				
			case SingleStatVolume:
				return processSingleStatVolume(regInput, false);
				
			case SingleStatVolumeText:
				return processSingleStatVolume(regInput, true);
			
			default: 
				throw new IllegalStateException(String.valueOf(regInput.render));
		}
	}
}
