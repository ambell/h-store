package edu.brown.benchmark.example;

import edu.brown.benchmark.AbstractProjectBuilder;
import edu.brown.benchmark.BenchmarkComponent;
import edu.brown.benchmark.example.procedures.GetData;

public class ExampleProjectBuilder extends AbstractProjectBuilder {

    // REQUIRED: Retrieved via reflection by BenchmarkController
    public static final Class<? extends BenchmarkComponent> m_clientClass = ExampleClient.class;

    // REQUIRED: Retrieved via reflection by BenchmarkController
    public static final Class<? extends BenchmarkComponent> m_loaderClass = ExampleLoader.class;

    public static final Class<?> PROCEDURES[] = new Class<?>[] { GetData.class, };
    public static final String PARTITIONING[][] = new String[][] {
            // { "TABLE NAME", "PARTITIONING COLUMN NAME" }
            { "TABLEA", "A_ID" }, { "TABLEB", "B_A_ID" }, };

    public ExampleProjectBuilder() {
        super("example", ExampleProjectBuilder.class, PROCEDURES, PARTITIONING);
    }
}