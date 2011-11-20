package edu.brown.catalog;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Host;
import org.voltdb.catalog.Partition;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;

import edu.brown.designer.AccessGraph;
import edu.brown.designer.ColumnSet;
import edu.brown.designer.DesignerEdge;
import edu.brown.designer.DesignerInfo;
import edu.brown.designer.DesignerVertex;
import edu.brown.designer.generators.AccessGraphGenerator;
import edu.brown.utils.ArgumentsParser;
import edu.brown.utils.MathUtil;
import edu.brown.utils.StringUtil;
import edu.brown.workload.Workload;
import edu.mit.hstore.HStoreSite;

public class CatalogInfo {

    private static final String HOST_INNER = "\u251c";
    private static final String HOST_LAST = "\u2514";
    
    public static double complexity(ArgumentsParser args, Database catalog_db, Workload workload) throws Exception {
        AccessGraph agraph = new AccessGraph(catalog_db);
        DesignerInfo info = new DesignerInfo(args);
        for (Procedure catalog_proc : catalog_db.getProcedures()) {
            // Skip if there are no transactions in the workload for this procedure
            if (workload.getTraces(catalog_proc).isEmpty() || catalog_proc.getSystemproc()) continue;
            new AccessGraphGenerator(info, catalog_proc).generate(agraph);
        } // FOR
        
        double ret = 1;
        for (Table catalog_tbl : catalog_db.getTables()) {
            DesignerVertex v = agraph.getVertex(catalog_tbl);
            if (v == null) continue;
            
            Set<Column> used_cols = new HashSet<Column>();
            Collection<DesignerEdge> edges = agraph.getIncidentEdges(v); 
            if (edges == null) continue;
            for (DesignerEdge e : edges) {
                ColumnSet cset = e.getAttribute(agraph, AccessGraph.EdgeAttributes.COLUMNSET);
                assert(cset != null) : e.debug();
                Set<Column> cols = cset.findAllForParent(Column.class, catalog_tbl);
                assert(cols != null) : catalog_tbl + "\n" + cset.debug();
                used_cols.addAll(cols);
            }
            int num_cols = used_cols.size();
            
            // Picking columns + repl
            ret *= num_cols * num_cols;
            
            // Secondary Indexes
            for (int i = 0; i < num_cols-1; i++) {
                ret *= MathUtil.factorial(num_cols-1).doubleValue() / MathUtil.factorial(i).multiply(MathUtil.factorial(num_cols-1-i)).doubleValue();
            } // FOR
            
            
            System.err.println(catalog_tbl + ": " + num_cols + " - " + ret);
        }
        for (Procedure catalog_proc : catalog_db.getProcedures()) {
            if (catalog_proc.getParameters().isEmpty() || catalog_proc.getSystemproc()) continue;
            ret *= catalog_proc.getParameters().size() * catalog_proc.getParameters().size();
            System.err.println(catalog_proc + ": " + catalog_proc.getParameters().size() + " - " + ret);
        }
        return (ret);
    }
    
    /**
     * @param args
     */
    public static void main(String[] vargs) throws Exception {
        ArgumentsParser args = ArgumentsParser.load(vargs);
        args.require(ArgumentsParser.PARAM_CATALOG);
        
        // Just print out the Host/Partition Information
        int num_hosts = CatalogUtil.getCluster(args.catalog).getHosts().size();
        int num_sites = CatalogUtil.getCluster(args.catalog).getSites().size();
        int num_partitions = CatalogUtil.getNumberOfPartitions(args.catalog);
        
        Map<String, Object> m = new ListOrderedMap<String, Object>();
        m.put("Catalog File", args.catalog_path.getAbsolutePath());
        m.put("# of Hosts", num_hosts);
        m.put("# of Sites", num_sites);
        m.put("# of Partitions", num_partitions);
        if (args.hasParam(ArgumentsParser.PARAM_WORKLOAD)) {
            m.put("Complexity", complexity(args, args.catalog_db, args.workload));
        }
        
        System.out.println(StringUtil.formatMaps(":", false, false, false, true, true, true, m));
        System.out.println("Cluster Information:\n");
        
        Map<Host, Set<Site>> hosts = CatalogUtil.getSitesPerHost(args.catalog);
        Set<String> partition_ids = new TreeSet<String>();
        String partition_f = "%0" + Integer.toString(num_partitions).length() + "d";
        
        int num_cols = Math.min(4, hosts.size());
        String cols[] = new String[num_cols];
        for (int i = 0; i < num_cols; i++) cols[i] = "";
        
        int i = 0;
        for (Host catalog_host : hosts.keySet()) {
            int idx = i % num_cols;
            
            cols[idx] += String.format("[%02d] HOST %s\n", i, catalog_host.getIpaddr());
            Set<Site> sites = hosts.get(catalog_host);
            int j = 0;
            for (Site catalog_site : sites) {
                partition_ids.clear();
                for (Partition catalog_part : catalog_site.getPartitions()) {
                    partition_ids.add(String.format(partition_f, catalog_part.getId()));
                } // FOR
                String prefix = (++j == sites.size() ? HOST_LAST : HOST_INNER);
                cols[idx] += String.format("     %s SITE %s: %s\n", prefix, HStoreSite.formatSiteName(catalog_site.getId()), partition_ids);
            } // FOR
            cols[idx] += "\n";
            i++;
        } // FOR
        System.out.println(StringUtil.columns(cols));
    }

}
