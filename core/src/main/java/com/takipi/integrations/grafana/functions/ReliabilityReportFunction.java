package com.takipi.integrations.grafana.functions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.ocpsoft.prettytime.PrettyTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.deployment.SummarizedDeployment;
import com.takipi.api.client.data.metrics.Graph;
import com.takipi.api.client.data.metrics.Graph.GraphPoint;
import com.takipi.api.client.data.metrics.Graph.GraphPointContributor;
import com.takipi.api.client.data.transaction.TransactionGraph;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.event.EventSlimResult;
import com.takipi.api.client.result.event.EventsSlimVolumeResult;
import com.takipi.api.client.util.infra.Categories;
import com.takipi.api.client.util.performance.calc.PerformanceState;
import com.takipi.api.client.util.regression.RegressionInput;
import com.takipi.api.client.util.regression.RegressionUtil.RegressionWindow;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.functions.RegressionFunction.RegressionOutput;
import com.takipi.integrations.grafana.functions.ReliabilityKpiGraphFunction.KpiInterval;
import com.takipi.integrations.grafana.functions.ReliabilityKpiGraphFunction.ScoreInterval;
import com.takipi.integrations.grafana.functions.ReliabilityKpiGraphFunction.TaskKpiResult;
import com.takipi.integrations.grafana.input.EventsInput;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.RegressionsInput;
import com.takipi.integrations.grafana.input.RegressionsInput.RenderMode;
import com.takipi.integrations.grafana.input.ReliabilityKpiGraphInput;
import com.takipi.integrations.grafana.input.ReliabilityReportInput;
import com.takipi.integrations.grafana.input.ReliabilityReportInput.RelabilityKpi;
import com.takipi.integrations.grafana.input.ReliabilityReportInput.ReliabilityState;
import com.takipi.integrations.grafana.input.ReliabilityReportInput.ReportMode;
import com.takipi.integrations.grafana.input.ReliabilityReportInput.ScoreType;
import com.takipi.integrations.grafana.input.ReliabilityReportInput.SortType;
import com.takipi.integrations.grafana.input.ViewInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.GrafanaSettings;
import com.takipi.integrations.grafana.settings.GroupSettings;
import com.takipi.integrations.grafana.settings.input.RegressionReportSettings;
import com.takipi.integrations.grafana.util.ApiCache;
import com.takipi.integrations.grafana.util.DeploymentUtil;
import com.takipi.integrations.grafana.util.TimeUtil;

public class ReliabilityReportFunction extends EventsFunction {
	
	private static final Logger logger = LoggerFactory.getLogger(ReliabilityReportFunction.class);
	
	private static final String DEFAULT_KEY = "";
	
	private static final SimpleDateFormat singleDayDateformat;
	private static final SimpleDateFormat dayInMonthDateformat;
	
	static  {
		singleDayDateformat = new SimpleDateFormat("EEEE"); 
		dayInMonthDateformat = new SimpleDateFormat("EEE, MMM d"); 
		
		singleDayDateformat.setTimeZone(TimeZone.getTimeZone("UTC"));
		dayInMonthDateformat.setTimeZone(TimeZone.getTimeZone("UTC"));
	}
	
	public static class Factory implements FunctionFactory {
		
		@Override
		public GrafanaFunction create(ApiClient apiClient)
		{
			return new ReliabilityReportFunction(apiClient);
		}
		
		@Override
		public Class<?> getInputClass()
		{
			return ReliabilityReportInput.class;
		}
		
		@Override
		public String getName()
		{
			return "regressionReport";
		}
	}
	
	protected static class ReportKey implements Comparable<ReportKey>{
		
		protected String name;
		protected boolean isKey;
		
		protected ReportKey(String name, boolean isKey) {
			this.name = name;
			this.isKey = isKey;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
		
		@Override
		public boolean equals(Object obj)
		{
			if (!(obj instanceof ReportKey)) {
				return false;
			}
			
			return Objects.equal(name, ((ReportKey)(obj)).name);
		}
		
		@Override
		public int hashCode()
		{
			return name.hashCode();
		}

		@Override
		public int compareTo(ReportKey o)
		{
			return name.compareTo(o.name);
		}
	}
	
	protected static class DeploymentReportKey extends ReportKey {
		
		protected SummarizedDeployment previous;
		
		protected DeploymentReportKey(String name, boolean isKey, SummarizedDeployment previous) {
			super(name, isKey);
			this.previous = previous;
		}
	}
	
	protected static class KeyOutputEventVolume {
		protected long volume;
		protected int count;
		protected double rate;
	}
	
	protected static class ReportKeyOutput {
		protected ReportKey reportKey;
		protected RegressionKeyData regressionData;
		protected Map<TransactionKey, TransactionData> transactionMap;
		
		protected ReportKeyOutput(ReportKey key) {
			this.reportKey = key;
		}
	}
	
	protected static class ReportKeyResults {
		
		protected ReportKeyOutput output;
		
		protected int newIssues;
		protected int severeNewIssues;
		protected int criticalRegressions;
		protected int regressions;
		protected int slowdowns;
		
		protected double score;

		protected int severeSlowdowns;
		protected String slowDownsDesc; 
		protected String regressionsDesc; 
		protected String newIssuesDesc; 
		
		protected String description; 
		protected String scoreDesc;
		
		protected KeyOutputEventVolume volumeData;
		
		protected ReportKeyFailures failures;
		protected ReportKeyTransactions transactions;
		protected ReportKeyReliability relability;
			
		
		protected ReportKeyResults(ReportKeyOutput output) {
			this.output = output;	
		}
	}
	
	protected static class ReportAsyncResult {
		protected ReportKey key;
		
		protected ReportAsyncResult(ReportKey key) {
			this.key = key;
		}
	}
	
	protected static class RegressionAsyncResult extends ReportAsyncResult {
		protected RegressionOutput output;
		
		protected RegressionAsyncResult(ReportKey key, RegressionOutput output) {
			super(key);
			this.output = output;
		}
	}
	
	protected static class SlowdownAsyncResult extends ReportAsyncResult{
		
		protected Map<TransactionKey, TransactionData> transactionMap;
		
		protected SlowdownAsyncResult(ReportKey key, Map<TransactionKey, TransactionData> transactionMap) {
			super(key);
			this.transactionMap = transactionMap;
		}
	}

	public class SlowdownAsyncTask extends BaseAsyncTask implements Callable<Object> {
		
		protected String viewId;
		protected ReportKey reportKey;
		protected String serviceId;
		protected RegressionsInput input;
		protected Pair<DateTime, DateTime> timeSpan;
		protected boolean updateEvents;

		protected SlowdownAsyncTask(String serviceId, String viewId,  
				ReportKey key, RegressionsInput input,
				Pair<DateTime, DateTime> timeSpan, boolean updateEvents) {

			this.reportKey = key;
			this.serviceId = serviceId;
			this.input = input;
			this.timeSpan = timeSpan;
			this.viewId = viewId;
			this.updateEvents = updateEvents;
		}

		@Override
		public Object call() {

			beforeCall();

			try {

				RegressionFunction regressionFunction = new RegressionFunction(apiClient);
				Pair<RegressionInput, RegressionWindow> regPair = regressionFunction.getRegressionInput(serviceId, 
					viewId, input, timeSpan, false);
				
				if (regPair == null) {
					return new SlowdownAsyncResult(reportKey, Collections.emptyMap());
				}
					
				RegressionWindow regressionWindow = regPair.getSecond();
				
				DateTime to = DateTime.now();
				DateTime from = to.minusMinutes(regressionWindow.activeTimespan);
				Pair<DateTime, DateTime> activeWindow = Pair.of(from, to);
				
				Collection<TransactionGraph> transactionGraphs = getTransactionGraphs(input,
						serviceId, viewId, activeWindow, input.getSearchText(), 
						input.pointsWanted, regressionWindow.activeTimespan, 0);
								
				TransactionDataResult transactionDataResult = getTransactionDatas(
						transactionGraphs, serviceId, viewId, timeSpan, input, updateEvents, 0);				
				
				SlowdownAsyncResult result;
				
				if (transactionDataResult != null) {
					result = new SlowdownAsyncResult(reportKey, transactionDataResult.items);
				} else {
					result = new SlowdownAsyncResult(reportKey, Collections.emptyMap());
				}
							
				return result;
			} finally {
				afterCall();
			}
		}

		@Override
		public String toString() {
			return String.join(" ", "Regression", serviceId, reportKey.name);
		}
	}
	
	public static class RegressionAsyncTask extends BaseAsyncTask implements Callable<Object> {

		protected RegressionFunction function;
		protected ReportKey reportKey;
		protected String serviceId;
		protected RegressionsInput input;
		protected Pair<DateTime, DateTime> timeSpan;
		protected boolean newOnly;

		protected RegressionAsyncTask(RegressionFunction function, ReportKey key, String serviceId, RegressionsInput input,
				Pair<DateTime, DateTime> timeSpan, boolean newOnly) {

			this.function = function;
			this.reportKey = key;
			this.serviceId = serviceId;
			this.input = input;
			this.timeSpan = timeSpan;
			this.newOnly = newOnly;
		}

		@Override
		public Object call() {

			beforeCall();

			try {

				RegressionOutput output = function.runRegression(serviceId, input, newOnly); 
				RegressionAsyncResult result = new RegressionAsyncResult(reportKey, output);
				
				return result;
			} finally {
				afterCall();
			}

		}

		@Override
		public String toString() {
			return String.join(" ", "Regression", serviceId, reportKey.name);
		}
	}

	protected static class VolumeOutput {
		
		protected String key;
		protected long volume;
		
		protected VolumeOutput(String key) {
			this.key = key;
		}
	}

	protected class AppVolumeAsyncTask extends BaseAsyncTask implements Callable<Object> {

		protected String app;
		protected String serviceId;
		protected RegressionsInput input;
		protected Pair<DateTime, DateTime> timeSpan;

		protected AppVolumeAsyncTask(RegressionsInput input, String serviceId, String app,
				Pair<DateTime, DateTime> timeSpan) {

			this.app = app;
			this.serviceId = serviceId;
			this.timeSpan = timeSpan;
			this.input = input;
		}

		@Override
		public Object call() {

			beforeCall();

			try {
				
				long volume = 0;
				String viewId = getViewId(serviceId, input.view);
				
				if (viewId != null)
				{
					EventsSlimVolumeResult eventsVolume = getEventsVolume(serviceId, viewId, input, timeSpan.getFirst(),
							timeSpan.getSecond(), VolumeType.hits);

					if (eventsVolume != null) {
						for (EventSlimResult eventResult : eventsVolume.events) {
							volume += eventResult.stats.hits;
						}
					}
				}		

				VolumeOutput result = new VolumeOutput(app);
				result.volume = volume;

				return result;
			} finally {
				afterCall();
			}

		}

		@Override
		public String toString() {
			return String.join(" ", "App Volume", serviceId, app);
		}
	}
	
	protected class ReportKeyReliability {
		protected ReliabilityState failRateState;
		protected ReliabilityState scoreState;
		protected ReliabilityState reliabilityState;
		protected double failureRateDelta;
		protected double failRate;	
		protected String failureRateDesc;
		protected String relabilityDesc;

	}
	
	protected class ReportKeyTransactions {
		protected long transactionVolume;
		protected long baseTransactions;
		protected String deltaDesc;
		protected long errorVolume;
		protected double avgTimeDelta;
		protected String responseValue;	
	}
	
	protected static class ReportKeyFailures {
		protected long failures;
		protected long baseFailures;
		protected long eventCount;
	}
	
	protected class ReportRow {
		
		protected List<String> fields;
		protected List<Object> values;
		
		protected ReportRow(List<String> fields) {
			this.fields = fields;
			values = Arrays.asList(new Object[fields.size()]);

		}
		
		protected void set(String field, Object value) {
				
			int index = fields.indexOf(field);
				
			if (index != -1) {
				values.set(index, value);
			}
		}
	}
	

	public ReliabilityReportFunction(ApiClient apiClient) {
		super(apiClient);
	}

	private ReliabilityReportInput getInput(ReliabilityReportInput reportInput, 
		String serviceId, String name, boolean mustCopy) {
		
		ReportMode mode = reportInput.getReportMode();
		
		if ((mode == ReportMode.Default) && (!mustCopy)) {
			return reportInput;
		}
		
		Gson gson = new Gson();
		String json = gson.toJson(reportInput);
		ReliabilityReportInput result = gson.fromJson(json, ReliabilityReportInput.class);

		if (mode == ReportMode.Default) {
			return result;
		}
		
		switch (mode) {
		
			case Deployments:
				result.deployments = name;
				break;
				
			case Tiers:
				List<String> types = new ArrayList<String>();
				Collection<String> inputTypes = reportInput.getTypes(apiClient, serviceId);
				
				if (inputTypes != null) {
					for (String type : inputTypes) {
						
						if (EventFilter.isExceptionFilter(type)) {
							types.add(type);
						} else if (GroupSettings.isGroup(type)) {
							continue;
						} else {
							types.add(type);
						}
					}
				}
				
				types.add(GroupSettings.toGroupName(name));
					
				result.types = String.join(GrafanaFunction.ARRAY_SEPERATOR_RAW, types);
				break;
			
			case Applications:
			case Apps_Extended:
				result.applications = name;
				break;
			
			default:
				break;	
		}

		return result;
	}

	@Override
	protected List<String> getColumns(EventsInput input) {
		
		ReliabilityReportInput rrInput = (ReliabilityReportInput)input;
		
		switch (rrInput.getReportMode()) {
			
			case Applications:
				return ReliabilityReportInput.DEFAULT_APP_FIELDS;
				
			case Tiers:
			case Apps_Extended:
			case Timeline_Extended:
				return ReliabilityReportInput.DEFAULT_APP_EXT_FIELDS;
			
			case Deployments:
				return ReliabilityReportInput.DEFAULT_DEP_FIELDS;
			
			case Timeline:
				return ReliabilityReportInput.DEFAULT_TIMELINE_FIELDS;

			default:
				return ReliabilityReportInput.DEFAULT_APP_FIELDS;
			
		}
	}

	private Collection<ReportKey> getTiers(String serviceId, ReliabilityReportInput input,
			Pair<DateTime, DateTime> timeSpan) {
 		
		Collection<String> keyTiers = GrafanaSettings.getServiceSettings(apiClient, serviceId).getTierNames();
		
		Collection<String> types = input.getTypes(apiClient, serviceId);
		
		Set<ReportKey> result = new TreeSet<ReportKey>();
		
		if (!CollectionUtil.safeIsEmpty(types)) {
						
			for (String type : types) {
				
				if (EventFilter.isExceptionFilter(type)) {
					continue;
				}
				
				if (GroupSettings.isGroup(type)) {
					String name = GroupSettings.fromGroupName(type);
					boolean isKey = CollectionUtil.safeContains(keyTiers, name);
					ReportKey key = new ReportKey(name, isKey);
					result.add(key);
				}
			}
			
			if (result.size() > 0) {
				return result;
			}
		}
			
		if (keyTiers != null) {
			for (String keyTier: keyTiers) {
				result.add(new ReportKey(keyTier, true));
			}
		} 
		
		if ((input.limit != 0) && (result.size() >= input.limit)) {
			return result;
		}
		
		Map<String, EventResult> eventsMap = getEventMap(serviceId, input, timeSpan.getFirst(), 
			timeSpan.getSecond(), null);
		
		if (eventsMap == null) {
			return Collections.emptyList();
		}
		
		Categories categories = GrafanaSettings.getServiceSettings(apiClient, serviceId).getCategories();
				
		for (EventResult event : eventsMap.values()) {
						
			boolean is3rdPartyCode = false;
			
			if (event.error_origin != null) {
				
				Set<String> originLabels = categories.getCategories(event.error_origin.class_name);
				
				if (!CollectionUtil.safeIsEmpty(originLabels)) {
					result.addAll(toReportKeys(originLabels, false));
					is3rdPartyCode = true; 
				}
			}
			
			if (event.error_location != null) {
				
				Set<String> locationLabels = categories.getCategories(event.error_location.class_name);
				
				if (!CollectionUtil.safeIsEmpty(locationLabels)) {
					result.addAll(toReportKeys(locationLabels, false));
					is3rdPartyCode = true; 
				}
			}
			
			if ((input.addAppTier) && (!is3rdPartyCode)) {
				result.add(new ReportKey(EventFilter.APP_CODE, false));
			}
		}
		
		return result;
	}

	private List<VolumeOutput> getAppVolumes(String serviceId, ReliabilityReportInput input,
			Pair<DateTime, DateTime> timeSpan, Collection<String> apps) {

		List<Callable<Object>> tasks = new ArrayList<Callable<Object>>();

		for (String app : apps) {
			RegressionsInput appInput = getInput(input, serviceId, app, false);
			tasks.add(new AppVolumeAsyncTask(appInput, serviceId, app, timeSpan));
		}

		List<VolumeOutput> result = new ArrayList<VolumeOutput>();
		List<Object> taskResults = executeTasks(tasks, true);

		for (Object taskResult : taskResults) {

			if (taskResult instanceof VolumeOutput) {
				result.add((VolumeOutput) taskResult);
			}
		}

		return result;
	}

	protected static class RegressionKeyData {

		protected ReportKey reportKey;
		protected RegressionOutput regressionOutput;

		protected RegressionKeyData(ReportKey key,
				RegressionOutput regressionOutput) {

			this.reportKey = key;
			this.regressionOutput = regressionOutput;
		}
	}

	protected List<ReportAsyncResult> processAsync(String serviceId, ReliabilityReportInput input,
			Pair<DateTime, DateTime> timeSpan, Collection<ReportKey> reportKeys) {

		List<Callable<Object>> tasks = new ArrayList<Callable<Object>>();
		List<ReportAsyncResult> result = new ArrayList<ReportAsyncResult>();
				
		ScoreType scoreType = input.getScoreType();
		
		String viewId = getViewId(serviceId, input.view);
		
		if (viewId == null) {
			return Collections.emptyList();
		}
		
		for (ReportKey reportKey : reportKeys) {
			
			RegressionFunction regressionFunction = new RegressionFunction(apiClient);

			RegressionsInput regressionsInput = getInput(input, serviceId, reportKey.name, false);
			RegressionsInput transactionInput = getInput(input, serviceId, reportKey.name, true);

			transactionInput.pointsWanted = input.transactionPointsWanted;
			
			if ((scoreType == ScoreType.Combined) || (scoreType == ScoreType.Regressions)) {

				RegressionOutput regressionOutput = ApiCache.getRegressionOutput(apiClient, 
					serviceId, regressionsInput, regressionFunction, false, false);
						
				if (regressionOutput != null) {
					result.add(new RegressionAsyncResult(reportKey, regressionOutput));
				} else {
					tasks.add(new RegressionAsyncTask(regressionFunction, 
						reportKey, serviceId, regressionsInput, timeSpan, false));
				}
			}
			
			if ((scoreType == ScoreType.Combined) || (scoreType == ScoreType.Slowdowns)) {
				tasks.add(new SlowdownAsyncTask(serviceId, viewId,  
					reportKey, transactionInput, timeSpan, input.getReportMode() == ReportMode.Apps_Extended));
			}
			
			if (scoreType == ScoreType.NewOnly) {

				RegressionOutput regressionOutput = ApiCache.getRegressionOutput(apiClient, 
					serviceId, regressionsInput, regressionFunction, true, false);
						
				if (regressionOutput != null) {
					result.add(new RegressionAsyncResult(reportKey, regressionOutput));
				} else {
					tasks.add(new RegressionAsyncTask(regressionFunction, 
						reportKey, serviceId, regressionsInput, timeSpan, true));
				}
			}
		}
		
		if (tasks.size() == 1) {
			try {
				result.add((ReportAsyncResult)(tasks.get(0).call()));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		} else {
			List<Object> taskResults = executeTasks(tasks, false);
	
			for (Object taskResult : taskResults) {
	
				if (taskResult instanceof ReportAsyncResult) {
					result.add((ReportAsyncResult) taskResult);
				}
			}
		}

		return result;
	}

	private Collection<String> limitVolumes(List<VolumeOutput> volumes, int limit) {

		volumes.sort(new Comparator<VolumeOutput>() {

			@Override
			public int compare(VolumeOutput o1, VolumeOutput o2) {

				return (int) (o2.volume - o1.volume);
			}
		});

		List<String> result = new ArrayList<String>();

		for (int i = 0; i < Math.min(limit, volumes.size()); i++) {
			result.add(volumes.get(i).key);
		}

		return result;
	}

	private Collection<ReportKey> getActiveApplications(String serviceId, ReliabilityReportInput input,
			Pair<DateTime, DateTime> timeSpan) {

		List<String> keyApps = new ArrayList<String>();
		
		GroupSettings appGroups = GrafanaSettings.getData(apiClient, serviceId).applications;
		
		if (appGroups != null) {
			keyApps.addAll(appGroups.getAllGroupNames(true));
		}
		
		List<ReportKey> reportKeys;
		Collection<String> selectedApps = input.getApplications(apiClient, serviceId, false);
		
		if (!CollectionUtil.safeIsEmpty(selectedApps)) {
			
			reportKeys = new ArrayList<ReportKey>();
			
			for (String selectedApp : selectedApps) {
				boolean isKey = keyApps.contains(selectedApp);
				reportKeys.add(new ReportKey(selectedApp, isKey));
			}
			
			return reportKeys;
		}
			
		if (keyApps.size() > input.limit) {
			return toReportKeys(keyApps, true);
		}
		
		Collection<String> activeApps = ApiCache.getApplicationNames(apiClient, serviceId, true);
		
		int appSize = keyApps.size() + activeApps.size();
		
		if ((appSize > 0) && (appSize < input.limit)) {
			
			reportKeys = new ArrayList<ReportKey>(keyApps.size() + activeApps.size());
			
			reportKeys.addAll(toReportKeys(keyApps, true));
			reportKeys.addAll(toReportKeys(activeApps, false));
			
			reportKeys.sort(null);
			
			return reportKeys;
		}
		
		Collection<String> nonKeyApps;
		
		if (input.queryAppVolumes) {
			List<VolumeOutput> appVolumes = getAppVolumes(serviceId, input, timeSpan, activeApps);
			nonKeyApps = limitVolumes(appVolumes, input.limit);
		} else {
			List<String> sortedActiveApps = new ArrayList<String>(activeApps);
			sortApplicationsByProcess(serviceId, sortedActiveApps,
				input.getServers(serviceId), input.getDeployments(serviceId));			
			nonKeyApps =  sortedActiveApps; 
		}
		
		reportKeys = new ArrayList<ReportKey>();
		
		reportKeys.addAll(toReportKeys(keyApps, true));
		reportKeys.addAll(toReportKeys(nonKeyApps, false));
		
		List<ReportKey> result;
		
		if (reportKeys.size() > input.limit) {
			result = reportKeys.subList(0, input.limit);
		} else {
			result = reportKeys;
		}
		
		result.sort(null);
		
		return result;
	}

	private static void sortDeploymentNames(List<SummarizedDeployment> deps, boolean desc) {
		
		deps.sort(new Comparator<SummarizedDeployment>() {

			@Override
			public int compare(SummarizedDeployment o1, SummarizedDeployment o2) {
				
				if (desc) {
					return DeploymentUtil.compareDeployments(o1.name, o2.name);
				} else {
					return DeploymentUtil.compareDeployments(o2.name, o1.name);

				}
			}
		});

	}

	private Collection<ReportKey> toReportKeys(Collection<String> keys, boolean isKey) {
		
		List<ReportKey> result = new ArrayList<ReportKey>(keys.size());
		
		for (String key : keys) {
			result.add(new ReportKey(key, isKey));
		}
		
		return result;
	}
	
	private Collection<ReportKey> getActiveDeployments(String serviceId, 
		ReliabilityReportInput input, Pair<DateTime, DateTime> timespan) {

		List<ReportKey> result = new ArrayList<ReportKey>();
		List<String> selectedDeployments = input.getDeployments(serviceId);
		
		if (!CollectionUtil.safeIsEmpty(selectedDeployments)) {
			
			Collection<SummarizedDeployment> allDeps = DeploymentUtil.getDeployments(apiClient, serviceId, false);
			
			List<SummarizedDeployment> sortedDeps;
			
			if (allDeps != null) {
				sortedDeps = new ArrayList<SummarizedDeployment>(allDeps);
				sortDeploymentNames(sortedDeps, false);

			} else {
				sortedDeps = null;
			}
						
			for (String selectedDeployment :  selectedDeployments) {
				
				SummarizedDeployment prev = null;
				
				if (sortedDeps != null) {
						
					for (int i = 0; i < sortedDeps.size(); i++) {
						
						SummarizedDeployment dep = sortedDeps.get(i);
						
						if ((i > 0) && (dep.name.equals(selectedDeployment))) {
							prev = sortedDeps.get(i -1);
							break;
						}
					}
				}
				
				result.add(new DeploymentReportKey(selectedDeployment, false, prev));
			}
			
			return result;
			
		}
		
		Collection<SummarizedDeployment> activeDeps = DeploymentUtil.getDeployments(apiClient, serviceId, true);
			
		if (activeDeps != null) {
			
			List<SummarizedDeployment> sortedActive = new ArrayList<SummarizedDeployment>(activeDeps);
			sortDeploymentNames(sortedActive, true);
	
			for (int i = 0; i < Math.min(input.limit, sortedActive.size()); i++) {
				
				SummarizedDeployment activeDep = sortedActive.get(i);
				
				if (activeDep.last_seen != null) {
					DateTime firstSeen = TimeUtil.getDateTime(activeDep.last_seen);
					if (firstSeen.isBefore(timespan.getFirst())) {
						continue;
					}
				}
				
				SummarizedDeployment previous;
				
				if (i < sortedActive.size() - 1) {
					previous = sortedActive.get(i + 1);					
				} else {
					previous = null;
				}
				
				result.add(new DeploymentReportKey(activeDep.name, true, previous));
			}
		}
		
		if ((!input.liveDeploymentsOnly) && (input.limit - result.size() > 0)) {
			
			Collection<SummarizedDeployment> nonActiveDeps = DeploymentUtil.getDeployments(apiClient, serviceId, false);
			
			if (nonActiveDeps != null) {
			
				List<SummarizedDeployment> sortedNonActive = new ArrayList<SummarizedDeployment>(nonActiveDeps);
	
				sortDeploymentNames(sortedNonActive, true);
			
				for (int i = 0; i < sortedNonActive.size(); i++) {
					
					boolean canAdd = true;
					SummarizedDeployment dep = sortedNonActive.get(i);
					
					if (dep.last_seen != null) {
						DateTime firstSeen = TimeUtil.getDateTime(dep.last_seen);
						if (firstSeen.isBefore(timespan.getFirst())) {
							canAdd = false;
						}
					}
					
					boolean isLive = false;
					
					if (dep.last_seen != null) {
						DateTime lastSeen = TimeUtil.getDateTime(dep.last_seen);
						isLive = lastSeen.plusHours(1).isAfter(timespan.getSecond());
					}
					
					SummarizedDeployment previous;
					
					if (i < sortedNonActive.size() - 1) {
						previous = sortedNonActive.get(i + 1);					
					} else {
						previous = null;
					}
			
					DeploymentReportKey key = new DeploymentReportKey(dep.name, isLive, previous);
					
					int keyIndex = result.indexOf(key);
					
					if (keyIndex == -1) {
						if (canAdd) {
							result.add(key);
						}

					} else {
						DeploymentReportKey existingKey = (DeploymentReportKey)result.get(keyIndex);
						
						if (existingKey.previous == null) {
							existingKey.previous = previous;
						}
					}
					
					if (result.size() >= input.limit) {
						break;
					}
				}
			}
		}

		return result;
	}
	
	private Collection<ReportKey> getActiveKeys(String serviceId, ReliabilityReportInput regInput,
			Pair<DateTime, DateTime> timeSpan) {
		
		Collection<ReportKey> keys;
			
		ReportMode mode = regInput.getReportMode();
		
		switch (mode) {
				
			case Deployments:
				keys = getActiveDeployments(serviceId, regInput, timeSpan);
				break;
					
			case Tiers: 
				keys = getTiers(serviceId, regInput, timeSpan);
				break;
				
			case Apps_Extended: 
			case Applications: 
				keys = getActiveApplications(serviceId, regInput, timeSpan);
				break;
					
			case Default: 
				keys = Collections.singleton(getDefaultReportKey(serviceId, regInput));
				break;
					
			default: 
				throw new IllegalStateException("Unsopported mode " + mode);
			}

		return keys;
	}
	
	private ReportKey getDefaultReportKey(String serviceId, ReliabilityReportInput regInput) {
		
		boolean isKey = false;

		if (regInput.applications != null) {
			
			GroupSettings appGroups = GrafanaSettings.getData(apiClient, serviceId).applications;
			
			if (appGroups != null) {
				 
				List<String> keyApps = new ArrayList<String>(appGroups.getAllGroupNames(true));
				Collection<String> apps = regInput.getApplications(apiClient, serviceId, false);
				
				isKey = false;
				
				for (String app : apps) {
					
					if (keyApps.contains(app)) {
						isKey = true;
						break;
					}
				}
			}
		}
			
		return new ReportKey(DEFAULT_KEY, isKey);
	}
	
	private static void addDeduction(String name, int value, double weight, List<String> deductions) {
		if (value == 0) {
			return;
		}
		
		StringBuilder builder = new StringBuilder();
		
		if (value > 0) {
			builder.append(value);
			builder.append(" ");
			builder.append(name);
			
			if (value > 1) {
				builder.append("s");
			}
			
			if (weight > 1) {
				builder.append(" * ");
				
				if ((int)weight != weight) {
					builder.append(weight);
				} else {
					builder.append((int)weight);
				}
			}
		}
		
		deductions.add(builder.toString());
	}
	
	private static int getRegressionScoreWindow(RegressionOutput regressionOutput) {
		
		int result = 0;
		
		if (CollectionUtil.safeIsEmpty(regressionOutput.regressionInput.deployments)) {
			result = regressionOutput.regressionInput.activeTimespan;
		} else {
			DateTime lastPointTime = null;
			
			for (int i = regressionOutput.activeVolumeGraph.points.size() - 1; i >= 0; i--) {
				
				GraphPoint gp = regressionOutput.activeVolumeGraph.points.get(i);
				
				if ((gp.stats != null) && ((gp.stats.invocations > 0) || (gp.stats.hits > 0))) {
					lastPointTime = TimeUtil.getDateTime(gp.time);
					break;
				}
			}
			
			if (lastPointTime != null) {
				long delta  = lastPointTime.minus(regressionOutput.regressionInput.activeWindowStart.getMillis()).getMillis();
				result = (int)TimeUnit.MILLISECONDS.toMinutes(delta);
			} else {
				result = regressionOutput.regressionInput.activeTimespan;			
			}			
		}
		
		return result;
	}
	
	private static double getReportKeyWeight(RegressionReportSettings reportSettings, 
			ReportMode reportMode, boolean isKey) {
		
		if (reportMode == ReportMode.Deployments) {
			return reportSettings.score_weight;	
		}
		
		if ((isKey) && (reportSettings.key_score_weight > 0)) {
			return reportSettings.key_score_weight;
		} else {
			return reportSettings.score_weight;	
		}		
	}
	
	public static Pair<Double, Integer> getScore(
			RegressionOutput regressionOutput, RegressionReportSettings reportSettings, 
			int newEvents, int severeNewEvents, int regressions, int severeRegressions,
			int slowdowns, int severeSlowdowns, boolean isKey, boolean deductFrom100,
			ReportMode reportMode) {
		
		double newEventsScore = newEvents * reportSettings.new_event_score;
		double severeNewEventScore = severeNewEvents * reportSettings.severe_new_event_score;
		
		double criticalRegressionsScore = severeRegressions  * reportSettings.critical_regression_score;
		double regressionsScore = regressions * reportSettings.regression_score;
		
		if (reportMode != ReportMode.Tiers) {
			criticalRegressionsScore += severeSlowdowns * reportSettings.critical_regression_score;
			regressionsScore += slowdowns * reportSettings.regression_score;
		}
		
		int scoreWindow = getRegressionScoreWindow(regressionOutput);		
		double scoreDays = Math.max(1, (double)scoreWindow / 60 / 24);
	
		double weight = getReportKeyWeight(reportSettings, reportMode, isKey);
		
		double rawScore = (newEventsScore + severeNewEventScore + criticalRegressionsScore + regressionsScore) / scoreDays;
		
		double resultScore;
		
		if (deductFrom100) {
			resultScore = Math.max(100 - (weight * rawScore), 0);
		} else {
			resultScore = Math.max(weight * rawScore, 0);

		}	
		return Pair.of(resultScore, scoreWindow);
	}

	
	private Pair<Double, Integer> getScore(
		ReliabilityReportInput input, RegressionReportSettings reportSettings, 
		ReportKeyResults reportKeyResults) {
		
		RegressionOutput regressionOutput = reportKeyResults.output.regressionData.regressionOutput;
		
		return getScore(regressionOutput, reportSettings, 
				regressionOutput.newIssues, regressionOutput.severeNewIssues, 
				regressionOutput.regressions, regressionOutput.criticalRegressions, 
				reportKeyResults.slowdowns, reportKeyResults.severeSlowdowns, 
				reportKeyResults.output.reportKey.isKey,true, input.getReportMode());

	}
	
	private String getScoreDescription(ReliabilityReportInput input,
		RegressionReportSettings reportSettings,
		ReportKeyResults reportKeyResults, double resultScore, int period) {
		
		StringBuilder result = new StringBuilder();

		PrettyTime prettyTime = new PrettyTime();
		String duration = prettyTime.formatDuration(new DateTime().minusMinutes(period).toDate());
		
		RegressionOutput regressionOutput = reportKeyResults.output.regressionData.regressionOutput;

		result.append("Score ");
		
		String keyName = reportKeyResults.output.reportKey.name;
		
		if ((keyName != null) && (keyName.length() > 0)) {
			result.append(" for ");
			result.append(keyName);
		}
		
		ReportMode reportMode = input.getReportMode();
		
		if (reportMode == ReportMode.Deployments) {
			result.append(", introduced ");
			int activeTimespan = reportKeyResults.output.regressionData.regressionOutput.regressionInput.activeTimespan;
			Date introduced = DateTime.now().minusMinutes(activeTimespan).toDate();
			result.append(prettyTime.format(introduced));
		}
		
		result.append(" = 100");
		
		int allIssuesCount = regressionOutput.newIssues + regressionOutput.severeNewIssues + 
				regressionOutput.criticalRegressions + regressionOutput.regressions;
		
		if (reportMode != ReportMode.Tiers) {
			allIssuesCount += reportKeyResults.slowdowns + reportKeyResults.severeSlowdowns;
		}
		
		if (allIssuesCount > 0) {
			result.append(" - (");
			
			List<String> deductions = new ArrayList<String>();
			
			addDeduction("new issue", regressionOutput.newIssues, reportSettings.new_event_score, deductions);
			addDeduction("severe new issue", regressionOutput.severeNewIssues, reportSettings.severe_new_event_score, deductions);
			addDeduction("error increase", regressionOutput.regressions, reportSettings.regression_score, deductions);
			addDeduction("severe error increase", regressionOutput.criticalRegressions, reportSettings.critical_regression_score, deductions);
			
			if (reportMode != ReportMode.Tiers) {
				addDeduction("slowdown", reportKeyResults.slowdowns, reportSettings.regression_score, deductions);
				addDeduction("severe slowdown", reportKeyResults.severeSlowdowns, reportSettings.critical_regression_score, deductions);
			}
			
			double weight = getReportKeyWeight(reportSettings, reportMode, reportKeyResults.output.reportKey.isKey);
			
			String deductionString = String.join(" + ", deductions);
			result.append(deductionString);
			result.append(") * ");
			result.append(weight);			
			result.append(", avg over ");
			result.append(duration);
			result.append(" = ");
			
			if ((int)resultScore != resultScore) {
				result.append(decimalFormat.format(resultScore));
			} else {
				result.append((int)resultScore);
			}	
		}
		
		return result.toString(); 
	}
	
	protected Collection<ReportKeyOutput> executeTimeline(String serviceId,
		ReliabilityReportInput regInput, Pair<DateTime, DateTime> timeSpan) {
		
		ReliabilityKpiGraphFunction kpiGraph = new ReliabilityKpiGraphFunction(apiClient);
		
		Gson gson = new Gson();
		String json = gson.toJson(regInput);
		ReliabilityKpiGraphInput pkInput = gson.fromJson(json, ReliabilityKpiGraphInput.class);
		
		pkInput.kpi = RelabilityKpi.Score.toString();
		pkInput.reportInterval = regInput.reportInterval; 
		pkInput.transactionPointsWanted = regInput.transactionPointsWanted;
		pkInput.aggregate = false;
		pkInput.limit = regInput.limit;
		
		Collection<TaskKpiResult> taskResults = kpiGraph.executeIntervals(Collections.singleton(serviceId), 
			pkInput, timeSpan);
		
		Collection<ReportKeyOutput> result = getTimelineResults(taskResults);
		
		return result;
	}
	
	private Collection<ReportKeyOutput> getTimelineResults(Collection<TaskKpiResult> taskResults) {
		
		List<Pair<String, KpiInterval>> sortedKpis = new ArrayList<Pair<String, KpiInterval>>();

		for (TaskKpiResult taskKpiResult : taskResults) {
			
			for (KpiInterval kpiInterval : taskKpiResult.kpiIntervals) {
				sortedKpis.add(Pair.of(taskKpiResult.app, kpiInterval));
			}
		}
		
		sortedKpis.sort(new Comparator<Pair<String, KpiInterval>>() {

			@Override
			public int compare(Pair<String, KpiInterval> o1, Pair<String, KpiInterval> o2) {
				
				if (Objects.equal(o1.getFirst(), o2.getFirst())) {
					return o2.getSecond().period.getSecond().compareTo(o1.getSecond().period.getSecond());
				}
				
				if (o1.getFirst() == null) {
					return -1;
				}
				
				if (o2.getFirst() == null) {
					return -1;
				}
				
				return 0;
			}
		});
		
		List<ReportKeyOutput> result = new ArrayList<ReportKeyOutput>();
		
		for (Pair<String, KpiInterval> pair : sortedKpis) {
				
			String app = pair.getFirst();
			KpiInterval kpiInterval  = pair.getSecond();
			
			String intervalName = formatInterval(kpiInterval.period);
			String reportKeyName;
				
			if ((app != null) && (app.length()) > 0) {					
				reportKeyName = app + " " + intervalName;
			} else {
				reportKeyName = intervalName;
			}
				
			ReportKey reportKey = new ReportKey(reportKeyName, false);
			ReportKeyOutput reportKeyOutput = new ReportKeyOutput(reportKey);
				
			if (kpiInterval instanceof ScoreInterval) {
				
				ScoreInterval scoreInterval = (ScoreInterval)kpiInterval;
					
				reportKeyOutput.transactionMap = scoreInterval.slowdownInterval.transactionMap;
				reportKeyOutput.regressionData = new RegressionKeyData(reportKey, scoreInterval.regressionInterval.output);
			}
				
			result.add(reportKeyOutput);	
		}
		
		return result;
	}
	
	private String formatInterval(Pair<DateTime, DateTime> period) {
				
		String result;
		
		DateTime dateTime = period.getFirst();
		Date date = dateTime.toDate();
		if (dateTime.isAfter(TimeUtil.now().minusDays(7))) {
			result = singleDayDateformat.format(date);
		} else {
			result = dayInMonthDateformat.format(date);
		}	
		
		return result; 
	}
	
	protected Collection<ReportKeyOutput> executeReport( 
			String serviceId, ReliabilityReportInput regInput,
			Pair<DateTime, DateTime> timeSpan) {
		
		ReportMode reportMode = regInput.getReportMode();
		
		if ((reportMode == ReportMode.Timeline) 
		|| (reportMode == ReportMode.Timeline_Extended)) {
			return executeTimeline(serviceId, regInput, timeSpan);
		}
		
		Collection<ReportKey> keys = getActiveKeys(serviceId, regInput, timeSpan);
		
		logger.debug("Executing report " + reportMode + " keys: " + Arrays.toString(keys.toArray()));

		List<ReportAsyncResult> AsyncResults = processAsync(serviceId, regInput, timeSpan, keys);

		Map<ReportKey, ReportKeyOutput> reportKeyOutputMap = new HashMap<ReportKey, ReportKeyOutput>();
		
		for (ReportAsyncResult asyncResult : AsyncResults) {

			ReportKeyOutput reportKeyOutput = reportKeyOutputMap.get(asyncResult.key);
			
			if (reportKeyOutput == null) {
				reportKeyOutput = new ReportKeyOutput(asyncResult.key);
				reportKeyOutputMap.put(asyncResult.key, reportKeyOutput);
			}
			
			if (asyncResult instanceof RegressionAsyncResult) {
			
				RegressionOutput regressionOutput = ((RegressionAsyncResult)asyncResult).output;
	
				if ((regressionOutput == null) || (regressionOutput.rateRegression == null)) {
					continue;
				}
				
				RegressionKeyData regressionData = new RegressionKeyData(asyncResult.key, 
						regressionOutput);
				
				reportKeyOutput.regressionData = regressionData;
			}
			
			if (asyncResult instanceof SlowdownAsyncResult) {
				
				SlowdownAsyncResult slowdownAsyncResult = (SlowdownAsyncResult)asyncResult;
				reportKeyOutput.transactionMap = slowdownAsyncResult.transactionMap;
			}
			
		}
		
		List<ReportKeyOutput> result;
		
		if (reportMode == ReportMode.Deployments) {
			boolean sortAsc = getSortedAsc(regInput.getSortType(), true);
			List<ReportKeyOutput> sorted = new ArrayList<ReportKeyOutput>(reportKeyOutputMap.values());
			sortDeploymentKeys(sorted, sortAsc);
			result = sorted;
		} else {
			
			boolean sortAsc = getSortedAsc(regInput.getSortType(), true);
			List<ReportKeyOutput> sorted = new ArrayList<ReportKeyOutput>(reportKeyOutputMap.values());
			sortKeys(sorted, sortAsc);
			result = sorted;			
		}
		
		return result;
	}
	
	private boolean getSortedAsc(SortType sortType, boolean defaultValue) {
		
		switch (sortType) {
			
			case Ascending: 
				return true;
				
			case Descending: 
				return false;
				
			case Default: 
				return defaultValue;
				
			default:
				throw new IllegalStateException();
		}
	}
	
	private void sortDeploymentKeys(List<ReportKeyOutput> scores, boolean asc) {
		scores.sort(new Comparator<ReportKeyOutput>() {

			@Override
			public int compare(ReportKeyOutput o1, ReportKeyOutput o2) {

				if (asc) {
					return DeploymentUtil.compareDeployments(o1.reportKey, o2.reportKey);
				} else {
					return DeploymentUtil.compareDeployments(o2.reportKey, o1.reportKey);
				}
			}
		});
	}
	
	private void sortKeys(List<ReportKeyOutput> scores, boolean asc) {
		
		scores.sort(new Comparator<ReportKeyOutput>() {

			@Override
			public int compare(ReportKeyOutput o1, ReportKeyOutput o2) {

				if (asc) {
					return o1.reportKey.name.compareTo(o2.reportKey.name);
				} else {
					return o2.reportKey.name.compareTo(o1.reportKey.name);
				}
			}
		});
	}

	@Override
	public List<Series> process(FunctionInput functionInput) {

		if (!(functionInput instanceof ReliabilityReportInput)) {
			throw new IllegalArgumentException("functionInput");
		}

		ReliabilityReportInput input = (ReliabilityReportInput)getInput((ViewInput)functionInput);

		if (input.render == null) {
			throw new IllegalStateException("Missing render mode");
		}
		
		switch (input.render) {
			
			case Graph:
				return processGraph(input);
			case Grid:
				return super.process(input);	
			case SingleStat:
				return processSingleStat(input);	
			case SingleStatDesc:
				return processSingleStat(input);	
			default:
				throw new IllegalStateException("Unsupported render mode " + input.render); 
		}
	}
	
	private Object getServiceSingleStat(Collection<String> serviceIds, String serviceId, Pair<DateTime, DateTime> timeSpan, ReliabilityReportInput input)
	{
		Collection<ReportKeyOutput> reportKeyOutputs = executeReport(serviceId, input, timeSpan);
		Collection<ReportKeyResults> reportKeyResults = getReportResults(serviceId, timeSpan, input, reportKeyOutputs);
		
		Object result;
		
		if (reportKeyResults.size() > 0) {
			ReportKeyResults reportKeyResult = reportKeyResults.iterator().next();
			result = getReportKeyValue(serviceIds, serviceId, timeSpan, input, reportKeyResult);
		} else {
			result = 0;
		}
		
		return result;
	}
	
	private Object getSingleStat(Collection<String> serviceIds, Pair<DateTime, DateTime> timeSpan, 
		ReliabilityReportInput input)
	{	
		if (CollectionUtil.safeIsEmpty(serviceIds)) {
			return 0;
		}
		
		List<Object> serviceValues = new ArrayList<Object>(serviceIds.size());
		
		for (String serviceId : serviceIds) {
			Object serviceValue = getServiceSingleStat(serviceIds, serviceId, timeSpan, input);
			serviceValues.add(serviceValue);
		}
		
		Object result;
		
		if (serviceValues.size() == 1) {
			result = serviceValues.iterator().next();
		} else {
			int count = 0;
			double sum = 0;

			for (Object serviceValue : serviceValues) {
				
				if (serviceValue instanceof Double) {
					sum += (Double)serviceValue;
					count++;
				}
			}
			
			if (count > 0) {
				result = sum / count;
			} else {
				result = 0;
			}
		}
		
		return result;
	}

	private List<Series> processSingleStat(ReliabilityReportInput input)
	{
		Collection<String> serviceIds = getServiceIds(input);
		
		if (CollectionUtil.safeIsEmpty(serviceIds))
		{
			return Collections.emptyList();
		}
		
		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);
		
		Object singleStatText = getSingleStat(serviceIds, timeSpan, input);
		
		return createSingleStatSeries(timeSpan, singleStatText);
	}

	private static Object formatIssues(ReliabilityReportInput input, int nonSevere, int severe)
	{
		
		Object result;
		
		if (severe > 0)
		{
			if (nonSevere == 0)
			{
				if (input.sevOnlyFormat != null) {
					result = String.format(input.sevOnlyFormat, severe);
				} else {
					result = String.valueOf(severe);
				}
			}
			else
			{
				if (input.sevAndNonSevFormat != null) {
					result = String.format(input.sevAndNonSevFormat, nonSevere + severe, severe);
				} else {
					result = String.valueOf(nonSevere + severe);
				}
			}
		}
		else
		{
			if (nonSevere != 0) {
				result = Integer.valueOf(nonSevere);
			} else {
				result = "";
			}
		}
		
		return result;
	}

	
		
	private Collection<ReportKeyResults> getReportResults(String serviceId, 
			Pair<DateTime, DateTime> timeSpan, ReliabilityReportInput input, 
			Collection<ReportKeyOutput> reportKeyOutputs) {
		
		RegressionReportSettings reportSettings = GrafanaSettings.getData(apiClient, serviceId).regression_report;
		
		if (reportSettings == null) {
			throw new IllegalStateException("Unable to acquire regression report settings for " + serviceId);
		}			
		
		List<ReportKeyResults> result = new ArrayList<ReportKeyResults>();
		
		for (ReportKeyOutput reportKeyOutput : reportKeyOutputs) {			
			
			ReportKeyResults reportKeyResults = new ReportKeyResults(reportKeyOutput);
		
			if (reportKeyOutput.regressionData != null) {
				
				reportKeyResults.newIssues = reportKeyOutput.regressionData.regressionOutput.newIssues;
				reportKeyResults.severeNewIssues = reportKeyOutput.regressionData.regressionOutput.severeNewIssues;
				reportKeyResults.criticalRegressions = reportKeyOutput.regressionData.regressionOutput.criticalRegressions;
				reportKeyResults.regressions = reportKeyOutput.regressionData.regressionOutput.regressions;
				
				reportKeyResults.newIssuesDesc = RegressionFunction.getNewIssuesDesc(
					reportKeyOutput.regressionData.regressionOutput, RegressionsInput.MAX_TOOLTIP_ITEMS);
					
				reportKeyResults.regressionsDesc = RegressionFunction.getRegressionsDesc(
						reportKeyOutput.regressionData.regressionOutput, RegressionsInput.MAX_TOOLTIP_ITEMS);
			}
			
			if (reportKeyOutput.transactionMap != null) {
				
				Pair<Integer, Integer> slowdownPair = getSlowdowns(reportKeyOutput.transactionMap.values());
				reportKeyResults.slowdowns = slowdownPair.getFirst().intValue();
				reportKeyResults.severeSlowdowns = slowdownPair.getSecond().intValue();
				reportKeyResults.slowDownsDesc = TransactionsListFunction.getSlowdownsDesc(reportKeyOutput.transactionMap.values(), 
					null, RegressionsInput.MAX_TOOLTIP_ITEMS);
			}
			
			if (reportKeyOutput.regressionData != null) {
			
				Pair<Double, Integer> scorePair = getScore(input, reportSettings, reportKeyResults);
				
				reportKeyResults.score = scorePair.getFirst();
				
				reportKeyResults.scoreDesc = getScoreDescription(input, reportSettings, reportKeyResults,
						reportKeyResults.score, scorePair.getSecond());
				
				reportKeyResults.description = getDescription(reportKeyOutput.regressionData, 
					reportKeyResults.newIssuesDesc, reportKeyResults.regressionsDesc, reportKeyResults.slowDownsDesc);
					
				reportKeyResults.volumeData = getKeyOutputEventVolume(reportKeyOutput);
				 
				reportKeyResults.failures = getAppFailureData(serviceId, input, timeSpan, reportKeyResults);
				reportKeyResults.transactions = getAppTransactionData(reportKeyResults);
				
				reportKeyResults.relability = getAppReliabilityData(reportKeyResults, 
					input, reportKeyResults.transactions, reportKeyResults.failures);
					
				
				result.add(reportKeyResults);
			}			
		}
		
		boolean isSingleStat = (input.render == RenderMode.SingleStat)
							|| (input.render == RenderMode.SingleStatDesc);
		
		if ((!isSingleStat) && (result.size() > input.limit)) {
			return limitByScore(result, input);
		}
		
		return result;
	}
	
	
	private int compareKeys(ReportKeyResults o1, ReportKeyResults o2) {
		
		int scoreDelta = (int)Math.round((o1.score - o2.score) * 100);
		
		int result;
		
		if (scoreDelta != 0) {
			result = scoreDelta;
		} else {
			
			double failDelta = o2.relability.failRate - o1.relability.failRate;
			
			if (failDelta > 0) {
				result = 1;
			} else if (failDelta < 0) {
				result = -1;
			} else {
				result = 0;
			}
		}
	
		return result;
	}
	
	private void sortByScore(List<ReportKeyResults> results) {
		
		results.sort(new Comparator<ReportKeyResults>()
		{
			@Override
			public int compare(ReportKeyResults o1, ReportKeyResults o2)
			{	
				ReportKey k1 = o1.output.reportKey;
				ReportKey k2 = o2.output.reportKey;
				
				int scoreDelta = compareKeys(o1, o2);

				if (k2.isKey) {
					if (k1.isKey) {
						return scoreDelta;
					} else {
						return 1;
					}
				} else if (k1.isKey) {
					return -1;
				} else {
					return scoreDelta;
				}
			}
		});	
	}
	
	private Collection<ReportKeyResults> limitByScore(List<ReportKeyResults> results, 
			ReliabilityReportInput input) {
		
		ReportMode reportMode = input.getReportMode();
		
		boolean isTimeline = (reportMode == ReportMode.Timeline) 
						  || (reportMode == ReportMode.Timeline_Extended);
		
		if (!isTimeline) { 
			sortByScore(results);
		}
		
		return results.subList(0, input.limit);
	}
	
	private KeyOutputEventVolume getKeyOutputEventVolume(ReportKeyOutput output) {
		
		Collection<EventResult> events = output.regressionData.regressionOutput.regressionInput.events;
		
		if (events == null) {
			return null;
		}		
	
		KeyOutputEventVolume result = new KeyOutputEventVolume();

		for (EventResult event : events) {
			
			if (event.stats != null) {
				result.volume += event.stats.hits;
				result.count++;
			}	
		}
		if (result.volume > 0) {
			
			for (EventResult event : events) {
				
				if ((event.stats == null)  || (event.stats.invocations == 0)) {
					continue;
				}
					
				result.rate += (double)event.stats.hits / (double)event.stats.invocations / result.volume;
			}
		}
		
		return result;
	}
	
	private String getDescription(RegressionKeyData regressionData, String newErrorsDesc, 
		String regressionDesc, String slowdownDesc) {
		
		StringBuilder result = new StringBuilder();
		
		result.append(regressionData.reportKey);
		
		result.append(" over ");
		
		DateTime activeWindow = regressionData.regressionOutput.regressionInput.activeWindowStart;
		result.append(". ");
		
		result.append(new PrettyTime().format(new Date(activeWindow.getMillis())));
		
		if ((newErrorsDesc != null) && (newErrorsDesc.length() > 0)) {
			result.append("New errors: ");
			result.append(newErrorsDesc);
		}
		

		if ((regressionDesc != null) && (regressionDesc.length() > 0)) {
			result.append("Increasing Errors: ");
			result.append(regressionDesc);
		}
		
		if ((slowdownDesc != null) && (slowdownDesc.length() > 0)) {
			result.append("Slowdowns: ");
			result.append(slowdownDesc);
		}
		
		return result.toString();
		
	}
	
	private EventFilter getFailureTypeFilter(String serviceId, 
		Pair<DateTime, DateTime> timespan, ReliabilityReportInput input,
		ReportKeyResults reportKeyResult) {
		
		Gson gson = new Gson();
		String json = gson.toJson(input);
		ReliabilityReportInput failureInput = gson.fromJson(json, input.getClass());
		
		ReportMode reportMode = input.getReportMode() ;
		
		switch (reportMode) {
			
			case Apps_Extended:
			case Timeline_Extended: 
				failureInput.types = input.getFailureTypes(); 
				break;
		
			case Tiers:
				failureInput.types = GroupSettings.toGroupName(reportKeyResult.output.reportKey.name);
				break;
				
			default: return null;
		}
		
		EventFilter result = getEventFilter(serviceId, failureInput, timespan);
		
		return result;
	}
	
	private ReliabilityState getReliabilityState(RegressionInput regressionInput, double deltaValue) {
		
		ReliabilityState result;
		
		if (deltaValue > regressionInput.criticalRegressionDelta) {
			result = ReliabilityState.Severe;
		} else if (deltaValue > regressionInput.regressionDelta) {
			result = ReliabilityState.Warning;
		} else {
			result = ReliabilityState.OK;
		}

		return result;
		
	}
	
	private Pair<ReliabilityState, Double> getReliabilityState(double baseAvg, double avg,  
		long volume, RegressionInput regressionInput) {
				
		ReliabilityState state;
		double deltaValue;
		
		if ((baseAvg > 0) && (volume > regressionInput.minVolumeThreshold)) {	
			deltaValue = (avg - baseAvg) / baseAvg;
			state = getReliabilityState(regressionInput, deltaValue);					
		} else {
			deltaValue = 0;
			state = ReliabilityState.OK;
		}
		
		return Pair.of(state, deltaValue);
	}
	
	private String formatRateDelta(ReliabilityState state, boolean addParen, double value) {
		
		String result;
		
		if (state != ReliabilityState.OK) {
			
			StringBuilder builder = new StringBuilder(0);
			
			if (addParen) {
				builder.append(" (");
			}
			
			builder.append("+");
			builder.append(formatRate(value, false));

			if (addParen) {
				builder.append(")");
			}
			
			result = builder.toString();
		} else {
			result = "";
		}
		
		return result;
	}
	
	private Pair<Double, String> getAvgResponseState(double denom, double num, 
		double baseDenom, double baseNum, long volume, 
		RegressionInput regressionInput) {
		
		double avg;
		
		if (denom > 0) {
			avg = num / denom;
		} else {
			avg = 0;
		}
		
		double baseAvg;
		
		if (baseDenom > 0) {
			baseAvg = baseNum / baseDenom;
		} else {
			baseAvg = 0;
		}
		
		Pair<ReliabilityState, Double> statePair = getReliabilityState(baseAvg, avg, volume, regressionInput);
		String deltaStr = formatRateDelta(statePair.getFirst(), true, statePair.getSecond());
		
		return Pair.of(avg, deltaStr);
	}
	
	private ReportKeyFailures getAppFailureData(String serviceId, 
			ReliabilityReportInput input, Pair<DateTime, DateTime> timespan,
			ReportKeyResults reportKeyResult) {
		
		ReportKeyFailures result = new ReportKeyFailures();
		
		EventFilter failureFilter = getFailureTypeFilter(serviceId, timespan, input, reportKeyResult);
		
		if (failureFilter == null) {
			return result;
		}
		
		Map<String, EventResult> eventListMap = reportKeyResult.output.regressionData.regressionOutput.eventListMap;
			
		for (EventResult event : eventListMap.values()) {
				
			if (failureFilter.filter(event)) {
				continue;
			}
				
			result.failures += event.stats.hits;	
			result.eventCount++;			
		}
		
		Map<TransactionKey, TransactionData> transactionMap = reportKeyResult.output.transactionMap;
		
		if (transactionMap == null) {
			return result;	
		}
		
		Graph baseGraph = reportKeyResult.output.regressionData.regressionOutput.baseVolumeGraph;
		
		for (GraphPoint gp : baseGraph.points) {
			
			if (CollectionUtil.safeIsEmpty(gp.contributors)) {
				continue;
			}
			
			for (GraphPointContributor gpc : gp.contributors) {
				
				EventResult event = eventListMap.get(gpc.id);
				
				if ((event == null) || (failureFilter.filter(event))) {
					continue;
				}
				
				TransactionData eventTransaction = getEventTransactionData(transactionMap, event);
				
				if (eventTransaction == null) {
					continue;
				}
				
				result.baseFailures += gpc.stats.hits;						
			}
		}
		
		return result;
	}
		
	private ReliabilityState combineStates(ReliabilityState r1, ReliabilityState r2) {
		
		if ((r1 == ReliabilityState.Severe) || (r2 == ReliabilityState.Severe)) {
			return ReliabilityState.Severe;
		} else if ((r1 == ReliabilityState.Warning) || (r2 == ReliabilityState.Warning)) {
			return  ReliabilityState.Warning;
		}
		
		return ReliabilityState.OK;
	}
	
	private ReportKeyReliability getAppReliabilityData(ReportKeyResults reportKeyResult,
		ReliabilityReportInput input,
		ReportKeyTransactions transactionData, ReportKeyFailures failureData) {
		
		ReportKeyReliability result = new ReportKeyReliability();
							
		if (transactionData.transactionVolume > 0) {
			result.failRate = (double)failureData.failures / (double)transactionData.transactionVolume; 
		} else {
			result.failRate = 0;
		}
		
		double baseFailRate;
		
		if (transactionData.baseTransactions > 0) {
			baseFailRate = (double)failureData.baseFailures / (double)transactionData.baseTransactions; 
		} else {
			baseFailRate = 0;
		}
		
		RegressionInput regressionInput = reportKeyResult.output.regressionData.regressionOutput.regressionInput;
		
		Pair<ReliabilityState, Double> failurePair;
		
		if (result.failRate > input.minFailRate) {
			failurePair = getReliabilityState(baseFailRate, result.failRate,
				transactionData.transactionVolume, regressionInput);

		} else {
			failurePair = Pair.of(ReliabilityState.OK, Double.valueOf(0));
		}
	
		result.failRateState = failurePair.getFirst();
		result.failureRateDelta = Math.max(0f, failurePair.getSecond());

		result.scoreState = getScoreState(input, reportKeyResult.score);
		
		result.reliabilityState = combineStates(result.failRateState , result.scoreState);
		
		Pair<String, String> descsPair = getReliabilityDescs(result, reportKeyResult, input, 
			transactionData, failureData, baseFailRate);
		
		result.relabilityDesc = descsPair.getFirst();
		result.failureRateDesc = descsPair.getSecond();

		return result;
	}
	
	private Pair<String, String> getReliabilityDescs(ReportKeyReliability reliabilityData,
		ReportKeyResults reportKeyResult,ReliabilityReportInput input,
		ReportKeyTransactions transactionData, ReportKeyFailures failureData,
		double baseFailRate) {
	
		StringBuilder failDelta = new StringBuilder();
		
		failDelta.append(formatRate(reliabilityData.failRate, true));
		
		StringBuilder failureDesc = new StringBuilder();
		
		failureDesc.append(formatLongValue(failureData.failures));
		failureDesc.append(" failures (");

		if (reliabilityData.failRateState != ReliabilityState.OK) {
			
			failDelta.append(" up from ");
			failDelta.append(formatRate(baseFailRate, true));
		}
		
		failureDesc.append(failDelta);
		failureDesc.append(") in ");
		failureDesc.append(formatLongValue(transactionData.transactionVolume));
		failureDesc.append(" transactions");
		
		StringBuilder relabilityDesc = new StringBuilder();
		
		relabilityDesc.append(reliabilityData.reliabilityState.toString().toUpperCase());
		relabilityDesc.append(": ");

		if (reliabilityData.failRateState != ReliabilityState.OK) {
			relabilityDesc.append("Transaction fail rate ");
			relabilityDesc.append(failDelta);		
		} 
		
			
		if (reliabilityData.failRateState != ReliabilityState.OK) {
			relabilityDesc.append(". ");
		}
			
		relabilityDesc.append("Score = ");
		
		if ((int)reportKeyResult.score != reportKeyResult.score) {
			relabilityDesc.append(reportKeyResult.score);
		} else {
			relabilityDesc.append((int)reportKeyResult.score);
		}
		
		if (reliabilityData.reliabilityState != ReliabilityState.OK) {
			String timeRange = TimeUtil.getTimeRange(input.timeFilter);
			
			if (timeRange != null) {
				relabilityDesc.append( " in the last ");
				relabilityDesc.append(timeRange);
			}
		}
					
		return Pair.of(relabilityDesc.toString(), failureDesc.toString());
	}
		
	private ReportKeyTransactions getAppTransactionData(ReportKeyResults reportKeyResult) {
		
		ReportKeyTransactions result = new  ReportKeyTransactions();
		
		Map<TransactionKey, TransactionData> transactionMap = reportKeyResult.output.transactionMap;
		
		if (transactionMap == null) {
			return result;
		}
		
		double avgTimeNum = 0;
		double avgTimeDenom = 0;
		
		double baseAvgTimeNum = 0;
		double baseAvgTimeDenom = 0;
		
		for (TransactionData transactionData : transactionMap.values()) {
			
			result.errorVolume += transactionData.errorsHits;
			
			result.transactionVolume += transactionData.stats.invocations;
			result.baseTransactions += transactionData.baselineInvocations;
					
			avgTimeNum += transactionData.stats.avg_time * transactionData.stats.invocations;
			avgTimeDenom += transactionData.stats.invocations;
			
			baseAvgTimeNum += transactionData.baselineAvg * transactionData.baselineInvocations;
			baseAvgTimeDenom += transactionData.baselineInvocations;
		}
		
		RegressionInput regressionInput = reportKeyResult.output.regressionData.regressionOutput.regressionInput;

		Pair<Double, String> responsePair = getAvgResponseState(avgTimeDenom, avgTimeNum, 
				baseAvgTimeDenom, baseAvgTimeNum, result.transactionVolume, regressionInput);
		
		result.avgTimeDelta = responsePair.getFirst();
		result.deltaDesc = responsePair.getSecond();
		
		result.responseValue =  formatMilli(responsePair.getFirst()) + responsePair.getSecond();		
	
		return result;
	}
	
	private ReliabilityState getScoreState(ReliabilityReportInput input, double score) {
		
		if (input.scoreRanges == null) {
			return ReliabilityState.OK;
		}
		
		String[] parts = input.scoreRanges.split(ARRAY_SEPERATOR);
		
		if (parts.length != 2) {
			return ReliabilityState.OK;
		}
		
		int low = Integer.valueOf(parts[0]);
		int high = Integer.valueOf(parts[1]);
		
		if (score >= high) {
			return ReliabilityState.OK;
		}
		
		if (score < low) {
			return ReliabilityState.Severe;
		}
		
		return ReliabilityState.Warning;
	}
	
	private String getStatusPrefix(ReliabilityReportInput input, 
		ReliabilityState state) {
		
		if (input.statusPrefixes == null) {
			return null;
		}
		
		String[] parts = input.statusPrefixes.split(ARRAY_SEPERATOR);
		
		if (parts.length != 3) {
			return null;
		}
		
		String result = parts[state.ordinal()];	
		
		return result;
		
	}
	
	private String getAppStatusName(ReliabilityReportInput input, 
		ReliabilityState state, String name) {
		
		String prefix = getStatusPrefix(input, state);
		
		if (prefix == null) {
			return "";
		}
		
		String result =  prefix + name;
		
		return result;
		
	}
	
	private void addAppExtendedFields(
			ReliabilityReportInput input, ReportRow row, 
			ReportKeyResults reportKeyResult, String serviceName) {
				
		String appStatusName = getAppStatusName(input, reportKeyResult.relability.reliabilityState, serviceName);
		Object failRateDelta = getFailRateDelta(input, reportKeyResult.relability, false, false);
		
		row.set(ReliabilityReportInput.STATUS_NAME, appStatusName);

		row.set(ReliabilityReportInput.TRANSACTION_VOLUME, formatLongValue(reportKeyResult.transactions.transactionVolume));
		row.set(ReliabilityReportInput.TRANSACTION_AVG_RESPONSE, reportKeyResult.transactions.responseValue);
		
		row.set(ReliabilityReportInput.TRANSACTION_FAILURES, reportKeyResult.failures.failures);
		row.set(ReliabilityReportInput.TRANSACTION_FAIL_RATE, reportKeyResult.relability.failRate);
		row.set(ReliabilityReportInput.TRANSACTION_FAIL_RATE_DELTA, failRateDelta);
		row.set(ReliabilityReportInput.TRANSACTION_FAIL_DESC, reportKeyResult.relability.failureRateDesc);

		row.set(ReliabilityReportInput.ERROR_VOLUME, reportKeyResult.transactions.errorVolume);
		row.set(ReliabilityReportInput.ERROR_COUNT, reportKeyResult.failures.eventCount);
		
		row.set(ReliabilityReportInput.RELIABILITY_STATE, reportKeyResult.relability.reliabilityState.ordinal());	
		row.set(ReliabilityReportInput.RELIABILITY_DESC, reportKeyResult.relability.relabilityDesc);	
	}
	
	private void addTimelineFields(ReportRow row) {
		row.set(ReliabilityReportInput.TIMELINE_DIFF_STATE, "");
	}
	
	private void addDepCompareFields(ReportRow row, 
		ReportKeyResults reportKeyResult, Pair<Object, Object> fromTo) {
		

		String previousDepName = "";
		Integer previousDepState =  Integer.valueOf(0);
		Object previousDepFrom = fromTo.getFirst();
		
		if (reportKeyResult.output.reportKey instanceof DeploymentReportKey) {
			
			DeploymentReportKey depKey = (DeploymentReportKey)reportKeyResult.output.reportKey;
			
			if (depKey.previous != null) {
				previousDepName = depKey.previous.name;
				previousDepState = Integer.valueOf(1);
				
				if (depKey.previous.first_seen != null) {
					DateTime firstSeen = TimeUtil.getDateTime(depKey.previous.first_seen);
					
					long delta = DateTime.now().minus(firstSeen.getMillis()).getMillis();
					long timespan = TimeUnit.MILLISECONDS.toMinutes(delta) + TimeUnit.HOURS.toMinutes(1);
					
					Pair<Object, Object> timeFilter = getTimeFilterPair(null, 
						TimeUtil.getLastWindowMinTimeFilter((int)timespan));
		
					previousDepFrom = timeFilter.getFirst();
				}
			}
		} 
		
		row.set(ReliabilityReportInput.PREV_DEP_NAME, previousDepName);
		row.set(ReliabilityReportInput.PREV_DEP_FROM, previousDepFrom);
		row.set(ReliabilityReportInput.PREV_DEP_STATE, previousDepState);
	}
	
	@Override
	protected List<List<Object>> processServiceEvents(Collection<String> serviceIds,
			String serviceId, EventsInput input,
			Pair<DateTime, DateTime> timeSpan) {

		List<List<Object>> result = new ArrayList<List<Object>>();
		
		ReliabilityReportInput rrInput = (ReliabilityReportInput)input;
		
		Collection<ReportKeyOutput> reportKeyOutputs = executeReport(serviceId, rrInput, timeSpan);
		Collection<ReportKeyResults > reportKeyResults = getReportResults(serviceId, timeSpan, rrInput, reportKeyOutputs);
			
		ReportMode reportMode = rrInput.getReportMode();
		
		for (ReportKeyResults reportKeyResult : reportKeyResults) {
			
			RegressionWindow regressionWindow = reportKeyResult.output.regressionData.regressionOutput.regressionWindow;
			
			String timeRange;
			Pair<Object, Object> fromTo;
			
			if ((reportMode == ReportMode.Timeline)
			  ||(reportMode == ReportMode.Timeline_Extended)) {
				DateTime activeStart = regressionWindow.activeWindowStart;
				DateTime activEnd = activeStart.plusMinutes(regressionWindow.activeTimespan);
				
				timeRange = null;
				fromTo = Pair.of(activeStart.getMillis(), activEnd.getMillis());
			} else {
				if (regressionWindow != null) {
					
					timeRange = TimeUtil.getTimeRange(regressionWindow.activeTimespan);
					
					DateTime from = regressionWindow.activeWindowStart;
					DateTime to = regressionWindow.activeWindowStart.plusMinutes(regressionWindow.activeTimespan);
					
					fromTo = getTimeFilterPair(Pair.of(from, to), 
						TimeUtil.getLastWindowMinTimeFilter(regressionWindow.activeTimespan));
	
				} else {
					fromTo = getTimeFilterPair(timeSpan, input.timeFilter);
					timeRange = TimeUtil.getTimeRange(input.timeFilter);	
				}
			}
					
			Object newIssuesValue = formatIssues(rrInput, reportKeyResult.newIssues, reportKeyResult.severeNewIssues);
			Object regressionsValue = formatIssues(rrInput, reportKeyResult.regressions, reportKeyResult.criticalRegressions);
			Object slowdownsValue = formatIssues(rrInput, reportKeyResult.slowdowns, reportKeyResult.severeSlowdowns);
								
			String serviceValue = getServiceValue(getKeyName(reportKeyResult), serviceId, serviceIds);
			String name = reportKeyResult.output.reportKey.name;
				
			List<String> fields = getColumns(rrInput);
				
			ReportRow row = new ReportRow(fields);
			
			row.set(ViewInput.FROM, fromTo.getFirst());
			row.set(ViewInput.TO, fromTo.getSecond());
			row.set(ViewInput.TIME_RANGE, timeRange);

			row.set(ReliabilityReportInput.SERVICE, serviceId);
			row.set(ReliabilityReportInput.KEY, name);
			row.set(ReliabilityReportInput.NAME, serviceValue);
			
			switch (rrInput.getReportMode()) {
				
				case Tiers:
				case Apps_Extended:
				case Timeline_Extended:
					addAppExtendedFields(rrInput, row, reportKeyResult, serviceValue);	
					break;
				case Deployments:
					addDepCompareFields(row, reportKeyResult, fromTo);
					break;
				case Timeline:
					addTimelineFields(row);
					break;
				default:
					break;	
			}
			
			row.set(ReliabilityReportInput.NEW_ISSUES, newIssuesValue);
			row.set(ReliabilityReportInput.REGRESSIONS, regressionsValue);
			row.set(ReliabilityReportInput.SLOWDOWNS, slowdownsValue);
			
			row.set(ReliabilityReportInput.NEW_ISSUES_DESC, reportKeyResult.newIssuesDesc);
			row.set(ReliabilityReportInput.REGRESSIONS_DESC, reportKeyResult.regressionsDesc);
			row.set(ReliabilityReportInput.SLOWDOWNS_DESC, reportKeyResult.slowDownsDesc);
			
			row.set(ReliabilityReportInput.SCORE, reportKeyResult.score);
			row.set( ReliabilityReportInput.SCORE_DESC, reportKeyResult.scoreDesc);
			
			result.add(row.values);
		}

		return result;
	}
	
	private String getKeyName(ReportKeyResults reportKeyResult) {
		
		String name = GroupSettings.fromGroupName(reportKeyResult.output.reportKey.name);
		
		if (reportKeyResult.output.reportKey.isKey) {
			return name  + "*";
		} else {
			return name;
		}
	}
	
	private String getPostfix(ReliabilityReportInput input, double value) {
		
		if (input.thresholds == null) {
			return null;
		}
		
		if (input.postfixes == null) {
			return null;
		}
		
		String[] thresholds = input.thresholds.split(GRAFANA_SEPERATOR);
		
		if (thresholds.length != 2) {
			return null;
		}
		
		String[] postfixes = input.postfixes.split(GRAFANA_SEPERATOR);
		
		if (postfixes.length != 3) {
			return null;
		}
		
		int min = convert(thresholds[0]);
		int max = convert(thresholds[1]);
		
		if ((min < 0) || (max < 0)) {
			return null;
		}

		if (value < min) {
			return postfixes[0];
		}
		
		if (value < max) {
			return postfixes[1];
		}
		
		return postfixes[2];
	}
	
	private static int convert(String s) {
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return -1;
		}
	}

	private Pair<Integer, Integer> getSlowdowns(Collection<TransactionData> transactionDatas) {
		
		int slowdowns = 0;
		int severeSlowdowns = 0;
		
		for (TransactionData transactionData : transactionDatas) {
			if (PerformanceState.SLOWING.equals(transactionData.state)) {
				slowdowns++;
			} else if (PerformanceState.CRITICAL.equals(transactionData.state)) {
				severeSlowdowns++;
			}
		}
		
		return Pair.of(slowdowns, severeSlowdowns);
	}
	
	private Object getReportKeyValue(Collection<String> serviceIds, String serviceId, Pair<DateTime, DateTime> timeSpan,
		ReliabilityReportInput input, ReportKeyResults reportKeyResult) {
			
		RelabilityKpi graphType = ReliabilityReportInput.getKpi(input.graphType);

		if (graphType == null) {
			return reportKeyResult.score;
		}
		
		switch (graphType) {
			
			case NewErrors: return reportKeyResult.newIssues + reportKeyResult.severeNewIssues;
			case SevereNewErrors: return reportKeyResult.severeNewIssues; 
			case ErrorIncreases: return reportKeyResult.regressions + reportKeyResult.criticalRegressions;
			case SevereErrorIncreases: return reportKeyResult.criticalRegressions; 
			case Slowdowns: return reportKeyResult.slowdowns + reportKeyResult.severeSlowdowns;
			case SevereSlowdowns: return reportKeyResult.severeSlowdowns; 
			
			case ErrorVolume: 
				if (reportKeyResult.volumeData != null) {
					return reportKeyResult.volumeData.volume;
				} else {
					return 0;
				}
			
			case ErrorCount: 
				if (reportKeyResult.volumeData != null) {
					return reportKeyResult.volumeData.count;
				} else {
					return 0;
				}
			
			case ErrorRate: 
				if (reportKeyResult.volumeData != null) {
					return reportKeyResult.volumeData.rate;
				} else {
					return 0;
				}
			
			case FailRateDelta: 
				
				if (serviceIds.size() == 1) {
					return getFailRateDelta(serviceId, 	timeSpan, input, reportKeyResult, true);
				} else {
					return 0;
				}
			
			case FailRateDesc: return getFailRateDesc(serviceIds, serviceId, 
					timeSpan, input, reportKeyResult);
			
			case ScoreDesc: return reportKeyResult.scoreDesc; 
	
			case Score: return reportKeyResult.score; 
		}
		
		return 0;
	}
	
	private ReportKeyReliability getAppReliabilityData(String serviceId, 
			Pair<DateTime, DateTime> timespan, ReliabilityReportInput input, ReportKeyResults reportKeyResult) {
		
		ReportKeyFailures appFailureData = getAppFailureData(serviceId, input, timespan, reportKeyResult);
		ReportKeyTransactions appTransactionsData = getAppTransactionData(reportKeyResult);
		
		ReportKeyReliability result = getAppReliabilityData(reportKeyResult, input, appTransactionsData, appFailureData);
				
		return result;
	}
	
	private Object getFailRateDesc(Collection<String> serviceIds, String serviceId, 
			Pair<DateTime, DateTime> timespan, ReliabilityReportInput input, ReportKeyResults reportKeyResult) {
			
		if (serviceIds.size() > 1) {
			return 0;
		}
			
		ReportKeyReliability appReliabilityData = getAppReliabilityData(serviceId, timespan, input, reportKeyResult);
		
		String result = appReliabilityData.failureRateDesc;
			
		return result;
	}
	
	private Object getFailRateDelta(String serviceId, Pair<DateTime, DateTime> timespan, 
			ReliabilityReportInput input, ReportKeyResults reportKeyResult, boolean postfix) {
		
		ReportKeyReliability appReliabilityData = getAppReliabilityData(serviceId, timespan, input, reportKeyResult);
		Object result = getFailRateDelta(input, appReliabilityData, true, postfix);
		
		return result;
	}
	
	private Object getFailRateDelta(ReliabilityReportInput input, ReportKeyReliability appReliabilityData,
			boolean stringFormat, boolean postfix) {
		
		if (appReliabilityData.failureRateDelta == 0) {
			return "";
		}
				
		Object result;
	
		String rateStr;
			
		if (appReliabilityData.failureRateDelta > 1) {
			rateStr = "Δ>100%";	 
		} else {		
			if (stringFormat) {
				rateStr = "+" + formatRate(appReliabilityData.failureRateDelta, false);
			} else {
				rateStr = null;
			}
		}
		
		if (rateStr != null) {
			
			if (postfix) {
				String statusPrefix = getStatusPrefix(input, appReliabilityData.failRateState);
				result = String.format("%s %s", rateStr, statusPrefix);
			} else {
				result = rateStr;
			}
			
		} else {
			result = appReliabilityData.failureRateDelta;
		}
		
		return result;
	}

	private List<Series> processGraph(ReliabilityReportInput input) {

		List<Series> result = new ArrayList<Series>();

		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);
		Collection<String> serviceIds = getServiceIds(input);

		for (String serviceId : serviceIds) {

			Collection<ReportKeyOutput> reportKeyOutputs = executeReport(serviceId, input, timeSpan);
			Collection<ReportKeyResults > reportKeyResults = getReportResults(serviceId, timeSpan, input, reportKeyOutputs);

			for (ReportKeyResults reportKeyResult : reportKeyResults) {

				Object keyValue = getReportKeyValue(serviceIds, serviceId, timeSpan, input, reportKeyResult);
				
				String seriesName;
				String postfix;
				
				if (keyValue instanceof Double) {
					postfix = getPostfix(input, (Double)keyValue);
				} else {
					postfix = null;
				}
				
				String name = getKeyName(reportKeyResult);
				
				if (postfix != null) {
					seriesName = getServiceValue(name + postfix, serviceId, serviceIds);
				} else {
					seriesName = getServiceValue(name, serviceId, serviceIds);
				}
								
				Series series = new Series();
				series.values = new ArrayList<List<Object>>();

				series.name = EMPTY_NAME;
				series.columns = Arrays.asList(new String[] { TIME_COLUMN, seriesName });
				series.values
						.add(Arrays.asList(new Object[] { timeSpan.getSecond().getMillis(), keyValue }));

				result.add(series);
			}
		}

		return result;
	}
}
