package org.transitclock.integration_tests.prediction;

import org.junit.Test;

/**
 * Bad predictions on route 921.
 */
public class PredictionAccuracy921IntegrationTest extends AbstractPredictionAccuracyIntegrationTest {
    private static final String GTFS = "classpath:gtfs/921";
    private static final String AVL = "classpath:avl/921.csv";
    private static final String PREDICTIONS_CSV = "classpath:pred/921.csv";
    private static final String HISTORY = "classpath:history/921.csv";
    private static final String OUTPUT_DIRECTORY = "/tmp/output/921";

    public PredictionAccuracy921IntegrationTest() {
        super ("921", OUTPUT_DIRECTORY, GTFS, AVL, PREDICTIONS_CSV, HISTORY);
    }

    @Test
    public void testPredictions() {
        super.testPredictions();
    }
}
