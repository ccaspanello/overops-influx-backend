package com.takipi.integrations.grafana.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Queues;
import com.google.gson.Gson;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.application.SummarizedApplication;
import com.takipi.api.client.data.deployment.SummarizedDeployment;
import com.takipi.api.client.data.metrics.Graph;
import com.takipi.api.client.data.transaction.TransactionGraph;
import com.takipi.api.client.request.application.ApplicationsRequest;
import com.takipi.api.client.request.deployment.DeploymentsRequest;
import com.takipi.api.client.request.event.EventRequest;
import com.takipi.api.client.request.event.EventsRequest;
import com.takipi.api.client.request.event.EventsSlimVolumeRequest;
import com.takipi.api.client.request.metrics.GraphRequest;
import com.takipi.api.client.request.process.JvmsRequest;
import com.takipi.api.client.request.service.ServicesRequest;
import com.takipi.api.client.request.transaction.TransactionsGraphRequest;
import com.takipi.api.client.request.transaction.TransactionsVolumeRequest;
import com.takipi.api.client.request.view.ViewsRequest;
import com.takipi.api.client.result.application.ApplicationsResult;
import com.takipi.api.client.result.deployment.DeploymentsResult;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.event.EventsResult;
import com.takipi.api.client.result.event.EventsSlimVolumeResult;
import com.takipi.api.client.result.metrics.GraphResult;
import com.takipi.api.client.result.process.JvmsResult;
import com.takipi.api.client.result.service.ServicesResult;
import com.takipi.api.client.result.transaction.TransactionsGraphResult;
import com.takipi.api.client.result.transaction.TransactionsVolumeResult;
import com.takipi.api.client.result.view.ViewsResult;
import com.takipi.api.client.util.regression.RegressionInput;
import com.takipi.api.client.util.regression.RegressionUtil;
import com.takipi.api.client.util.regression.RegressionUtil.RegressionWindow;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.api.core.request.intf.ApiGetRequest;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.functions.GrafanaFunction;
import com.takipi.integrations.grafana.functions.GrafanaThreadPool;
import com.takipi.integrations.grafana.functions.RegressionFunction;
import com.takipi.integrations.grafana.functions.RegressionFunction.RegressionOutput;
import com.takipi.integrations.grafana.input.BaseEventVolumeInput;
import com.takipi.integrations.grafana.input.BaseGraphInput;
import com.takipi.integrations.grafana.input.EventFilterInput;
import com.takipi.integrations.grafana.input.ViewInput;
import com.takipi.integrations.grafana.storage.FolderStorage;
import com.takipi.integrations.grafana.storage.KeyValueStorage;

public class ApiCache {
	
	private static final Logger logger = LoggerFactory.getLogger(ApiCache.class);
	
	private static final int CACHE_SIZE = 500;
	private static final int CACHE_REFRESH_RETENTION = 90;
		
	public static final int NO_GRAPH_SLICE = -1;
	public static final int MIN_SLICE_POINTS = 3;
	
	public static boolean CACHE_GRAPHS = true;
	public static boolean SLICE_GRAPHS = true;
	
	private static final String CACHE_FOLDER = "GraphCacheFolder";
	public static boolean PRINT_DURATIONS = true;

	public static class QueryLogItem {
		
		public long apiHash;
		public final long t1;
		public long t2;
		public String serviceId;
		public Class<?> loaderClass;
		public String loaderData;
		public String exception;
		public int functionThreadSize;
		public int functionQueueSize;
		public int queryThreadSize;
		public int queryQueueSize;	
		
		public QueryLogItem() {
			this.t1 = System.currentTimeMillis();
		}	

		@Override
		public String toString() {
			
			StringBuilder result = new StringBuilder();
			
			if (serviceId != null) {
				result.append(serviceId);
				result.append(" ");
			}

			result.append(getLoaderClassName(loaderClass));
			
			double delta = Math.max(0, t2 - t1);
			
			result.append(": ");
			result.append(GrafanaFunction.singleDigitFormatter.format(delta / 1000));
			result.append("sec, ");
			
			if (loaderData != null) {
				result.append("data: ");
				result.append(loaderData);
				result.append(", ");
			}
			
			if (exception != null) {
				result.append("ex: ");
				result.append(exception);
				result.append(", ");
			}
			
			result.append("FTS:");
			result.append(functionThreadSize);
			result.append(", FQS:");
			result.append(functionQueueSize);
			
			result.append(" QTS:");
			result.append(queryThreadSize);
			result.append(", QQS:");
			result.append(queryQueueSize);
			
			return result.toString();	
		}
	}
	
	public abstract static class BaseCacheLoader {

		public ApiClient apiClient;
		public ApiGetRequest<?> request;
		
		public long loadT1;
		public long loadT2;

		
		public BaseCacheLoader(ApiClient apiClient, ApiGetRequest<?> request) {
			this.apiClient = apiClient;
			this.request = request;
		}
		
		/**
		 * @param response - for children 
		 */
		public String getLoaderData(Response<?> response) {
			return null;
		}
		
		protected String getServiceId() {
			return null;
		}

		protected boolean printDuration() {
			return true;
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof BaseCacheLoader)) {
				return false;
			}

			BaseCacheLoader other = (BaseCacheLoader) obj;

			if (!Objects.equal(apiClient, other.apiClient)) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return apiClient.getHostname().hashCode();
		}
		
		public Response<?> load() {
						
			QueryLogItem queryLogItem = new QueryLogItem();
			
			queryLogItem.apiHash = apiClient.hashCode();
			this.loadT1 = queryLogItem.t1;
			
			queryLogItems.add(queryLogItem);
		
			try {
					
				queryLogItem.loaderClass = this.getClass();
				queryLogItem.serviceId = this.getServiceId();
				
				if (queryLogItem.serviceId != null) {
					
					Pair<ThreadPoolExecutor, ThreadPoolExecutor> serviceExecutors = 
						GrafanaThreadPool.getExecutors(apiClient);
					
					queryLogItem.functionThreadSize = serviceExecutors.getFirst().getActiveCount();
					queryLogItem.functionQueueSize = serviceExecutors.getFirst().getQueue().size();
					
					queryLogItem.queryThreadSize = serviceExecutors.getSecond().getActiveCount();
					queryLogItem.queryQueueSize = serviceExecutors.getSecond().getQueue().size();				
				}
				
				Response<?> result = apiClient.get(request);
				
				queryLogItem.t2 = System.currentTimeMillis();
				this.loadT2 = queryLogItem.t2;
				
				if (result != null) {
					queryLogItem.loaderData = getLoaderData(result);
				}
				
				if ((PRINT_DURATIONS)  && (this.printDuration())) {
					logger.info(queryLogItem.toString());
				}
								
				return result;
				
			} catch (Throwable e) {
				queryLogItem.t2 = System.currentTimeMillis();
				this.loadT2 = queryLogItem.t2;
				
				queryLogItem.exception = e.getMessage();
				
				throw new IllegalStateException("Error executing " + queryLogItem.toString() , e);
			}
		}
		
		@Override
		public String toString() {
			return getLoaderClassName(this.getClass());
		}
	}
	
	protected static class ServicesCacheLoader extends BaseCacheLoader {

		public ServicesCacheLoader(ApiClient apiClient, ApiGetRequest<?> request) {
			super(apiClient, request);
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof ServicesCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			return true;
		}		
	}

	protected abstract static class ServiceCacheLoader extends BaseCacheLoader {

		protected String serviceId;

		public ServiceCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId) {
			super(apiClient, request);
			this.serviceId = serviceId;
		}

		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof ServiceCacheLoader)) {
				return false;
			}

			ServiceCacheLoader other = (ServiceCacheLoader) obj;

			if (!super.equals(obj)) {
				return false;
			}

			if (!Objects.equal(serviceId, other.serviceId)) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return super.hashCode() ^ serviceId.hashCode();
		}
		
		@Override
		protected String getServiceId() {
			return serviceId;
		}
	}
	
	protected static class EventCacheLoader extends ServiceCacheLoader {

		protected String Id;

		public EventCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, String Id) {

			super(apiClient, request, serviceId);
			this.Id = Id;
		}
		
		@Override
		protected boolean printDuration()
		{
			return false;
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof EventCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			EventCacheLoader other = (EventCacheLoader) obj;

			if (!Objects.equal(Id, other.Id)) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {

			if (Id == null) {
				return super.hashCode();
			}

			return super.hashCode() ^ Id.hashCode();
		}
		
		@Override
		public String toString() {
			return getLoaderClassName(this.getClass()) + ": " + Id;
		}
	}

	protected static class DeploymentsCacheLoader extends ServiceCacheLoader {
		
		protected boolean active;
		
		public DeploymentsCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, 
			String serviceId, boolean active) {

			super(apiClient, request, serviceId);
			
			this.active = active;
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof DeploymentsCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			DeploymentsCacheLoader other = (DeploymentsCacheLoader) obj;

			if (active != other.active) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return super.hashCode();
		}
		
		@Override
		public String toString() {
			return getLoaderClassName(this.getClass()) + " active: " + active;
		}
	}
	
	protected static class ProcessesCacheLoader extends ServiceCacheLoader {
		
		protected boolean active;
		
		public ProcessesCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, 
			String serviceId, boolean active) {

			super(apiClient, request, serviceId);
			
			this.active = active;
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof ProcessesCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			ProcessesCacheLoader other = (ProcessesCacheLoader) obj;

			if (active != other.active) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return super.hashCode();
		}
		
		@Override
		public String toString() {
			return getLoaderClassName(this.getClass()) + " active: " + active;
		}
	}
	
	protected static class ApplicationsCacheLoader extends ServiceCacheLoader {
		
		protected boolean active;
		
		public ApplicationsCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, 
			String serviceId, boolean active) {

			super(apiClient, request, serviceId);
			
			this.active = active;
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof ApplicationsCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			ApplicationsCacheLoader other = (ApplicationsCacheLoader) obj;

			if (active != other.active) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return super.hashCode();
		}
		
		@Override
		public String toString() {
			return getLoaderClassName(this.getClass()) + " active: " + active;
		}
	}
	
	protected static class ViewCacheLoader extends ServiceCacheLoader {

		protected String viewName;

		public ViewCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, String viewName) {

			super(apiClient, request, serviceId);
			
			this.viewName = viewName;
		}

		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof ViewCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			ViewCacheLoader other = (ViewCacheLoader) obj;

			if (!Objects.equal(GrafanaFunction.getViewName(viewName),
					GrafanaFunction.getViewName(other.viewName))) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			
			return super.hashCode() ^ 
				GrafanaFunction.getViewName(viewName).hashCode();
		}
		
		@Override
		public String toString() {
			return this.getClass().getSimpleName() + ": " + viewName + " " + viewName;
		}
	}

	protected abstract static class ViewInputCacheLoader extends ServiceCacheLoader {

		protected ViewInput input;

		public ViewInputCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input) {
			super(apiClient, request, serviceId);
			
			this.input = input;
		}

		private static boolean compare(Collection<String> a, Collection<String> b) {

			if (a.size() != b.size()) {
				return false;
			}

			for (String s : a) {
				if (!b.contains(s)) {
					return false;
				}
			}

			return true;
		}
		
		private boolean compareTimeFilters(String t1, String t2) {
			
			String tu1 = TimeUtil.getTimeUnit(t1);
			String tu2 = TimeUtil.getTimeUnit(t2);
			
			if ((tu1 == null) || (tu2 == null)) {
				return t1.equals(t2);
			}
			
			int ti1 = TimeUtil.parseInterval(tu1);
			int ti2 = TimeUtil.parseInterval(tu2);

			boolean result = ti1 == ti2;
			
			return result;
			
		}
		
		protected boolean compareTimeframes(ViewInputCacheLoader other) {
			
			if ((input.timeFilter != null) && (other.input.timeFilter != null) &&
		    		(!compareTimeFilters(input.timeFilter, other.input.timeFilter))) {	
				return false;	
			}
		
			return true;
		}

		@Override
		public boolean equals(Object obj) {
		
			if (!(obj instanceof ViewInputCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			ViewInputCacheLoader other = (ViewInputCacheLoader) obj;
			
			if (!compareTimeframes(other)) {
				return false;
			}

			if (!Objects.equal(GrafanaFunction.getViewName(input.view),
				GrafanaFunction.getViewName((other.input.view)))) {
				return false;
			}

			Collection<String> deps = input.getDeployments(serviceId);
			Collection<String> otherDeps = other.input.getDeployments(serviceId);
			
			if (!compare(deps, otherDeps)) {
				return false;
			}
			
			Collection<String> servers = input.getServers(serviceId);
			Collection<String> otherServers = other.input.getServers(serviceId);
			
			if (!compare(servers, otherServers)) {
				return false;
			}

			Collection<String> apps = input.getApplications(null, null, serviceId, false);
			Collection<String> otherApps = other.input.getApplications(null, null, serviceId, false);
			
			if (!compare(apps, otherApps)) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {

			int result = super.hashCode() ^ 
				GrafanaFunction.getViewName(input.view).hashCode();
			
			return result;
		}

		@Override
		public String toString() {
			return getLoaderClassName(this.getClass()) + ": " + serviceId + " " + input.view + " Deps: " + input.deployments
					+ " Apps: " + input.applications + " Servs: " + input.servers;
		}
	}

	protected abstract static class VolumeCacheLoader extends ViewInputCacheLoader {

		protected VolumeType volumeType;

		public VolumeCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				VolumeType volumeType) {

			super(apiClient, request, serviceId, input);
			this.volumeType = volumeType;
		}

		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof VolumeCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			VolumeCacheLoader other = (VolumeCacheLoader) obj;
			
			if (volumeType != null) {
				
				switch (volumeType) {
					case hits: 
						if ((other.volumeType == null) || (other.volumeType.equals(VolumeType.invocations))) {
							return false;
						}
						break;
					
					case invocations: 
						if ((other.volumeType == null) || (other.volumeType.equals(VolumeType.hits))) {
							return false;
						}
						break;
					case all: 
						if ((other.volumeType == null) || (!other.volumeType.equals(VolumeType.all))) {
							return false;
						}
						break;
				}
			}
			
			if (!Objects.equal(volumeType, other.volumeType)) {
				return false;
			}

			return true;
		}

		@Override
		public String toString() {
			return super.toString() + " volumeType: " + volumeType;
		}
	}
	
	protected static class SlimVolumeCacheLoader extends VolumeCacheLoader {
		
		public SlimVolumeCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				VolumeType volumeType) {

			super(apiClient, request, serviceId, input, volumeType);
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof SlimVolumeCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			return true;
		}
	}


	protected static class EventsCacheLoader extends VolumeCacheLoader {
		
		public EventsCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				VolumeType volumeType) {

			super(apiClient, request, serviceId, input, volumeType);
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof EventsCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			return true;
		}
		
		@Override
		public String getLoaderData(Response<?> response) {
			
			if (response.data instanceof EventsResult) {
				
				EventsResult eventsResult = (EventsResult)response.data;
				
				if (eventsResult.events == null) {
					return "0";
				}
				
				return String.valueOf(eventsResult.events.size());	
			}
			
			return super.getLoaderData(response);
		}
	}

	protected static class GraphCacheLoader extends VolumeCacheLoader {
		
		protected int pointsWanted;
		protected int activeWindow;
		protected int baselineWindow;
		protected int windowSlice;
		protected Pair<DateTime, DateTime> timespan;
		protected boolean cachable;

		public GraphCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				VolumeType volumeType, int pointsWanted, 
				int baselineWindow, int activeWindow, int windowSlice,
				Pair<DateTime, DateTime> timespan, boolean cachable) {

			super(apiClient, request, serviceId, input, volumeType);
			this.pointsWanted = pointsWanted;
			this.activeWindow = activeWindow;
			this.baselineWindow = baselineWindow;
			this.windowSlice = windowSlice;
			this.timespan = timespan;
			this.cachable = cachable;
		}
				
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof GraphCacheLoader)) {
				return false;
			}

			if (!super.equals( obj)) {
				return false;
			}

			GraphCacheLoader other = (GraphCacheLoader) obj;

			/*
			if (pointsWanted != other.pointsWanted) {
				return false;
			}
			*/
			
			if (activeWindow != other.activeWindow) {
				return false;
			}
			
			if (baselineWindow != other.baselineWindow) {
				return false;
			}
			
			if (windowSlice != other.windowSlice) {
				return false;
			}

			return true;
		}
		
		@Override
		public Response<?> load() {			
			
			String writeKeyName = null;
			
			if (cachable) {
				
				Pair<String, Collection<String>> keyPair = getkeyNames();
				
				writeKeyName = keyPair.getFirst();
				Collection<String> readKeyNames = keyPair.getSecond();
				
				String value = null;
				
				for (String keyName : readKeyNames) {
				
					this.loadT1 = System.currentTimeMillis();
					
					try {
						value = cacheStorage.getValue(keyName);
					} catch (Exception e) {
						logger.error("Could not load data for " + keyName, e);
					}
			
					this.loadT2 = System.currentTimeMillis();
					
					if (value != null) {
						break;
					}
				}

				if (value != null) {
					
					Gson gson = new Gson();
					GraphResult graphResult = gson.fromJson(value, GraphResult.class);
					
					if (graphResult != null) {
						Response<GraphResult> response = Response.of(200, graphResult);						
						return response;
					}
				}
			} 
			
			@SuppressWarnings("unchecked")
			Response<GraphResult> response = (Response<GraphResult>)super.load();

			if ((cachable) && (response != null) 
			&& (response.data != null) && (response.isOK())) {
				
				try {
					Gson gson = new Gson();
					String value = gson.toJson(response.data); 
					cacheStorage.setValue(writeKeyName, value);
				} catch (Exception e) {
					logger.error("Could not store data for " + writeKeyName, e);
				}
				
			}
			
			return response;
		}
			
		private String getKeyVolumeTypName(VolumeType volumeType) {
			
			String result = String.join("_", serviceId, input.view, input.applications,
					input.deployments, input.servers, 
					String.valueOf(volumeType), TimeUtil.toString(timespan.getFirst()),
					TimeUtil.toString(timespan.getSecond()));
			
			return result;
		}
		
		private Pair<String, Collection<String>> getkeyNames() {
			
			List<String> readKeyNames = new ArrayList<>(2);
			
			if (volumeType == VolumeType.hits) {
				readKeyNames.add(getKeyVolumeTypName(VolumeType.all));
			}
			
			String writeKeyName = getKeyVolumeTypName(this.volumeType);
			
			readKeyNames.add(writeKeyName);

			return Pair.of(writeKeyName, readKeyNames);
		}
		
		@Override
		public String toString() {
			
			String result = super.toString() + " activeWindow: " 
				+ activeWindow + " baselineWindow: " + baselineWindow + " graph slice: " + windowSlice;
			
			return result;
		}
		
		@Override
		public String getLoaderData(Response<?> response) {
			
			if (response.data instanceof GraphResult) {
				
				GraphResult graphResult = (GraphResult)response.data;
				
				if (graphResult.graphs == null) {
					return "0";
				}
				
				int points = 0;
				
				for (Graph graph : graphResult.graphs) {
					
					if (graph.points != null) {
						points += graph.points.size();	
					}
				}
				
				return graphResult.graphs.size() + "\\" + points;
			}
		
			return super.getLoaderData(response);
		}
	}
	
	protected static class TransactionsCacheLoader extends ViewInputCacheLoader {
		
		protected int baselineTimespan;
		protected int activeTimespan;
		
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof TransactionsCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}
			
			TransactionsCacheLoader other = (TransactionsCacheLoader)obj;
			
			if (activeTimespan != other.activeTimespan) {
				return false;
			}
			
			if (baselineTimespan != other.baselineTimespan) {
				return false;
			}

			return true;
		}

		public TransactionsCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input) {
			this(apiClient, request, serviceId, input, 0, 0);
		}
		
		public TransactionsCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input, int baselineTimespan, int activeTimespan) {
			super(apiClient, request, serviceId, input);
			
			this.baselineTimespan = baselineTimespan;
			this.activeTimespan = activeTimespan;
		}
		
		@Override
		public String getLoaderData(Response<?> response) {
			
			if (response.data instanceof TransactionsVolumeResult) {
				
				TransactionsVolumeResult transactionsVolumeResult = (TransactionsVolumeResult)response.data;
				
				if (transactionsVolumeResult.transactions == null) {
					return "0";
				}
				
				return String.valueOf(transactionsVolumeResult.transactions.size());	
			}
			
			return super.getLoaderData(response);
		}
	}

	protected static class TransactionsGraphCacheLoader extends ViewInputCacheLoader {

		protected int pointsWanted;
		protected int baselineTimespan;
		protected int activeTimespan;

		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof TransactionsGraphCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			TransactionsGraphCacheLoader other = (TransactionsGraphCacheLoader) obj;

			/*
			if (pointsWanted != other.pointsWanted) {
				return false;
			}
			*/
			
			if (baselineTimespan != other.baselineTimespan) {
				return false;
			}
			
			if (activeTimespan != other.activeTimespan) {
				return false;
			}

			return true;
		}
		
		@Override
		public String toString() {
			return super.toString() + " aw = " + activeTimespan + " bw = " + baselineTimespan;
		}

		public TransactionsGraphCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId,
				ViewInput input, int pointsWanted) {
			this(apiClient, request, serviceId, input, pointsWanted, 0, 0);

		}
		
		@Override
		public String getLoaderData(Response<?> response) 	{

			if (response.data instanceof TransactionsGraphResult) {
				
				TransactionsGraphResult transactionsGraphResult = (TransactionsGraphResult)response.data;
				
				if (transactionsGraphResult.graphs == null) {
					return "0";
				}
				
				int points = 0;
				
				for (TransactionGraph graph : transactionsGraphResult.graphs) {
					
					if (graph.points != null) {
						points += graph.points.size();	
					}
				}
				
				return transactionsGraphResult.graphs.size() + "\\" + points;
			}
			
			return super.getLoaderData(response);
		}
		
		public TransactionsGraphCacheLoader(ApiClient apiClient, ApiGetRequest<?> request, String serviceId,
				ViewInput input, int pointsWanted, int baselineTimespan, int activeTimespan) {

			super(apiClient, request, serviceId, input);
			this.pointsWanted = pointsWanted;
			this.activeTimespan = activeTimespan;
			this.baselineTimespan = baselineTimespan;
		}
	}
	
	private static class RegresionWindowCacheLoader {
		protected RegressionInput input;
		protected ApiClient apiClient;

		protected RegresionWindowCacheLoader(ApiClient apiClient, RegressionInput input) {
			this.input = input;
			this.apiClient = apiClient;
		}

		@Override
		public boolean equals(Object obj) {

			RegresionWindowCacheLoader other = (RegresionWindowCacheLoader) obj;

			if (!apiClient.getHostname().equals(other.apiClient.getHostname())) {
				return false;
			}

			if (!input.serviceId.equals(other.input.serviceId)) {
				return false;
			}

			if (!input.viewId.equals(other.input.viewId)) {
				return false;
			}

			if (input.activeTimespan != other.input.activeTimespan) {
				return false;
			}

			if (input.baselineTimespan != other.input.baselineTimespan) {
				return false;
			}

			if ((input.deployments == null) != (other.input.deployments == null)) {
				return false;
			}
			
			if (!Objects.equal(input.activeWindowStart, other.input.activeWindowStart)) {
				return false;
			}

			if (input.deployments != null) {
				
				if (input.deployments.size() != other.input.deployments.size()) {
					return false;
				}
	
				for (String dep : input.deployments) {
					if (!other.input.deployments.contains(dep)) {
						return false;
					}
				}
			}

			return true;
		}

		@Override
		public int hashCode() {

			StringBuilder result = new StringBuilder();

			result.append(input.serviceId).append(input.viewId);

			if (input.deployments != null) {

				for (String dep : input.deployments) {
					result.append(dep);
				}

			}

			return result.toString().hashCode();
		}
	}
	
	public static class RegressionCacheLoader extends EventsCacheLoader {

		protected boolean newOnly;
		protected RegressionFunction function;
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof RegressionCacheLoader)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			RegressionCacheLoader other = (RegressionCacheLoader) obj;
			
			EventFilterInput eventInput = (EventFilterInput)input;
			EventFilterInput otherInput = (EventFilterInput)(other.input);
			
			if (!Objects.equal(eventInput.types, otherInput.types)) {
				return false;
			}
						
			if (!Objects.equal(eventInput.hasTransactions(), 
				otherInput.hasTransactions())) {
				return false;
			}
			
			if ((eventInput.hasTransactions())
			&& (!Objects.equal(eventInput.transactions, 
				otherInput.transactions))) {
				return false;
			}
			
			if (!Objects.equal(eventInput.searchText, 
				otherInput.searchText)) {
				return false;
			}
			
			if (!Objects.equal(eventInput.eventLocations, 
				otherInput.eventLocations)) {
				return false;
			}
			
			if (newOnly != other.newOnly) {
				return false;
			}

			return true;
		}
		
		public RegressionOutput executeRegression() {
			return function.executeRegression(serviceId, 
				(BaseEventVolumeInput)input, newOnly);
		}
		
		/*
		@Override
		protected boolean compareTimeframes(ViewInputCacheLoader other)
		{
			if (input.deployments != null) {
				return Objects.equal(input.deployments, other.input.deployments);
			} else {
				return super.compareTimeframes(other);
			}
		}
		*/
		
		public String getLoaderData(RegressionOutput regressionOutput) {
			
			StringBuilder result = new StringBuilder();
			
			if ((regressionOutput.baseVolumeGraph != null) 
			&& (regressionOutput.baseVolumeGraph.points != null)) {
				result.append("Baseline graph points: ");
				result.append(regressionOutput.baseVolumeGraph.points.size());
				result.append(", ");
			}	
			
			if ((regressionOutput.activeVolumeGraph != null) 
			&& (regressionOutput.activeVolumeGraph.points != null)) {
				result.append("Active graph points: ");
				result.append(regressionOutput.activeVolumeGraph.points.size());
				result.append(", ");
			}	
			
			if (regressionOutput.eventListMap != null) {
				result.append("Event list: ");
				result.append(regressionOutput.eventListMap.size());
			}
		
			return result.toString();
		}

		public RegressionCacheLoader(ApiClient apiClient, String serviceId, 
				ViewInput input, RegressionFunction function, boolean newOnly) {

			super(apiClient, null, serviceId, input, null);
			this.function = function;
			this.newOnly = newOnly;
		}
	}

	private static Response<?> getItem(BaseCacheLoader key) {
		
		try {
			Response<?> result = queryCache.get(key);
			
			if (result.isBadResponse()) {
				queryCache.invalidate(key);
			} 
			
			return result;
			
		} catch (ExecutionException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	public static Response<ViewsResult> getView(ApiClient apiClient, String serviceId, String viewName,
		ViewsRequest viewsRequest) {

		ViewCacheLoader cacheKey = new ViewCacheLoader(apiClient, viewsRequest, serviceId, viewName);
		Response<ViewsResult> response = (Response<ViewsResult>)getItem(cacheKey);

		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<DeploymentsResult> getDeployments(ApiClient apiClient, String serviceId, boolean active) {

		DeploymentsRequest request = DeploymentsRequest.newBuilder().setServiceId(serviceId).setActive(active).build();
		DeploymentsCacheLoader cacheKey = new DeploymentsCacheLoader(apiClient, request, serviceId, active);
		Response<DeploymentsResult> response = (Response<DeploymentsResult>)getItem(cacheKey);

		return response;
	}
	
	public static Collection<String> getDeploymentNames(ApiClient apiClient, String serviceId, boolean active) {

		List<String> result;
		Response<DeploymentsResult> response = getDeployments(apiClient, serviceId, active);	
		
		if ((response.data != null) && (response.data.deployments != null)) {
			
			result = new ArrayList<String>();
			
			for (SummarizedDeployment dep : response.data.deployments) {
				result.add(dep.name);
			}
		} else {
			result = Collections.emptyList();
		}
		
		return result;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<JvmsResult> getProcesses(ApiClient apiClient, String serviceId, boolean connected) {

		JvmsRequest request = JvmsRequest.newBuilder().setServiceId(serviceId).setConnected(connected).build();
		ProcessesCacheLoader cacheKey = new ProcessesCacheLoader(apiClient, request, serviceId, connected);
		Response<JvmsResult> response = (Response<JvmsResult>)getItem(cacheKey);

		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<ServicesResult> getServices(ApiClient apiClient) {

		ServicesRequest request = ServicesRequest.newBuilder().build();
		ServicesCacheLoader cacheKey = new ServicesCacheLoader(apiClient, request);
		Response<ServicesResult> response = (Response<ServicesResult>)getItem(cacheKey);

		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<ApplicationsResult> getApplications(ApiClient apiClient, String serviceId, boolean active) {

		ApplicationsRequest request = ApplicationsRequest.newBuilder().setServiceId(serviceId).setActive(active).build();
		ApplicationsCacheLoader cacheKey = new ApplicationsCacheLoader(apiClient, request, serviceId, active);
		Response<ApplicationsResult> response = (Response<ApplicationsResult>)getItem(cacheKey);

		return response;
	}
	
	public static Collection<String> getApplicationNames(ApiClient apiClient, String serviceId, boolean active) {

		Response<ApplicationsResult> response = getApplications(apiClient, serviceId, active);
		
		List<String> result;
		
		if ((response.data != null) && (response.data.applications != null)) {
			
			result = new ArrayList<String>(response.data.applications.size());

			for (SummarizedApplication app : response.data.applications) {
				result.add(app.name);
			}
		} else {
			result = Collections.emptyList();
		}
		
		return result;
	}
	
	public static Response<TransactionsVolumeResult> getTransactionsVolume(ApiClient apiClient, String serviceId,
			ViewInput input, TransactionsVolumeRequest request) {
		return getTransactionsVolume(apiClient, serviceId, input, 0, 0, request);
	}

	@SuppressWarnings("unchecked")
	public static Response<TransactionsVolumeResult> getTransactionsVolume(ApiClient apiClient, String serviceId,
			ViewInput input, int activeTimespan, int baselineTimespan, TransactionsVolumeRequest request) {

		TransactionsCacheLoader cacheKey = new TransactionsCacheLoader(apiClient, request, serviceId, input, activeTimespan, baselineTimespan);
		Response<TransactionsVolumeResult> response = (Response<TransactionsVolumeResult>)getItem(cacheKey);

		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<GraphResult> getEventGraph(ApiClient apiClient, String serviceId,
			ViewInput input, VolumeType volumeType, GraphRequest request, int pointsWanted,
			int baselineWindow, int activeWindow, int windowSlice,
			Pair<DateTime, DateTime> timespan, boolean cache) {

		boolean cachable = (CACHE_GRAPHS) && (cacheStorage != null) && (cache);
		
		GraphCacheLoader cacheKey = new GraphCacheLoader(apiClient, request, serviceId, input, volumeType, 
				pointsWanted, baselineWindow, activeWindow, windowSlice, timespan, cachable);
		
		Response<GraphResult> response = (Response<GraphResult>) getItem(cacheKey);

		return response;
	}
	
	public static void putEventGraph(ApiClient apiClient, String serviceId,
			ViewInput input, VolumeType volumeType, GraphRequest request, int pointsWanted,
			int baselineWindow, int activeWindow, Response<GraphResult> graphResult) {

		GraphCacheLoader cacheKey = new GraphCacheLoader(apiClient, request, serviceId, input, volumeType, 
			pointsWanted, baselineWindow, activeWindow, NO_GRAPH_SLICE, null, false);
		
		cacheKey.loadT1 = System.currentTimeMillis();
		
		queryCache.put(cacheKey, graphResult);
	}

	@SuppressWarnings("unchecked")
	public static Response<EventsSlimVolumeResult> getEventVolume(ApiClient apiClient, String serviceId, 
			ViewInput input, VolumeType volumeType, EventsSlimVolumeRequest request) {

		SlimVolumeCacheLoader cacheKey = new SlimVolumeCacheLoader(apiClient, request, serviceId, input, volumeType);
		Response<EventsSlimVolumeResult> response = (Response<EventsSlimVolumeResult>)getItem(cacheKey);
		return response;
	}

	private static Response<?> getEventList(ApiClient apiClient, String serviceId, 
			ViewInput input, EventsRequest request, VolumeType volumeType, boolean load) {
		
		EventsCacheLoader cacheKey = new EventsCacheLoader(apiClient, request, serviceId, input, volumeType);		
		Response<?> response;
		
		if (load) {
			response = getItem(cacheKey);
		} else {
			response = queryCache.getIfPresent(cacheKey);
		}
	
		return response;
	}
	
	public static Response<?> getEventList(ApiClient apiClient, String serviceId, 
			ViewInput input, EventsRequest request) {
		
		Response<?> response;
		
		for (VolumeType volumeType : VolumeType.values()) {
			
			response = getEventList(apiClient, serviceId, 
					input, request,volumeType, false);
			
			if ((response != null)  && (response.data != null)) {
				return response;
			}
		}
		
		response = getEventList(apiClient, serviceId, 
				input, request,null, true);
		
		return response;
	}
	
	public static Response<TransactionsGraphResult> getTransactionsGraph(ApiClient apiClient, String serviceId,
			BaseGraphInput input, int pointsWanted,
			TransactionsGraphRequest request) {
		return getTransactionsGraph(apiClient, serviceId, input, pointsWanted, 0, 0, request);
	}

	@SuppressWarnings("unchecked")
	public static Response<TransactionsGraphResult> getTransactionsGraph(ApiClient apiClient, String serviceId,
			BaseEventVolumeInput input, int pointsWanted, int baselineTimespan, int activeTimespan, 
			TransactionsGraphRequest request) {

		TransactionsGraphCacheLoader cacheKey = new TransactionsGraphCacheLoader(apiClient, request, serviceId, input,
				pointsWanted, baselineTimespan, activeTimespan);
		Response<TransactionsGraphResult> response = (Response<TransactionsGraphResult>) ApiCache.getItem(cacheKey);
		
		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<EventResult> getEvent(ApiClient apiClient, String serviceId,
			String Id) {

		EventRequest.Builder builder = EventRequest.newBuilder().setServiceId(serviceId).setEventId(Id);
		
		EventCacheLoader cacheKey = new EventCacheLoader(apiClient, builder.build(), serviceId, Id);
		Response<EventResult> response = (Response<EventResult>) ApiCache.getItem(cacheKey);
		
		return response;
	}

	public static RegressionWindow getRegressionWindow(ApiClient apiClient, RegressionInput input) {

		try {
			return regressionWindowCache.get(new RegresionWindowCacheLoader(apiClient, input));

		} catch (ExecutionException e) {
			throw new IllegalStateException(e);
		}
	}
	
	public static RegressionOutput getRegressionOutput(ApiClient apiClient, String serviceId, 
		EventFilterInput input, RegressionFunction function, boolean newOnly, boolean load) {
			
		RegressionCacheLoader key = new RegressionCacheLoader(apiClient, serviceId, input, function, newOnly);
		
		if (load) {
			
			try {
				RegressionOutput result = regressionOutputCache.get(key);
				
				if (result.empty) {
					regressionOutputCache.invalidate(key);
				}
				
				return result;
			} catch (ExecutionException e) {
				throw new IllegalStateException(e);
			}
		} else {
			return regressionOutputCache.getIfPresent(key);
		}
	}
	
	public static void setCacheStorage(KeyValueStorage cacheStorage) {
		ApiCache.cacheStorage = cacheStorage;
	}
	
	private static final Map<Class<?>, String> loaderClassNames;
	
	static {
		loaderClassNames = new HashMap<Class<?>, String>();
		
		loaderClassNames.put(EventsCacheLoader.class, "Events");
		loaderClassNames.put(GraphCacheLoader.class, "Graph");
		loaderClassNames.put(TransactionsGraphCacheLoader.class, "TxGraph");
		loaderClassNames.put(TransactionsCacheLoader.class, "Tx");
		loaderClassNames.put(EventCacheLoader.class, "Event");
		loaderClassNames.put(RegresionWindowCacheLoader.class, "Reg");
		loaderClassNames.put(ViewCacheLoader.class, "View");
		loaderClassNames.put(ServicesCacheLoader.class, "Services");
		loaderClassNames.put(ApplicationsCacheLoader.class, "Apps");
		loaderClassNames.put(DeploymentsCacheLoader.class, "Deps");
		loaderClassNames.put(ProcessesCacheLoader.class, "Process");
		loaderClassNames.put(SlimVolumeCacheLoader.class, "SlimEv");

	}
	
	private static String getLoaderClassName(Class<?> loaderClass) {
		
		String result = loaderClassNames.get(loaderClass);
		
		if (result == null) {
			result = loaderClass.getSimpleName();
		}
		
		return result;
		
	}
	
	private static KeyValueStorage cacheStorage = new FolderStorage(CACHE_FOLDER); 

	public static final LoadingCache<RegressionCacheLoader, RegressionOutput> regressionOutputCache = CacheBuilder
			.newBuilder().maximumSize(CACHE_SIZE).expireAfterWrite(CACHE_REFRESH_RETENTION, TimeUnit.SECONDS)
			.build(new CacheLoader<RegressionCacheLoader, RegressionOutput>() {
				
				@Override
				public RegressionOutput load(RegressionCacheLoader key) {
					return key.executeRegression();
				}
			});

	private static final LoadingCache<RegresionWindowCacheLoader, RegressionWindow> regressionWindowCache = CacheBuilder
			.newBuilder().maximumSize(CACHE_SIZE)
			.expireAfterWrite(CACHE_REFRESH_RETENTION, TimeUnit.SECONDS)
			.build(new CacheLoader<RegresionWindowCacheLoader, RegressionWindow>() {
				
				@Override
				public RegressionWindow load(RegresionWindowCacheLoader key) {
					
					RegressionWindow result = RegressionUtil.getActiveWindow(key.apiClient, key.input, 
							System.out);
					
					return result;
				}
			});
	
	public static final LoadingCache<BaseCacheLoader, Response<?>> queryCache = CacheBuilder.newBuilder()
			.maximumSize(CACHE_SIZE)
			.expireAfterWrite(CACHE_REFRESH_RETENTION, TimeUnit.SECONDS)
			.build(new CacheLoader<BaseCacheLoader, Response<?>>() {
				
				@Override
				public Response<?> load(BaseCacheLoader key) {
					
					Response<?> result = key.load();
					return result;
				}
			});
	
	public static final Queue<QueryLogItem> queryLogItems = Queues.synchronizedQueue(
			EvictingQueue.create(CACHE_SIZE));
		
}
