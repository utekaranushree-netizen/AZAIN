package com.stationaryshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.stationaryshop.service.OrderService;
import com.stationaryshop.support.AbstractDbIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Tests A and O: the application context starts against a clean database created by stationary_shop.sql. */
class ApplicationContextIntegrationTest extends AbstractDbIntegrationTest {
    @Autowired ApplicationContext context;

    @Test
    void contextStartsAndHibernateAcceptsTheSqlSchema() {
        // reaching this line means Hibernate ddl-auto=validate found every mapped table and column
        assertNotNull(context.getBean(OrderService.class));
    }

    @Test
    void schemaScriptCreatedTheExpectedObjects() {
        assertEquals(11, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"));
        assertEquals(5, count("SELECT COUNT(*) FROM information_schema.views WHERE table_schema = 'public'"));
        assertEquals(10, count("SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal"));
        assertEquals(1, count("SELECT COUNT(*) FROM pg_proc WHERE proname = 'get_order_total'"));
    }

    @Test
    void sampleDataMeetsTheCollegeMinimums() {
        assertEquals(true, count("SELECT COUNT(*) FROM products") >= 10);
        assertEquals(true, count("SELECT COUNT(*) FROM orders") >= 10);
        assertEquals(true, count("SELECT COUNT(*) FROM payments") >= 10);
        assertEquals(true, count("SELECT COUNT(*) FROM purchase_orders") >= 10);
    }
}
