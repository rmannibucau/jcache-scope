package com.github.rmannibucau.hazelcast.internal;

import org.apache.deltaspike.core.api.config.ConfigResolver;
import org.apache.deltaspike.core.util.context.ContextualInstanceInfo;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.Extension;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

import static java.util.Arrays.asList;

public class JCacheScopeExtension implements Extension {
    private volatile CachingProvider provider;
    private volatile CacheManager manager;
    private volatile Cache<String, ContextualInstanceInfo<?>> cache;

    void addClusterScope(final @Observes AfterBeanDiscovery afb) {
        afb.addContext(new JCacheContext());
    }

    void addClusterScope(final @Observes BeforeShutdown ignored) {
        // TODO: find a better way to release entries
        if ("true".equalsIgnoreCase(ConfigResolver.getPropertyValue(JCacheScopeExtension.class.getName() + ".jcache.release-nodes", "true"))) {
            for (final Cache.Entry<String, ContextualInstanceInfo<?>> info : getStorage()) {
                info.getValue().getCreationalContext().release();
            }
        }

        for (final Closeable c : asList(cache, manager, provider)) {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (final Exception e) {
                // no-op
            }
        }
    }

    public Cache<String, ContextualInstanceInfo<?>> getStorage() {
        try {
            ensureInstance();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return cache;
    }

    private void ensureInstance() throws IOException {
        if (provider == null) {
            synchronized (this) {
                if (provider == null) {
                    final String prefix = JCacheScopeExtension.class.getName() + ".jcache.";
                    final String uri = ConfigResolver.getPropertyValue(JCacheScopeExtension.class.getName() + ".jcache.config-uri");
                    final String name = ConfigResolver.getPropertyValue(prefix + "name", "jcache-cdi");

                    final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

                    final Properties props = new Properties();
                    props.putAll(ConfigResolver.getAllProperties());

                    provider = Caching.getCachingProvider();
                    try {
                        manager = provider.getCacheManager(
                                uri == null ? provider.getDefaultURI() : new URI(uri),
                                contextClassLoader,
                                props);

                        final MutableConfiguration<String, ContextualInstanceInfo<?>> configuration = new MutableConfiguration<String, ContextualInstanceInfo<?>>()
                                .setReadThrough("true".equalsIgnoreCase(ConfigResolver.getPropertyValue(prefix + "readThrough", "false")))
                                .setWriteThrough("true".equalsIgnoreCase(ConfigResolver.getPropertyValue(prefix + "writeThrough", "false")))
                                .setManagementEnabled("true".equalsIgnoreCase(ConfigResolver.getPropertyValue(prefix + "managementEnabled", "false")))
                                .setStatisticsEnabled("true".equalsIgnoreCase(ConfigResolver.getPropertyValue(prefix + "statisticsEnabled", "false")))
                                .setStoreByValue("true".equalsIgnoreCase(ConfigResolver.getPropertyValue(prefix + "storeByValue", "false")));

                        final String loader = ConfigResolver.getPropertyValue(prefix + "loaderFactory");
                        if (loader != null) {
                            configuration.setCacheLoaderFactory(newInstance(contextClassLoader, loader, Factory.class));
                        }
                        final String writer = ConfigResolver.getPropertyValue(prefix + "writerFactory");
                        if (writer != null) {
                            configuration.setCacheWriterFactory(newInstance(contextClassLoader, writer, Factory.class));
                        }
                        final String expiry = ConfigResolver.getPropertyValue(prefix + "expiryFactory");
                        if (expiry != null) {
                            configuration.setExpiryPolicyFactory(newInstance(contextClassLoader, expiry, Factory.class));
                        }

                        cache = manager.createCache(name, configuration);
                    } catch (final URISyntaxException e) {
                        throw new IllegalArgumentException(e);
                    }
                }
            }
        }
    }

    private static <T> T newInstance(final ClassLoader contextClassLoader, final String clazz,final Class<T> cast) {
        try {
            return (T) contextClassLoader.loadClass(clazz).newInstance();
        } catch (final Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
