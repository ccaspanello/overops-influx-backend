package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.metrics.Graph;
import com.takipi.api.client.data.metrics.Graph.GraphPoint;
import com.takipi.api.client.data.metrics.Graph.GraphPointContributor;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.input.BaseGraphInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.FunctionInput.TimeFormat;
import com.takipi.integrations.grafana.input.GraphInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.ServiceSettings;
import com.takipi.integrations.grafana.util.TimeUtil;

public class GraphFunction extends BaseGraphFunction {

	protected static boolean PRINT_GRAPH_EVENTS = false;
	
	public static class Factory implements FunctionFactory {

		@Override
		public GrafanaFunction create(ApiClient apiClient) {
			return new GraphFunction(apiClient);
		}

		@Override
		public Class<?> getInputClass() {
			return GraphInput.class;
		}

		@Override
		public String getName() {
			return "graph";
		}
	}
	
	protected static class SeriesVolume {
		
		protected List<List<Object>> values;
		protected long volume;
		
		protected static SeriesVolume of(List<List<Object>> values, long volume) {
			SeriesVolume result = new SeriesVolume();
			result.volume = volume;
			result.values = values;
			return result;
		}
	}
	
	public GraphFunction(ApiClient apiClient) {
		super(apiClient);
	}
	
	public GraphFunction(ApiClient apiClient, Map<String, ServiceSettings> settingsMaps) {
		super(apiClient, settingsMaps);
	}
	
	@Override
	protected List<GraphSeries> processServiceGraph(Collection<String> serviceIds, String serviceId, String viewId, String viewName,
			BaseGraphInput input, Pair<DateTime, DateTime> timeSpan, int pointsWanted) {
		
		return doProcessServiceGraph(serviceIds, serviceId,
			viewId, input, timeSpan, pointsWanted);
	}

	protected List<GraphSeries> doProcessServiceGraph(Collection<String> serviceIds, String serviceId, 
			String viewId, BaseGraphInput input, 
			Pair<DateTime, DateTime> timeSpan, int pointsWanted) {

		GraphInput graphInput = (GraphInput) input;

		Graph graph = getEventsGraph(serviceId, viewId, pointsWanted, graphInput, 
			graphInput.volumeType, timeSpan.getFirst(), timeSpan.getSecond());
		
		if (graph == null) {
			return Collections.emptyList();
		}
						
		SeriesVolume seriesData = processGraphPoints(serviceId, viewId, timeSpan, graph, graphInput);

		String tagName = getSeriesName(input, input.seriesName, serviceId, serviceIds);		
		String cleanTagName = cleanSeriesName(tagName);
		
		List<List<Object>> values;
		
		if ((graphInput.condense) && (seriesData.values.size() > pointsWanted) &&
			(graphInput.getTimeFormat() == TimeFormat.EPOCH)) {
			values = condensePoints(seriesData.values, pointsWanted);
		} else {
			values = seriesData.values;
		}

		Series series = createGraphSeries(cleanTagName, seriesData.volume, values);

		return Collections.singletonList(GraphSeries.of(series, seriesData.volume, cleanTagName));

	}
	
	private static long getPointTime(List<List<Object>> points, int index) {
		return ((Long)(points.get(index).get(0))).longValue();
	}
	
	private static long getPointValue(List<List<Object>> points, int index) {
		return ((Long)(points.get(index).get(1))).longValue();
	}
	
	private List<List<Object>> condensePoints(List<List<Object>> points, 
		int pointsWanted) {
				
		double groupSize = (points.size() - 2) / ((double)pointsWanted - 2);
		double currSize = groupSize;

		long[] values = new long[pointsWanted - 2];

		int index = 0;
		
		for (int i = 1; i < points.size() - 1; i++) {
			
			long pointValue = getPointValue(points, i);
			
			if (currSize >= 1) {
				values[index] += pointValue;	
				currSize--;
			} else {
				
				values[index] += pointValue * currSize;
				index++;
				values[index] += pointValue * (1 - currSize);
				currSize = groupSize - (1 - currSize);
			}
		}
		
		List<List<Object>> result = new ArrayList<List<Object>>(pointsWanted);
		
		long start = getPointTime(points, 0);
		long end = getPointTime(points, points.size() - 1);
	
		long timeDelta = (end - start) / (pointsWanted -1);
		
		result.add(points.get(0));
		
		for (int i = 0; i < values.length; i++) {
			
			long time = start + timeDelta * (i + 1);
			long value = values[i] / (long)groupSize;
			result.add(Arrays.asList(new Object[] {Long.valueOf(time), Long.valueOf(value) }));
		}

		result.add(points.get(points.size() - 1));

		return result;
	}

	/**
	 * @param viewId - needed by children 
	 */
	protected SeriesVolume processGraphPoints(String serviceId, String viewId, 
			Pair<DateTime, DateTime> timeSpan, Graph graph, GraphInput input) {

		long volume = 0;
	
		List<List<Object>> values = new ArrayList<List<Object>>(graph.points.size());
		
		EventFilter eventFilter;
		Map<String, EventResult> eventMap;
		
		if (input.hasEventFilter()) {
			
			eventMap = getEventMap(serviceId, input, timeSpan.getFirst(), timeSpan.getSecond(),
				input.volumeType, input.pointsWanted);
			
			eventFilter = getEventFilter(serviceId, input, timeSpan);
			
			if (eventFilter == null) {
				return SeriesVolume.of(values, volume);
			}

		} else {
			eventMap = null;
			eventFilter = null;
		}
		
		Map<String, Long> debugMap;
		
		if (PRINT_GRAPH_EVENTS) {
			debugMap = new HashMap<String, Long>();
		} else {
			debugMap = null;
		}	
		
		for (GraphPoint gp : graph.points) {

			Object timeValue;
			
			if (input.getTimeFormat() == TimeFormat.ISO_UTC) {
				timeValue = gp.time;
			} else {
				DateTime gpTime = TimeUtil.getDateTime(gp.time);
				timeValue = Long.valueOf(gpTime.getMillis());
			}	

			if (gp.contributors == null) {
				values.add(Arrays.asList(new Object[] {timeValue , Long.valueOf(0l) }));
				continue;
			}
			
			EventResult event = null;
			long value = 0;

			for (GraphPointContributor gpc : gp.contributors) {

				if (eventMap != null) {
					event = eventMap.get(gpc.id);

					if ((event == null) || ((eventFilter != null) && (eventFilter.filter(event)))) {
						continue;
					}
				}
				
				if (debugMap != null) {
					Long v = debugMap.get(gpc.id);
					
					if (v != null) {
						debugMap.put(gpc.id, v + gpc.stats.hits);
					} else {
						debugMap.put(gpc.id, gpc.stats.hits);
					}
				}

				if (input.volumeType.equals(VolumeType.invocations)) {
					value += gpc.stats.invocations;
				} else {
					value += gpc.stats.hits;
				}
			}

			volume += value;
			values.add(Arrays.asList(new Object[] { timeValue, Long.valueOf(value) }));
		}
			
		if (debugMap != null) {
			for (Map.Entry<String, Long> entry : debugMap.entrySet()) {
				System.err.println(entry.getKey() + " = " + entry.getValue());
			}
		}
		
		return SeriesVolume.of(values,volume);
	}

	@Override
	public List<Series> process(FunctionInput functionInput) {
		
		if (!(functionInput instanceof GraphInput)) {
			throw new IllegalArgumentException("functionInput");
		}

		GraphInput input = (GraphInput) functionInput;

		if ((input.volumeType == null)) {
			throw new IllegalArgumentException("volumeType");
		}

		return super.process(functionInput);
	}
}
