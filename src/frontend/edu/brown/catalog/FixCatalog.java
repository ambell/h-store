package edu.brown.catalog;

import java.io.File;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.VoltDB;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Host;
import org.voltdb.catalog.Partition;
import org.voltdb.catalog.Site;

import edu.brown.mappings.ParameterMappingsSet;
import edu.brown.utils.ArgumentsParser;
import edu.brown.utils.FileUtil;
import edu.brown.utils.ProjectType;

/**
 * 
 * @author pavlo
 */
public abstract class FixCatalog {
    private static final Logger LOG = Logger.getLogger(FixCatalog.class);
    
    public static final int HOSTS = 1;
    public static final int HOST_CORES = 2;
    public static final int HOST_THREADS_PER_CORE = 1;
    public static final long HOST_MEMORY = 1073741824l; 

    /**
     * Added a hosts/sites/partitions in the catalog.
     * @param orig_catalog
     * @param triplets - [0] host [1] port# [2] site#
     * @return
     */
    @SuppressWarnings("unchecked")
    public static Catalog addHostInfo(Catalog orig_catalog, ClusterConfiguration cc) {
        Catalog catalog = CatalogCloner.cloneBaseCatalog(orig_catalog, Site.class, Host.class, Partition.class);
        Cluster catalog_clus = CatalogUtil.getCluster(catalog);
        
        // Add a bunch of hosts and partitions to this mofo
        assert(catalog_clus != null);
        int host_id = VoltDB.FIRST_SITE_ID;
        
        int partition_ctr = 0;
        for (String host : cc.getHosts()) {
            String host_name = String.format("host%02d", host_id++);
            Host catalog_host = catalog_clus.getHosts().add(host_name);
            assert(catalog_host != null);
            catalog_host.setIpaddr(host);
            LOG.debug("Created new host " + catalog_host + " on node '" + host + "'");

            int proc_port = VoltDB.DEFAULT_PORT;
            int messenger_port = proc_port + 10000;
            
            // Now create the sites for this host
            for (Integer siteid : cc.getSites(host)) {
                LOG.debug("Adding Site #" + siteid + " on " + host);
                
                Site catalog_site = catalog_clus.getSites().add(siteid.toString());
                assert(catalog_site != null);
                catalog_site.setId(siteid);
                catalog_site.setHost(catalog_host);
                catalog_site.setProc_port(proc_port++);
                catalog_site.setMessenger_port(messenger_port++);
                
                // Add all the partitions
                for (Integer partition_id : cc.getPartitionIds(host, siteid)) {
                    Partition catalog_part = catalog_site.getPartitions().add(partition_id.toString());
                    assert(catalog_part != null);
                    catalog_part.setId(partition_id);
                    partition_ctr++;
                } // FOR
                
            } // FOR
            // LOG.debug("Added " + ctr + " partitions for " + catalog_host);
        } // FOR
        catalog_clus.setNum_partitions(partition_ctr);
        LOG.info("Updated host information in catalog with " + (host_id-1) + " new hosts and " + partition_ctr + " partitions");
        return (catalog);
    }
    
    public static Catalog addHostInfo(Catalog orig_catalog, int num_hosts, int num_sites_per_host, int num_partitions_per_site) {
        return (FixCatalog.addHostInfo(orig_catalog, "node-%02d", num_hosts, num_sites_per_host, num_partitions_per_site));
    }
    
    public static Catalog addHostInfo(Catalog orig_catalog, String hostname_format, int num_hosts, int num_sites_per_host, int num_partitions_per_site) {
        ClusterConfiguration cc = new ClusterConfiguration();
        int siteid = 0;
        int partitionid = 0;
        
        final boolean use_format = hostname_format.contains("%");
        for (int host = 0; host < num_hosts; host++) {
            String hostname = (use_format ? String.format(hostname_format, host) : hostname_format);
            for (int site = 0; site < num_sites_per_host; site++) {
                for (int partition = 0; partition < num_partitions_per_site; partition++) {
                    cc.addPartition(hostname, siteid, partitionid++);
                } // FOR (partitions)
                siteid++;
            } // FOR (sites)
        } // FOR (hosts)
        return (FixCatalog.addHostInfo(orig_catalog, cc));
    }
    
    /**
     * 
     * @param catalog_db
     * @throws Exception
     */
    public static void populateCatalog(Database catalog_db, ProjectType type) throws Exception {
        //
        // Foreign Keys
        //
        Map<String, ForeignKeysUtil.ForeignKeyMapping> foreign_keys = ForeignKeysUtil.FOREIGN_KEYS.get(type);
        if (foreign_keys != null) {
            try {
                ForeignKeysUtil.populateCatalog(catalog_db, foreign_keys);
                LOG.info("Updated foreign key dependencies in catalog for schema type '" + type + "'");
            } catch (RuntimeException ex) {
                // Ignore these...
                LOG.warn(ex.getLocalizedMessage());
            }
        }
        
        //
        // StmtParameter->ProcParameter Mapping
        //
        Map<String, ParametersUtil.DefaultParameterMapping> param_map = ParametersUtil.getParameterMapping(type);
        if (param_map != null) {
            try {
                ParametersUtil.populateCatalog(catalog_db, param_map);
                LOG.info("Updated parameter mapping information in catalog for schema type '" + type + "'");
            } catch (RuntimeException ex) {
                // Ignore these...
                LOG.warn(ex.getLocalizedMessage());
            }
        }
        
        return;
    }
    
    
    /**
     * @param args
     */
    public static void main(String[] vargs) throws Exception {
        ArgumentsParser args = ArgumentsParser.load(vargs);
        args.require(ArgumentsParser.PARAM_CATALOG_TYPE,
                     ArgumentsParser.PARAM_CATALOG_OUTPUT);
        
        // ProjectType type = args.catalog_type;
        String catalogOutputPath = args.getParam(ArgumentsParser.PARAM_CATALOG_OUTPUT);
        
        // Populate Parameter Mappings
        if (args.hasParam(ArgumentsParser.PARAM_MAPPINGS)) {
            File input_path = new File(args.getParam(ArgumentsParser.PARAM_MAPPINGS));
            if (input_path.exists()) {
                ParameterMappingsSet mappings = new ParameterMappingsSet();
                mappings.load(input_path.getAbsolutePath(), args.catalog_db);
                ParametersUtil.applyParameterMappings(args.catalog_db, mappings);
                LOG.info("Applied ParameterMappings file to '" + input_path + "' catalog parameter mappings...");
            } else {
                LOG.warn("ParameterMappings file '" + input_path + "' does not exist. Ignoring...");
            }
        }
        
        // Fix the catalog!
        // populateCatalog(args.catalog_db, type);

        // Populate host information
        Catalog new_catalog = args.catalog;
        if (args.hasIntParam(ArgumentsParser.PARAM_CATALOG_NUM_HOSTS)) {
            String host_format = args.getParam(ArgumentsParser.PARAM_CATALOG_HOSTS);
            
            int num_hosts = args.getIntParam(ArgumentsParser.PARAM_CATALOG_NUM_HOSTS);
            int num_sites_per_host = (args.hasIntParam(ArgumentsParser.PARAM_CATALOG_SITES_PER_HOST) ?
                                      args.getIntParam(ArgumentsParser.PARAM_CATALOG_SITES_PER_HOST) : 2);
            int num_partitions_per_site = (args.hasIntParam(ArgumentsParser.PARAM_CATALOG_PARTITIONS_PER_SITE) ?
                                           args.getIntParam(ArgumentsParser.PARAM_CATALOG_PARTITIONS_PER_SITE) : 2);

            if (host_format == null) {
                new_catalog = FixCatalog.addHostInfo(new_catalog, num_hosts, num_sites_per_host, num_partitions_per_site);
            } else {
                new_catalog = FixCatalog.addHostInfo(new_catalog, host_format, num_hosts, num_sites_per_host, num_partitions_per_site);
            }

        // Use host list
        } else {
            ClusterConfiguration cc = new ClusterConfiguration(args.getParam(ArgumentsParser.PARAM_CATALOG_HOSTS));
            new_catalog = FixCatalog.addHostInfo(new_catalog, cc);
        }
        
        // Now construct the new Dtxn.Coordinator configuration
        // String new_dtxn = HStoreDtxnConf.toHStoreDtxnConf(new_catalog);
        
        // We need to write this things somewhere now...
        FileUtil.writeStringToFile(new File(catalogOutputPath), new_catalog.serialize());
        LOG.info("Wrote updated catalog specification to '" + catalogOutputPath + "'");
        
        // FileUtil.writeStringToFile(new File(dtxnOutputPath), new_dtxn);
        // LOG.info("Wrote updated Dtxn.Coordinator configuration to '" + dtxnOutputPath + "'");

        return;
    }
    
}