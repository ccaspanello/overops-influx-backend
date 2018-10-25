package com.takipi.integrations.grafana.functions;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.takipi.common.api.ApiClient;
import com.takipi.common.api.result.event.EventResult;
import com.takipi.common.api.util.Pair;
import com.takipi.common.api.util.ValidationUtil.VolumeType;
import com.takipi.integrations.grafana.input.BaseVolumeInput;
import com.takipi.integrations.grafana.input.BaseVolumeInput.AggregationType;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.utils.TimeUtils;

public abstract class BaseVolumeFunction extends GrafanaFunction {

	protected static class EventVolume {
		protected double sum;
		protected long count;
	}

	public BaseVolumeFunction(ApiClient apiClient) {
		super(apiClient);
	}

	@Override
	public List<Series> process(FunctionInput functionInput) {

		if (!(functionInput instanceof BaseVolumeInput)) {
			throw new IllegalArgumentException("GraphInput");
		}

		BaseVolumeInput input = (BaseVolumeInput) functionInput;

		if (AggregationType.valueOf(input.type) == null) {
			throw new IllegalArgumentException("type");
		}

		return null;
	}

	protected List<Series> createSeries(BaseVolumeInput input, Pair<String, String> timeSpan, EventVolume volume,
			AggregationType type) {
		Series series = new Series();

		series.name = SERIES_NAME;
		series.columns = Arrays.asList(new String[] { TIME_COLUMN, SUM_COLUMN });

		Object value;

		switch (type) {
		case sum:
			value = Double.valueOf(volume.sum);
			break;

		case avg:
			value = Double.valueOf((double) volume.sum / (double) volume.count);
			break;

		case count:
			value = Long.valueOf(volume.count);
			break;

		default:
			throw new IllegalStateException(input.type);
		}

		Long time = Long.valueOf(TimeUtils.getLongTime(timeSpan.getSecond()));

		series.values = Collections.singletonList(Arrays.asList(new Object[] { time, value }));

		return Collections.singletonList(series);
	}

	private EventVolume processServiceVolume(String serviceId, BaseVolumeInput input, VolumeType volumeType,
			Pair<String, String> timeSpan) {

		EventVolume result = new EventVolume();

		Collection<EventResult> events = getEventList(serviceId, input, timeSpan, volumeType);

		if (events == null) {
			return result;
		}

		EventFilter eventFilter = input.getEventFilter(serviceId);

		for (EventResult event : events) {

			if (eventFilter.filter(event)) {
				continue;
			}

			if (event.stats != null) {
				switch (volumeType) {
				case invocations:
					result.sum += event.stats.invocations;
					break;

				case hits:
				case all:
					result.sum += event.stats.hits;
					break;
				}
			}
			result.count++;
		}

		return result;
	}

	protected EventVolume getEventVolume(BaseVolumeInput input, VolumeType volumeType,
			Pair<String, String> timeSpan) {

		String[] serviceIds = getServiceIds(input);

		EventVolume volume = new EventVolume();

		for (String serviceId : serviceIds) {
			EventVolume serviceVolume = processServiceVolume(serviceId, input, volumeType, timeSpan);

			volume.sum = volume.sum + serviceVolume.sum;
			volume.count = volume.count + serviceVolume.count;
		}

		return volume;
	}

}
