package edu.brown.markov;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.CatalogType;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Statement;

import edu.brown.catalog.CatalogKey;
import edu.brown.catalog.CatalogUtil;
import edu.brown.graphs.AbstractVertex;
import edu.brown.graphs.exceptions.InvalidGraphElementException;
import edu.brown.logging.LoggerUtil;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.MathUtil;
import edu.brown.utils.StringUtil;
import edu.brown.utils.TableUtil;

/**
 * 
 * @author svelagap
 * @author pavlo
 */
public class MarkovVertex extends AbstractVertex implements MarkovHitTrackable, Estimation {
    private static final Logger LOG = Logger.getLogger(MarkovVertex.class);
    private final static AtomicBoolean debug = new AtomicBoolean(LOG.isDebugEnabled());
    private final static AtomicBoolean trace = new AtomicBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
//    private static boolean d = debug.get();
    private static boolean t = trace.get();
    
    // ----------------------------------------------------------------------------
    // INTERNAL DATA ENUMS
    // ----------------------------------------------------------------------------
    
    public enum Members {
        COUNTER,
        TYPE,
        PARTITIONS,
        PAST_PARTITIONS,
        TOTALHITS,
        INSTANCEHITS,
        PROBABILITIES,
        EXECUTION_TIME,
    };

    public enum Type {
        QUERY,
        START,
        COMMIT,
        ABORT
    };

    public enum Probability {
        SINGLE_SITED    (true,  0.0f),
        ABORT           (true,  0.0f),
        READ_ONLY       (false, 0.0f),
        WRITE           (false, 0.0f),
        DONE            (false, 1.0f);
        
        final boolean single_value;
        final float default_value;
        Probability(boolean single_value, float default_value) {
            this.single_value = single_value;
            this.default_value = default_value;
        }
        
        protected static final Map<Integer, Probability> idx_lookup = new HashMap<Integer, Probability>();
        protected static final Map<String, Probability> name_lookup = new HashMap<String, Probability>();
        static {
            for (Probability vt : EnumSet.allOf(Probability.class)) {
                Probability.idx_lookup.put(vt.ordinal(), vt);
                Probability.name_lookup.put(vt.name().toLowerCase().intern(), vt);
            }
        }
        
        public static Probability get(Integer idx) {
            assert(idx >= 0);
            return (idx_lookup.get(idx));
        }

        public static Probability get(String name) {
            return (name_lookup.get(name.toLowerCase().intern()));
        }
    };

    // ----------------------------------------------------------------------------
    // GLOBAL CONFIGURATION
    // ----------------------------------------------------------------------------

    /**
     * This is the partition id that is used for probabilities that are not partition specific
     * For example, the ABORT probability is global to all partitions, so we only need to store one
     * value for it
     */
    private static final int DEFAULT_PARTITION_ID = 0;
    
    /**
     * The Query Instance Index is the counter for the number of times this particular Statement
     * was executed in the transaction 
     */
    public int counter;
    
    /**
     * The number of times this Vertex has been traversed
     */
    public int totalhits = 0;
    
    /**
     * The type of this vertex: Abort/Stop/Query/Start
     */
    public Type type;
    
    /**
     * The partitions this query touches
     */
    public Set<Integer> partitions = new HashSet<Integer>();
    
    /**
     * The partitions that the txn has touched in the past
     */
    public Set<Integer> past_partitions = new HashSet<Integer>();

    // ----------------------------------------------------------------------------
    // ADDITIONAL NON-STATE DATA MEMBERS
    // ----------------------------------------------------------------------------

    /**
     * The average execution time of this transaction
     */
    public long execution_time = 0l;

    /**
     * Mapping from Probability type to another map from partition id
     */
    public float probabilities[][];
    
    // ----------------------------------------------------------------------------
    // TRANSIENT DATA MEMBERS
    // ----------------------------------------------------------------------------

    /**
     * The number of times this vertex has been touched in the current on-line run
     */
    public transient int instancehits = 0;
    
    /**
     * The execution times of the transactions in the on-line run
     * A map of the xact_id to the time it took to get to this vertex
     */
    private transient final Map<Long, Long> instancetimes = new ConcurrentHashMap<Long, Long>();
    
    /**
     * The count, used to figure out the average execution time above
     */
    private transient long execution_time_count = 0l;
    
    
    private transient String to_string = null;
    

    // ----------------------------------------------------------------------------
    // CONSTRUCTORS
    // ----------------------------------------------------------------------------

    /**
     * Empty constructor
     */
    public MarkovVertex() {
        // This is needed for serialization
        super();
        this.probabilities = new float[MarkovVertex.Probability.values().length][];
    }
    
    /**
     * Constructor for barebones vertices such as STOP, ABORT, and START
     * @param catalog_stmt
     * @param type
     */
    public MarkovVertex(Statement catalog_stmt, MarkovVertex.Type type) {
        this(catalog_stmt, type, 0, null, null);
    }
    
    /**
     * Constructor used to make the unit tests
     * @param catalog_stmt
     * @param partitions
     * @param xact_count
     */
    public MarkovVertex(Statement catalog_stmt, Integer[] partitions, Integer[] past_partitions) {
        this(catalog_stmt, Type.QUERY, 0, Arrays.asList(partitions), Arrays.asList(past_partitions));
    }

    /**
     * Constructor used to create the actual graphs
     * @param catalog_stmt - query this vertex is associated with
     * @param type - QUERY, ABORT, START, or STOP
     * @param query_instance_index - the number of times we've executed this query before
     * @param partitions - the partitions this procedure touches
     * @param past_partitions - the partitions that we've touched in the past
     */
    public MarkovVertex(Statement catalog_stmt, MarkovVertex.Type type, int query_instance_index, Collection<Integer> partitions, Collection<Integer> past_partitions) {
        super(catalog_stmt);
        this.type = type;
        if (partitions != null) this.partitions.addAll(partitions);
        if (past_partitions != null) this.past_partitions.addAll(past_partitions);
        this.counter = query_instance_index;
        this.probabilities = new float[MarkovVertex.Probability.values().length][];
        this.init();
    }
    
    /**
     * Copy Constructor
     * Only really used for testing
     * @param v
     */
    public MarkovVertex(MarkovVertex v) {
        super(v.getCatalogItem());
        this.type = v.type;
        this.partitions.addAll(v.partitions);
        this.past_partitions.addAll(v.past_partitions);
        this.counter = v.counter;
        this.probabilities = new float[MarkovVertex.Probability.values().length][];
        this.init();
        
        for (int i = 0; i < v.probabilities.length; i++) {
            for (int j = 0; j < v.probabilities[i].length; j++) {
                this.probabilities[i][j] = v.probabilities[i][j];
            } // FOR
        } // FOR
    }
    
    /**
     * Initialize the probability tables
     */
    private void init() {
        int num_partitions = CatalogUtil.getNumberOfPartitions(this.catalog_item); 
        MarkovVertex.Probability ptypes[] = MarkovVertex.Probability.values();
        for (MarkovVertex.Probability ptype : ptypes) {
            int inner_len = (ptype.single_value ? 1 : num_partitions);
            this.probabilities[ptype.ordinal()] = new float[inner_len];
        } // FOR
        this.resetAllProbabilities();
    }
    
    public boolean isValid(MarkovGraph markov) {
        try {
            this.validate(markov);
        } catch (InvalidGraphElementException ex) {
            return (false);
        }
        return (true);
    }

    protected void validate(MarkovGraph markov) throws InvalidGraphElementException {
        Collection<MarkovEdge> outbound = markov.getOutEdges(this);
        Collection<MarkovEdge> inbound = markov.getInEdges(this);
        
        switch (this.type) {
            case START: {
                // START should not have any inbound edges
                if (inbound.size() > 0) {
                    throw new InvalidGraphElementException(markov, this, String.format("START state has %d inbound edges", outbound.size()));
                }
                break;
            }
            case COMMIT:
            case ABORT: {
                // COMMIT and ABORT should not have any outbound edges
                if (outbound.size() > 0) {
                    throw new InvalidGraphElementException(markov, this, String.format("%s state has %d outbound edges", this.type, outbound.size()));
                }
                break;
            }
            case QUERY: {
                // Every QUERY vertex should have an inbound edge
                if (inbound.isEmpty()) {
                    throw new InvalidGraphElementException(markov, this, "QUERY state does not have any inbound edges");
                }
                for (MarkovVertex.Probability ptype : MarkovVertex.Probability.values()) {
                    int idx = ptype.ordinal();
                    for (int i = 0, cnt = this.probabilities[idx].length; i < cnt; i++) {
                        float prob = this.probabilities[idx][i];
                        if (MathUtil.greaterThanEquals(prob, 0.0f, MarkovGraph.PROBABILITY_EPSILON) == false ||
                            MathUtil.lessThanEquals(prob, 1.0f, MarkovGraph.PROBABILITY_EPSILON) == false) {
                            throw new InvalidGraphElementException(markov, this, String.format("Invalid %s probability at partition #%d: %f", ptype.name(), i, prob));
                        }
                    } // FOR
                }
                
                // If this isn't the first time we are executing this query, then we should at least have
                // past partitions that we have touched
                if (this.counter > 0 && this.past_partitions.isEmpty()) {
                    throw new InvalidGraphElementException(markov, this, "No past partitions for at non-first query vertex");
                }
                
                // And we should always have some partitions that we're touching now
                if (this.partitions.isEmpty()) {
                    throw new InvalidGraphElementException(markov, this, "No current partitions");
                }
                break;
            }
            default:
                assert(false) : "Unexpected vertex type " + this.type;
        } // SWITCH
    }
    
    // ----------------------------------------------------------------------------
    // DATA MEMBER METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Return the vertex type
     * @return
     */
    public Type getType() {
        return this.type;
    }
    /**
     * Returns true if this Vertex is one of the ending states (commit/abort) 
     * @return
     */
    public boolean isEndingVertex() {
        return (this.type == Type.COMMIT || this.type == Type.ABORT);
    }
    public boolean isQueryVertex() {
        return (this.type == Type.QUERY);
    }
    public boolean isStartVertex() {
        return (this.type == Type.START);
    }
    public boolean isCommitVertex() {
        return (this.type == Type.COMMIT);
    }
    public boolean isAbortVertex() {
        return (this.type == Type.ABORT);
    }
    
    public int getQueryInstanceIndex() {
        return (int)this.counter;
    }
    

    /**
     * Return the set of partitions that the query represented by this vertex touches
     * @return
     */
    public Set<Integer> getPartitions() {
        return partitions;
    }
    
    /**
     * Return the set of partitions that the txn has touched in the past
     * @return
     */
    public Set<Integer> getPastPartitions() {
        return past_partitions;
    }

    public boolean equals(Object o) {
        if (o instanceof MarkovVertex) {
            MarkovVertex v = (MarkovVertex) o;
            if (this.to_string == null) this.toString();
            if (v.to_string == null) v.toString();
            return (this.to_string.equals(v.to_string));

//            return (this.type.equals(v.type) &&
//                    this.catalog_item.equals(v.catalog_item) &&
//                    this.partitions.equals(v.partitions) &&
//                    (MarkovGraph.USE_PAST_PARTITIONS == false || this.past_partitions.equals(v.past_partitions)) &&
//                    this.query_instance_index == v.query_instance_index);
        }
        return false;
    }
    
    /**
     * Perform equality check distinct from equals() method. Checks partitions, catalog_statement,
     * and the index of the query within the transaction
     * @param other_stmt
     * @param other_partitions
     * @param other_past
     * @param other_queryInstanceIndex
     * @return
     */
    public boolean isEqual(Statement other_stmt, Collection<Integer> other_partitions, Collection<Integer> other_past, int other_queryInstanceIndex) {
        return (this.isEqual(other_stmt, other_partitions, other_past, other_queryInstanceIndex, MarkovGraph.USE_PAST_PARTITIONS));
    }
    
    /**
     * Perform equality check distinct from equals() method. Checks partitions, catalog_statement,
     * and the index of the query within the transaction
     * This version of isEqual() allows you to pass in the use_past_partitions flag
     * 
     * @param other_stmt
     * @param other_partitions
     * @param other_past
     * @param other_queryInstanceIndex
     * @param use_past_partitions
     * @return
     */
    public boolean isEqual(Statement other_stmt, Collection<Integer> other_partitions, Collection<Integer> other_past, int other_queryInstanceIndex, boolean use_past_partitions) {
        return (other_queryInstanceIndex == this.counter && 
                other_stmt.equals(this.catalog_item) &&
                this.partitions.equals(other_partitions) &&
                (use_past_partitions ? this.past_partitions.equals(other_past) : true));
    }
    
    @Override
    public String toString() {
        if (this.to_string == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("{").append(this.catalog_item.getName());
            if (this.type == Type.QUERY) {
                sb.append(String.format(" Id:%d,Indx:%d,Prtns:%s,Past:%s",
                                        this.getElementId(), this.counter, this.partitions, this.past_partitions));
            }
            sb.append("}");
            this.to_string = sb.toString();
        }
        return (this.to_string); 
    }
    
    /**
     * Produce a table of all the partitions
     */
    public String debug() {
        Map<String, Object> m0 = new ListOrderedMap<String, Object>();
        Map<String, Object> m1 = new ListOrderedMap<String, Object>();
        Map<String, String> m2 = null;
        DecimalFormat formatter = new DecimalFormat("0.000");

        // Basic Information
        m0.put("Statement", this.catalog_item.getName() + (this.isQueryVertex() ? " #" + this.counter : ""));
        m0.put("ElementId", this.getElementId());
        m0.put("ExecutionTime", this.getExecutionTime());
        m0.put("Total Hits", this.totalhits);
        m0.put("Instance Hits", this.instancehits);
        
        if (this.isQueryVertex()) {
            m0.put("Partitions", this.partitions);
            m0.put("Previous", this.past_partitions);
            
            // Global Probabilities
            List<String> header = new ArrayList<String>();
            header.add(" ");
            MarkovVertex.Probability ptypes[] = MarkovVertex.Probability.values();
            for (MarkovVertex.Probability type : ptypes) {
                if (type.single_value) {
                    float val = this.probabilities[type.ordinal()][DEFAULT_PARTITION_ID];
                    String val_str = (val == MarkovUtil.NULL_MARKER ? "<NONE>" : formatter.format(val));
                    m1.put(type.name(), val_str);
                } else {
                    header.add(type.name());
                }
            } // FOR
    
            // Partition-based Probabilities
            int num_partitions = this.probabilities[MarkovVertex.Probability.WRITE.ordinal()].length;
            Object rows[][] = new String[num_partitions][header.size()];
            for (int row_idx = 0, cnt = num_partitions; row_idx < cnt; row_idx++) {
                int col_idx = 0;
                rows[row_idx][col_idx++] = String.format("[%02d]", row_idx);
                for (MarkovVertex.Probability type : ptypes) {
                    if (type.single_value) continue;
                    float val = this.probabilities[type.ordinal()][row_idx];
                    rows[row_idx][col_idx++] = (val == MarkovUtil.NULL_MARKER ? "<NONE>" : formatter.format(val));
                } // FOR
            } // FOR
            
            m2 = TableUtil.tableMap(header.toArray(new String[0]), rows);
        }

        return (StringUtil.formatMaps(m0, m1, m2));

    }

    // ----------------------------------------------------------------------------
    // PROBABILITY METHODS
    // ----------------------------------------------------------------------------

    /**
     * Returns true if the query for this vertex only touches one partition and that
     * partition is the as the base_partition (i.e., where the procedure's Java code is executing)
     * @return
     */
    public boolean isLocalPartitionOnly() {
        if (this.type == Type.QUERY) {
            // If there is more than one partition then we know immediately that this is busted
            if (this.partitions.size() != 1) return (false);
            
            // If there are not past partitions yet, then yes this is technically single-partitioned
            if (this.past_partitions.isEmpty()) return (true);
            
            // Lastly, we can check... 
            return (this.partitions.size() == 1 && this.past_partitions.size() == 1 && this.partitions.containsAll(this.past_partitions));
        }
        return (true);
    }
    
    public boolean isSingleSited() {
        return partitions.size() <= 1;
    }

    public boolean isReadOnly() {
        return ((Statement) this.catalog_item).getReadonly();
    }
    
    /**
     * Returns the probability of name if it is found in the mapping, otherwise returns d
     * @param name
     * @param default_value
     * @return
     */
    private float getSpecificProbability(MarkovVertex.Probability ptype, int partition) {
        float value = this.probabilities[ptype.ordinal()][partition];
        if (value == MarkovUtil.NULL_MARKER) value = ptype.default_value;
        
        // Handle funky rounding error that I think is due to casting
        // Note that we only round when we hand out the number. If we try to round it before we 
        // stick it in then it still comes out wrong sometimes...
        return (value);
//        return (MathUtil.roundToDecimals(value, PROBABILITY_PRECISION)); 
    }
    
    /**
     * Use for incrementing a certain probability
     * @param name
     * @param probability
     */
    private void addToProbability(MarkovVertex.Probability ptype, int partition, float probability) {
        // Important: If the probability is unset, then we need to set its initial value
        // to zero and to the default value
        float previous = this.probabilities[ptype.ordinal()][partition];
        if (previous == MarkovUtil.NULL_MARKER) previous = 0.0f;
        this.setProbability(ptype, partition, previous + probability);
    }

    /**
     * 
     * @param ptype
     * @param partition
     * @param probability
     */
    private void setProbability(MarkovVertex.Probability ptype, int partition, float probability) {
        if (trace.get()) LOG.trace("(" + ptype + ", " + partition + ") => " + probability);
        assert(MathUtil.greaterThanEquals(probability, 0.0f, MarkovGraph.PROBABILITY_EPSILON) &&
               MathUtil.lessThanEquals(probability, 1.0f, MarkovGraph.PROBABILITY_EPSILON)) :
            String.format("%s - Invalid %s probability at partition #%d: %f", this, ptype, partition, probability);
        this.probabilities[ptype.ordinal()][partition] = probability;
    }

    /**
     * Reset all probabilities. Keeps partitions in maps
     */
    public void resetAllProbabilities() {
        for (MarkovVertex.Probability ptype : MarkovVertex.Probability.values()) {
            int i = ptype.ordinal();
            if (this.probabilities[i] == null) continue;
            for (int j = 0; j < this.probabilities[i].length; j++) {
                this.probabilities[i][j] = MarkovUtil.NULL_MARKER;
            } // FOR
        } // FOR
    }
    
    // ----------------------------------------------------------------------------
    // SINGLE-SITED PROBABILITY
    // ----------------------------------------------------------------------------
    
    public void addSingleSitedProbability(float probability) {
        this.addToProbability(Probability.SINGLE_SITED, DEFAULT_PARTITION_ID, probability);
    }
    public void setSingleSitedProbability(float probability) {
        this.setProbability(Probability.SINGLE_SITED, DEFAULT_PARTITION_ID, probability);
    }
    public float getSingleSitedProbability() {
        return (this.getSpecificProbability(Probability.SINGLE_SITED, DEFAULT_PARTITION_ID));
    }
    public boolean isSingleSitedProbabilitySet() {
        return (this.getSpecificProbability(Probability.SINGLE_SITED, DEFAULT_PARTITION_ID) != MarkovUtil.NULL_MARKER);
    }

    // ----------------------------------------------------------------------------
    // READ-ONLY PROBABILITY
    // ----------------------------------------------------------------------------
    
    public void addReadOnlyProbability(int partition, float probability) {
        this.addToProbability(Probability.READ_ONLY, partition, probability);
    }
    public void setReadOnlyProbability(int partition, float probability) {
        this.setProbability(Probability.READ_ONLY, partition, probability);
    }
    public float getReadOnlyProbability(int partition) {
        return (this.getSpecificProbability(Probability.READ_ONLY, partition));
    }
    public boolean isReadOnlyProbabilitySet(int partition) {
        return (this.getSpecificProbability(Probability.READ_ONLY, partition) != MarkovUtil.NULL_MARKER);
    }
    
    // ----------------------------------------------------------------------------
    // WRITE PROBABILITY
    // ----------------------------------------------------------------------------
    
    public void addWriteProbability(int partition, float probability) {
        this.addToProbability(Probability.WRITE, partition, probability);
    }
    public void setWriteProbability(int partition, float probability) {
        this.setProbability(Probability.WRITE, partition, probability);
    }
    public float getWriteProbability(int partition) {
        return (this.getSpecificProbability(Probability.WRITE, partition));
    }
    public boolean isWriteProbabilitySet(int partition) {
        return (this.getSpecificProbability(Probability.WRITE, partition) != MarkovUtil.NULL_MARKER);
    }
    
    // ----------------------------------------------------------------------------
    // DONE PROBABILITY
    // ----------------------------------------------------------------------------
    
    public void addDoneProbability(int partition, float probability) {
        this.addToProbability(Probability.DONE, partition, probability);
    }
    public void setDoneProbability(int partition, float probability) {
        this.setProbability(Probability.DONE, partition, probability);
    }
    public float getDoneProbability(int partition) {
        return (this.getSpecificProbability(Probability.DONE, partition));
    }
    public boolean isDoneProbabilitySet(int partition) {
        return (this.getSpecificProbability(Probability.DONE, partition) != MarkovUtil.NULL_MARKER);
    }

    // ----------------------------------------------------------------------------
    // ABORT PROBABILITY
    // ----------------------------------------------------------------------------

    public void addAbortProbability(float probability) {
        this.addToProbability(Probability.ABORT, DEFAULT_PARTITION_ID, probability);
    }
    public void setAbortProbability(float probability) {
        this.setProbability(Probability.ABORT, DEFAULT_PARTITION_ID, probability);
    }
    public float getAbortProbability() {
        return (this.getSpecificProbability(Probability.ABORT, DEFAULT_PARTITION_ID));
    }
    public boolean isAbortProbabilitySet() {
        return (this.getSpecificProbability(Probability.DONE, DEFAULT_PARTITION_ID) != MarkovUtil.NULL_MARKER);
    }

    /**
     * The 'score' of a vertex is a measure of how often it has been hit in the current workload.
     * When this value differs enough from getOriginalScore() shoudlRecompute() will return true
     * @param xact_count
     * @return
     */
    public double getChangeScore(int xact_count) {
        return (double) (this.instancehits * 1.0 / xact_count);
    }

    /**
     * When the hits this vertex has received in this current run differs from the original hitrate enough,
     * it returns true.
     * @param xact_count
     * @param recomputeTolerance - the threshold at which we should recompute
     * @param workload_count - the transaction count of the workload used to make the graph this Vertex is a part of
     * @return
     */
    public boolean shouldRecompute(int xact_count, double recomputeTolerance, int workload_count) {
        double original_score = this.totalhits / (1.0f * workload_count);
        return (getChangeScore(xact_count) - original_score) / original_score >= recomputeTolerance;
    }

    public void setExecutiontime(long executiontime) {
        this.execution_time = executiontime;
    }

    /**
     * The amount of execution time remaining until a transaction at this vertex commits
     * @return
     */
    public long getExecutionTime() {
        return this.execution_time;
    }

    public void addExecutionTime(long l) {
        this.execution_time = (this.execution_time * execution_time_count + l) / ++execution_time_count;
    }
    public void addToExecutionTime(long l){
        this.execution_time += l;
    }

    /**
     * @return a map of xact_ids to times
     */
    public Map<Long,Long> getInstanceTimes() {
        return instancetimes;
    }
    
    /**
     * Add another instance time to the map. We use these times to figure out how long each
     * transaction takes to execute in the on-line model.
     */
    public MarkovVertex addInstanceTime(long xact_id, long time){
        this.instancetimes.put(xact_id, time);
        return (this);
    }
    
    /**
     * Since we cannot know when a transaction ends in the on-line updates world, we wait until we find out
     * that we need to recompute, then we normalize all the vertices in every graph with the end times, to
     * get how long the xact actually lasted
     * @param end_times
     */
    protected void normalizeInstanceTimes(Map<Long, Long> end_times, List<Long> to_remove) {
        for (Long txn_id : this.instancetimes.keySet()) {
            Long start = this.instancetimes.get(txn_id);
            Long stop = end_times.get(txn_id);
            if (start != null && stop != null) {
                long time = end_times.get(txn_id) - this.instancetimes.get(txn_id);
                this.addExecutionTime(time);
                to_remove.add(txn_id);
                if (t) LOG.trace(String.format("Updating %s with %d time units from txn #%d", this, time, txn_id)); 
            }
        } // FOR
        for (Long txn_id : to_remove) {
            end_times.remove(txn_id);
        } // FOR
    }

    
    // ----------------------------------------------------------------------------
    // ONLINE UPDATE METHODS
    // ----------------------------------------------------------------------------
   
    @Override
    public void applyInstanceHitsToTotalHits() {
        this.totalhits += this.instancehits;
        this.instancehits = 0;
    }
    @Override
    public void incrementTotalHits() {
        this.totalhits++;
    }
    @Override
    public long getTotalHits() {
        return this.totalhits;
    }
    @Override
    public void setInstanceHits(int instancehits) {
        this.instancehits = instancehits;
    }
    @Override
    public int getInstanceHits() {
        return this.instancehits;
    }
    @Override
    public int incrementInstanceHits() {
        return (++this.instancehits);
    }
    
    // ----------------------------------------------------------------------------
    // SERIALIZATION METHODS
    // ----------------------------------------------------------------------------

    /**
     * Implementation of the toJSONString method for an AbstractVertex
     */
    public void toJSONStringImpl(JSONStringer stringer) throws JSONException {
        super.toJSONStringImpl(stringer);
     
        Set<Members> members_set = CollectionUtil.getAllExcluding(Members.values(), Members.PROBABILITIES);
        Members members[] = new Members[members_set.size()];
        members_set.toArray(members);
        super.fieldsToJSONString(stringer, MarkovVertex.class, members);
        
        // Probabilities Map
        stringer.key(Members.PROBABILITIES.name()).object();
        for (Probability type : Probability.values()) {
            stringer.key(type.name()).array();
            int i = type.ordinal();
            for (int j = 0, cnt = this.probabilities[i].length; j < cnt; j++) {
                stringer.value(this.probabilities[i][j]);
            } // FOR
            stringer.endArray();
        } // FOR
        stringer.endObject();
    }

    @SuppressWarnings("unchecked")
    public void fromJSONObjectImpl(JSONObject object, Database catalog_db) throws JSONException {
        // Lists in Java suck. We want to let fieldsFromJSONObject handle all our fields except for TYPE
        Set<Members> members_set = CollectionUtil.getAllExcluding(Members.values(), Members.TYPE, Members.PROBABILITIES);
        Members members[] = new Members[members_set.size()];
        members_set.toArray(members);
        super.fieldsFromJSONObject(object, catalog_db, MarkovVertex.class, members);
        
        // HACK
        this.partitions = Collections.unmodifiableSet(this.partitions);
        this.past_partitions = Collections.unmodifiableSet(this.past_partitions);

        // Probabilities Map
        JSONObject json_probabilities = object.getJSONObject(Members.PROBABILITIES.name());
        Iterator<String> keys = json_probabilities.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Probability type = Probability.get(key);
            assert(type != null) : "Invalid name '" + key + "'";
            int i = type.ordinal();
            
            JSONArray json_arr = json_probabilities.getJSONArray(key);
            this.probabilities[i] = new float[json_arr.length()];
            for (int j = 0, cnt = this.probabilities[i].length; j < cnt; j++) {
                this.probabilities[i][j] = (float)json_arr.getDouble(j);
            } // FOR
        } // WHILE
        
        // I'm lazy...
        String s = object.getString(Members.TYPE.name());
        for (Type e : Type.values()) {
            if (e.name().startsWith(s)) {
                this.type = e;
                break;
            }
        } // FOR

        // We have to call this ourselves because we need to be able to handle
        // our special START/STOP/ABORT catalog objects
        super.fromJSONObjectImpl(object, catalog_db);
        this.fieldsFromJSONObject(object, catalog_db, AbstractVertex.class, AbstractVertex.Members.values());
        this.catalog_class = (Class<? extends CatalogType>) ClassUtil.getClass(object.getString(AbstractVertex.Members.CATALOG_CLASS.name()));
        assert (this.catalog_class != null);

        switch (this.type) {
            case START:
            case COMMIT:
            case ABORT:
                this.catalog_item = MarkovUtil.getSpecialStatement(catalog_db, this.type);
                break;
            default:
                this.catalog_item = CatalogKey.getFromKey(catalog_db, this.catalog_key, this.catalog_class);
            break;
        } // SWITCH
    }


}
