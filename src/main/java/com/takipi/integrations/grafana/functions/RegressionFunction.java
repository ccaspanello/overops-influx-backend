package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.metrics.Graph;
import com.takipi.api.client.data.metrics.Graph.GraphPoint;
import com.takipi.api.client.data.metrics.Graph.GraphPointContributor;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.metrics.GraphResult;
import com.takipi.api.client.util.regression.RateRegression;
import com.takipi.api.client.util.regression.RegressionInput;
import com.takipi.api.client.util.regression.RegressionResult;
import com.takipi.api.client.util.regression.RegressionStringUtil;
import com.takipi.api.client.util.regression.RegressionUtil;
import com.takipi.api.client.util.regression.RegressionInput.TypeThresholds;
import com.takipi.api.client.util.regression.RegressionUtil.RegressionWindow;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.input.EventFilterInput;
import com.takipi.integrations.grafana.input.EventsInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.RegressionsInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.GrafanaSettings;
import com.takipi.integrations.grafana.settings.RegressionReportSettings;
import com.takipi.integrations.grafana.settings.RegressionSettings;
import com.takipi.integrations.grafana.util.ApiCache;
import com.takipi.integrations.grafana.util.EventLinkEncoder;
import com.takipi.integrations.grafana.util.TimeUtil;

public class RegressionFunction extends EventsFunction
{
	
	private static String REG_DELTA = "regDelta";
	private static String REGRESSION = "regression";
	private static String SEVERITY = "severity";
	
	private static final String SEV_AND_NONSEV = "%d (%d p1)";
	private static final String SEVERE_ONLY = "%d p1";
	private static final String EMPTY_POSTFIX = ".";
	
	public static enum RegressionType
	{
		NewIssues,
		SevereNewIssues,
		Regressions,
		SevereRegressions,
		Slowdowns,
		SevereSlowdowns,
		Score;
	}
	
	public static class Factory implements FunctionFactory
	{
		
		@Override
		public GrafanaFunction create(ApiClient apiClient)
		{
			return new RegressionFunction(apiClient);
		}
		
		@Override
		public Class<?> getInputClass()
		{
			return RegressionsInput.class;
		}
		
		@Override
		public String getName()
		{
			return "regressions";
		}
	}
	
	protected class RegressionData extends EventData
	{
		
		protected RegressionType type;
		protected RegressionResult regression;
		protected RateRegression regResult;
		protected RegressionInput input;
		
		protected RegressionData(RateRegression regResult, RegressionInput input,
				EventResult event, RegressionType type)
		{
			super(event);
			this.type = type;
			this.regResult = regResult;
			this.input = input;
			this.regression = null;
		}
			
		protected RegressionData(RateRegression regResult, RegressionInput input,
				RegressionResult regression, RegressionType type)
		{
			this(regResult, input, regression.getEvent(), type);
			this.regression = regression;
		}
		
		@Override
		public boolean equals(Object obj)
		{
			if (!super.equals(obj))
			{
				return false;
			}
			
			RegressionData other = (RegressionData)obj;
			
			if (!Objects.equal(type, other.type))
			{
				return false;
			}
			
			return true;
		}
		
		public String getText()
		{
			
			switch (type)
			{
				case NewIssues:
					return RegressionStringUtil.NEW_ISSUE;
				case SevereNewIssues:
					return RegressionStringUtil.SEVERE_NEW;
				case Regressions:
					return RegressionStringUtil.REGRESSION;
				case SevereRegressions:
					return RegressionStringUtil.SEVERE_REGRESSION;
				case Slowdowns:
					return RegressionStringUtil.REGRESSION;
				case SevereSlowdowns:
					return RegressionStringUtil.SEVERE_REGRESSION;
				case Score:
					return String.valueOf(RegressionType.Score);
				default:
					return null;
			}
		}
		
		@Override
		public String toString()
		{
			return String.valueOf(type) + " " + event.toString();
		}
	}
	
	protected static class RegressionFullRateFormatter  extends FieldFormatter
	{
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan)
		{
			
			RegressionData regData = (RegressionData)eventData;
			
			if (regData.regression != null)
			{
				return RegressionStringUtil.getRegressedEventRate(regData.regression);
			}
			else
			{
				return RegressionStringUtil.getEventRate(regData.event);
			}
		}
	}
	
	protected static class RegressionRateFormatter extends FieldFormatter
	{
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan)
		{
			
			RegressionData regData = (RegressionData)eventData;
			
			if (regData.regression != null)
			{
				double baselineRate = (double) regData.regression.getBaselineHits() 
					/ (double) regData.regression.getBaselineInvocations()  * 100;
				
				double activeRate = (double) regData.event.stats.hits
						/ (double) regData.event.stats.invocations  * 100;
				
				double delta = activeRate - baselineRate;
					
				return (int)(delta);
				
			}
			else
			{
				return RegressionStringUtil.getEventRate(regData.event);
			}
		}
	}
	
	protected class RegressionSeverityFormatter extends FieldFormatter
	{
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan)
		{
			
			RegressionReportSettings settings = GrafanaSettings.getData(apiClient, serviceId).regression_report;
			
			if (settings == null) {
				return Integer.valueOf(0);
			}
			
			RegressionData regData = (RegressionData)eventData;
			
			switch (regData.type) {
				
				case NewIssues:
					return Integer.valueOf(settings.new_event_score);
					
				case SevereNewIssues:
					return Integer.valueOf(settings.severe_new_event_score);
					
				case Regressions:
				case Slowdowns:
					return Integer.valueOf(settings.regression_score);
					
				case SevereRegressions:
				case SevereSlowdowns:
					return Integer.valueOf(settings.critical_regression_score);
				default:
					break;
			}
			
			return Integer.valueOf(0);
		}
	}
	
	public static int expandBaselineTimespan(int baselineTimespanFactor, int minBaselineTimespan,
			RegressionWindow activeWindow)
	{
		
		int result;
		double factor = (double)minBaselineTimespan / (double)activeWindow.activeTimespan;
		
		if (factor > baselineTimespanFactor)
		{
			result = minBaselineTimespan;
		}
		else
		{
			result = activeWindow.activeTimespan * baselineTimespanFactor;
		}
		
		return result;
		
	}
	
	protected class RegressionLinkFormatter extends FieldFormatter
	{
		
		@Override
		protected Object getValue(EventData eventData, String serviceId, EventsInput input,
				Pair<DateTime, DateTime> timeSpan)
		{
			
			RegressionData regData = (RegressionData)eventData;
			
			DateTime from = regData.regResult.getActiveWndowStart().minusMinutes(regData.input.baselineTimespan);
			DateTime to = DateTime.now();
			
			return EventLinkEncoder.encodeLink(apiClient, serviceId, input, eventData.event, from, to);
		}
		
		@Override
		protected Object formatValue(Object value, EventsInput input)
		{
			return value;
		}
	}
	
	public RegressionFunction(ApiClient apiClient)
	{
		super(apiClient);
	}
	
	private void sortRegressions(String serviceId, List<EventData> eventData)
	{
		
		RegressionSettings regressionSettings = GrafanaSettings.getData(apiClient, serviceId).regression;
		
		if ((regressionSettings == null) || (regressionSettings.sort_order == null))
		{
			return;
		}
		
		List<String> order = Arrays.asList(regressionSettings.sort_order.split(GrafanaFunction.ARRAY_SEPERATOR));
		List<String> types = Arrays.asList(regressionSettings.type_order.split(GrafanaFunction.ARRAY_SEPERATOR));
		
		eventData.sort(new Comparator<EventData>()
		{
			
			@Override
			public int compare(EventData o1, EventData o2)
			{
				
				RegressionData r1 = (RegressionData)o1;
				RegressionData r2 = (RegressionData)o2;
				
				int result = order.indexOf(r1.getText()) - order.indexOf(r2.getText());
				
				if (result != 0)
				{
					return result;
				}
				
				result = types.indexOf(r1.event.type) - types.indexOf(r2.event.type);
				
				if (result != 0)
				{
					return result;
				}
				
				return (int)(o2.event.stats.hits - o1.event.stats.hits);
			}
		});
	}
	
	public List<EventData> processRegression(EventFilterInput functionInput, RegressionInput input,
			RateRegression rateRegression, boolean includeNew)
	{
		List<EventData> result;
		List<EventData> eventDatas = processRegressionData(input, rateRegression, includeNew);
				
		if (functionInput.hasTransactions()) {
			result = eventDatas;
		} else {
			result = doMergeSimilarEvents(input.serviceId, eventDatas);
		}
		
		return result;
	}
	
	private List<EventData> processRegressionData(RegressionInput input,
			RateRegression rateRegression, boolean includeNew)
	{
		
		List<EventData> result = new ArrayList<EventData>();
		
		if (includeNew)
		{
			
			for (EventResult event : rateRegression.getSortedCriticalNewEvents())
			{
				result.add(new RegressionData(rateRegression, input, event, RegressionType.SevereNewIssues));
			}
			
			for (EventResult event : rateRegression.getSortedExceededNewEvents())
			{
				result.add(new RegressionData(rateRegression, input, event, RegressionType.SevereNewIssues));
			}
			
			for (EventResult event : rateRegression.getSortedAllNewEvents())
			{
				
				if (rateRegression.getExceededNewEvents().containsKey(event.id))
				{
					continue;
				}
				
				if (rateRegression.getCriticalNewEvents().containsKey(event.id))
				{
					continue;
				}
				
				result.add(new RegressionData(rateRegression, input, event, RegressionType.NewIssues));
				
			}
		}
		
		for (RegressionResult regressionResult : rateRegression.getSortedCriticalRegressions())
		{
			
			RegressionType type;
			
			if (regressionResult.getEvent().type.equals(TIMER))
			{
				type = RegressionType.SevereSlowdowns;
			}
			else
			{
				type = RegressionType.SevereRegressions;
			}
			
			result.add(new RegressionData(rateRegression, input, regressionResult, type));
		}
		
		for (RegressionResult regressionResult : rateRegression.getSortedAllRegressions())
		{
			
			if (rateRegression.getCriticalRegressions().containsKey(regressionResult.getEvent().id))
			{
				continue;
			}
			
			RegressionType type;
			
			if (regressionResult.getEvent().type.equals(TIMER))
			{
				type = RegressionType.Slowdowns;
			}
			else
			{
				type = RegressionType.Regressions;
			}
			
			result.add(new RegressionData(rateRegression, input, regressionResult, type));
		}
		
		return result;
	}
	
	@Override
	protected FieldFormatter getFormatter(String column)
	{
		
		if (column.equals(REG_DELTA))
		{
			return new RegressionRateFormatter();
		}
		
		if (column.equals(REGRESSION))
		{
			return new RegressionFullRateFormatter();
		}
		
		if (column.equals(SEVERITY))
		{
			return new RegressionSeverityFormatter();
		}
		
		if (column.equals(LINK))
		{
			return new RegressionLinkFormatter();
		}
		
		return super.getFormatter(column);
	}
	
	public static class RegressionOutput
	{	
		public static RegressionOutput emptyOutput = new RegressionOutput(true);
		
		public boolean empty;
		
		public RegressionInput regressionInput;
		public RateRegression rateRegression;
		public Graph baseVolumeGraph;
		public Graph activeVolumeGraph;
		public Map<String, EventResult> eventListMap;
		public List<EventData> eventDatas;
		
		protected double score;
		long volume;
		
		protected int slowsdowns;
		protected int severeSlowsdowns;
		
		protected int severeNewIssues;
		protected int newIssues;
		
		protected int criticalRegressions;
		protected int regressions;
		
		public RegressionOutput(boolean empty) {
			this.empty = empty;
		}
		
		public double getScore(RegressionReportSettings reportSettings)
		{
			
			int newEventsScore = newIssues * reportSettings.new_event_score;
			int severeNewEventScore = severeNewIssues * reportSettings.severe_new_event_score;
			int criticalRegressionsScore = criticalRegressions * reportSettings.critical_regression_score;
			int regressionsScore = regressions * reportSettings.regression_score;
			
			long days = Math.max(1, TimeUnit.MINUTES.toDays(regressionInput.activeTimespan));			
			double score = (newEventsScore + severeNewEventScore + criticalRegressionsScore + regressionsScore) / days;
			double weightedScore = Math.max(100 - (reportSettings.score_weight * score), 0);
			
			return weightedScore;
		}
		
		public double getStat(RegressionType type, RegressionReportSettings settings) {
			
			switch (type) {	
				case NewIssues:
					return newIssues;
				
				case SevereNewIssues:
					return severeNewIssues;
				
				case Regressions:
					return regressions;
				
				case SevereRegressions:
					return criticalRegressions;
				
				case Slowdowns:
					return slowsdowns;
				
				case SevereSlowdowns:
					return severeSlowsdowns;
				
				case Score:
					return getScore(settings);
					
				default:
					return 0;
			}
		}
	}
	
	private RegressionSettings getRegressionSettings(String serviceId)
	{
		
		RegressionSettings regressionSettings = GrafanaSettings.getData(apiClient, serviceId).regression;
		
		if (regressionSettings == null)
		{
			throw new IllegalStateException("Missing regression settings for " + serviceId);
		}
		
		return regressionSettings;
	}
	
	private Pair<RegressionInput, RegressionWindow> getRegressionInput(String serviceId, String viewId,
			EventFilterInput input,
			Pair<DateTime, DateTime> timeSpan)
	{
		
		RegressionSettings regressionSettings = getRegressionSettings(serviceId);
		
		RegressionInput regressionInput = new RegressionInput();
		
		regressionInput.serviceId = serviceId;
		regressionInput.viewId = viewId;
		regressionInput.deployments = input.getDeployments(serviceId);
		
		regressionInput.activeTimespan = (int)TimeUnit.MILLISECONDS
				.toMinutes(timeSpan.getSecond().getMillis() - timeSpan.getFirst().getMillis());
		
		regressionInput.baselineTimespan = regressionSettings.min_baseline_timespan;
		
		RegressionWindow regressionWindow = ApiCache.getRegressionWindow(apiClient, regressionInput);
		
		if ((!CollectionUtil.safeIsEmpty(regressionInput.deployments)) && (!regressionWindow.deploymentFound))
		{
			return null;
		}
		
		int expandedBaselineTimespan = expandBaselineTimespan(regressionSettings.baseline_timespan_factor,
				regressionSettings.min_baseline_timespan,
				regressionWindow);
		
		regressionInput.activeWindowStart = regressionWindow.activeWindowStart;
		regressionInput.activeTimespan = regressionWindow.activeTimespan;
		regressionInput.baselineTimespan = expandedBaselineTimespan;
		
		regressionInput.applictations = input.getApplications(apiClient, serviceId);
		regressionInput.servers = input.getServers(serviceId);
		
		Collection<String> criticalExceptionTypes = regressionSettings.getCriticalExceptionTypes();
		
		regressionInput.criticalExceptionTypes = criticalExceptionTypes;
		
		regressionInput.minVolumeThreshold = regressionSettings.error_min_volume_threshold;
		regressionInput.minErrorRateThreshold = regressionSettings.error_min_rate_threshold;
		
		regressionInput.regressionDelta = regressionSettings.error_regression_delta;
		regressionInput.criticalRegressionDelta = regressionSettings.error_critical_regression_delta;
		regressionInput.applySeasonality = regressionSettings.apply_seasonality;
		
		TypeThresholds timerThresholds = new TypeThresholds();
		
		timerThresholds.regressionDelta = regressionSettings.timer_regression_delta;
		timerThresholds.criticalRegressionDelta = regressionSettings.timer_critical_regression_delta;
		timerThresholds.minVolumeThreshold = regressionSettings.timer_min_volume_threshold;
		timerThresholds.minErrorRateThreshold = regressionSettings.timer_min_rate_threshold;
	
		regressionInput.typeThresholdsMap = Collections.singletonMap(TIMER, timerThresholds);
		
		return Pair.of(regressionInput, regressionWindow);
		
	}
	
	private Pair<Graph, Graph> getRegressionGraphs(String serviceId, String viewId,
			RegressionInput regressionInput, RegressionWindow regressionWindow,
			EventFilterInput input)
	{
		
		int ratioBaselinePoints = (regressionInput.baselineTimespan / regressionWindow.activeTimespan) * 2;
		int baselineDays = (int)TimeUnit.MINUTES.toDays(regressionInput.baselineTimespan);
		
		int baselinePoints = Math.max(Math.min(ratioBaselinePoints, baselineDays / 3), input.pointsWanted);
		
		if (baselinePoints <= 0)
		{
			throw new IllegalStateException(
					"Negative points for dep " +	Arrays.toString(regressionInput.deployments.toArray()) + " " +
											ratioBaselinePoints + " " + baselineDays + "  " +
											regressionWindow.activeWindowStart);
		}
		
		EventFilterInput baselineInput;
		
		if (input.deployments != null) {
			Gson gson = new Gson();
			baselineInput = gson.fromJson(gson.toJson(input), EventFilterInput.class);
			baselineInput.deployments = null;
		} else {
			baselineInput = input;
		}
				
		/*
		Graph baselineGraph = getEventsGraph(serviceId, viewId, baselinePoints, 
				input, VolumeType.all,
				regressionWindow.activeWindowStart.minusMinutes(regressionInput.baselineTimespan),
				regressionWindow.activeWindowStart, 0, regressionInput.baselineTimespan);
	
		Graph activeWindowGraph = getEventsGraph(serviceId, viewId, input.pointsWanted, input,
				VolumeType.all, regressionWindow.activeWindowStart, DateTime.now(), regressionWindow.activeTimespan, 0);
		*/
		
		Collection<GraphSliceTask> baselineGraphTasks = getGraphTasks(serviceId, viewId, baselinePoints, baselineInput, 
				VolumeType.all, regressionWindow.activeWindowStart.minusMinutes(regressionInput.baselineTimespan),
			regressionWindow.activeWindowStart, regressionInput.baselineTimespan, 0, false);
		
		int graphActiveTimespan;
		
		if (input.hasDeployments()) {
			graphActiveTimespan = regressionWindow.activeTimespan;
		} else {
			graphActiveTimespan = 0;
		}
		
		Collection<GraphSliceTask> activeGraphTasks = getGraphTasks(serviceId, viewId, input.pointsWanted, 
			input, VolumeType.all, regressionWindow.activeWindowStart, DateTime.now(), 0, graphActiveTimespan, false);
		
		List<GraphSliceTask> graphTasks = new ArrayList<GraphSliceTask>(); 
		
		graphTasks.addAll(baselineGraphTasks);
		graphTasks.addAll(activeGraphTasks);
		
		Collection<GraphSliceTaskResult> graphSliceTaskResults = executeGraphTasks(graphTasks, false);
		
		List<GraphSliceTaskResult> baseLineGraphResults = new ArrayList<GraphSliceTaskResult>();
		List<GraphSliceTaskResult> activeGraphResults = new ArrayList<GraphSliceTaskResult>();
	
		for (GraphSliceTaskResult graphSliceTaskResult : graphSliceTaskResults) {
			
			if (baselineGraphTasks.contains(graphSliceTaskResult.task)) {
				baseLineGraphResults.add(graphSliceTaskResult);
			}
			
			if (activeGraphTasks.contains(graphSliceTaskResult.task)) {
				activeGraphResults.add(graphSliceTaskResult);
			}
		}
		
		Graph baselineGraph = mergeGraphs(baseLineGraphResults);
		Graph activeWindowGraph = mergeGraphs(activeGraphResults);		
		
		GraphResult activeGraphResult = new GraphResult();
		activeGraphResult.graphs = Collections.singletonList(activeWindowGraph);		
		
		GraphResult baselineGraphResult = new GraphResult();
		activeGraphResult.graphs = Collections.singletonList(baselineGraph);		
		
		ApiCache.putEventGraph(apiClient, serviceId, input, VolumeType.all, null,
				input.pointsWanted, 0, graphActiveTimespan, 0, Response.of(200, activeGraphResult));
		
		ApiCache.putEventGraph(apiClient, serviceId, baselineInput, VolumeType.all, null,
				input.pointsWanted, regressionInput.baselineTimespan, 0, 0, Response.of(200, baselineGraphResult));
		
		return Pair.of(baselineGraph, activeWindowGraph);
		
	}
	
	private Pair<Collection<EventResult>, Long> getEventList(String serviceId, Map<String, EventResult> eventListMap,
			Graph activeWindowGraph, EventFilterInput input)
	{
		long volume = 0;
		
		for (GraphPoint gp : activeWindowGraph.points)
		{
			
			if (gp.contributors == null)
			{
				continue;
			}
			
			for (GraphPointContributor gpc : gp.contributors)
			{		
				EventResult event = eventListMap.get(gpc.id);
				
				if (event != null)
				{
					event.stats.invocations += gpc.stats.invocations;
					event.stats.hits += gpc.stats.hits;
					volume += gpc.stats.hits;
				}
			}
		}
		
		EventFilter eventFilter = input.getEventFilter(apiClient, serviceId);
		
		Map<String, EventResult> filteredEvents = new HashMap<String, EventResult>();
		
		for (EventResult event : eventListMap.values())
		{	
			if (eventFilter.filter(event))
			{
				continue;
			}
			
			filteredEvents.put(event.id, event);
		}
		
		return Pair.of(filteredEvents.values(), Long.valueOf(volume));
	}
		
	protected RegressionOutput createRegressionOutput(EventFilterInput input,
			RegressionInput regressionInput,RateRegression rateRegression, Map<String, EventResult> eventListMap,
			Graph baseVolumeGraph, Graph activeVolumeGraph, long volume)
	{
		
		RegressionOutput result = new RegressionOutput(false);
		
		result.regressionInput = regressionInput;
		result.rateRegression = rateRegression;
		result.baseVolumeGraph = baseVolumeGraph;
		result.activeVolumeGraph = activeVolumeGraph;
		result.eventListMap = eventListMap;
		result.volume = volume;
		
		if ((regressionInput != null) && (rateRegression != null)) {
			
			result.eventDatas = processRegression(input, regressionInput, rateRegression, true);
			
			for (EventData eventData : result.eventDatas)
			{
				
				RegressionData regData = (RegressionData)eventData;
				
				if (regData.event.stats.hits == 0) 
					continue;
				
				switch (regData.type)
				{
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
					case Slowdowns:
						result.slowsdowns++;
						break;
					case SevereSlowdowns:
						result.severeSlowsdowns++;
						break;
					default:
				}
			}
		}
		
		return result;
	}
		
	public RegressionOutput executeRegression(String serviceId, EventFilterInput input)
	{
		
		String viewId = getViewId(serviceId, input.view);
		
		if (viewId == null)
		{
			return RegressionOutput.emptyOutput;
		}
		
		Pair<DateTime, DateTime> timespan = TimeUtil.getTimeFilter(input.timeFilter);
		
		Pair<RegressionInput, RegressionWindow> regressionInputs =
				getRegressionInput(serviceId, viewId, input, timespan);
		
		if (regressionInputs == null)
		{
			return RegressionOutput.emptyOutput;
		}
		
		RegressionInput regressionInput = regressionInputs.getFirst();
		RegressionWindow regressionWindow = regressionInputs.getSecond();
		
		Map<String, EventResult> eventListMap = getEventMap(serviceId, input, timespan.getFirst(), timespan.getSecond(),
				null, input.pointsWanted);
	
		Graph baselineGraph;
		Graph activeWindowGraph;
		
		if (eventListMap != null)
		{
			Pair<Graph, Graph> regressionGraphs =
					getRegressionGraphs(serviceId, viewId, regressionInput, regressionWindow, input);
			
			baselineGraph = regressionGraphs.getFirst();
			activeWindowGraph = regressionGraphs.getSecond();
		} else {
			baselineGraph = null;
			activeWindowGraph = null;		
		}
		
		if ((baselineGraph == null) ||	(activeWindowGraph == null) || (baselineGraph.points == null) ||
			(activeWindowGraph.points == null))
		{
			return RegressionOutput.emptyOutput;
		}
		
		Pair<Collection<EventResult>, Long> eventsPair = getEventList(serviceId, eventListMap, activeWindowGraph, input);
		
		Collection<EventResult> events = eventsPair.getFirst();
		long volume = eventsPair.getSecond().longValue();
		
		regressionInput.events = events;
		regressionInput.baselineGraph = baselineGraph;
		
		regressionInput.validate();
		
		RateRegression rateRegression = RegressionUtil.calculateRateRegressions(apiClient, regressionInput, null,
				false);
		
		RegressionOutput result = createRegressionOutput(input, regressionInput, rateRegression, eventListMap,
				baselineGraph, activeWindowGraph, volume);
				
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
		
		if (result.size() > 1) {
			System.out.println();
		}
		
		return result;
	}
	
	private EventData mergeRegressionsOfType(List<EventData> eventDatas)
	{
		
		RegressionData first = (RegressionData)(eventDatas.get(0));
		List<EventData> merged = super.mergeEventDatas(eventDatas);
		
		RegressionData result = new RegressionData(first.regResult, first.input, merged.get(0).event, first.type);
		
		if (first.regression != null)
		{	
			long baselineHits = 0;
			long baselineInvocations = 0;
			
			for (EventData eventData : eventDatas)
			{
				
				RegressionData regressionData = (RegressionData)eventData;
				
				if (regressionData.regression != null)
				{
					baselineHits += regressionData.regression.getBaselineHits();
					baselineInvocations += regressionData.regression.getBaselineInvocations();
				}
			}
			
			result.regression = RegressionResult.of(result.event, baselineHits, baselineInvocations);
		}
		
		return result;
	}
	
	@Override
	protected List<EventData> mergeEventDatas(List<EventData> eventDatas)
	{
		List<EventData> result = new ArrayList<EventData>();
		Map<RegressionType, List<EventData>> regressionMap = getRegressionMap(eventDatas);
		
		for (List<EventData> typeEvents : regressionMap.values()) {
			result.add(mergeRegressionsOfType(typeEvents));
		}
		
		return result;
	}
	
	private List<EventData> doMergeSimilarEvents(String serviceId, List<EventData> eventDatas)
	{
		List<EventData> result = new ArrayList<EventData>(super.mergeSimilarEvents(serviceId, eventDatas));
		sortRegressions(serviceId, result);
		return result;
	}
	
	@Override
	protected List<EventData> mergeSimilarEvents(String serviceId, List<EventData> eventDatas)
	{
		return eventDatas;
	}
	
	public RegressionOutput runRegression(String serviceId, EventFilterInput regInput)
	{
		RegressionOutput regressionOutput = ApiCache.getRegressionOutput(apiClient, serviceId, regInput, this, true);
		return regressionOutput;
	}
	
	@Override
	protected List<EventData> getEventData(String serviceId, EventsInput input,
			Pair<DateTime, DateTime> timeSpan)
	{
		
		RegressionsInput regInput = (RegressionsInput)input;
		RegressionOutput regressionOutput = runRegression(serviceId, regInput);
		
		if ((regressionOutput == null) || (regressionOutput.rateRegression == null) ||
			(regressionOutput.regressionInput == null))
		{
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
	
	protected RegressionReportSettings getRegressionReportSettings(String serviceId)
	{
		
		RegressionReportSettings reportSettings = GrafanaSettings.getData(apiClient, serviceId).regression_report;
		
		if (reportSettings == null)
		{
			throw new IllegalStateException("Unable to acquire regression report settings for " + serviceId);
		}
		
		return reportSettings;
	}
		
	private double getServiceSingleStat(String serviceId, RegressionsInput input)
	{
		
		RegressionOutput regressionOutput = runRegression(serviceId, input);
		RegressionReportSettings regressionReportSettings = getRegressionReportSettings(serviceId);
		
		if ((regressionOutput == null) || (regressionOutput.empty))
		{
			return 0;
		}
		
		double result = 0;
		Collection<RegressionType> regressionTypes = input.getRegressionTypes();
		
		for (RegressionType regressionType : regressionTypes) {
			result += regressionOutput.getStat(regressionType, regressionReportSettings);
		}
		
		return result;
	}
	
	private double getSingleStat(Collection<String> serviceIds, RegressionsInput input)
	{
		
		double result = 0;
		
		for (String serviceId : serviceIds)
		{
			result += getServiceSingleStat(serviceId, input);
		}
		
		return result;
	}
	
	protected static Object formatIssueType(int nonSevere, int severe)
	{
		
		Object result;
		
		if (severe > 0)
		{
			if ((nonSevere == 0) || (nonSevere == severe))
			{
				result = String.format(SEVERE_ONLY, severe);
			}
			else
			{
				result = String.format(SEV_AND_NONSEV, nonSevere + severe, severe);
			}
		}
		else
		{
			result = Integer.valueOf(nonSevere);
		}
		
		return result;
	}
	
	private List<Series> processSingleStat(RegressionsInput input)
	{
		
		Collection<String> serviceIds = getServiceIds(input);
		
		if (CollectionUtil.safeIsEmpty(serviceIds))
		{
			return Collections.emptyList();
		}
		
		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);
		
		Object singleStatText;
		double singleStatValue = getSingleStat(serviceIds, input);
		
		Collection<RegressionType> regressionTypes = input.getRegressionTypes();

		if (regressionTypes == null) {
			return Collections.emptyList();
		}
		
		if (regressionTypes.contains(RegressionType.Score))
		{
			singleStatText = Double.valueOf(singleStatValue / serviceIds.size());
		}
		else
		{
			if (input.singleStatFormat != null)
			{
				if (singleStatValue > 0)
				{
					singleStatText = String.format(input.singleStatFormat, String.valueOf((int)singleStatValue));
				}
				else
				{
					singleStatText = EMPTY_POSTFIX;
				}
			}
			else
			{
				singleStatText = Integer.valueOf((int)singleStatValue);
			}
		}
		
		return createSingleStatSeries(timeSpan, singleStatText);
	}
	
	@Override
	public List<Series> process(FunctionInput functionInput)
	{
		
		if (!(functionInput instanceof RegressionsInput))
		{
			throw new IllegalArgumentException("functionInput");
		}
		
		RegressionsInput regInput = (RegressionsInput)functionInput;
		
		if (regInput.render == null)
		{
			throw new IllegalStateException("Missing render mode");
		}
		
		switch (regInput.render)
		{
			case Grid:
				return super.process(functionInput);
			
			case Graph:
				throw new IllegalStateException("Graph not supported");
				
			default:
				return processSingleStat(regInput);
			
		}
	}
}
