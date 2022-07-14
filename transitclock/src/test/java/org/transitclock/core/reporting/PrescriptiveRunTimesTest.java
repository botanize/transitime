package org.transitclock.core.reporting;

import org.hibernate.Session;
import org.junit.*;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.transitclock.applications.Core;
import org.transitclock.core.ServiceType;
import org.transitclock.core.reporting.dao.*;
import org.transitclock.db.query.ArrivalDepartureQuery;
import org.transitclock.db.query.RunTimeForRouteQuery;
import org.transitclock.db.query.TripQuery;
import org.transitclock.db.structs.*;
import org.transitclock.ipc.data.IpcPrescriptiveRunTimesForPattern;
import org.transitclock.ipc.data.IpcPrescriptiveRunTimesForPatterns;
import org.transitclock.ipc.data.IpcPrescriptiveRunTimesForTimeBand;
import org.transitclock.reporting.dao.RunTimeRoutesDao;
import org.transitclock.reporting.service.RunTimeService;
import org.transitclock.reporting.service.runTime.prescriptive.PrescriptiveRunTimeService;
import org.transitclock.reporting.service.runTime.prescriptive.PrescriptiveTimebandService;
import org.transitclock.reporting.service.runTime.prescriptive.timebands.PrescriptiveRuntimeClusteringService;
import org.transitclock.utils.MapKey;
import org.transitclock.utils.Time;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;


public class PrescriptiveRunTimesTest extends AbstractPrescriptiveRunTimesTests{

    private MockedStatic<Core> singletonCore;
    private MockedStatic<Trip> staticTrip;
    private MockedStatic<TripPattern> staticTripPattern;
    private MockedStatic<Calendar> staticCalendar;
    private MockedStatic<CalendarDate> staticCalendarDate;
    private MockedStatic<ArrivalDeparture> arrivalDepartureMockedStatic;



    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static LocalDate beginDate = LocalDate.now();
    private static LocalDate endDate = LocalDate.now();
    private static ServiceType serviceType = ServiceType.WEEKDAY;
    private static boolean readOnly = true;

    @Before
    public void setup() throws IOException, ParseException {
        // Mock Core
        Stop stop = new Stop();
        Time time = new Time(TimeZone.getDefault().getDisplayName());
        Core mockCore = mock(Core.class, Mockito.RETURNS_DEEP_STUBS);
        when(mockCore.getTime()).thenReturn(time);
        when(mockCore.getDbConfig().getStop(anyString())).thenReturn(stop);

        // Setup static mocks
        singletonCore = mockStatic(Core.class);
        singletonCore.when(() -> Core.getInstance()).thenReturn(mockCore);

        staticTrip = mockStatic(Trip.class);
        staticTripPattern = mockStatic(TripPattern.class);
        staticCalendar = mockStatic(Calendar.class);
        staticCalendarDate = mockStatic(CalendarDate.class);
        arrivalDepartureMockedStatic = mockStatic(ArrivalDeparture.class);
    }

    @After
    public void teardown(){
        singletonCore.close();
        staticTrip.close();
        staticTripPattern.close();
        staticCalendar.close();
        staticCalendarDate.close();
        arrivalDepartureMockedStatic.close();
    }

    @Test
    public void prescriptiveRunTimeServiceTest_Service_171_Route_001() throws Exception {
        String servicePeriod = "171 - 2022-06-13 - 2022-09-11";
        String routeShortName = "001";
        Integer configRev = 12;
        Double minExpected = 64d;
        routeTest(servicePeriod, routeShortName, configRev, minExpected);
    }

    @Test
    public void prescriptiveRunTimeServiceTest_Service_171_Route_003() throws Exception {
        String servicePeriod = "171 - 2022-06-13 - 2022-09-11";
        String routeShortName = "003";
        Integer configRev = 12;
        Double minExpected = 58d;
        routeTest(servicePeriod, routeShortName, configRev, minExpected);
    }

    @Test
    public void prescriptiveRunTimeServiceTest_Service_171_Route_015() throws Exception {
        String servicePeriod = "171 - 2022-06-13 - 2022-09-11";
        String routeShortName = "015";
        Integer configRev = 12;
        Double minExpected = 55d;
        routeTest(servicePeriod, routeShortName, configRev, minExpected);
    }

    @Test
    public void prescriptiveRunTimeServiceTest_Service_171_Route_028() throws Exception {
        String servicePeriod = "171 - 2022-06-13 - 2022-09-11";
        String routeShortName = "028";
        Integer configRev = 12;
        Double minExpected = 59d;
        routeTest(servicePeriod, routeShortName, configRev, minExpected);
    }

    public void routeTest(String servicePeriod, String routeShortName, Integer configRev, Double minExpected) throws Exception {
        setServicePeriod(servicePeriod);

        // Data
        List<RunTimesForRoutes> runTimesForRoutes = getRunTimesForRoutes(routeShortName);
        List<ArrivalDeparture> arrivalDepartures = getArrivalDepartures(routeShortName);
        List<Calendar> calendars = getCalendars(routeShortName);
        List<CalendarDate> calendarDates = getCalendarDates(routeShortName);
        List<TripPattern> tripPatterns = getTripPatternsWithStopPaths(routeShortName);
        List<Trip> trips = getTripsWithScheduleTimes(routeShortName);

        // Mock RunTimeRoutesDao
        RunTimeRoutesDao runTimeRoutesDao = mock(RunTimeRoutesDao.class);
        when(runTimeRoutesDao.getRunTimesForRoutes(any(RunTimeForRouteQuery.class))).thenReturn(runTimesForRoutes);

        // Mock RunTimeService
        RunTimeService runTimeService = spy(RunTimeService.class);

        // Mock PrescriptiveTimebandService
        PrescriptiveTimebandService timebandService = spy(PrescriptiveTimebandService.class);

        // Mock PrescriptiveRunTimeService
        PrescriptiveRuntimeClusteringService prescriptiveClusteringService = spy(PrescriptiveRuntimeClusteringService.class);

        // Mocked Static Methods
        staticTrip.when(() -> Trip.getTripsFromDb(any(TripQuery.class))).thenReturn(trips);
        staticTripPattern.when(() -> TripPattern.getTripPatternsForRoute(anyString(), anyInt(), anyBoolean())).thenReturn(tripPatterns);
        staticCalendar.when(() -> Calendar.getCalendars(any(Session.class), anyInt())).thenReturn(calendars);
        staticCalendarDate.when(() -> CalendarDate.getCalendarDates(any(Session.class), anyInt())).thenReturn(calendarDates);
        arrivalDepartureMockedStatic.when(() -> ArrivalDeparture.getArrivalsDeparturesFromDb(any(ArrivalDepartureQuery.class))).thenReturn(arrivalDepartures);

        // Instantiate Prescriptive RunTimes Service
        PrescriptiveRunTimeService prescriptiveRunTimeService = new PrescriptiveRunTimeService();

        // RunTimeService Inject Mocked Classes
        runTimeService.setDao(runTimeRoutesDao);

        // TimebandService Inject Mocked Classes
        timebandService.setRunTimeService(runTimeService);
        timebandService.setClusteringService(prescriptiveClusteringService);

        // PrescriptiveRunTimeService Inject Mocked Classes
        prescriptiveRunTimeService.setRunTimeService(runTimeService);
        prescriptiveRunTimeService.setTimebandService(timebandService);

        IpcPrescriptiveRunTimesForPatterns timeBands = prescriptiveRunTimeService.
                getPrescriptiveRunTimeBands(beginDate, endDate, routeShortName, serviceType, configRev, readOnly);

        Assert.assertNotNull(timeBands);
        Assert.assertEquals(tripPatterns.size(), timeBands.getRunTimesForPatterns().size());

        List<IpcPrescriptiveRunTimesForPattern> patterns = timeBands.getRunTimesForPatterns();
        for(int i=0; i<patterns.size(); i++){
            TripPattern currentTripPattern = tripPatterns.get(i);
            IpcPrescriptiveRunTimesForPattern runTimesForPattern = patterns.get(i);

            Assert.assertEquals(routeShortName, runTimesForPattern.getRouteShortName());
            Assert.assertEquals(currentTripPattern.getScheduleAdhStopPaths().size(), runTimesForPattern.getTimePoints().size());

            List<IpcPrescriptiveRunTimesForTimeBand> timebands = runTimesForPattern.getRunTimesForTimeBands();

            System.out.println(System.lineSeparator() + currentTripPattern.getRouteShortName() + " - " + currentTripPattern.getHeadsign());
            System.out.println("----------------------------------------------------");

            for(int j=0; j < timebands.size(); j++){
                IpcPrescriptiveRunTimesForTimeBand timeband = timebands.get(j);
                if(j == 0){
                    Assert.assertEquals("00:00", timeband.getStartTime());
                }
                System.out.println(System.lineSeparator() + timeband.getStartTime() + " - " + timeband.getEndTime());
                System.out.println("Current OTP: " + getFormattedPercentOutput(timeband.getCurrentOtp() * 100));
                System.out.println("Expected OTP: " + getFormattedPercentOutput(timeband.getExpectedOtp() * 100));
                //Assert.assertTrue(timeband.getExpectedOtp() * 100 > 50);
            }

            patterns.get(i).getRunTimesForTimeBands();
        }

        double currentOnTimeCount = timeBands.getCurrentOnTime();
        double expectedOnTimeCount = timeBands.getExpectedOnTime();
        double totalCount = timeBands.getTotalRunTimes();
        Double currentOverallOtp = currentOnTimeCount/totalCount * 100;
        Double expectedOverallOtp = expectedOnTimeCount/totalCount * 100;


        System.out.println("CURRENT ONTIME COUNT : " + currentOnTimeCount);
        System.out.println("EXPECTED ONTIME COUNT : " + expectedOnTimeCount);
        System.out.println("TOTAL COUNT : " + totalCount);

        String currentFormattedOverallOtp = getFormattedPercentOutput(currentOverallOtp);
        String expectedFormattedOverallOtp = getFormattedPercentOutput(expectedOverallOtp);

        System.out.println("-----------------------------------------------------");
        System.out.println("Overall Current OTP: " + currentFormattedOverallOtp);
        System.out.println("Overall Expected OTP: " + expectedFormattedOverallOtp);

        if(minExpected != null){
            if(expectedOverallOtp < minExpected){
                fail(String.format("expected minimum value of %s, but instead got %s", minExpected, expectedOverallOtp));
            }
        }

    }

    private String getFormattedPercentOutput(double doubleVal){
        return df.format(doubleVal) + "%";
    }


}
