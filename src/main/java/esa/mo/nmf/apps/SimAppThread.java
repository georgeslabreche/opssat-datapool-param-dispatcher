package esa.mo.nmf.apps;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.tuple.Pair;

public class SimAppThread extends Thread {
    
    private static final Logger LOGGER = Logger.getLogger(SimAppThread.class.getName());
    
    private int id;
    private int interval;
    private int iterations;
    private List<String> paramsToGet;
    
    private SimAppDataGetHandler dataHandler;
    
    /**
     * Make default constructor inaccessible.
     */
    @SuppressWarnings("unused")
    private SimAppThread(){}
    
    /**
     * 
     * @param adapter the adapter
     * @param id the id of the app simulation
     * @param iterations the number of run loop iterations in the app simulation
     * @param interval the interval between each loop iteration in the app simulation (in milliseconds)
     * @param paramsToGet
     */
    SimAppThread(StressTesterMCAdapter adapter, int id, int iterations, int interval, List<String> paramsToGet) {
        this.id = id;
        this.iterations = iterations;
        this.interval = interval;
        this.paramsToGet = paramsToGet;
        
        // Data handler for this simulated app instance.
        double intervalsInSeconds = interval / 1000;
        this.dataHandler = new SimAppDataGetHandler(adapter, id, intervalsInSeconds, paramsToGet);
        
        this.dataHandler.toggleSupervisorParametersSubscription(true);
    }

    @Override
    public void run() {
        
        try {
            
            // Log start of simulation app.
            LOGGER.log(Level.INFO,  "[App #" + this.id + "] Starting App, Fetching: " + String.join(", ", this.paramsToGet));
            
            // Subscribe to parameter data provisioning service
            this.dataHandler.toggleSupervisorParametersSubscription(true);
            
            for (int i = 1; i <= this.iterations; i++) {
                
                LOGGER.log(Level.INFO,  "[App #" + this.id + "] Iteration: " + i + "/" + this.iterations);

                // Check if we are stopping the app B\before waiting to make the next request
                if(!ApplicationManager.getInstance().isSimKeepAlive()) {
                    break;
                }
                
                // Wait for a bit.
                Thread.sleep(this.interval);
                Pair<Long, Map<String, String>> parametersValues = this.dataHandler.getParametersValues();
                
                Long timestamp = parametersValues.getLeft();
                
                // Build CSV row string of fetched values
                StringBuffer line = new StringBuffer();
                line.append(String.format("%.3f", timestamp / 1000.0));
                line.append(",");
                
                for (String param : this.dataHandler.getParametersNames()) {
                    String value = parametersValues.getRight().get(param);
                    
                    // skip samples with at least one null value
                    if (SimAppDataGetHandler.PARAMS_DEFAULT_VALUE.equals(value)) {
                        return;
                        }
                  
                    line.append(value);
                    line.append(",");
                }
                
                // TODO: Write into CSV file.
                line.replace(line.length() - 1, line.length(), "\n");
                
                
                /**
                 * Check again if we are stopping the app before fetching data pool parameters.
                 * Do this in case the sleep was long.
                 * 
                 * Note that the best way to stop a thread with long waits/sleeps is to use Thread.interrupt, see here:
                 * https://docs.oracle.com/javase/1.5.0/docs/guide/misc/threadPrimitiveDeprecation.html
                 */
                if(!ApplicationManager.getInstance().isSimKeepAlive()) {
                    break;
                }
            }
            
            // Unsubscribe to parameter data provisioning service
            this.dataHandler.toggleSupervisorParametersSubscription(false);
                
        } catch(InterruptedException e) {
            LOGGER.log(Level.SEVERE, "[App #" + this.id + "]" + e.getMessage(), e);
        }
    }
}