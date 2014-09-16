package com.github.rmannibucau.hazelcast.api;

import com.github.rmannibucau.hazelcast.internal.JCacheScopeExtension;
import org.apache.deltaspike.core.util.context.ContextualInstanceInfo;
import org.apache.openejb.Injector;
import org.junit.Test;

import javax.cache.Cache;
import javax.cache.Caching;
import javax.ejb.embeddable.EJBContainer;
import javax.inject.Inject;
import javax.naming.NamingException;
import java.io.Serializable;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class JCacheScopedTest {
    @Inject
    private ClusterBean bean;

    @Inject
    private JCacheScopeExtension extension;

    @Test
    public void cluster() throws NamingException {
        final EJBContainer container = EJBContainer.createEJBContainer();
        Injector.inject(this);

        assertNull(bean.getName());

        final Cache<String, ContextualInstanceInfo<?>> cache = extension.getStorage();

        ContextualInstanceInfo<?> contextualInstanceInfo = null;
        String id = null;
        for (final String beanId : asList(
                "MANAGED#class com.github.rmannibucau.hazelcast.api.JCacheScopedTest$ClusterBean#@javax.enterprise.inject.Default(),@javax.enterprise.inject.Any(),",
                "MANAGED#class com.github.rmannibucau.hazelcast.api.JCacheScopedTest$ClusterBean#@javax.enterprise.inject.Any(),@javax.enterprise.inject.Default(),"
        )) {
            contextualInstanceInfo = cache.get(beanId);
            if (contextualInstanceInfo != null) {
                id = beanId;
                break;
            }
        }
        assertNotNull(id);

        final ClusterBean instance = ClusterBean.class.cast(contextualInstanceInfo.getContextualInstance());
        instance.setName("cluster");
        cache.put(id, contextualInstanceInfo);

        // check injection was updated
        assertEquals("cluster", bean.getName());

        container.close();
    }

    @JCacheScoped
    public static class ClusterBean implements Serializable {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }
}
