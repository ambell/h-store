package org.voltdb;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.PlanFragment;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.types.QueryType;

import edu.brown.BaseTestCase;
import edu.brown.benchmark.tm1.procedures.GetAccessData;
import edu.brown.benchmark.tm1.procedures.GetNewDestination;
import edu.brown.benchmark.tm1.procedures.InsertCallForwarding;
import edu.brown.benchmark.tm1.procedures.UpdateLocation;
import edu.brown.catalog.CatalogUtil;
import edu.brown.hashing.DefaultHasher;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.ProjectType;
import edu.mit.hstore.HStoreConf;
import edu.mit.hstore.HStoreConstants;

public class TestBatchPlanner extends BaseTestCase {

    private static final Class<? extends VoltProcedure> SINGLESITE_PROCEDURE = GetAccessData.class;
    private static final String SINGLESITE_STATEMENT = "GetData";
    
    private static final Class<? extends VoltProcedure> MULTISITE_PROCEDURE = UpdateLocation.class;
    private static final String MULTISITE_STATEMENT = "update";
    
    private static final Object SINGLESITE_PROCEDURE_ARGS[] = {
        new Long(1), // S_ID
        new Long(1), // SF_TYPE
    };
    private static final Object MULTISITE_PROCEDURE_ARGS[] = {
        new Long(1),        // VLR_LOCATION
        new String("XXX"),  // SUB_NBR
    };

    private static final Long TXN_ID = 1000l;
    private static final long CLIENT_HANDLE = 99999l;
    private static final int LOCAL_PARTITION = 1;
    private static final int REMOTE_PARTITION = 0;
    private static final int NUM_PARTITIONS = 10;
    
    private Procedure catalog_proc;
    private Statement catalog_stmt;
    private Collection<Integer> all_partitions;
    private SQLStmt batch[];
    private ParameterSet args[];
    
    @Override
    protected void setUp() throws Exception {
        super.setUp(ProjectType.TM1);
        this.addPartitions(NUM_PARTITIONS);
        p_estimator = new PartitionEstimator(catalog_db, new DefaultHasher(catalog_db, NUM_PARTITIONS));
        this.all_partitions = CatalogUtil.getAllPartitionIds(catalog_db);
    }
    
    private void init(Class<? extends VoltProcedure> volt_proc, String stmt_name, Object raw_args[]) {
        this.catalog_proc = this.getProcedure(volt_proc);
        assertNotNull(this.catalog_proc);
        this.catalog_stmt = this.catalog_proc.getStatements().get(stmt_name);
        assertNotNull(this.catalog_stmt);
        
        CatalogMap<PlanFragment> fragments = null;
        if (this.catalog_stmt.getQuerytype() == QueryType.INSERT.getValue()) {
            fragments = this.catalog_stmt.getFragments();
        } else {
            assert(this.catalog_stmt.getHas_multisited());
            fragments = this.catalog_stmt.getMs_fragments();
        }

        // Create a SQLStmt batch
        this.batch = new SQLStmt[] { new SQLStmt(this.catalog_stmt, fragments) };
        this.args = new ParameterSet[] { VoltProcedure.getCleanParams(this.batch[0], raw_args) };
    }
    
    protected static int getLocalFragmentCount(Collection<FragmentTaskMessage> ftasks, int base_partition) {
        int cnt = 0;
        for (FragmentTaskMessage ftask : ftasks) {
            if (ftask.getDestinationPartitionId() == base_partition) cnt++;
        } // FOR
        return (cnt);
    }
    protected static int getRemoteFragmentCount(Collection<FragmentTaskMessage> ftasks, int base_partition) {
        int cnt = 0;
        for (FragmentTaskMessage ftask : ftasks) {
            if (ftask.getDestinationPartitionId() != base_partition) cnt++;
        } // FOR
        return (cnt);
    }
    
//    /**
//     * testGenerateDependencyIds
//     */
//    public void testGenerateDependencyIds() throws Exception {
//        this.init(MULTISITE_PROCEDURE, MULTISITE_STATEMENT, MULTISITE_PROCEDURE_ARGS);
//        List<PlanFragment> catalog_frags = Arrays.asList(this.catalog_stmt.getMs_fragments().values());
//        assertFalse(catalog_frags.isEmpty());
//        // Shuffle the list
//        Collections.shuffle(catalog_frags);
//        
//        List<Set<Integer>> frag_input_ids = new ArrayList<Set<Integer>>();
//        List<Set<Integer>> frag_output_ids = new ArrayList<Set<Integer>>();
//        BatchPlanner.generateDependencyIds(catalog_frags, frag_input_ids, frag_output_ids);
//        assertEquals(catalog_frags.size(), frag_input_ids.size());
//        assertEquals(catalog_frags.size(), frag_output_ids.size());
//        
//        for (int i = 0, cnt = catalog_frags.size(); i < cnt; i++) {
//            PlanFragment catalog_frag = catalog_frags.get(i);
//            Set<Integer> input_ids = frag_input_ids.get(i);
//            Set<Integer> output_ids = frag_output_ids.get(i);
//            assertNotNull(catalog_frag);
//            assertNotNull(input_ids);
//            assertNotNull(output_ids);
//            
//            // Make sure that if this PlanFragment has an input id for each ReceivePlanNode
//            AbstractPlanNode root = QueryPlanUtil.deserializePlanFragment(catalog_frag);
//            Set<ReceivePlanNode> receive_nodes = PlanNodeUtil.getPlanNodes(root, ReceivePlanNode.class);
//            assertEquals(receive_nodes.size(), input_ids.size());
//            
//            // Likewise, make sure we have an output id for each SendPlanNode
//            Set<SendPlanNode> send_nodes = PlanNodeUtil.getPlanNodes(root, SendPlanNode.class);
//            assertEquals(send_nodes.size(), output_ids.size());
//            
////            System.out.println(catalog_frag);
////            System.out.println("  IN:  " + input_ids);
////            System.out.println("  OUT: " + output_ids);
////            System.out.println(PlanNodeUtil.debug(root));
////            System.out.println("------------");
//        } // FOR
//    }
    
    /**
     * testPlanVertexHashCode
     */
    @Test
    public void testPlanVertexHashCode() throws Exception {
        // Need to test that our short-cut methods for calculating the hash code works
        this.init(SINGLESITE_PROCEDURE, SINGLESITE_STATEMENT, SINGLESITE_PROCEDURE_ARGS);
        
        PlanFragment catalog_frag = CollectionUtil.first(CollectionUtil.first(this.catalog_proc.getStatements()).getFragments());
        assertNotNull(catalog_frag);
        
        int round = 0;
        int stmt_index = 0;
        int input = 1;
        int output = 1;
        boolean is_local = true;
        
        BatchPlanner.PlanVertex v0 = new BatchPlanner.PlanVertex(catalog_frag, stmt_index, round, input, output, is_local);
        assert(v0.hash_code > 0);
//        System.err.println("v0 = " + v0.hashCode());
        
        BatchPlanner.PlanVertex v1 = new BatchPlanner.PlanVertex(catalog_frag, stmt_index, round+1, input, output, is_local);
        assert(v1.hash_code > 0);
//        System.err.println("v1 = " + v1.hashCode());
        
        BatchPlanner.PlanVertex v2 = new BatchPlanner.PlanVertex(catalog_frag, stmt_index+1, round, input, output, is_local);
        assert(v2.hash_code > 0);
//        System.err.println("v2 = " + v2.hashCode());
        
        assert(v0.hashCode() != v1.hashCode());
        assert(v0.hashCode() != v2.hashCode());
        assert(v1.hashCode() != v2.hashCode());
    }
    
    /**
     * testSingleSitedLocalPlan
     */
    public void testSingleSitedLocalPlan() throws Exception {
        this.init(SINGLESITE_PROCEDURE, SINGLESITE_STATEMENT, SINGLESITE_PROCEDURE_ARGS);
        BatchPlanner batchPlan = new BatchPlanner(batch, this.catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, LOCAL_PARTITION, Collections.singleton(LOCAL_PARTITION), this.args, true);
        assertNotNull(plan);
        Collection<FragmentTaskMessage> ftasks = plan.getFragmentTaskMessages(this.args);
        int local_frags = getLocalFragmentCount(ftasks, LOCAL_PARTITION);
        int remote_frags = getRemoteFragmentCount(ftasks, LOCAL_PARTITION);
        
        assertTrue(plan.isLocal());
        assertTrue(plan.isSingleSited());
        assertFalse(plan.hasMisprediction());
        assertEquals(1, local_frags);
        assertEquals(0, remote_frags);
    }
    
    /**
     * testSingleSitedLocalPlanCaching
     */
    public void testSingleSitedLocalPlanCaching() throws Exception {
        HStoreConf hstore_conf = HStoreConf.singleton();
        boolean orig = hstore_conf.site.planner_caching;
        hstore_conf.site.planner_caching = true;
        
        try {
            this.init(SINGLESITE_PROCEDURE, SINGLESITE_STATEMENT, SINGLESITE_PROCEDURE_ARGS);
            BatchPlanner planner = new BatchPlanner(batch, this.catalog_proc, p_estimator);
            BatchPlanner.BatchPlan plan0 = planner.plan(TXN_ID, CLIENT_HANDLE, LOCAL_PARTITION, Collections.singleton(LOCAL_PARTITION), this.args, true);
            assertNotNull(plan0);
            assertFalse(plan0.hasMisprediction());
            assertTrue(plan0.isLocal());
            assertTrue(plan0.isSingleSited());
            Collection<FragmentTaskMessage> ftasks = plan0.getFragmentTaskMessages(this.args);
            assertEquals(1, getLocalFragmentCount(ftasks, LOCAL_PARTITION)); // local_frags
            assertEquals(0, getRemoteFragmentCount(ftasks, LOCAL_PARTITION)); // remote_frags
            
            BatchPlanner.BatchPlan plan1 = planner.plan(TXN_ID, CLIENT_HANDLE, LOCAL_PARTITION, Collections.singleton(LOCAL_PARTITION), this.args, true);
            assertNotNull(plan1);
            assertFalse(plan1.hasMisprediction());
            assert(plan0 == plan1);
            
        } finally {
            hstore_conf.site.planner_caching = orig;    
        }
    }
    
    /**
     * testSingleSitedLocalPlan2
     */
    public void testSingleSitedLocalPlan2() throws Exception {
        Object params[] = new Object[] {
            new Long(LOCAL_PARTITION),  // S_ID
            new Long(LOCAL_PARTITION),  // S_ID
            new Long(0),                // SF_TYPE
            new Long(0),                // START_TIME
            new Long(0),                // END_TIME
        };
        
        this.init(GetNewDestination.class, "GetData", params);
        BatchPlanner batchPlan = new BatchPlanner(batch, this.catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, LOCAL_PARTITION, Collections.singleton(LOCAL_PARTITION), this.args, true);
        assertNotNull(plan);
        assertFalse(plan.hasMisprediction());
        Collection<FragmentTaskMessage> ftasks = plan.getFragmentTaskMessages(this.args);
        int local_frags = getLocalFragmentCount(ftasks, LOCAL_PARTITION);
        int remote_frags = getRemoteFragmentCount(ftasks, LOCAL_PARTITION);
        
        assertTrue(plan.isLocal());
        assertTrue(plan.isSingleSited());
        assertEquals(1, local_frags);
        assertEquals(0, remote_frags);
    }
    
    /**
     * testSingleSitedLocalPlanInsert
     */
    public void testSingleSitedLocalPlanInsert() throws Exception {
        Object params[] = new Object[] {
            new Long(LOCAL_PARTITION),  // S_ID
            new Long(0),                // SF_TYPE
            new Long(0),                // START_TIME
            new Long(0),                // END_TIME
            "XYZ",                      // NUMBERX
        };
        
        this.init(InsertCallForwarding.class, "update", params);
        BatchPlanner batchPlan = new BatchPlanner(batch, this.catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, LOCAL_PARTITION, Collections.singleton(LOCAL_PARTITION), this.args, true);
        assertNotNull(plan);
        assertFalse(plan.hasMisprediction());
        Collection<FragmentTaskMessage> ftasks = plan.getFragmentTaskMessages(this.args);
        int local_frags = getLocalFragmentCount(ftasks, LOCAL_PARTITION);
        int remote_frags = getRemoteFragmentCount(ftasks, LOCAL_PARTITION);
        
        assertTrue(plan.isLocal());
        assertTrue(plan.isSingleSited());
        assertEquals(1, local_frags);
        assertEquals(0, remote_frags);
    }
    
    /**
     * testSingleSitedRemotePlan
     */
    public void testSingleSitedRemotePlan() throws Exception {
        this.init(SINGLESITE_PROCEDURE, SINGLESITE_STATEMENT, SINGLESITE_PROCEDURE_ARGS);
        BatchPlanner batchPlan = new BatchPlanner(batch, this.catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, REMOTE_PARTITION, Collections.singleton(LOCAL_PARTITION), this.args, false);
        assertNotNull(plan);
        assertFalse(plan.hasMisprediction());
        Collection<FragmentTaskMessage> ftasks = plan.getFragmentTaskMessages(this.args);
        int local_frags = getLocalFragmentCount(ftasks, REMOTE_PARTITION);
        int remote_frags = getRemoteFragmentCount(ftasks, REMOTE_PARTITION);
        
        assertFalse(plan.isLocal());
        assertTrue(plan.isSingleSited());
        assertEquals(0, local_frags);
        assertEquals(1, remote_frags);
    }
    
    /**
     * testMultiSitedLocalPlan
     */
    public void testMultiSitedLocalPlan() throws Exception {
        this.init(MULTISITE_PROCEDURE, MULTISITE_STATEMENT, MULTISITE_PROCEDURE_ARGS);
        BatchPlanner batchPlan = new BatchPlanner(batch, this.catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, LOCAL_PARTITION, this.all_partitions, this.args, false);
        assertNotNull(plan);
        assertFalse(plan.hasMisprediction());
        Collection<FragmentTaskMessage> ftasks = plan.getFragmentTaskMessages(this.args);
        int local_frags = getLocalFragmentCount(ftasks, LOCAL_PARTITION);
        int remote_frags = getRemoteFragmentCount(ftasks, LOCAL_PARTITION);
        
        assertFalse(plan.isLocal());
        assertFalse(plan.isSingleSited());
        assertEquals(2, local_frags);
        assertEquals(NUM_PARTITIONS-1, remote_frags);
    }
    
    /**
     * testMultiSitedRemotePlan
     */
    public void testMultiSitedRemotePlan() throws Exception {
        this.init(MULTISITE_PROCEDURE, MULTISITE_STATEMENT, MULTISITE_PROCEDURE_ARGS);
        BatchPlanner batchPlan = new BatchPlanner(batch, this.catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, REMOTE_PARTITION, this.all_partitions, this.args, false);
        assertNotNull(plan);
        assertFalse(plan.hasMisprediction());
        Collection<FragmentTaskMessage> ftasks = plan.getFragmentTaskMessages(this.args);
        int local_frags = getLocalFragmentCount(ftasks, LOCAL_PARTITION);
        int remote_frags = getRemoteFragmentCount(ftasks, LOCAL_PARTITION);
         
        assertFalse(plan.isLocal());
        assertFalse(plan.isSingleSited());
        assertEquals(1, local_frags);
        assertEquals(NUM_PARTITIONS, remote_frags);
    }

    /**
     * testGetFragmentTaskMessages
     */
    public void testGetFragmentTaskMessages() throws Exception {
        this.init(MULTISITE_PROCEDURE, MULTISITE_STATEMENT, MULTISITE_PROCEDURE_ARGS);
        BatchPlanner batchPlan = new BatchPlanner(batch, this.catalog_proc, p_estimator);
        BatchPlanner.BatchPlan plan = batchPlan.plan(TXN_ID, CLIENT_HANDLE, LOCAL_PARTITION, this.all_partitions, this.args, false);
        assertNotNull(plan);
        assertFalse(plan.hasMisprediction());
        
        List<FragmentTaskMessage> ftasks = plan.getFragmentTaskMessages(this.args);
//        System.err.println("TASKS:\n" + ftasks);
//        System.err.println("----------------------------------------");
        Set<Integer> output_dependencies = new HashSet<Integer>();
        FragmentTaskMessage local_ftask = null;
        for (FragmentTaskMessage ftask : ftasks) {
            ftask.toString(); // Why does this prevent the test from failing????
            assertEquals(FragmentTaskMessage.USER_PROC, ftask.getFragmentTaskType());
            
            // All tasks for the multi-partition query should have exactly one output with no inputs
            if (!ftask.hasInputDependencies()) {
                assertEquals("FragmentTaskMessage for multi-partition query does not have the right # of fragments", 1, ftask.getFragmentCount());
                for (int i = 0, cnt = ftask.getFragmentCount(); i < cnt; i++) {
                    assertEquals(HStoreConstants.NULL_DEPENDENCY_ID, ftask.getOnlyInputDepId(i));
                } // FOR
                assertNotNull(ftask.getOutputDependencyIds());
                assertEquals(1, ftask.getOutputDependencyIds().length);
                output_dependencies.add(ftask.getOutputDependencyIds()[0]);
            } else {
                assertNull("Already have local task:\n" + local_ftask, local_ftask);
                local_ftask = ftask;
            }
//            System.err.println("Partition #" + partition);
//            System.err.println(Arrays.asList(p_ftasks.get(partition)));
//            System.err.println();
        } // FOR
        assertNotNull(local_ftask);

        assertEquals("Local partition does not have the right # of fragments", 1, local_ftask.getFragmentCount());
        int with_input_dependencies = 0;
        // All the local partition's tasks should output something
        // System.err.println(local_ftask);
        assertNotNull(local_ftask.getOutputDependencyIds());
        assertEquals(1, local_ftask.getOutputDependencyIds().length);

        // Check that one of them needs input and that it outputs something
        // that the other partitions are not outputting (i.e., data for the client)
        if (local_ftask.hasInputDependencies()) {
            with_input_dependencies++;
            assertEquals(output_dependencies.size(), local_ftask.getInputDependencyCount());
            for (int i = 0, cnt = local_ftask.getFragmentCount(); i < cnt; i++) {
                int input_dependency = local_ftask.getOnlyInputDepId(i);
                assert(output_dependencies.contains(input_dependency));
            } // FOR
            assertFalse(output_dependencies.contains(local_ftask.getOutputDependencyIds()[0]));
        }
    }

}