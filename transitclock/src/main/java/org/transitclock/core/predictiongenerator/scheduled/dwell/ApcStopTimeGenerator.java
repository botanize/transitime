package org.transitclock.core.predictiongenerator.scheduled.dwell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitclock.applications.Core;
import org.transitclock.avl.ApcCache;
import org.transitclock.avl.ApcDataProcessor;
import org.transitclock.avl.ApcModule;
import org.transitclock.config.BooleanConfigValue;
import org.transitclock.config.DoorFactorConfigValue;
import org.transitclock.config.DoubleConfigValue;
import org.transitclock.config.DoubleListConfigValue;
import org.transitclock.config.IntegerConfigValue;
import org.transitclock.core.Algorithm;
import org.transitclock.core.Indices;
import org.transitclock.core.PredictionResult;
import org.transitclock.core.VehicleState;
import org.transitclock.core.dataCache.KalmanError;
import org.transitclock.core.dataCache.VehicleDataCache;
import org.transitclock.core.predictiongenerator.HistoricalPredictionLibrary;
import org.transitclock.core.predictiongenerator.kalman.KalmanPredictionResult;
import org.transitclock.core.predictiongenerator.scheduled.traveltime.kalman.KalmanPredictionGeneratorImpl;
import org.transitclock.db.structs.AvlReport;
import org.transitclock.db.structs.ScheduleTime;
import org.transitclock.db.structs.VehicleConfig;
import org.transitclock.utils.IntervalTimer;
import org.transitclock.utils.Time;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 *  Kalman Dwell time implementation based on
 *  https://scholarcommons.usf.edu/cgi/viewcontent.cgi?article=1342&context=jpt
 *
 *  From the above paper we define:
 *     prediction = (loopGain*dwellTime)+(gain*historicalDwellTime)
 *     real-time component:
 *     dwellTime = passengerArrivalRate * predictedHeadway * passengerBoardingTime
 *     passengerArrivalRate = yesterday's boardings / actual previous headway
 *     predictedHeadway = typical headway calculation
 *     passengerBoardingTime = heuristic based on vehicle/route characteristics
 *     historical component:
 *     historicalDwellTime = historicalPassengerArrivalRate * previousHeadway * passengerBoardingTIme
 */
public class ApcStopTimeGenerator extends KalmanPredictionGeneratorImpl {

  private static final IntegerConfigValue minKalmanDays = new IntegerConfigValue(
          "transitclock.prediction.data.kalman.apc.mindays", new Integer(3),
          "Min number of days trip data that needs to be available before Kalman prediction is used instead of default transitClock prediction.");

  private static final IntegerConfigValue maxKalmanDays = new IntegerConfigValue(
          "transitclock.prediction.data.kalman.apc.maxdays", new Integer(5),
          "Max number of historical days trips to include in Kalman prediction calculation.");

  private static final IntegerConfigValue maxKalmanDaysToSearch = new IntegerConfigValue(
          "transitclock.prediction.data.kalman.apc.maxdaystosearch", new Integer(21),
          "Max number of days to look back for data. This will also be effected by how old the data in the cache is.");

  private static final DoubleConfigValue initialErrorValue = new DoubleConfigValue(
          "transitclock.prediction.data.kalman.apc.initialerrorvalue", new Double(50),
          "Initial Kalman error value to use to start filter.");

  private static final DoubleConfigValue passengerBoardingConstant = new DoubleConfigValue(
          "transitclock.prediction.data.kalman.apc.boardingConstant",
          2.6,
          "base boarding constant");

  private static final IntegerConfigValue maxHeadwaySecs = new IntegerConfigValue(
          "transitclock.prediction.data.kalman.apc.maxHeadwaySeconds",
          7200,
          "max time in seconds buses can be spaced and still considered a headway value.");

  private static final BooleanConfigValue allowEmptyDwell = new BooleanConfigValue(
          "transitclock.prediction.data.kalman.apc.allowEmptyDwell",
          true,
          "if data doesn't support a dwell time, assume no dwell happened.  " +
                  "Alternatively fall back on base dewll algorithm");

  private static final DoorFactorConfigValue doorFactorMatrix = new DoorFactorConfigValue(
          "transitclock.prediction.data.kalman.apc.doorFactors",
          null,
          "Matrix of boarding types + door count to ratio of ons/offs.  " +
                  "Used to precisely tune boarding algorithm");

  private static final DoubleListConfigValue secondsPerBoarding = new DoubleListConfigValue(
          "transitclock.prediction.data.kalman.apc.secondsPerBoarding",
          DoubleListConfigValue.unencodeString("4.3;2.5;3.4"),
          "Second per Boarding per trip boarding type");

  private static final DoubleConfigValue defaultSecondsPerBoarding = new DoubleConfigValue(
          "transitclock.prediction.data.kalman.apc.defaultSecondsPerBoarding",
          2.5,
          "Default seconds per boarding of boarding type unknown");

  private static final Logger logger = LoggerFactory.getLogger(ApcStopTimeGenerator.class);

  @Override
  public PredictionResult getStopTimeForPath(Indices indices, AvlReport avlReport, VehicleState vehicleState) {
    if (indices.atBeginningOfTrip()) {
      logger.debug("returning base prediction for start of trip " + indices);
      return super.getStopTimeForPath(indices, avlReport, vehicleState);;
    }

    IntervalTimer apcTimer = new IntervalTimer();
    if (!hasApcData()) {
      logger.debug("exiting apc dwell time, no apc data");
      logMiss();
      return super.getStopTimeForPath(indices, avlReport, vehicleState);
    }

    Long cachedResult = ApcStopTimeCache.getInstance().get(indices, avlReport, vehicleState);
    if (cachedResult != null) {
      getMonitoring().rateMetric("PredictionDwellApcProcessingHit", true);
      return new PredictionResult(cachedResult, Algorithm.APC_DWELL);
    }
    getMonitoring().rateMetric("PredictionDwellApcProcessingHit", false);

    IntervalTimer parTimer = new IntervalTimer();
    Double passengerArrivalRateInSeconds = getPassengerArrivalRate(indices, vehicleState);
    if (passengerArrivalRateInSeconds == null) {
      // we didn't have enough information, fall back on default impl
      logger.debug("exiting apc dwell time, no passenger arrival rate");
      getMonitoring().rateMetric("PredictionDwellApcHit", false);
      logMiss();
      return getDefaultStopTime(indices, avlReport, vehicleState);
    } else {
      getMonitoring().rateMetric("PredictionDwellApcHit", true);
      getMonitoring().averageMetric("PredictionApcPARProcessingTime", parTimer.elapsedMsec());
    }

    IntervalTimer headwayTimer = new IntervalTimer();
    Long currentHeadwayInSeconds = getRealtimeHeadwayInSeconds(vehicleState, indices);
    if (currentHeadwayInSeconds == null) {
      logMiss();
      getMonitoring().rateMetric("PredictionDwellHeadwayHit", false);

      // for headway failure we need fall back on default algol
      return super.getStopTimeForPath(indices, avlReport, vehicleState);
    } else {
      getMonitoring().rateMetric("PredictionDwellHeadwayHit", true);
      getMonitoring().averageMetric("PredictionApcHeadwayProcessingTime", headwayTimer.elapsedMsec());
    }

    IntervalTimer boardingTimer = new IntervalTimer();
    double passengerBoardingTime = getPassengerBoardingTime(indices, vehicleState, passengerArrivalRateInSeconds, currentHeadwayInSeconds);
    long dwellTime = new Double(passengerArrivalRateInSeconds * currentHeadwayInSeconds * passengerBoardingTime).longValue();
    logger.debug("dwellTime={} = passengerArrivalRateInSeconds={} * currentHeadway={} * passengerBoardingTime={}",
            dwellTime, passengerArrivalRateInSeconds, currentHeadwayInSeconds, passengerBoardingTime);
    getMonitoring().averageMetric("PredictionApcBoardingProcessingTime", boardingTimer.elapsedMsec());

    IntervalTimer historyTimer = new IntervalTimer();
    List<Long> historicalDwellTimes = getHistoricalDwellTimes(indices, vehicleState, passengerBoardingTime);
    if (historicalDwellTimes == null || historicalDwellTimes.size() < 3) {
      logger.debug("exiting apc dwell time, no historical data");
      getMonitoring().rateMetric("PredictionDwellHistoryHit", false);
      logMiss();
      // either stop is new, or not used for boarding
      return getDefaultStopTime(indices, avlReport, vehicleState);
    } else {
      getMonitoring().rateMetric("PredictionDwellHistoryHit", true);
      getMonitoring().averageMetric("PredictionApcHistoryProcessingTime", historyTimer.elapsedMsec());
    }

    IntervalTimer predictTimer = new IntervalTimer();
    KalmanPredictionResult result = predict(dwellTime, historicalDwellTimes, getLastPredictionError(indices));
    getKalmanErrorCache().putDwellErrorValue(indices, result.getFilterError());
    logHit();
    getMonitoring().averageMetric("PredictionApcProcessingTime", apcTimer.elapsedMsec());
    getMonitoring().averageMetric("PredictionApcPredictProcessingTime", predictTimer.elapsedMsec());
    ApcStopTimeCache.getInstance().put(indices, avlReport, vehicleState, new Double(result.getResult()).longValue());
    return new PredictionResult(new Double(result.getResult()).longValue(), Algorithm.APC_DWELL);
  }

  private PredictionResult getDefaultStopTime(Indices indices, AvlReport avlReport, VehicleState vehicleState) {
    if (allowEmptyDwell.getValue()) {
      return new PredictionResult(0L, Algorithm.APC_DWELL);
    }
    return super.getStopTimeForPath(indices, avlReport, vehicleState);
  }

  private void logHit() {
    getMonitoring().rateMetric("PredictionDwellHit", true);
  }

  private void logMiss() {
    getMonitoring().rateMetric("PredictionDwellHit", false);
  }

  private double getLastPredictionError(Indices indices) {
    KalmanError result;
    try {
      result = getKalmanErrorCache().getDwellErrorValue(indices);
    } catch (Exception e) {
      result = null;
    }
    if (result == null) {
      return initialErrorValue.getValue();
    }
    return result.getError();
  }

  private boolean hasApcData() {
    return ApcDataProcessor.getInstance().isEnabled();
  }

  private Long getRealtimeHeadwayInSeconds(VehicleState vehicleState, Indices indices) {
    try {
      long stopArrivalTime = getScheduledArrivalTimeIncludingSchedAdh(indices, vehicleState);
      Long headway = HistoricalPredictionLibrary.getLastHeadway(
              indices.getStopPath().getStopId(),
              indices.getTrip().getRouteId(), new Date(stopArrivalTime));
      if (headway != null) {
        long headwaySec = headway / Time.MS_PER_SEC;
        if (headwaySec > maxHeadwaySecs.getValue()) {
          return null;
        }
        return headwaySec;
      }
    } catch (Exception e) {
      logger.error("travel time lookup threw exception {}", e, e);
    }
    return null;
  }

  private KalmanPredictionResult predict(long dwellTime, List<Long> historicalDwellTimes,
                       double lastPredictionError) {

    double average = historicalAverage(historicalDwellTimes);
    double variance = historicalVariance(historicalDwellTimes, average);
    double gain = gain(average, variance, lastPredictionError);
    double loopGain = 1 - gain;

    KalmanPredictionResult result = new KalmanPredictionResult(
            prediction(gain,
                    loopGain,
                    historicalDwellTimes,
                    dwellTime,
                    average),

            filterError(variance, gain));

    return result;
  }

  private double prediction(double gain, double loopGain, List<Long> historicalDwellTimes, long dwellTime, double average) {
    double averageHistoricalDuration = historicalAverage(historicalDwellTimes);
    double prediction = ((loopGain * dwellTime) + (gain * averageHistoricalDuration));
    logger.debug("(loopGain={} * dwellTime={}) + (gain={} * averageHistoricalDuration={}{}) = ({}) + ({}) = {}",
            loopGain, dwellTime, gain, averageHistoricalDuration, historicalDwellTimes,
            loopGain*dwellTime, gain*averageHistoricalDuration,
            prediction);
    return prediction;
  }

  private double filterError(double variance, double loopGain) {
    return variance*loopGain;
  }

  private double gain(double average, double variance, double lastPredictionError) {
    double gain = (lastPredictionError + variance) / (lastPredictionError + (2 * variance));
    logger.debug("(lastPredictionError={} + variance={}) / (lastPredictionError + (2 * variance) = {}/{} = {} ",
            lastPredictionError, variance,
            lastPredictionError + variance, lastPredictionError + 2*variance, gain);
    return gain;
  }

  private double historicalVariance(List<Long> historicalDwellTimes, double average) {
    double total = 0.0;
    for (long l : historicalDwellTimes) {
      double diff = l - average;
      double longDiffSquared = diff * diff;
      total = total + longDiffSquared;
    }
    return total/historicalDwellTimes.size();
  }

  private double historicalAverage(List<Long> historicalDwellTimes) {
    double total = 0.0;
    for (long l : historicalDwellTimes) {
      total = total + l;
    }
    return total / historicalDwellTimes.size();
  }

  private List<Long> getHistoricalDwellTimes(Indices indices, VehicleState vehicleState, double passengerBoardingTime) {
    ArrayList<Long> historicalDwells = new ArrayList<>();
    int daysBack = 0;
    long currentArrivalTime = getScheduledArrivalTimeIncludingSchedAdh(indices, vehicleState);
    String stopId = indices.getStopPath().getStopId();
    while (daysBack < maxKalmanDaysToSearch.getValue()) {
      daysBack++;
      currentArrivalTime = currentArrivalTime - Time.MS_PER_DAY;
      Long historicalDwellTime = ApcModule.getInstance()
              .getDwellTime(indices.getTrip().getRouteId(),
                      stopId,
                      new Date(currentArrivalTime));
      if (historicalDwellTime != null) {
        logger.debug("historicalDwellTime {} for stop {} ",
                historicalDwellTime, stopId);
        historicalDwells.add(historicalDwellTime);
      }
      if (historicalDwells.size() > maxKalmanDays.getValue()) {
        return historicalDwells;
      }

    }
    return historicalDwells;
  }


  /**
   * passengerArrivalRate = boardings per second
   * @param indices
   * @return
   */
  private Double getPassengerArrivalRate(Indices indices, VehicleState vehicleState) {
    String stopId = indices.getStopPath().getStopId();

    // add schedule deviation to scheduled arrival as a simple prediction
    Long arrivalTime = getScheduledArrivalTimeIncludingSchedAdh(indices, vehicleState);

    if (arrivalTime == null) return null;
    // we ask for arrival time of current time, under the covers it looks for yesterday
    Double arrivalRate = ApcModule.getInstance().getPassengerArrivalRate(indices.getTrip(), stopId, new Date(arrivalTime));

    if (arrivalRate != null)
      return arrivalRate;
    // if we don't have any data assume no boarding during this period

    Integer defaultValue = ApcCache.DEFAULT_PAR_VALUE.getValue();
    if (defaultValue == null)
      return null;
    return new Double(defaultValue);
  }

  private Long getScheduledArrivalTimeIncludingSchedAdh(Indices indices, VehicleState vehicleState) {
    ScheduleTime st = indices.getScheduleTime();
    if (st == null) return null;
    if (vehicleState == null) return null;

    Long arrivalTime = Core.getInstance().getTime()
            .getEpochTime(indices.getScheduleTime().getTime()
                    + vehicleState.getRealTimeSchedAdh().getTemporalDifference()/Time.MS_PER_SEC,
                    vehicleState.getAvlReport().getTime());

    logger.debug("arrivalTime={} compared to scheduleTime={} with schDev={}",
            new Date(arrivalTime),
            indices.getScheduleTime(),
            vehicleState.getRealTimeSchedAdh());
    return arrivalTime;
  }
  private double getPassengerBoardingTime(Indices indices, VehicleState vehicleState, Double passengerArrivalRateInSeconds, Long currentHeadwayInSeconds) {
    return passengerBoardingConstant.getValue() * Time.MS_PER_SEC
            + (passengerArrivalRateInSeconds * currentHeadwayInSeconds) /* ons */
            * getBoardingFactor(indices, vehicleState)
            * getSecondsPerBoarding(indices);

  }

  private double getSecondsPerBoarding(Indices indices) {
    Integer boardingType = indices.getTrip().getBoardingType();
    if (boardingType == null) return defaultSecondsPerBoarding.getValue();
    if (secondsPerBoarding.getValue() == null || boardingType > secondsPerBoarding.getValue().size()) {
      return defaultSecondsPerBoarding.getValue();
    }
    return secondsPerBoarding.getValue().get(boardingType);
  }

  private double getBoardingFactor(Indices indices, VehicleState vehicleState) {
    Integer boardingType = indices.getTrip().getBoardingType();
    VehicleConfig vehicleConfig = VehicleDataCache.getInstance().getVehicleConfigById(vehicleState.getVehicleId());
    if (vehicleConfig == null) {
      logger.error("vehicle {} has no configuration data", vehicleState.getVehicleId());
      return getBoardingFactor(1, 1);
    }
    Integer doorCount = vehicleConfig.getDoorCount();
    return getBoardingFactor(boardingType, doorCount);
  }

  private double getBoardingFactor(Integer boardingType, Integer doorCount) {
    if (boardingType == null || doorCount == null)
      return 1; // default ratio

    if (doorFactorMatrix.getValue() == null)
      return 1; // default ratio

    return doorFactorMatrix.getValueForIndex(boardingType, doorCount);
  }


}
