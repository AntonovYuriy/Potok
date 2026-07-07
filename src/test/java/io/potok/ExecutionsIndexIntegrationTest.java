package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unfiltered dashboard list (GET /api/executions with no workflowId) orders by
 * created_at desc. M12 adds an index that serves it; the old (workflow_id, created_at)
 * index could not (wrong leading column). Assert the index exists AND that the planner
 * uses it for the ordered list (seqscan disabled → it must pick the index if usable).
 */
class ExecutionsIndexIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void createdAtIndexExistsAndServesTheGlobalOrderedList() {
        long present = jdbc.sql(
                        "select count(*) from pg_indexes where indexname = 'workflow_execution_created_at_idx'")
                .query(Long.class).single();
        assertThat(present).as("index present").isEqualTo(1);

        String plan = new TransactionTemplate(txManager).execute(status -> {
            jdbc.sql("set local enable_seqscan = off").update();
            return String.join("\n", jdbc.sql(
                            "explain select id from workflow_execution order by created_at desc limit 20")
                    .query(String.class).list());
        });

        assertThat(plan).as("planner uses the created_at index")
                .contains("workflow_execution_created_at_idx");
    }
}
