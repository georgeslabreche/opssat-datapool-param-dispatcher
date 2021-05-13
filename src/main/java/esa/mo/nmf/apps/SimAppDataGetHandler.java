package esa.mo.nmf.apps;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.ccsds.moims.mo.com.structures.InstanceBooleanPair;
import org.ccsds.moims.mo.com.structures.InstanceBooleanPairList;
import org.ccsds.moims.mo.mal.MALException;
import org.ccsds.moims.mo.mal.MALHelper;
import org.ccsds.moims.mo.mal.MALInteractionException;
import org.ccsds.moims.mo.mal.structures.Duration;
import org.ccsds.moims.mo.mal.structures.Identifier;
import org.ccsds.moims.mo.mal.structures.IdentifierList;
import org.ccsds.moims.mo.mal.structures.LongList;
import org.ccsds.moims.mo.mal.structures.UInteger;
import org.ccsds.moims.mo.mc.aggregation.consumer.AggregationStub;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationCategory;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationCreationRequest;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationCreationRequestList;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationDefinitionDetails;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationDefinitionDetailsList;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationParameterSet;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationParameterSetList;
import org.ccsds.moims.mo.mc.aggregation.structures.AggregationParameterValueList;
import org.ccsds.moims.mo.mc.parameter.consumer.ParameterStub;
import org.ccsds.moims.mo.mc.parameter.structures.ParameterValue;
import org.ccsds.moims.mo.mc.structures.ObjectInstancePairList;
import esa.mo.helpertools.helpers.HelperAttributes;
import esa.mo.mc.impl.provider.AggregationInstance;
import esa.mo.nmf.commonmoadapter.CompleteAggregationReceivedListener;


public class SimAppDataGetHandler {
    private static final Logger LOGGER = Logger.getLogger(SimAppDataGetHandler.class.getName());
    
    // Parameters default value before first acquisition
    public static final String PARAMS_DEFAULT_VALUE = "null";
    
    // M&C interface of the application.
    private final StressTesterMCAdapter adapter;

    // Lock for accessing our latest aggregation instance received from supervisor
    private final ReentrantLock aggInstanceLock = new ReentrantLock();

    // Latest aggregation instance received from supervisor
    private AggregationInstance aggInstance;

    // he listener for parameters values coming from supervisor
    private CompleteAggregationReceivedListener aggregationListener;
    
    // Aggregation Id string
    private String aggIdStr;
    
    // Aggregation Id of the aggregation we create
    private Long aggId;
    
    // Aggregation description
    private String aggDescription;

    // The Id of the simulated app
    private int appId;
    
    // Time interval between 2 sampling iterations in milliseconds for the simulated app
    private double paramSamplingInterval;
    
    // Supervisor (OBSW) parameters names
    private List<String> parametersNames;
    
    // Prefix for log messages so that we know what simulated app instance triggered the log message
    private String loggerMessagePrefix;
    
    /**
     * 
     * @param adapter the adapter
     * @param appId the app Id
     * @param paramSamplingInterval sampling interval in seconds
     * @param parametersNames names of datapool parameters to sample
     */
    public SimAppDataGetHandler(StressTesterMCAdapter adapter, int appId, double paramSamplingInterval, List<String> parametersNames) {
        this.adapter = adapter;
        this.appId = appId;
        this.paramSamplingInterval = paramSamplingInterval;
        this.parametersNames = parametersNames;
        
        this.aggIdStr = "Exp" + Constants.EXPERIMENT_ID + "_App" + this.appId + "_Agg";
        this.aggDescription = "Exp" + Constants.EXPERIMENT_ID  + " App #" + this.appId + " Aggregation";
        
        this.loggerMessagePrefix = "[App #" + this.appId + "] ";
    }

    /**
     * Toggle the subscription to the OBSW parameters values we need.
     * 
     * @param subscribe True if we want supervisor to push new parameters data, false to stop the push
     * @return null if it was successful. If not null, then the returned value holds the error number
     */
    public synchronized UInteger toggleSupervisorParametersSubscription(boolean subscribe) {    
        if (subscribe) {
            return enableSupervisorParameterSubscription();
        } else {
            return disableSupervisorParametersSubscription();
        }
    }

    /**
     * Subscribes to the OBSW parameters values we need by creating and enabling an aggregation in the
     * aggregation service of the supervisor.
     * 
     * @return null if it was successful. If not null, then the returned value holds the error number
     */
    private UInteger enableSupervisorParameterSubscription() {
        ParameterStub paramStub =
                adapter.getSupervisorSMA().getMCServices().getParameterService().getParameterStub();

      // list parameters to fetch and get their IDs from the supervisor
      IdentifierList paramIdentifier = new IdentifierList();
      this.parametersNames.stream().forEach(name -> paramIdentifier.add(new Identifier(name)));
      ObjectInstancePairList objInstPairList;
      
      try {
          objInstPairList = paramStub.listDefinition(paramIdentifier);
      } catch (MALInteractionException | MALException e) {
          LOGGER.log(Level.SEVERE, this.loggerMessagePrefix + "Error listing parameters to fetch in the supervisor", e);
          return new UInteger(3);
      }
      
      // List of param ids
      LongList paramIds = new LongList();
      
      objInstPairList.stream()
          .forEach(objInstPair -> paramIds.add(objInstPair.getObjIdentityInstanceId()));

      // create (or update) and enable an aggregation for the parameters to fetch
      UInteger error = createOrUpdateAggForParams(paramIds);
      if (error != null) {
          return error;
      }

      // create and register the aggregation listener
      this.aggregationListener = new CompleteAggregationReceivedListener() {
          @Override
          public void onDataReceived(AggregationInstance aggregationInstance) {
              if (aggregationInstance == null) {
                  LOGGER.log(Level.WARNING, "Received null aggregation instance");
                  return;
              }
          
              aggInstanceLock.lock();
              aggInstance = aggregationInstance;
              aggInstanceLock.unlock();
          }
      };

      adapter.getSupervisorSMA().addDataReceivedListener(this.aggregationListener);
      LOGGER.log(Level.INFO, this.loggerMessagePrefix + "Started fetching parameters from supervisor");

      return null;
    }

    /**
     * Creates (or updates) and enables an aggregation in the supervisor containing the parameters to
     * fetch.
     * 
     * @param paramIds Instance ids of the parameters
     * @return null if it was successful. If not null, then the returned value holds the error number
     */
    private UInteger createOrUpdateAggForParams(LongList paramIds) {
        AggregationStub aggStub =
                adapter.getSupervisorSMA().getMCServices().getAggregationService().getAggregationStub();
      
        Identifier aggIdentifier = new Identifier(this.aggIdStr);

        // list aggregations to test if we already have one defined
        IdentifierList identifierList = new IdentifierList();
        identifierList.add(aggIdentifier);
      
        try {
            ObjectInstancePairList objInstPairList = aggStub.listDefinition(identifierList);
            this.aggId = objInstPairList.get(0).getObjIdentityInstanceId();
        } catch (MALInteractionException e) {
            // only log if error is unexpected
            if (!MALHelper.UNKNOWN_ERROR_NUMBER.equals(e.getStandardError().getErrorNumber())) {
                LOGGER.log(Level.SEVERE, this.loggerMessagePrefix + "Error listing aggregations in the supervisor", e);
                return new UInteger(4);
            }
        } catch (MALException e) {
            LOGGER.log(Level.SEVERE, this.loggerMessagePrefix + "Error listing aggregations in the supervisor", e);
            return new UInteger(5);
        }

        // prepare aggregation details containing the parameters to fetch
        AggregationParameterSet paramSet =
                new AggregationParameterSet(null, paramIds, new Duration(0), null);
      
        AggregationParameterSetList paramSetList = new AggregationParameterSetList();
        paramSetList.add(paramSet);
      
        AggregationDefinitionDetails aggDetails = new AggregationDefinitionDetails(
                this.aggDescription, AggregationCategory.GENERAL.getOrdinalUOctet(),
                new Duration(this.paramSamplingInterval), true, false, false, new Duration(0), true, paramSetList);

        // update existing definition
        if (this.aggId != null) {
            LongList aggIdList = new LongList();
            aggIdList.add(this.aggId);
            AggregationDefinitionDetailsList aggDetailsList = new AggregationDefinitionDetailsList();
            aggDetailsList.add(aggDetails);
        
            try {
                aggStub.updateDefinition(aggIdList, aggDetailsList);
            } catch (MALInteractionException | MALException e) {
                LOGGER.log(Level.SEVERE,
                        this.loggerMessagePrefix + "Error updating aggregation with parameters to fetch in the supervisor", e);
                return new UInteger(6);
            }
        }
      
        // create new definition
        else {
            AggregationCreationRequest aggCreationRequest =
                    new AggregationCreationRequest(aggIdentifier, aggDetails);
        
            AggregationCreationRequestList aggCreationRequestList = new AggregationCreationRequestList();
            aggCreationRequestList.add(aggCreationRequest);
        
            try {
                ObjectInstancePairList aggObjInstPairList = aggStub.addAggregation(aggCreationRequestList);
          
                // check the aggregation was created successfully
                if (aggObjInstPairList.size() > 0 && aggObjInstPairList.get(0).getObjIdentityInstanceId() != null) {
                    this.aggId = aggObjInstPairList.get(0).getObjIdentityInstanceId();
                }
                
                if (this.aggId == null) {
                    LOGGER.log(Level.SEVERE,
                            this.loggerMessagePrefix + "AddAggregation from supervisor didn't return an aggregation id");
                    return new UInteger(7);
                }
            } catch (MALInteractionException | MALException e) {
                LOGGER.log(Level.SEVERE,
                        this.loggerMessagePrefix + "Error creating aggregation with parameters to fetch in the supervisor", e);
                return new UInteger(8);
            }
        }

      return null;
    }

    /**
     * Stops the subscription to the OBSW parameters values by disabling the generation of the
     * aggregation we created in the aggregation service of the supervisor.
     * 
     * @return null if it was successful. If not null, then the returned value holds the error number
     */
    private UInteger disableSupervisorParametersSubscription() {
        AggregationStub aggStub = 
                adapter.getSupervisorSMA().getMCServices().getAggregationService().getAggregationStub();

        // disable generation of our aggregation
        InstanceBooleanPairList instBoolPairList = new InstanceBooleanPairList();
        instBoolPairList.add(new InstanceBooleanPair(this.aggId, false));
      
        try {
            aggStub.enableGeneration(false, instBoolPairList);
        } catch (MALInteractionException | MALException e) {
            LOGGER.log(Level.SEVERE,
                    this.loggerMessagePrefix + "Error disabling generation of aggregation with parameters to fetch in the supervisor",
                    e);
            return new UInteger(9);
        }

        LOGGER.log(Level.INFO, this.loggerMessagePrefix + "Stopped fetching parameters from supervisor");
        return null;
    }

    /**
     * Returns latest parameters values fetched from supervisor with their timestamp.
     *
     * @return The time stamped (milliseconds) map of parameters names and their values
     */
    public Pair<Long, Map<String, String>> getParametersValues() {
        Long timestamp;
        Map<String, String> parametersValues = new HashMap<>();

        aggInstanceLock.lock();
      
        // no aggregation received yet
        if (aggInstance == null) {
            for (String paramName : this.parametersNames) {
                parametersValues.put(paramName, PARAMS_DEFAULT_VALUE);
            }
            timestamp = getTimestamp();
        }
        // read latest received aggregation
        else {
            timestamp = aggInstance.getTimestamp().getValue();
            AggregationParameterValueList aggParamValueList =
            aggInstance.getAggregationValue().getParameterSetValues().get(0).getValues();
        
            for (int i = 0; i < this.parametersNames.size(); i++) {
                // FIXME: Out of bound exception here with index value.
                ParameterValue paramValue = aggParamValueList.get(i).getValue();
                String paramValueS = HelperAttributes.attribute2string(paramValue.getRawValue());
                String paramName = this.parametersNames.get(i);
                parametersValues.put(paramName, paramValueS);
            }
        }
      
        aggInstanceLock.unlock();
        return new ImmutablePair<>(timestamp, parametersValues);
    }
    
    public List<String> getParametersNames(){
        return this.parametersNames;
    }

    /**
     * Returns a time stamp for the time of the call.
     *
     * @return the time stamp in milliseconds
     */
    public static long getTimestamp() {
        return System.currentTimeMillis();
    }

}