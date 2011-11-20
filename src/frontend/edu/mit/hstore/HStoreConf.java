package edu.mit.hstore;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.log4j.Logger;
import org.voltdb.catalog.Site;

import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.utils.ArgumentsParser;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.StringUtil;
import edu.mit.hstore.interfaces.ConfigProperty;

public final class HStoreConf {
    private static final Logger LOG = Logger.getLogger(HStoreConf.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    static final Pattern REGEX_URL = Pattern.compile("(http[s]?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])");
    static final String REGEX_URL_REPLACE = "<a href=\"$1\">$1</a>";
    
    static final Pattern REGEX_CONFIG = Pattern.compile("\\$\\{([\\w]+)\\.([\\w\\_]+)\\}");
    static final String REGEX_CONFIG_REPLACE = "<a href=\"/documentation/configuration/properties-file/$1#$2\" class=\"property\">\\${$1.$2}</a>";
    
    
    // ============================================================================
    // GLOBAL
    // ============================================================================
    public final class GlobalConf extends Conf {
        
        @ConfigProperty(
            description="Temporary directory used to store various artifacts related to H-Store.",
            defaultString="/tmp/hstore",
            experimental=false
        )
        public String temp_dir = "/tmp/hstore";

        @ConfigProperty(
            description="Options used when logging into client/server hosts. " + 
                        "We assume that there will be no spaces in paths or options listed here.",
            defaultString="-x",
            experimental=false
        )
        public String sshoptions;

        @ConfigProperty(
            description="The default hostname used when generating cluster configurations.",
            defaultString="localhost",
            experimental=false
        )
        public String defaulthost = "localhost";
        
        @ConfigProperty(
            description="", // TODO
            defaultBoolean=true,
            experimental=true
        )
        public boolean ringbuffer_debug;
    }
    
    // ============================================================================
    // SITE
    // ============================================================================
    public final class SiteConf extends Conf {
    
        @ConfigProperty(
            description="HStoreSite log directory on the host that the BenchmarkController is invoked from.",
            defaultString="${global.temp_dir}/logs/sites",
            experimental=false
        )
        public String log_dir = HStoreConf.this.global.temp_dir + "/logs/sites";
        
        @ConfigProperty(
            description="The amount of memory to allocate for each site process (in MB)",
            defaultInt=2048,
            experimental=false
        )
        public int memory;

        @ConfigProperty(
            description="When enabled, the ExecutionSite threads will be pinned to the first n CPU cores (where " +
                        "n is the total number of partitions hosted by the local HStoreSite). All other threads " +
                        "(e.g., for network handling) will be pinned to the remaining CPU cores. If there are fewer " +
                        "CPU cores than partitions, then this option will be disabled. ",
            defaultBoolean=true,
            experimental=false
        )
        public boolean cpu_affinity;
        
        @ConfigProperty(
            description="When used in conjunction with ${site.cpu_affinity}, each ExecutionSite thread will be " +
                        "assigned to one and only CPU core. No other thread within the HStoreSite (including all " +
                        "other ExecutionSites) will be allowed to execute on that core. This configuration option is " +
                        "mostly used for debugging and is unlikely to provide any speed improvement because the " +
                        "operating system will automatically maintain CPU affinity.",
            defaultBoolean=false,
            experimental=true
        )
        public boolean cpu_affinity_one_partition_per_core;
        
        // ----------------------------------------------------------------------------
        // Execution Options
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="ExecutionEngine log level.",
            defaultInt=500,
            experimental=false
        )
        public int exec_ee_log_level;
        
        @ConfigProperty(
            description="Enable execution site profiling. This will keep track of how busy each ExecutionSite thread" +
                        "is during execution (i.e., the percentage of time that it spends executing a transaction versus " +
                        "waiting for work to be added to its queue).",
            defaultBoolean=false,
            experimental=false
        )
        public boolean exec_profiling;
        
        @ConfigProperty(
            description="If this feature is enabled, then each HStoreSite will attempt to speculatively execute " +
                        "single-partition transactions whenever it completes a work request for a multi-partition " +
                        "transaction running on a different node.",
            defaultBoolean=true,
            experimental=true
        )
        public boolean exec_speculative_execution;
        
        @ConfigProperty(
            description="If this feature is enabled, then those non-speculative single partition transactions that are " +
                        "deemed to never abort will be executed without undo logging. Requires Markov model estimations.",
            defaultBoolean=false,
            experimental=true
        )
        public boolean exec_no_undo_logging;

        @ConfigProperty(
            description="All transactions are executed without any undo logging. For testing purposes only.",
            defaultBoolean=false,
            experimental=true
        )
        public boolean exec_no_undo_logging_all;
        
        @ConfigProperty(
            description="If this parameter is set to true, then each HStoreSite will not send every transaction request " +
                        "through the Dtxn.Coordinator. Only multi-partition transactions will be sent to the " +
                        "Dtxn.Coordinator (in order to ensure global ordering). Setting this property to true provides a " +
                        "major throughput improvement.",
            defaultBoolean=true,
            experimental=false
        )
        public boolean exec_avoid_coordinator;
        
        @ConfigProperty(
            description="If this feature is true, then H-Store will use DB2-style transaction redirects. Each request will " +
                        "execute as a single-partition transaction at a random partition on the node that the request " +
                        "originally arrives on. When the transaction makes a query request that needs to touch data from " +
                        "a partition that is different than its base partition, then that transaction is immediately aborted, " +
                        "rolled back, and restarted on the partition that has the data that it was requesting. If the " +
                        "transaction requested more than partition when it was aborted, then it will be executed as a " +
                        "multi-partition transaction on the partition that was requested most often by queries " +
                        "(using random tie breakers). " +
                        "See http://ibm.co/fLR2cH for more information.",
            defaultBoolean=false,
            experimental=true
        )
        public boolean exec_db2_redirects;
        
        @ConfigProperty(
            description="Always execute transactions as single-partitioned (excluding sysprocs). If a transaction requests " +
                        "data on a partition that is different than where it is executing, then it is aborted, rolled back, " +
                        "and re-executed on the same partition as a multi-partition transaction that touches all partitions. " +
                        "Note that this is independent of how H-Store decides what partition to execute the transaction's Java " +
                        "control code on.",
            defaultBoolean=true,
            experimental=false
        )
        public boolean exec_force_singlepartitioned;
        
        @ConfigProperty(
            description="Always execute each transaction on a random partition on the node where the request originally " +
                        "arrived on. Note that this is independent of whether the transaction is selected to be " +
                        "single-partitioned or not. It is likely that you do not want to use this option.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean exec_force_localexecution;
    
        @ConfigProperty(
            description="Whether the VoltProcedure should crash the HStoreSite when a transaction is mispredicted. A " +
                        "mispredicted transaction is one that was originally identified as single-partitioned but then " +
                        "executed a query that attempted to access multiple partitions. This is primarily used for debugging " +
                        "the TransactionEstimator.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean exec_mispredict_crash;
        
        @ConfigProperty(
            description="If this enabled, HStoreSite will use a separate thread to process every outbound ClientResponse for " +
                        "all of the ExecutionSites. This may help with multi-partition transactions but will be the bottleneck " +
                        "for single-partition txn heavy workloads because the thread must acquire the lock on each partition's " +
                        "ExecutionEngine in order to commit or abort a transaction.",
            defaultBoolean=false,
            experimental=true
        )
        public boolean exec_postprocessing_thread;
        
        @ConfigProperty(
            description="The number of post-processing threads to use per HStoreSite. " +
                        "The ${site.exec_postprocessing_thread} parameter must be set to true.",
            defaultInt=1,
            experimental=true
        )
        public int exec_postprocessing_thread_count;
        

        @ConfigProperty(
            description="If this enabled with speculative execution, then HStoreSite only invoke the commit operation in the " +
                        "EE for the last transaction in the queued responses. This will cascade to all other queued responses " +
                        "successful transactions that were speculatively executed.",
            defaultBoolean=true,
            experimental=true
        )
        public boolean exec_queued_response_ee_bypass;
        
        // ----------------------------------------------------------------------------
        // Incoming Transaction Queue Options
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="Enable transaction profiling. This will measure the amount of time a transaction spends" +
            		    "in different parts of the system (e.g., waiting in the work queue, planning, executing).",
            defaultBoolean=false,
            experimental=false
        )
        public boolean txn_profiling;
        
        @ConfigProperty(
            description="", // TODO
            defaultInt=10,
            experimental=true
        )
        public int txn_incoming_delay;
        
        @ConfigProperty(
            description="", // TODO
            defaultInt=10,
            experimental=false
        )
        public int txn_restart_limit;
        
        @ConfigProperty(
            description="", // TODO
            defaultInt=10,
            experimental=false
        )
        public int txn_restart_limit_sysproc;
        
        // ----------------------------------------------------------------------------
        // Distributed Transaction Queue Options
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="Max size of queued transactions before an HStoreSite will stop accepting new requests " +
                        "from clients and will send back a ClientResponse with the throttle flag enabled.",
            defaultInt=1000,
            experimental=false
        )
        public int queue_incoming_max_per_partition;
        
        @ConfigProperty(
            description="If the HStoreSite is throttling incoming client requests, then that HStoreSite " +
                        "will not accept new requests until the number of queued transactions is less than " +
                        "this percentage. This includes all transactions that are waiting to be executed, " +
                        "executing, and those that have already executed and are waiting for their results " +
                        "to be sent back to the client. The incoming queue release is calculated as " +
                        "${site.txn_incoming_queue_max} * ${site.txn_incoming_queue_release_factor}",
            defaultDouble=0.25,
            experimental=false
        )
        public double queue_incoming_release_factor;
        
        @ConfigProperty(
            description="Whenever a transaction completes, the HStoreSite will check whether the work queue " +
                        "for that transaction's base partition is empty (i.e., the ExecutionSite is idle). " +
                        "If it is, then the HStoreSite will increase the ${site.txn_incoming_queue_max_per_partition} " +
                        "value by this amount. The release limit will also be recalculated using the new value " +
                        "for ${site.txn_incoming_queue_max_per_partition}. Note that this will only occur after " +
                        "the first non-data loading transaction has been issued from the clients.",
            defaultInt=100,
            experimental=false
        )
        public int queue_incoming_increase;
        
        @ConfigProperty(
            description="Max size of queued transactions before an HStoreSite will stop accepting new requests " +
                        "from clients and will send back a ClientResponse with the throttle flag enabled.",
            defaultInt=5000,
            experimental=false
        )
        public int queue_dtxn_max_per_partition;
        
        @ConfigProperty(
            description="If the HStoreSite is throttling incoming client requests, then that HStoreSite " +
                        "will not accept new requests until the number of queued transactions is less than " +
                        "this percentage. This includes all transactions that are waiting to be executed, " +
                        "executing, and those that have already executed and are waiting for their results " +
                        "to be sent back to the client. The incoming queue release is calculated as " +
                        "${site.txn_incoming_queue_max} * ${site.txn_incoming_queue_release_factor}",
            defaultDouble=0.50,
            experimental=false
        )
        public double queue_dtxn_release_factor;
        
        @ConfigProperty(
            description="Whenever a transaction completes, the HStoreSite will check whether the work queue " +
                        "for that transaction's base partition is empty (i.e., the ExecutionSite is idle). " +
                        "If it is, then the HStoreSite will increase the ${site.txn_incoming_queue_max_per_partition} " +
                        "value by this amount. The release limit will also be recalculated using the new value " +
                        "for ${site.txn_incoming_queue_max_per_partition}. Note that this will only occur after " +
                        "the first non-data loading transaction has been issued from the clients.",
            defaultInt=100,
            experimental=false
        )
        public int queue_dtxn_increase;
        
        // ----------------------------------------------------------------------------
        // Markov Transaction Estimator Options
        // ----------------------------------------------------------------------------

        @ConfigProperty(
            description="Recompute a Markov model's execution state probabilities every time a transaction " +
                        "is aborted due to a misprediction. The Markov model is queued in the ExecutionSiteHelper " +
                        "for processing rather than being executed directly within the ExecutionSite's thread.",
            defaultBoolean=true,
            experimental=false
        )
        public boolean markov_mispredict_recompute;

        
        @ConfigProperty(
            description="If this is set to true, TransactionEstimator will try to reuse MarkovPathEstimators" +
                        "for transactions running at the same partition.",
            defaultBoolean=true,
            experimental=true
        )
        public boolean markov_path_caching;
    
        @ConfigProperty(
            description="This threshold defines how accurate our cached MarkovPathEstimators have to be in order " +
                        "to keep using them. If (# of accurate txs / total txns) for a paritucular MarkovGraph " +
                        "goes below this threshold, then we will disable the caching",
            defaultDouble=1.0,
            experimental=true
        )
        public double markov_path_caching_threshold;
        
        @ConfigProperty(
            description="The minimum number of queries that must be in a batch for the TransactionEstimator " +
                        "to cache the path segment in the procedure's MarkovGraph. Provides a minor speed improvement " +
                        "for large batches with little variability in their execution paths.",
            defaultInt=3,
            experimental=true
        )
        public int markov_batch_caching_min;
        
        @ConfigProperty(
            description="Enable a hack for TPC-C where we inspect the arguments of the TPC-C neworder transaction and figure " +
                        "out what partitions it needs without having to use the TransactionEstimator. This will crash the " +
                        "system when used with other benchmarks. See edu.mit.hstore.util.NewOrderInspector",
            defaultBoolean=false,
            experimental=true
        )
        public boolean exec_neworder_cheat;

        // ----------------------------------------------------------------------------
        // BatchPlanner
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="Enable BatchPlanner profiling. This will keep of how long the BatchPlanner spends performing " +
                        "certain operations.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean planner_profiling;
        
        @ConfigProperty(
            description="Enable caching in the BatchPlanner. This will provide a significant speed improvement for " +
                        "single-partitioned queries because the BatchPlanner is able to quickly identify what partitions " +
                        "a batch of queries will access without having to process the request using the PartitionEstimator. " +
                        "This parameter is so great I should probably just hardcode to be always on, but maybe you don't " +
                        "believe me and want to see how slow things go with out this...",
            defaultBoolean=true,
            experimental=false
        )
        public boolean planner_caching;
        
        @ConfigProperty(
            description="The maximum number of execution rounds allowed per batch.",
            defaultInt=10,
            experimental=false
        )
        public int planner_max_round_size;
        
        @ConfigProperty(
            description="The maximum number of SQLStmts that can be queued per batch in a transaction.",
            defaultInt=128,
            experimental=false
        )
        public int planner_max_batch_size;
        
        // ----------------------------------------------------------------------------
        // HStoreCoordinator
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="If this enabled, HStoreCoordinator will use a separate thread to process incoming initialization " +
                        "requests from other HStoreSites. This is useful when ${client.txn_hints} is disabled.",
            defaultBoolean=true,
            experimental=false
        )
        public boolean coordinator_init_thread;
        
        @ConfigProperty(
            description="If this enabled, HStoreCoordinator will use a separate thread to process incoming finish " +
                        "requests for restarted transactions from other HStoreSites. ",
            defaultBoolean=true,
            experimental=false
        )
        public boolean coordinator_finish_thread;
        
        @ConfigProperty(
            description="If this enabled, HStoreCoordinator will use a separate thread to process incoming redirect " +
                        "requests from other HStoreSites. This is useful when ${client.txn_hints} is disabled.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean coordinator_redirect_thread;

        // ----------------------------------------------------------------------------
        // ExecutionSiteHelper
        // ----------------------------------------------------------------------------
    
        @ConfigProperty(
            description="How many ms to wait initially before starting the ExecutionSiteHelper after " +
                        "the HStoreSite has started.",
            defaultInt=2000,
            experimental=true
        )
        public int helper_initial_delay;
        
        @ConfigProperty(
            description="How often (in ms) should the ExecutionSiteHelper execute to clean up completed transactions.",
            defaultInt=100,
            experimental=false
        )
        public int helper_interval;
        
        @ConfigProperty(
            description="How many txns can the ExecutionSiteHelper clean-up per partition per round. Any value less " +
                        "than zero means that it will clean-up all txns it can per round",
            defaultInt=-1,
            experimental=true
        )
        public int helper_txn_per_round;
        
        @ConfigProperty(
            description="The amount of time after a transaction completes before its resources can be garbage collected " +
                        "and returned back to the various object pools in the HStoreSite.",
            defaultInt=100,
            experimental=true
        )
        public int helper_txn_expire;
        
        // ----------------------------------------------------------------------------
        // Output Tracing
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="When this property is set to true, all TransactionTrace records will include the stored procedure output result",
            defaultBoolean=false,
            experimental=false
        )
        public boolean trace_txn_output;

        @ConfigProperty(
            description="When this property is set to true, all QueryTrace records will include the query output result",
            defaultBoolean=false,
            experimental=false
        )
        public boolean trace_query_output;
        
        // ----------------------------------------------------------------------------
        // HSTORESITE STATUS UPDATES
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="Enable HStoreSite's StatusThread (# of milliseconds to print update). " +
                        "Set this to be -1 if you want to disable the status messages.",
            defaultInt=20000,
            experimental=false
        )
        public int status_interval;

        @ConfigProperty(
            description="Allow the HStoreSiteStatus thread to kill the cluster if it's local HStoreSite has " +
                        "not executed and completed any new transactions since the last time it took a status snapshot.", 
            defaultBoolean=true,
            experimental=false
        )
        public boolean status_kill_if_hung;
        
        @ConfigProperty(
            description="When this property is set to true, HStoreSite status will include transaction information",
            defaultBoolean=false,
            experimental=false
        )
        public boolean status_show_txn_info;

        @ConfigProperty(
            description="When this property is set to true, HStoreSite status will include information about each ExecutionSite, " +
                        "such as the number of transactions currently queued, blocked for execution, or waiting to have their results " +
                        "returned to the client.",
            defaultBoolean=true,
            experimental=false
        )
        public boolean status_show_executor_info;
        
        @ConfigProperty(
            description="When this property is set to true, HStoreSite status will include a snapshot of running threads",
            defaultBoolean=false,
            experimental=false
        )
        public boolean status_show_thread_info;
        
        // ----------------------------------------------------------------------------
        // OBJECT POOLS
        // ----------------------------------------------------------------------------
        
        @ConfigProperty(
            description="The scale factor to apply to the object pool configuration values.",
            defaultDouble=1.0,
            experimental=false
        )
        public double pool_scale_factor;
        
        @ConfigProperty(
            description="Whether to track the number of objects created, passivated, and destroyed from the pool. " + 
                        "Results are shown in HStoreSiteStatus updates.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean pool_profiling;
        
        @ConfigProperty(
            description="The max number of LocalTransactionStates to keep in the pool",
            defaultInt=5000,
            experimental=false
        )
        public int pool_localtxnstate_idle;
        
        @ConfigProperty(
            description="The max number of RemoteTransactionStates to keep in the pool",
            defaultInt=500,
            experimental=false
        )
        public int pool_remotetxnstate_idle;
        
        @ConfigProperty(
            description="The max number of MarkovPathEstimators to keep in the pool",
            defaultInt=1000,
            experimental=false
        )
        public int pool_pathestimators_idle;
        
        @ConfigProperty(
            description="The max number of TransactionEstimator.States to keep in the pool. " + 
                        "Should be the same as the number of MarkovPathEstimators.",
            defaultInt=1000,
            experimental=false
        )
        public int pool_estimatorstates_idle;
        
        @ConfigProperty(
            description="The max number of DependencyInfos to keep in the pool. " +
                        "Should be the same as the number of MarkovPathEstimators. ",
            defaultInt=500,
            experimental=false
        )
        public int pool_dependencyinfos_idle;
        
        @ConfigProperty(
            description="The max number of TransactionRedirectCallbacks to keep idle in the pool",
            defaultInt=10000,
            experimental=false
        )
        public int pool_txnredirect_idle;
        
        @ConfigProperty(
            description="The max number of TransactionRedirectResponseCallbacks to keep idle in the pool.",
            defaultInt=2500,
            experimental=false
        )
        public int pool_txnredirectresponses_idle;
        
        @ConfigProperty(
            description="The max number of TransactionInitCallbacks to keep idle in the pool.",
            defaultInt=2500,
            experimental=false
        )
        public int pool_txninit_idle;
        
        @ConfigProperty(
            description="The max number of TransactionInitWrapperCallbacks to keep idle in the pool.",
            defaultInt=2500,
            experimental=false
        )
        public int pool_txninitwrapper_idle;
        
        @ConfigProperty(
            description="The max number of TransactionPrepareCallbacks to keep idle in the pool.",
            defaultInt=2500,
            experimental=false
        )
        public int pool_txnprepare_idle;
    }

    // ============================================================================
    // COORDINATOR
    // ============================================================================
    public final class CoordinatorConf extends Conf {
        
        @ConfigProperty(
            description="Dtxn.Coordinator log directory  on the host that the BenchmarkController " +
                        "is invoked from.",
            defaultString="${global.temp_dir}/logs/coordinator",
            experimental=false
        )
        public String log_dir = HStoreConf.this.global.temp_dir + "/logs/coordinator";
        
        @ConfigProperty(
            description="The hostname to deploy the Dtxn.Coordinator on in the cluster.",
            defaultString="${global.defaulthost}",
            experimental=false
        )
        public String host = HStoreConf.this.global.defaulthost;
        
        @ConfigProperty(
            description="The port number that the Dtxn.Coordinator will listen on.",
            defaultInt=12348,
            experimental=false
        )
        public int port;

        @ConfigProperty(
            description="How long should we wait before starting the Dtxn.Coordinator (in milliseconds). " +
                        "You may need to increase this parameter for larger cluster sizes or if the " +
                        "HStoreSites have to load a lot of supplemental files (e.g., Markov models) before " +
                        "they attempt to connect to other sites.",
            defaultInt=0,
            experimental=false
        )
        public int delay;
    }
    
    // ============================================================================
    // CLIENT
    // ============================================================================
    public final class ClientConf extends Conf {
        
        @ConfigProperty(
            description="Benchmark client log directory on the host that the BenchmarkController " +
                        "is invoked from.",
            defaultString="${global.temp_dir}/logs/clients",
            experimental=false
        )
        public String log_dir = HStoreConf.this.global.temp_dir + "/logs/clients";
        
        @ConfigProperty(
            description="The amount of memory to allocate for each client process (in MB)",
            defaultInt=512,
            experimental=false
        )
        public int memory;

        @ConfigProperty(
            description="Default client host name",
            defaultString="${global.defaulthost}",
            experimental=false
        )
        public String host = HStoreConf.this.global.defaulthost;

        @ConfigProperty(
            description="The number of txns that client process submits (per ms). The underlying " +
                        "BenchmarkComponent will continue invoke the client driver's runOnce() method " +
                        "until it has submitted enough transactions to satisfy ${client.txnrate}. " +
                        "If ${client.blocking} is disabled, then the total transaction rate for a benchmark run is " +
                        "${client.txnrate} * ${client.processesperclient} * ${client.count}.",
            defaultInt=10000,
            experimental=false
        )
        public int txnrate;

        @ConfigProperty(
            description="Number of processes to use per client host.",
            defaultInt=1,
            experimental=false
        )
        public int processesperclient;

        @ConfigProperty(
            description="Number of clients hosts to use in the benchmark run.",
            defaultInt=1,
            experimental=false
        )
        public int count;

        @ConfigProperty(
            description="How long should the benchmark trial run (in milliseconds). Does not " +
                        "include ${client.warmup time}.",
            defaultInt=60000,
            experimental=false
        )
        public int duration;

        @ConfigProperty(
            description="How long should the system be allowed to warmup (in milliseconds). Any stats " +
                        "collected during this period are not counted in the final totals.",
            defaultInt=0,
            experimental=false
        )
        public int warmup;

        @ConfigProperty(
            description="How often (in milliseconds) should the BenchmarkController poll the individual " +
                        "client processes and get their intermediate results.",
            defaultInt=10000,
            experimental=false
        )
        public int interval;

        @ConfigProperty(
            description="Whether to use the BlockingClient. When this is true, then each client process will " +
                        "submit one transaction at a time and wait until the result is returned before " +
                        "submitting the next. The clients still follow the ${client.txnrate} parameter.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean blocking;
        
        @ConfigProperty(
            description="When the BlockingClient is enabled with ${client.blocking}, this defines the number " +
                        "of concurrent transactions that each client instance can submit to the H-Store cluster " +
                        "before it will block.",
            defaultInt=1,
            experimental=false
        )
        public int blocking_concurrent;
        
        @ConfigProperty(
            description="", // TODO
            defaultBoolean=false,
            experimental=false
        )
        public boolean blocking_loader;

        @ConfigProperty(
            description="The scaling factor determines how large to make the target benchmark's data set. " +
                        "A scalefactor less than one makes the data set larger, while greater than one " +
                        "makes it smaller. Implementation depends on benchmark specification.",
            defaultDouble=10.0,
            experimental=false
        )
        public double scalefactor;

        @ConfigProperty(
            description="How much skew to use when generating the benchmark data set. " +
                        "Default is zero (no skew). The amount skew gets larger for values " +
                        "greater than one. Implementation depends on benchmark specification. ",
            defaultDouble=0.0,
            experimental=true
        )
        public double skewfactor;

        @ConfigProperty(
            description="Used to define the amount of temporal skew in the benchmark data set. " +
                        "Implementation depends on benchmark specification.",
            defaultInt=0,
            experimental=true
        )
        public int temporalwindow;
        
        @ConfigProperty(
            description="Used to define the amount of temporal skew in the benchmark data set. " +
                        "Implementation depends on benchmark specification.",
            defaultInt=100,
            experimental=true
        )
        public int temporaltotal;
        
        @ConfigProperty(
            description="If ${client.tick_interval} is greater than one, then it determines how often " +
                        "(in ms) the BenchmarkComponent will execute tick(). " +
                        "A client driver implementation can reliably use this to perform some " +
                        "maintence operation or change data distributions. By default, tick() will be " +
                        "invoked at the interval defined by ${client.interval}.",
            defaultInt=-1,
            experimental=false
        )
        public int tick_interval;

        @ConfigProperty(
            description="The amount of time (in ms) that the client will back-off from sending requests " +
                        "to an HStoreSite when told that the site is throttled.",
            defaultInt=500,
            experimental=false
        )
        public int throttle_backoff;
        
        @ConfigProperty(
            description="If this enabled, then each DBMS will dump their entire database contents into " +
                        "CSV files after executing a benchmark run.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean dump_database = false;
        
        @ConfigProperty(
            description="If ${client.dump_database} is enabled, then each DBMS will dump their entire " +
                        "database contents into CSV files in the this directory after executing a benchmark run.",
            defaultString="${global.temp_dir}/dumps",
            experimental=false
        )
        public String dump_database_dir = HStoreConf.this.global.temp_dir + "/dumps";
        
        @ConfigProperty(
            description="If set to true, then the benchmark data loader will generate a WorkloadStatistics " +
                        "based on the data uploaded to the server. These stats will be written to the path " +
                        "specified by ${client.tablestats_output}.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean tablestats = false;
        
        @ConfigProperty(
            description="If ${client.tablestats} is enabled, then the loader will write out a database statistics " +
                        "file in the directory defined in this parameter.",
            defaultString="${global.temp_dir}/stats",
            experimental=false
        )
        public String tablestats_dir = HStoreConf.this.global.temp_dir + "/stats";
        
        @ConfigProperty(
            description="If this parameter is set to true, then each the client will calculate the base partition " +
                        "needed by each transaction request before it sends to the DBMS. This base partition is " +
                        "embedded in the StoreProcedureInvocation wrapper and is automatically sent to the HStoreSite " +
                        "that has that partition. Note that the HStoreSite will not use the PartitionEstimator to " +
                        "determine whether the client is correct, but the transaction can be restarted and re-executed " +
                        "if ${site.exec_db2_redirects} is enabled.",
            defaultBoolean=false,
            experimental=false
        )
        public boolean txn_hints = false;
        
        @ConfigProperty(
            description="If a node is executing multiple client processes, then the node may become overloaded if " +
                        "all the clients are started at the same time. This parameter defines the threshold for when " +
                        "the BenchmarkController will stagger the start time of clients. For example, if a node will execute " +
                        "ten clients and ${client.delay_threshold} is set to five, then the first five processes will start " +
                        "right away and the remaining five will wait until the first ones finish before starting themselves.", 
            defaultInt=8,
            experimental=false
        )
        public int delay_threshold = 8;
        
        @ConfigProperty(
            description="The URL of the CodeSpeed site that the H-Store BenchmarkController will post the transaction " +
                        "throughput rate after a benchmark invocation finishes. This parameter must be a well-formed HTTP URL. " +
                        "See the CodeSpeed documentation page for more info (https://github.com/tobami/codespeed).", 
            defaultNull=true,
            experimental=false
        )
        public String codespeed_url;
        
        @ConfigProperty(
            description="The name of the project to use when posting the benchmark result to CodeSpeed." +
                        "This parameter is required by CodeSpeed and cannot be empty. " +
                        "Note that the the ${client.codespeed_url} parameter must also be set.", 
            defaultString="H-Store",
            experimental=false
        )
        public String codespeed_project;
        
        @ConfigProperty(
            description="The name of the environment to use when posting the benchmark result to CodeSpeed. " +
                        "The value of this parameter must already exist in the CodeSpeed site. " +
                        "This parameter is required by CodeSpeed and cannot be empty. " +
                        "Note that the the ${client.codespeed_url} parameter must also be set.",
            defaultNull=true,
            experimental=false
        )
        public String codespeed_environment;

        @ConfigProperty(
            description="The name of the executable to use when posting the benchmark result to CodeSpeed. " +
                        "This parameter is required by CodeSpeed and cannot be empty. " +
                        "Note that the the ${client.codespeed_url} parameter must also be set.",
            defaultNull=true,
            experimental=false
        )
        public String codespeed_executable;
        
        @ConfigProperty(
            description="The Subversion revision number of the H-Store source code that is reported " +
                        "when posting the benchmark result used to CodeSpeed. " +
                        "This parameter is required by CodeSpeed and cannot be empty. " +
                        "Note that the the ${client.codespeed_url} parameter must also be set.", 
            defaultNull=true,
            experimental=false
        )
        public String codespeed_commitid;
        
        @ConfigProperty(
            description="The branch corresponding for this version of H-Store used when posting the benchmark " +
                        "result to CodeSpeed. This is parameter is optional.",
            defaultNull=true,
            experimental=false
        )
        public String codespeed_branch;
        
        @ConfigProperty(
            description="",
            defaultBoolean=false,
            experimental=false
        )
        public boolean output_clients;
        
        @ConfigProperty(
            description="",
            defaultBoolean=false,
            experimental=false
        )
        public boolean output_basepartitions;
        
        @ConfigProperty(
            description="",
            defaultBoolean=false,
            experimental=false
        )
        public boolean output_json;
    }
    
    /**
     * Base Configuration Class
     */
    private abstract class Conf {
        
        final Map<Field, ConfigProperty> properties;
        final String prefix;
        final Class<? extends Conf> confClass; 
        
        {
            this.confClass = this.getClass();
            this.prefix = confClass.getSimpleName().replace("Conf", "").toLowerCase();
            HStoreConf.this.confHandles.put(this.prefix, this);
            
            this.properties =  ClassUtil.getFieldAnnotations(confClass.getFields(), ConfigProperty.class);
            this.setDefaultValues();
        }
        
        private void setDefaultValues() {
            // Set the default values for the parameters based on their annotations
            for (Entry<Field, ConfigProperty> e : this.properties.entrySet()) {
                Field f = e.getKey();
                ConfigProperty cp = e.getValue();
                Object value = getDefaultValue(f, cp);
                
                try {
                    if (value != null) f.set(this, value);
                } catch (Exception ex) {
                    throw new RuntimeException(String.format("Failed to set default value '%s' for field '%s'", value, f.getName()), ex);
                }
//                System.err.println(String.format("%-20s = %s", f.getName(), value));
            } // FOR   
        }

        @SuppressWarnings("unchecked")
        public <T> T getValue(String name) {
            T val = null;
            try {
                Field f = this.confClass.getField(name);
                val = (T)f.get(this);
            } catch (Exception ex) {
                throw new RuntimeException("Invalid field '" + name + "' for " + this.confClass.getSimpleName(), ex);
            }
            return (val);
        }
        
        @Override
        public String toString() {
            return (this.toString(false));
        }
        
        public String toString(boolean experimental) {
            final Map<String, Object> m = new TreeMap<String, Object>();
            for (Entry<Field, ConfigProperty> e : this.properties.entrySet()) {
                ConfigProperty cp = e.getValue();
                if (experimental == false && cp.experimental()) continue;
                
                Field f = e.getKey();
                String key = f.getName().toUpperCase();
                try {
                    m.put(key, f.get(this));
                } catch (IllegalAccessException ex) {
                    m.put(key, ex.getMessage());
                }
            }
            return (StringUtil.formatMaps(m));
        }
    }
    
    // ----------------------------------------------------------------------------
    // INTERNAL 
    // ----------------------------------------------------------------------------
    
    private PropertiesConfiguration config = null;

    /**
     * Prefix -> Configuration
     */
    private final Map<String, Conf> confHandles = new ListOrderedMap<String, Conf>();
    
    /**
     * Easy Access Handles
     */
    public final GlobalConf global = new GlobalConf();
    public final SiteConf site = new SiteConf();
    public final CoordinatorConf coordinator = new CoordinatorConf();
    public final ClientConf client = new ClientConf();
    
    /**
     * Singleton Object
     */
    private static HStoreConf conf;
    
    private final Map<Conf, Set<String>> loaded_params = new HashMap<Conf, Set<String>>();
    
    // ----------------------------------------------------------------------------
    // METHODS
    // ----------------------------------------------------------------------------

    private HStoreConf() {
        // Empty configuration...
    }
    
    /**
     * Constructor
     */
    private HStoreConf(ArgumentsParser args, Site catalog_site) {
        if (args != null) {
            
            // Configuration File
            if (args.hasParam(ArgumentsParser.PARAM_CONF)) {
                this.loadFromFile(args.getFileParam(ArgumentsParser.PARAM_CONF));
            }
            
            // Ignore the Dtxn.Coordinator
            if (args.hasBooleanParam(ArgumentsParser.PARAM_SITE_IGNORE_DTXN)) {
                site.exec_avoid_coordinator = args.getBooleanParam(ArgumentsParser.PARAM_SITE_IGNORE_DTXN);
                if (site.exec_avoid_coordinator) LOG.info("Ignoring the Dtxn.Coordinator for all single-partition transactions");
            }
//            // Enable speculative execution
//            if (args.hasBooleanParam(ArgumentsParser.PARAM_NODE_ENABLE_SPECULATIVE_EXECUTION)) {
//                site.exec_speculative_execution = args.getBooleanParam(ArgumentsParser.PARAM_NODE_ENABLE_SPECULATIVE_EXECUTION);
//                if (site.exec_speculative_execution) LOG.info("Enabling speculative execution");
//            }
            // Enable DB2-style txn redirecting
            if (args.hasBooleanParam(ArgumentsParser.PARAM_SITE_ENABLE_DB2_REDIRECTS)) {
                site.exec_db2_redirects = args.getBooleanParam(ArgumentsParser.PARAM_SITE_ENABLE_DB2_REDIRECTS);
                if (site.exec_db2_redirects) LOG.info("Enabling DB2-style transaction redirects");
            }
            // Force all transactions to be single-partitioned
            if (args.hasBooleanParam(ArgumentsParser.PARAM_SITE_FORCE_SINGLEPARTITION)) {
                site.exec_force_singlepartitioned = args.getBooleanParam(ArgumentsParser.PARAM_SITE_FORCE_SINGLEPARTITION);
                if (site.exec_force_singlepartitioned) LOG.info("Forcing all transactions to execute as single-partitioned");
            }
            // Force all transactions to be executed at the first partition that the request arrives on
            if (args.hasBooleanParam(ArgumentsParser.PARAM_SITE_FORCE_LOCALEXECUTION)) {
                site.exec_force_localexecution = args.getBooleanParam(ArgumentsParser.PARAM_SITE_FORCE_LOCALEXECUTION);
                if (site.exec_force_localexecution) LOG.info("Forcing all transactions to execute at the partition they arrive on");
            }
            // Enable the "neworder" parameter hashing hack for the VLDB paper
//            if (args.hasBooleanParam(ArgumentsParser.PARAM_NODE_FORCE_NEWORDERINSPECT)) {
//                site.exec_neworder_cheat = args.getBooleanParam(ArgumentsParser.PARAM_NODE_FORCE_NEWORDERINSPECT);
//                if (site.exec_neworder_cheat) LOG.info("Enabling the inspection of incoming neworder parameters");
//            }
//            // Enable setting the done partitions for the "neworder" parameter hashing hack for the VLDB paper
//            if (args.hasBooleanParam(ArgumentsParser.PARAM_NODE_FORCE_NEWORDERINSPECT_DONE)) {
//                site.exec_neworder_cheat_done_partitions = args.getBooleanParam(ArgumentsParser.PARAM_NODE_FORCE_NEWORDERINSPECT_DONE);
//                if (site.exec_neworder_cheat_done_partitions) LOG.info("Enabling the setting of done partitions for neworder inspection");
//            }
            // Clean-up Interval
            if (args.hasIntParam(ArgumentsParser.PARAM_SITE_CLEANUP_INTERVAL)) {
                site.helper_interval = args.getIntParam(ArgumentsParser.PARAM_SITE_CLEANUP_INTERVAL);
                LOG.debug("Setting Cleanup Interval = " + site.helper_interval + "ms");
            }
            // Txn Expiration Time
            if (args.hasIntParam(ArgumentsParser.PARAM_SITE_CLEANUP_TXN_EXPIRE)) {
                site.helper_txn_expire = args.getIntParam(ArgumentsParser.PARAM_SITE_CLEANUP_TXN_EXPIRE);
                LOG.debug("Setting Cleanup Txn Expiration = " + site.helper_txn_expire + "ms");
            }
            // Profiling
            if (args.hasBooleanParam(ArgumentsParser.PARAM_SITE_ENABLE_PROFILING)) {
                site.txn_profiling = args.getBooleanParam(ArgumentsParser.PARAM_SITE_ENABLE_PROFILING);
                if (site.txn_profiling) LOG.info("Enabling procedure profiling");
            }
            // Mispredict Crash
            if (args.hasBooleanParam(ArgumentsParser.PARAM_SITE_MISPREDICT_CRASH)) {
                site.exec_mispredict_crash = args.getBooleanParam(ArgumentsParser.PARAM_SITE_MISPREDICT_CRASH);
                if (site.exec_mispredict_crash) LOG.info("Enabling crashing HStoreSite on mispredict");
            }
        }
        
        this.computeDerivedValues(catalog_site);
    }
    
    /**
     * 
     * @param catalog_site
     */
    protected void computeDerivedValues(Site catalog_site) {
        // Negate Parameters
        if (site.exec_neworder_cheat) {
            site.exec_force_singlepartitioned = false;
            site.exec_force_localexecution = false;
        }
    }
    
    private Object getDefaultValue(Field f, ConfigProperty cp) {
        Class<?> f_class = f.getType();
        Object value = null;
        
        if (cp.defaultNull() == false) {
            if (f_class.equals(int.class)) {
                value = cp.defaultInt();
            } else if (f_class.equals(long.class)) {
                value = cp.defaultLong();
            } else if (f_class.equals(double.class)) {
                value = cp.defaultDouble();
            } else if (f_class.equals(boolean.class)) {
                value = cp.defaultBoolean();
            } else if (f_class.equals(String.class)) {
                value = cp.defaultString();
            } else {
                LOG.warn(String.format("Unexpected default value type '%s' for property '%s'", f_class.getSimpleName(), f.getName()));
            }
        }
        return (value);
    }
    
    private Pattern makePattern() {
        return Pattern.compile(String.format("(%s)\\.(.*)", StringUtil.join("|", this.confHandles.keySet())));
    }
    
    /**
     * 
     */
    @SuppressWarnings("unchecked")
    public void loadFromFile(File path) {
        try {
            this.config = new PropertiesConfiguration(path);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load configuration file " + path);
        }

        Pattern p = this.makePattern();
        for (Object obj_k : CollectionUtil.iterable(this.config.getKeys())) {
            String k = obj_k.toString();
            Matcher m = p.matcher(k);
            boolean found = m.matches();
            if (m == null || found == false) {
                if (debug.get()) LOG.warn("Invalid key '" + k + "' from configuration file '" + path + "'");
                continue;
            }
            assert(m != null);
            
            Conf handle = confHandles.get(m.group(1));
            Class<?> confClass = handle.getClass();
            assert(confClass != null);
            Field f = null;
            String f_name = m.group(2);
            try {
                f = confClass.getField(f_name);
            } catch (Exception ex) {
                if (debug.get()) LOG.warn("Invalid configuration property '" + k + "'. Ignoring...");
                continue;
            }
            ConfigProperty cp = handle.properties.get(f);
            assert(cp != null) : "Missing ConfigProperty for " + f;
            Class<?> f_class = f.getType();
            Object defaultValue = (cp != null ? this.getDefaultValue(f, cp) : null);
            Object value = null;
            
            if (f_class.equals(int.class)) {
                value = this.config.getInt(k, (Integer)defaultValue);
            } else if (f_class.equals(long.class)) {
                value = this.config.getLong(k, (Long)defaultValue);
            } else if (f_class.equals(double.class)) {
                value = this.config.getDouble(k, (Double)defaultValue);
            } else if (f_class.equals(boolean.class)) {
                value = this.config.getBoolean(k, (Boolean)defaultValue);
            } else if (f_class.equals(String.class)) {
                value = this.config.getString(k, (String)defaultValue);
            } else {
                LOG.warn(String.format("Unexpected value type '%s' for property '%s'", f_class.getSimpleName(), f_name));
            }
            
            try {
                f.set(handle, value);
//                if (defaultValue != null && defaultValue.equals(value) == false) LOG.info(String.format("SET %s = %s", k, value));
                if (debug.get()) LOG.debug(String.format("SET %s = %s", k, value));
            } catch (Exception ex) {
                throw new RuntimeException("Failed to set value '" + value + "' for field '" + f_name + "'", ex);
            }
        } // FOR
    }
    
    public void loadFromArgs(String args[]) {
        final Pattern split_p = Pattern.compile("=");
        
        final Map<String, String> argsMap = new ListOrderedMap<String, String>();
        for (int i = 0, cnt = args.length; i < cnt; i++) {
            final String arg = args[i];
            final String[] parts = split_p.split(arg, 2);
            String k = parts[0].toLowerCase();
            String v = parts[1];
            if (k.startsWith("-")) k = k.substring(1);
            
            if (parts.length == 1) {
                continue;
            } else if (k.equalsIgnoreCase("tag")) {
                continue;
            } else if (v.startsWith("${") || k.startsWith("#")) {
                continue;
            } else {
                argsMap.put(k, v);
            }
        } // FOR
        this.loadFromArgs(argsMap);
    }
    
    public void loadFromArgs(Map<String, String> args) {
        Pattern p = this.makePattern();
        for (Entry<String, String> e : args.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            
            Matcher m = p.matcher(k);
            boolean found = m.matches();
            if (m == null || found == false) {
                if (debug.get()) LOG.warn("Invalid key '" + k + "'");
                continue;
            }
            assert(m != null);

            String confName = m.group(1);
            Conf confHandle = confHandles.get(confName);
            Class<?> confClass = confHandle.getClass();
            assert(confClass != null);
            Field f = null;
            String f_name = m.group(2).toLowerCase();
            try {
                f = confClass.getField(f_name);
            } catch (Exception ex) {
                if (debug.get()) LOG.warn("Invalid configuration property '" + k + "'. Ignoring...");
                continue;
            }
            ConfigProperty cp = confHandle.properties.get(f);
            assert(cp != null) : "Missing ConfigProperty for " + f;
            Class<?> f_class = f.getType();
            Object value = null;
            
            if (f_class.equals(int.class)) {
                value = Integer.parseInt(v);
            } else if (f_class.equals(long.class)) {
                value = Long.parseLong(v);
            } else if (f_class.equals(double.class)) {
                value = Double.parseDouble(v);
            } else if (f_class.equals(boolean.class)) {
                value = Boolean.parseBoolean(v);
            } else if (f_class.equals(String.class)) {
                value = v;
            } else {
                LOG.warn(String.format("Unexpected value type '%s' for property '%s'", f_class.getSimpleName(), f_name));
                continue;
            }
            try {
                f.set(confHandle, value);
                if (debug.get()) LOG.debug(String.format("PARAM SET %s = %s", k, value));
            } catch (Exception ex) {
                throw new RuntimeException("Failed to set value '" + value + "' for field '" + f_name + "'", ex);
            } finally {
                Set<String> s = this.loaded_params.get(confHandle);
                if (s == null) {
                    s = new HashSet<String>();
                    this.loaded_params.put(confHandle, s);
                }
                s.add(f_name);
            }
        } // FOR
    }
    
    public Map<String, String> getParametersLoadedFromArgs() {
        Map<String, String> m = new HashMap<String, String>();
        for (Conf confHandle : this.loaded_params.keySet()) {
            for (String f_name : this.loaded_params.get(confHandle)) {
                Object val = confHandle.getValue(f_name);
                if (val != null) m.put(f_name, val.toString());
            } // FOR
        } // FOR
        return (m);
    }
    
    public String makeIndexHTML(String group) {
        final Conf handle = this.confHandles.get(group);
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<h2>%s Parameters</h2>\n<ul>\n", StringUtil.title(group)));
        
        for (Field f : handle.properties.keySet()) {
            ConfigProperty cp = handle.properties.get(f);
            assert(cp != null);
            
            // INDEX
            String entry = REGEX_CONFIG_REPLACE.replace("$1", group).replace("$2", f.getName()).replace("\\$", "$");
            sb.append("  <li>  ").append(entry).append("\n");
        } // FOR
        sb.append("</ul>\n\n");
        
        return (sb.toString());
    }
    
    public String makeHTML(String group) {
        final Conf handle = this.confHandles.get(group);
        
        StringBuilder sb = new StringBuilder();
        sb.append("<ul class=\"property-list\">\n\n");
        
        // Parameters:
        //  (1) parameter
        //  (2) parameter
        //  (3) experimental
        //  (4) default value
        //  (5) description 
        final String template = "<a name=\"@@PROP@@\"></a>\n" +
                                "<li><tt class=\"property\">@@PROPFULL@@</tt>@@EXP@@\n" +
                                "<table>\n" +
                                "<tr><td class=\"prop-default\">Default:</td><td><tt>@@DEFAULT@@</tt></td>\n" +
                                "<tr><td class=\"prop-type\">Permitted Type:</td><td><tt>@@TYPE@@</tt></td>\n" +
                                "<tr><td colspan=\"2\">@@DESC@@</td></tr>\n" +
                                "</table></li>\n\n";
        
        
        Map<String, String> values = new HashMap<String, String>();
        for (Field f : handle.properties.keySet()) {
            ConfigProperty cp = handle.properties.get(f);

            // PROP
            values.put("PROP", f.getName());
            values.put("PROPFULL", String.format("%s.%s", group, f.getName()));
            
            // DEFAULT
            Object defaultValue = this.getDefaultValue(f, cp);
            if (defaultValue != null) {
                String value = defaultValue.toString();
                Matcher m = REGEX_CONFIG.matcher(value);
                if (m.find()) value = m.replaceAll(REGEX_CONFIG_REPLACE);
                defaultValue = value;
            }
            values.put("DEFAULT", (defaultValue != null ? defaultValue.toString() : "null"));
            
            // TYPE
            values.put("TYPE", f.getType().getSimpleName().toLowerCase());
            
            // EXPERIMENTAL
            if (cp.experimental()) {
                values.put("EXP", " <b class=\"experimental\">Experimental</b>");
            } else {
                values.put("EXP", "");   
            }
            
            // DESC
            String desc = cp.description();
            
            // Create links to remote sites
            Matcher m = REGEX_URL.matcher(desc);
            if (m.find()) desc = m.replaceAll(REGEX_URL_REPLACE);
            
            // Create links to other parameters
            m = REGEX_CONFIG.matcher(desc);
            if (m.find()) desc = m.replaceAll(REGEX_CONFIG_REPLACE);
            values.put("DESC", desc);
            
            // CREATE HTML FROM TEMPLATE
            String copy = template;
            for (String key : values.keySet()) {
                copy = copy.replace("@@" + key.toUpperCase() + "@@", values.get(key));
            }
            sb.append(copy);
        } // FOR
        sb.append("</ul>\n\n[previous] [next]\n");
        return (sb.toString());
    }
    
    public String makeBuildXML(String group) {
        final Conf handle = this.confHandles.get(group);
        
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- " + group.toUpperCase() + " -->\n");
        for (Field f : handle.properties.keySet()) {
            ConfigProperty cp = handle.properties.get(f);
            if (cp.experimental()) {
                
            }
            String propName = String.format("%s.%s", group, f.getName());
            sb.append(String.format("<arg value=\"%s=${%s}\" />\n", propName, propName));
        } // FOR
        sb.append("\n");
        return (sb.toString());
    }
    
    
    /**
     * 
     */
    public String makeDefaultConfig() {
        return (this.makeConfig(false));
    }
    
    public String makeConfig(boolean experimental) {
        StringBuilder sb = new StringBuilder();
        for (String group : this.confHandles.keySet()) {
            Conf handle = this.confHandles.get(group);

            sb.append("## ").append(StringUtil.repeat("-", 100)).append("\n")
              .append("## ").append(StringUtil.title(group)).append(" Parameters\n")
              .append("## ").append(StringUtil.repeat("-", 100)).append("\n\n");
            
            for (Field f : handle.properties.keySet()) {
                ConfigProperty cp = handle.properties.get(f);
                if (cp.experimental() && experimental == false) continue;
                
                String key = String.format("%s.%s", group, f.getName());
                Object val = null;
                try {
                    val = f.get(handle);
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to get " + key, ex);
                }
                if (val instanceof String) {
                    String str = (String)val;
                    if (str.startsWith(global.temp_dir)) {
                        val = str.replace(global.temp_dir, "${global.temp_dir}");
                    } else if (str.equals(global.defaulthost)) {
                        val = str.replace(global.defaulthost, "${global.defaulthost}");
                    }
                }
                
                sb.append(String.format("%-50s= %s\n", key, val));
            } // FOR
            sb.append("\n");
        } // FOR
        return (sb.toString());
    }
    
    @Override
    public String toString() {
        return (this.toString(false));
    }
        
    public String toString(boolean experimental) {
        Class<?> confClass = this.getClass();
        final Map<String, Object> m = new TreeMap<String, Object>();
        for (Field f : confClass.getFields()) {
            String key = f.getName().toUpperCase();
            Object obj = null;
            try {
                obj = f.get(this);
            } catch (IllegalAccessException ex) {
                m.put(key, ex.getMessage());
            }
            
            if (obj instanceof Conf) {
                m.put(key, ((Conf)obj).toString(experimental));
            }
        }
        return (StringUtil.formatMaps(m));
    }
    
    // ----------------------------------------------------------------------------
    // STATIC ACCESS METHODS
    // ----------------------------------------------------------------------------

    public synchronized static HStoreConf init(File f, String args[]) {
        if (conf != null) throw new RuntimeException("Trying to initialize HStoreConf more than once");
        conf = new HStoreConf();
        if (f != null && f.exists()) conf.loadFromFile(f);
        if (args != null) conf.loadFromArgs(args);
        return (conf);
    }
    
    public synchronized static HStoreConf init(File f) {
        return HStoreConf.init(f, null);
    }
    
    public static HStoreConf initArgumentsParser(ArgumentsParser args) {
        return HStoreConf.initArgumentsParser(args, null);
    }
    
    public synchronized static HStoreConf initArgumentsParser(ArgumentsParser args, Site catalog_site) {
        if (conf != null) throw new RuntimeException("Trying to initialize HStoreConf more than once");
        conf = new HStoreConf(args, catalog_site);
        return (conf);
    }
    
    public synchronized static HStoreConf singleton() {
        return singleton(false);
    }
    
    public synchronized static HStoreConf singleton(boolean init) {
        if (conf == null && init == true) return init(null);
        if (conf == null) throw new RuntimeException("Requesting HStoreConf before it is initialized");
        return (conf);
    }
    
    public synchronized static boolean isInitialized() {
        return (conf != null);
    }

}
