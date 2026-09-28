package com.hmdm;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Hierarchy;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.RootLogger;
import org.apache.log4j.xml.DOMConfigurator;
import org.junit.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the server's only log4j config (src/main/resources/log4j.xml, which log4j loads from the classpath when the
 * first logger is created): INFO, stdout only, and the audit plugin's "AuditLogger" events on stdout at INFO. It used
 * to ship root DEBUG, which flooded every production start (tens of thousands of lines) and logged each SQL statement.
 * The file is loaded into a private Hierarchy, so the test does not depend on how other tests initialised log4j.
 */
public class LoggingConfigTest {

    private static Hierarchy load() {
        URL url = LoggingConfigTest.class.getResource("/log4j.xml");
        assertNotNull("log4j.xml is not on the classpath", url);
        assertTrue("testing " + url + ", not the shipped main-resources log4j.xml", url.getPath().endsWith("/classes/log4j.xml"));
        Hierarchy hierarchy = new Hierarchy(new RootLogger(Level.ALL));
        new DOMConfigurator().doConfigure(url, hierarchy);
        return hierarchy;
    }

    private static List<Appender> appenders(Logger logger) {
        List<Appender> list = new ArrayList<>();
        for (Enumeration<?> e = logger.getAllAppenders(); e.hasMoreElements(); ) {
            list.add((Appender) e.nextElement());
        }
        return list;
    }

    @Test
    public void rootLogsInfoToStdoutOnly() {
        Logger root = load().getRootLogger();
        assertEquals(Level.INFO, root.getLevel());
        List<Appender> appenders = appenders(root);
        assertEquals("root appenders", 1, appenders.size());
        assertTrue("root appender is not a ConsoleAppender", appenders.get(0) instanceof ConsoleAppender);
        assertEquals("System.out", ((ConsoleAppender) appenders.get(0)).getTarget());
    }

    @Test
    public void auditEventsGoToStdoutAtInfo() {
        Logger audit = load().getLogger("AuditLogger");
        assertEquals(Level.INFO, audit.getEffectiveLevel());
        assertEquals("AuditLogger pins its own level, so a raised root does not change it", Level.INFO, audit.getLevel());
        assertTrue("AuditLogger must reach the root's stdout appender", audit.getAdditivity());
    }

    @Test
    public void noLoggerHasItsOwnAppender() {
        Hierarchy hierarchy = load();
        for (Enumeration<?> e = hierarchy.getCurrentLoggers(); e.hasMoreElements(); ) {
            Logger logger = (Logger) e.nextElement();
            assertFalse(logger.getName() + " has its own appender (a file?)", appenders(logger).size() > 0);
        }
    }
}
