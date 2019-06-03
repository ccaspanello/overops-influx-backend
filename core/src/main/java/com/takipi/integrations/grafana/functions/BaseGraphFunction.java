package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import org.joda.time.DateTime;

import com.google.common.base.Objects;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.util.settings.GroupSettings;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.input.BaseGraphInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.ServiceSettings;
import com.takipi.integrations.grafana.util.TimeUtil;

public abstract class BaseGraphFunction extends GrafanaFunction {

	protected static class GraphData {
		
		protected Map<Long, Long> points;
		protected long volume;
		protected String key;
		
		protected GraphData(String key) {
			this.key = key;
			this.points = new TreeMap<Long, Long>();
		}
		
		@Override
		public boolean equals(Object obj) {
			return Objects.equal(key, ((GraphData)obj).key);
		}
		
		@Override
		public int hashCode() {
			return key.hashCode();
		}
	}
	
	protected static class GraphSeries {
		
		protected Series series;
		protected long volume;
		protected String name; 
			
		protected static GraphSeries of(Series series, long volume, String name) {
			
			GraphSeries result = new GraphSeries();
			
			result.series = series;
			result.volume = volume;
			result.name = name;
			
			return result;
		}
	}
	
	protected class GraphAsyncTask extends BaseAsyncTask implements Callable<Object>{
		
		protected String serviceId;
		protected String viewId;
		protected String viewName;
		protected BaseGraphInput input;
		protected Pair<DateTime, DateTime> timeSpan;
		protected Collection<String> serviceIds;
		protected Object tag;

		protected GraphAsyncTask(String serviceId, String viewId, String viewName,
			BaseGraphInput input, Pair<DateTime, DateTime> timeSpan,
			Collection<String> serviceIds, String tag) {

			this.serviceId = serviceId;
			this.viewId = viewId;
			this.viewName = viewName;
			this.input = input;
			this.timeSpan = timeSpan;
			this.serviceIds = serviceIds;
			this.tag = tag;
		}

		@Override
		public Object call() {
			
			beforeCall();
			
			try {
				List<GraphSeries> serviceSeries = processServiceGraph(serviceIds, serviceId, viewId, viewName,
						input, timeSpan, tag);
	
				return new TaskSeriesResult(serviceSeries);
			}
			finally {
				afterCall();
			}
		
		}
		
		@Override
		public String toString() {
			return String.join(" ", "Graph", serviceId, viewId, viewName, 
				timeSpan.toString(), String.valueOf(tag));
		}
	}

	protected static class TaskSeriesResult {
		protected List<GraphSeries> data;

		protected TaskSeriesResult(List<GraphSeries> data) {
			this.data = data;
		}
	}

	public BaseGraphFunction(ApiClient apiClient) {
		super(apiClient);
	}
	
	public BaseGraphFunction(ApiClient apiClient, Map<String, ServiceSettings> settingsMaps) {
		super(apiClient, settingsMaps);
	}

	@Override
	protected String getSeriesName(BaseGraphInput input, String seriesName, 
		String serviceId, Collection<String> serviceIds) {
		
		String tagName;
		
		if (seriesName != null) {
			tagName = seriesName;
		} else {
			Collection<String> types = getTypes(serviceId, false, input);
			
			if (!CollectionUtil.safeIsEmpty(types)) {
				tagName = String.join(ARRAY_SEPERATOR_RAW + " ", types);
			} else {
				tagName = input.view;
			}
		}
				
		String result = getServiceValue(tagName, serviceId, serviceIds);
		
		return result;
	}
	
	
	protected GraphSeries getGraphSeries(GraphData graphData, String name, FunctionInput input) {
							
		String cleanName = cleanSeriesName(name);

		Series series = createGraphSeries(cleanName, graphData.volume);
		
		long volume = graphData.volume;
			
		for (Map.Entry<Long, Long> graphPoint : graphData.points.entrySet()) {				
			Object timeValue = getTimeValue(graphPoint.getKey().longValue(), input);
			series.values.add(Arrays.asList(new Object[] { timeValue, graphPoint.getValue() }));
		}
		
		return GraphSeries.of(series, volume, cleanName);
	}
	
	protected List<Series> limitSeries(List<GraphSeries> series, int limit) {
		
		List<GraphSeries> limitedGraphSeries = limitGraphSeries(series, limit);
		
		sortSeriesByName(limitedGraphSeries);
		
		List<Series> result = new ArrayList<Series>(limitedGraphSeries.size());
		
		for (GraphSeries graphSeries : limitedGraphSeries) {
			result.add(graphSeries.series);
		}
		
		return result;
	}
	
	protected List<GraphSeries> limitGraphSeries(List<GraphSeries> series, int limit) {
		
		List<GraphSeries> sorted = new ArrayList<GraphSeries>(series);
		
		sortSeriesByVolume(sorted);
		
		List<GraphSeries> result = new ArrayList<GraphSeries>();
		
		for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
			
			GraphSeries graphSeries = sorted.get(i);
			
			if (graphSeries.volume > 0) {
				result.add(graphSeries);
			}
		}
		return result;
	}
	
	protected void sortSeriesByVolume(List<GraphSeries> series) {
		series.sort(new Comparator<GraphSeries>() {

			@Override
			public int compare(GraphSeries o1, GraphSeries o2) {
				return (int)(o2.volume - o1.volume);
			}
		});
	}
	
	protected String cleanSeriesName(String name) {	
		String result = GroupSettings.fromGroupName(name.trim());	
		return result;
	}
	
	protected void sortSeriesByName(List<GraphSeries> series) {
		
		series.sort(new Comparator<GraphSeries>() {

			@Override
			public int compare(GraphSeries o1, GraphSeries o2) {
						
				String s1 = cleanSeriesName(o1.name);
				String s2 = cleanSeriesName(o2.name);

				return s1.compareTo(s2);
			}
		});
	}
	
	protected Collection<Callable<Object>> getTasks(Collection<String> serviceIds, BaseGraphInput input,
			Pair<DateTime, DateTime> timeSpan) {
		
		List<Callable<Object>> result = new ArrayList<Callable<Object>>();
		
		for (String serviceId : serviceIds) {

			Map<String, String> views = getViews(serviceId, input);

			for (Map.Entry<String, String> entry : views.entrySet()) {

				String viewId = entry.getKey();
				String viewName = entry.getValue();

				result.add(new GraphAsyncTask(serviceId, viewId, viewName, input,
						timeSpan, serviceIds, null));
			}
		}
		
		return result;
	}
	
	protected List<GraphSeries> processAsync(Collection<String> serviceIds, BaseGraphInput request,
			Pair<DateTime, DateTime> timeSpan) {
 
		Collection<Callable<Object>> tasks = getTasks(serviceIds, request, timeSpan);
		List<Object> taskResults = executeTasks(tasks, true);
		
		List<GraphSeries> result = new ArrayList<GraphSeries>();
		
		for (Object taskResult : taskResults) {
			
			if (taskResult instanceof TaskSeriesResult) {
				TaskSeriesResult asyncResult = (TaskSeriesResult)taskResult;
				
				if (asyncResult.data != null) {
					result.addAll(asyncResult.data);
				}
			}
		}
		
		return result;
	}

	protected abstract List<GraphSeries> processServiceGraph(Collection<String> serviceIds, String serviceId, 
		String viewId, String viewName, BaseGraphInput input, 
		Pair<DateTime, DateTime> timeSpan, Object tag);

	protected Map<String, String> getViews(String serviceId, BaseGraphInput input) {
		String viewId = getViewId(serviceId, input.view);

		if (viewId != null) {
			return Collections.singletonMap(viewId, input.view);
		} else {
			return Collections.emptyMap();
		}
	}

	/**
	 * @param serviceIds  - needed by child classes
	 * @param input - needed by child classes 
	 */
	protected List<Series> processSeries(Collection<String> serviceIds, List<GraphSeries> series, BaseGraphInput input) {

		sortSeriesByName(series);
		
		List<Series> result = new ArrayList<Series>();

		for (GraphSeries entry : series) {
			result.add(entry.series);
		}

		return result;
	}
	
	protected List<GraphSeries> processSync(Collection<String> serviceIds, BaseGraphInput input,
			Pair<DateTime, DateTime> timeSpan) {
		
		List<GraphSeries> series = new ArrayList<GraphSeries>();
		
		Collection<Callable<Object>> tasks = getTasks(serviceIds, input, timeSpan);
		
		for (Callable<Object> task : tasks) {
			
			TaskSeriesResult taskResult;
			try {
				taskResult = (TaskSeriesResult)(task.call());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			
			if (taskResult.data != null) {
				series.addAll(taskResult.data);
			}
		}
		
		return series;
	}
	
	protected boolean isAsync(Collection<String> serviceIds) {
		return serviceIds.size() > 1;
	}

	@Override
	public List<Series> process(FunctionInput functionInput) {
		if (!(functionInput instanceof BaseGraphInput)) {
			throw new IllegalArgumentException("functionInput");
		}

		BaseGraphInput input = (BaseGraphInput) functionInput;

		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);

		Collection<String> serviceIds = getServiceIds(input);

		List<GraphSeries> series;
		
		boolean async = isAsync(serviceIds);
		
		if (async) {
			series = processAsync(serviceIds, input, timeSpan);
		} else {	
			series = processSync(serviceIds, input, timeSpan);
		}
		
		List<Series> result = processSeries(serviceIds, series, input);

		return result;
	}
}
