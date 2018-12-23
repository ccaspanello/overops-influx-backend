package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.takipi.api.client.ApiClient;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.VariableInput;
import com.takipi.integrations.grafana.output.Series;

public abstract class VariableFunction extends GrafanaFunction {

	private static final String KEY = "key";
	
	protected static class VariableAppender {
				protected Series series;
		
		protected void append(String value) {
			series.values.add(Arrays.asList(new Object[] {KEY, value}));
		}
	}
	
	public VariableFunction(ApiClient apiClient) {
		super(apiClient);
	}

	protected String getStringValue(Object o) {
		return o.toString().toLowerCase();
	}
	
	protected int compareValues(String o1, String o2) {
		return o1.compareTo(o2);
	}
	
	@SuppressWarnings("unchecked")
	private int compareListValues(Object o1, Object o2) {
		String a = getStringValue(((List<Object>) o1).get(1));
		String b = getStringValue(((List<Object>) o2).get(1));

		return compareValues(a, b);
	}
		
	protected void sort(List<List<Object>> series) {
		series.sort(new Comparator<Object>() {

			@Override
			public int compare(Object o1, Object o2) {
				return compareListValues(o1, o2);
			}
		});
	}
		
	protected abstract void populateValues(FunctionInput input, VariableAppender appender);
	
	@Override
	public  List<Series> process(FunctionInput functionInput) {
			
		if (!(functionInput instanceof VariableInput)) {
			throw new IllegalArgumentException("functionInput");
		}
		
		VariableInput varInput = (VariableInput)functionInput;

		Series series = new Series();
		series.name = SERIES_NAME;
		series.columns = Arrays.asList(new String[] { KEY_COLUMN, VALUE_COLUMN });
		series.values = new ArrayList<List<Object>>();
		
		VariableAppender appender = new VariableAppender();
		appender.series = series;
				
		populateValues(functionInput, appender);
		
		if (varInput.sorted) {
			sort(series.values);
		}
				
		return Collections.singletonList(series);
	}	
}
