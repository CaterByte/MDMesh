package com.hmdm.plugin;

import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Guards the ClassGraph classpath scan in {@link PluginList#init}: with every bundled plugin on the server's
 * classpath (exactly as in launcher.war), all of them must be discovered and yield Guice modules. Catches a
 * ClassGraph upgrade/API change that silently finds nothing (the server would boot with zero plugins).
 */
public class PluginDiscoveryTest {

    @Test
    public void scanFindsEveryBundledPlugin() {
        ServletContext context = (ServletContext) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ServletContext.class},
                (proxy, method, args) -> {
                    if ("getInitParameter".equals(method.getName())
                            && "plugin.devicelog.persistence.config.class".equals(args[0])) {
                        return "com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration";
                    }
                    return null;
                });

        PluginList.init(context);

        List<String> expected = Arrays.asList("audit", "deviceinfo", "devicelog", "messaging", "push", "xtra");
        assertEquals(new TreeSet<>(expected), new TreeSet<>(PluginList.getEnabledPlugins()));
        assertFalse("plugins contributed no Guice modules", PluginList.getPluginModules().isEmpty());
    }
}
