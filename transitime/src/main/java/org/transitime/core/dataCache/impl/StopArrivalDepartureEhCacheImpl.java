/**
 *
 */
package org.transitime.core.dataCache.impl;

import java.util.*;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.store.Policy;

import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.config.IntegerConfigValue;
import org.transitime.core.dataCache.*;
import org.transitime.core.dataCache.comparator.ArrivalDepartureComparator;
import org.transitime.core.dataCache.model.IStopArrivalDeparture;
import org.transitime.core.dataCache.model.StopArrivalDepartureCacheKey;
import org.transitime.core.dataCache.model.TripKey;
import org.transitime.db.structs.ArrivalDeparture;
import org.transitime.utils.Time;

/**
 * @author Sean Og Crudden This is a Cache to hold a sorted list of all arrival departure events
 *         for each stop in a cache. We can use this to look up all event for a
 *         stop for a day. The date used in the key should be the start of the
 *         day concerned.
 *
 *         TODO this could do with an interface, factory class, and alternative
 *         implementations, perhaps using Infinispan.
 */
public class StopArrivalDepartureEhCacheImpl implements StopArrivalDepartureCache {

	private static boolean debug = false;

	final private static String cacheByStop = "arrivalDeparturesByStop";

	private static final Logger logger = LoggerFactory.getLogger(StopArrivalDepartureEhCacheImpl.class);

	private Cache cache = null;

	/**
	 * Default is 4 as we need 3 days worth for Kalman Filter implementation
	 */
	private static final IntegerConfigValue tripDataCacheMaxAgeSec = new IntegerConfigValue(
			"transitime.tripdatacache.tripDataCacheMaxAgeSec", 4 * Time.SEC_PER_DAY,
			"How old an arrivaldeparture has to be before it is removed from the cache ");

	public StopArrivalDepartureEhCacheImpl() {
		CacheManager cm = CacheManager.getInstance();
		EvictionAgePolicy evictionPolicy = null;
		if (tripDataCacheMaxAgeSec != null) {
			evictionPolicy = new EvictionAgePolicy(tripDataCacheMaxAgeSec.getValue() * Time.MS_PER_SEC);
		} else {
			evictionPolicy = new EvictionAgePolicy(4 * Time.SEC_PER_DAY * Time.MS_PER_SEC);
		}

		if (cm.getCache(cacheByStop) == null) {
			cm.addCache(cacheByStop);
		}
		cache = cm.getCache(cacheByStop);
		cache.setMemoryStoreEvictionPolicy(evictionPolicy);
	}

    @Override
	@SuppressWarnings("unchecked")
	public List<StopArrivalDepartureCacheKey> getKeys() {
		return (List<StopArrivalDepartureCacheKey>) cache.getKeys();
	}

	public void logCache(Logger logger) {
		logger.debug("Cache content log.");
		@SuppressWarnings("unchecked")
		List<StopArrivalDepartureCacheKey> keys = cache.getKeys();

		for (StopArrivalDepartureCacheKey key : keys) {
			Element result = cache.get(key);
			if (result != null) {
				logger.debug("Key: " + key.toString());
				@SuppressWarnings("unchecked")

				List<ArrivalDeparture> ads = (List<ArrivalDeparture>) result.getObjectValue();

				for (ArrivalDeparture ad : ads) {
					logger.debug(ad.toString());
				}
			}
		}

	}

    @Override
    @SuppressWarnings("unchecked")
	synchronized public Set<IStopArrivalDeparture> getStopHistory(StopArrivalDepartureCacheKey key) {

		//logger.debug(cache.toString());
		Calendar date = Calendar.getInstance();
		date.setTime(key.getDate());

		date.set(Calendar.HOUR_OF_DAY, 0);
		date.set(Calendar.MINUTE, 0);
		date.set(Calendar.SECOND, 0);
		date.set(Calendar.MILLISECOND, 0);
		key.setDate(date.getTime());
		Element result = cache.get(key);
		logCache(logger);
		if (result != null) {
			return (Set<IStopArrivalDeparture>) result.getObjectValue();
		} else {
			return null;
		}
	}

    @Override
    @SuppressWarnings("unchecked")
	synchronized public StopArrivalDepartureCacheKey putArrivalDeparture(ArrivalDeparture arrivalDeparture) {

		logger.trace("Putting :" + arrivalDeparture.toString() + " in StopArrivalDepartureEhCacheImpl cache.");

		Calendar date = Calendar.getInstance();
		date.setTime(arrivalDeparture.getDate());

		date.set(Calendar.HOUR_OF_DAY, 0);
		date.set(Calendar.MINUTE, 0);
		date.set(Calendar.SECOND, 0);
		date.set(Calendar.MILLISECOND, 0);

		StopArrivalDepartureCacheKey key = new StopArrivalDepartureCacheKey(arrivalDeparture.getStop().getId(),
				date.getTime());

		Set<ArrivalDeparture> set = null;

		Element result = cache.get(key);

		if (result != null && result.getObjectValue() != null) {
			set = (Set<ArrivalDeparture>) result.getObjectValue();
			cache.remove(key);
		} else {
			set = new HashSet<ArrivalDeparture>();
		}

		set.add(arrivalDeparture);

		List<ArrivalDeparture> list = new ArrayList<>(set);

		Collections.sort(list, new ArrivalDepartureComparator());

		// This is java 1.8 list.sort(new ArrivalDepartureComparator());

		Element arrivalDepartures = new Element(key, Collections.synchronizedCollection(new LinkedHashSet(list)));

		cache.put(arrivalDepartures);

		return key;
	}

	private static <T> Iterable<T> emptyIfNull(Iterable<T> iterable) {
		return iterable == null ? Collections.<T> emptyList() : iterable;
	}

    @Override
	public void populateCacheFromDb(Session session, Date startDate, Date endDate) {
		Criteria criteria = session.createCriteria(ArrivalDeparture.class);

		@SuppressWarnings("unchecked")
		List<ArrivalDeparture> results = criteria.add(Restrictions.between("time", startDate, endDate)).list();

		for (ArrivalDeparture result : results) {
			putArrivalDeparture(result);
		}
	}

    @Override
    public boolean isCacheForDateProcessed(Date startDate, Date endDate){
        logger.debug("isCacheForDateProcessed not implemented");
        return false;
    }

    @Override
    public void saveCacheHistoryRecord(Date startDate, Date endDate) {
        logger.debug("saveCacheHistory not implemented");
        return;
    }

	/**
	 * This policy evicts arrival departures from the cache when they are X
	 * (age) number of milliseconds old
	 *
	 */
	private class EvictionAgePolicy implements Policy {
		private String name = "AGE";

		private long age = 0L;

		public EvictionAgePolicy(long age) {
			super();
			this.age = age;
		}

		@Override
		public boolean compare(Element arg0, Element arg1) {
			if (arg0.getObjectKey() instanceof StopArrivalDepartureCacheKey
					&& arg1.getObjectKey() instanceof StopArrivalDepartureCacheKey) {
				if (((StopArrivalDepartureCacheKey) arg0.getObjectKey()).getDate()
						.after(((StopArrivalDepartureCacheKey) arg1.getObjectKey()).getDate())) {
					return true;
				}

			}
			return false;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Element selectedBasedOnPolicy(Element[] arg0, Element arg1) {

			for (int i = 0; i < arg0.length; i++) {

				if (arg0[i].getObjectKey() instanceof TripKey) {
					StopArrivalDepartureCacheKey key = (StopArrivalDepartureCacheKey) arg0[i].getObjectKey();

					if (Calendar.getInstance().getTimeInMillis() - key.getDate().getTime() > age) {
						return arg0[i];
					}
				}
			}
			return null;
		}
	}
}
