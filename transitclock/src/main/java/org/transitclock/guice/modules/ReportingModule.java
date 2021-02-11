package org.transitclock.guice.modules;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import org.transitclock.ipc.servers.reporting.service.OnTimePerformanceService;
import org.transitclock.ipc.servers.reporting.service.RunTimeService;
import org.transitclock.ipc.servers.reporting.service.SpeedMapService;

public class ReportingModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(RunTimeService.class).in(Scopes.SINGLETON);
        bind(OnTimePerformanceService.class).in(Scopes.SINGLETON);
        bind(SpeedMapService.class).in(Scopes.SINGLETON);
    }
}
