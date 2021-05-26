package org.transitclock.avl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitclock.config.BooleanConfigValue;
import org.transitclock.config.IntegerConfigValue;
import org.transitclock.db.structs.ArrivalDeparture;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.transitclock.avl.ApcCache.WINDOW_IN_MINUTES;
import static org.transitclock.utils.DateUtils.dateBinning;


/**
 * Match an APC observation to an AVL ArrivalDeparture.  As they come from different
 * systems account for some error in the matching.
 */
public class ApcMatcher {

  private static final Logger logger =
          LoggerFactory.getLogger(ApcMatcher.class);

  public static final IntegerConfigValue APC_MATCH_WINDOW_MINUTES
          = new IntegerConfigValue("transitclock.apc.matchWindowMinutes",
          5,/*minutes*/
          "delta between ArrivalDeparture timestamp and apc timestamp");
  public static final BooleanConfigValue APC_IS_ARRIVAL
          = new BooleanConfigValue("transitclock.apc.recordIsArrival",
          false,
          "if the ping observation is meant to be an arrival by default");

  private List<ArrivalDeparture> arrivals = null;
  private Map<String, List<ArrivalDeparture>> cache = null;
  private boolean debug = false;
  public ApcMatcher(List<ArrivalDeparture> arrivals) {
    this.arrivals = arrivals;
  }

  /**
   * Match an APC record to an arrival/departure
   * @param apcRecords
   * @return
   */
  public List<ApcMatch> match(List<ApcParsedRecord> apcRecords) {
    List<ApcMatch> matches = new ArrayList<>();
    if (cache == null) {
      createCache();
    }

    int count = 0;
    for (ApcParsedRecord apc : apcRecords) {
      List<ArrivalDeparture> results = new ArrayList<>();
      List<String> searchedKeys = new ArrayList<>();

      String hash = hash(apc);
      searchedKeys.add(hash);
      List<ArrivalDeparture> result = cache.get(hash);
      if (result != null)
        results.addAll(result);

      if (results.isEmpty() && debug) {
        List<String> actualKeys = new ArrayList<>();
        for (String key : cache.keySet()) {
          if (key.startsWith(apc.getVehicleId() + ".")) {
            actualKeys.add(key);
          }
        }
        logger.info("search keys {}\n" +
                " but actual keys were {}\n",
                searchedKeys, actualKeys);
      }
      if (!results.isEmpty()) {
        matches.add(new ApcMatch(apc, results));
      }
      count++;
      if (count % 1000 == 0) {
        logger.debug("searched {} of {}", count, apcRecords.size());
      }
    }

    return matches;
  }

  private void createCache() {
    int count = 0;
    cache = new HashMap<>();
    for (ArrivalDeparture ad : this.arrivals) {
      if (ad == null || ad.getVehicleId() == null) {
        logger.info("bad data with {}", ad);
        continue;
      }
      if (APC_IS_ARRIVAL.getValue() && !ad.isArrival()) {
        continue;
      }

      String hash = hash(ad);
      List<ArrivalDeparture> list = cache.get(hash);
      if (list == null) {
        list = new ArrayList<>();
        list.add(ad);
        cache.put(hash, list);
      } else {
        list.add(ad);
      }
      count++;
      if (count % 1000 == 0) {
        logger.info("caching {} of {}", count, arrivals.size());
      }
    }
  }

  private String hash(ApcParsedRecord apc) {
    return apc.getVehicleId()
            +  "."
            + new SimpleDateFormat("yyyyMMddHHmm").format(
                    dateBinning(new Date(apc.getArrivalEpoch()),
                            Calendar.MINUTE, WINDOW_IN_MINUTES.getValue()));

  }


  private String hash(ArrivalDeparture ad) {
    return ad.getVehicleId() + "."
            + new SimpleDateFormat("yyyyMMddHHmm").format(
                    dateBinning(new Date(ad.getTime()),
                            Calendar.MINUTE,
                            WINDOW_IN_MINUTES.getValue()));
  }


}
