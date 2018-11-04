package com.takipi.integrations.grafana.util;

import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Objects;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.request.event.EventsRequest;
import com.takipi.api.client.request.event.EventsVolumeRequest;
import com.takipi.api.client.request.metrics.GraphRequest;
import com.takipi.api.client.request.transaction.TransactionsGraphRequest;
import com.takipi.api.client.request.transaction.TransactionsVolumeRequest;
import com.takipi.api.client.result.event.EventsResult;
import com.takipi.api.client.result.event.EventsVolumeResult;
import com.takipi.api.client.result.metrics.GraphResult;
import com.takipi.api.client.result.transaction.TransactionsGraphResult;
import com.takipi.api.client.result.transaction.TransactionsVolumeResult;
import com.takipi.api.client.util.validation.ValidationUtil.VolumeType;
import com.takipi.api.core.request.intf.ApiGetRequest;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.integrations.grafana.input.BaseGraphInput;
import com.takipi.integrations.grafana.input.ViewInput;

public class ApiCache {
	private static final int CACHE_SIZE = 1000;
	private static final int CACHE_RETENTION = 2;

	protected abstract static class CacheKey {

		protected ApiClient apiClient;
		protected ApiGetRequest<?> request;

		public CacheKey(ApiClient apiClient, ApiGetRequest<?> request) {
			this.apiClient = apiClient;
			this.request = request;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof CacheKey)) {
				return false;
			}

			CacheKey other = (CacheKey) obj;

			if (!Objects.equal(apiClient.getHostname(), other.apiClient.getHostname())) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return apiClient.getHostname().hashCode();
		}
	}

	protected abstract static class ServiceCacheKey extends CacheKey {

		protected String serviceId;

		public ServiceCacheKey(ApiClient apiClient, ApiGetRequest<?> request, String serviceId) {
			super(apiClient, request);
			
			this.serviceId = serviceId;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof ServiceCacheKey)) {
				return false;
			}

			ServiceCacheKey other = (ServiceCacheKey) obj;

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
	}

	protected abstract static class ViewCacheKey extends ServiceCacheKey {

		protected ViewInput input;

		public ViewCacheKey(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input) {
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

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof ViewCacheKey)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			ViewCacheKey other = (ViewCacheKey) obj;

			if (!Objects.equal(input.timeFilter, other.input.timeFilter)) {
				return false;
			}

			if (!Objects.equal(input.view, other.input.view)) {
				return false;
			}

			if (!compare(input.getDeployments(serviceId), other.input.getDeployments(serviceId))) {
				return false;
			}
			
			if (!compare(input.getServers(serviceId), other.input.getServers(serviceId))) {
				return false;
			}
			
			if (!compare(input.getApplications(serviceId), other.input.getApplications(serviceId))) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return super.hashCode() ^ serviceId.hashCode();
		}
		
		@Override
		public String toString() {
			return this.getClass().getSimpleName() + ": " + serviceId 
					+ " " + input.view + " D: " + input.deployments + " A: " + input.applications
					+ " S: " + input.servers;
		}
	}
	
	protected abstract static class VolumeKey extends ViewCacheKey {

		protected VolumeType volumeType;

		public VolumeKey(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				VolumeType volumeType) {
			super(apiClient, request, serviceId, input);
			
			this.volumeType = volumeType;
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof VolumeKey)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			VolumeKey other = (VolumeKey) obj;

			if (!Objects.equal(volumeType, other.volumeType)) {
				return false;
			}

			return true;
		}
		
		@Override
		public String toString() {
			return super.toString() + " " + volumeType;
		}
	}
	
	protected static class EventKey extends VolumeKey {

		public EventKey(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				VolumeType volumeType) {

			super(apiClient, request, serviceId, input, volumeType);
			this.volumeType = volumeType;
		}
		
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof EventKey)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			return true;
		}
	}

	protected static class GraphKey extends VolumeKey {

		protected int pointsWanted;

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof GraphKey)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			GraphKey other = (GraphKey) obj;

			if (pointsWanted != other.pointsWanted) {
				return false;
			}

			return true;
		}

		public GraphKey(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				VolumeType volumeType, int pointsWanted) {
			super(apiClient, request, serviceId, input, volumeType);
			
			this.pointsWanted = pointsWanted;
		}
	}
	
	protected static class TransactionsCacheKey extends ViewCacheKey {

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof TransactionsCacheKey)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}

			return true;
		}

		public TransactionsCacheKey(ApiClient apiClient, ApiGetRequest<?> request, 
			String serviceId, ViewInput input) {
			super(apiClient, request, serviceId, input);
		}
	}
	
	protected static class TransactionsGraphCacheKey extends ViewCacheKey {

		protected int pointsWanted;

		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof TransactionsGraphCacheKey)) {
				return false;
			}

			if (!super.equals(obj)) {
				return false;
			}
			
			TransactionsGraphCacheKey other = (TransactionsGraphCacheKey) obj;

			if (pointsWanted != other.pointsWanted) {
				return false;
			}

			return true;
		}

		public TransactionsGraphCacheKey(ApiClient apiClient, ApiGetRequest<?> request, String serviceId, ViewInput input,
				int pointsWanted) {
			super(apiClient, request, serviceId, input);
			
			this.pointsWanted = pointsWanted;
		}
	}

	private static Response<?> getItem(CacheKey key) {
		try {
			Response<?> result = cache.get(key);
			return result;
		} catch (ExecutionException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static Response<TransactionsVolumeResult> getTransactionsVolume(ApiClient apiClient, String serviceId, 
			String viewId, ViewInput input,TransactionsVolumeRequest request) {
		
		TransactionsCacheKey cacheKey = new TransactionsCacheKey(apiClient, request, serviceId, input);
		Response<TransactionsVolumeResult> response = (Response<TransactionsVolumeResult>)ApiCache.getItem(cacheKey);
		return response;
	}
	
	
	@SuppressWarnings("unchecked")
	public static Response<GraphResult> getEventGraph(ApiClient apiClient, String serviceId, 
			String viewId, ViewInput input, VolumeType volumeType, GraphRequest request, int pointsWanted) {
		
		GraphKey cacheKey = new GraphKey(apiClient, request, serviceId, input, volumeType, pointsWanted);
		Response<GraphResult> response = (Response<GraphResult>)ApiCache.getItem(cacheKey);
		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<EventsVolumeResult> getEventVolume(ApiClient apiClient, String serviceId, 
			String viewId, ViewInput input, VolumeType volumeType, EventsVolumeRequest request) {
		
		EventKey cacheKey = new EventKey(apiClient, request, serviceId, input, volumeType);
		Response<EventsVolumeResult> response = (Response<EventsVolumeResult>)ApiCache.getItem(cacheKey);
		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<EventsResult> getEventVolume(ApiClient apiClient, String serviceId, 
			String viewId, ViewInput input, EventsRequest  request) {
		
		EventKey cacheKey = new EventKey(apiClient, request, serviceId, input, null);
		Response<EventsResult> response = (Response<EventsResult>)ApiCache.getItem(cacheKey);
		return response;
	}
	
	@SuppressWarnings("unchecked")
	public static Response<TransactionsGraphResult> getTransactionsGraph(ApiClient apiClient, String serviceId, String viewId, 
			BaseGraphInput input, int pointsWanted, TransactionsGraphRequest request) {
		
		TransactionsGraphCacheKey cacheKey = new TransactionsGraphCacheKey(apiClient, request, serviceId, input, pointsWanted);
		Response<TransactionsGraphResult> response = (Response<TransactionsGraphResult>)ApiCache.getItem(cacheKey);
		return response;
	}

	private static final LoadingCache<CacheKey, Response<?>> cache = CacheBuilder.newBuilder()
			.maximumSize(CACHE_SIZE)
			.expireAfterWrite(CACHE_RETENTION, TimeUnit.MINUTES)
			.build(new CacheLoader<CacheKey, Response<?>>() {
				
				@Override
				public Response<?> load(CacheKey key) {
					Response<?> result = key.apiClient.get(key.request);
					return result;
				}
			});
}
