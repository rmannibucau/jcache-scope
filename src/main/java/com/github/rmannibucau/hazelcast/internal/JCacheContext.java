package com.github.rmannibucau.hazelcast.internal;

import com.github.rmannibucau.hazelcast.api.JCacheScoped;
import org.apache.commons.proxy2.Interceptor;
import org.apache.commons.proxy2.Invocation;
import org.apache.commons.proxy2.asm.ASMProxyFactory;
import org.apache.deltaspike.core.api.provider.BeanProvider;
import org.apache.deltaspike.core.util.context.ContextualInstanceInfo;

import javax.cache.Cache;
import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.PassivationCapable;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;

public class JCacheContext implements Context {
    private Cache<String, ContextualInstanceInfo<?>> storage;

    @Override
    public Class<? extends Annotation> getScope() {
        return JCacheScoped.class;
    }

    @Override
    public <T> T get(final Contextual<T> bean) {
        final Cache<String, ContextualInstanceInfo<?>> contextMap = storage();
        final ContextualInstanceInfo<T> contextualInstanceInfo = (ContextualInstanceInfo<T>) contextMap.get(getBeanKey(bean));
        if (contextualInstanceInfo == null) {
            return null;
        }
        return proxy((T) contextualInstanceInfo.getContextualInstance(), getBeanKey(bean), contextualInstanceInfo);
    }

    @Override
    public <T> T get(final Contextual<T> bean, final CreationalContext<T> creationalContext) {
        if (creationalContext == null) {
            return null;
        }
        if (!PassivationCapable.class.isInstance(bean)) {
            throw new IllegalStateException(bean + " doesn't implement " + PassivationCapable.class.getName());
        }

        final String beanKey = getBeanKey(bean);
        final Cache<String, ContextualInstanceInfo<?>> concurrentMap = storage();
        ContextualInstanceInfo<T> instanceInfo = (ContextualInstanceInfo<T>) concurrentMap.get(beanKey);
        if (instanceInfo == null) {
            instanceInfo = new ContextualInstanceInfo<T>();
            final ContextualInstanceInfo<T> old = (ContextualInstanceInfo<T>) concurrentMap.getAndPut(beanKey, instanceInfo);
            if (old != null) {
                instanceInfo = old;
                concurrentMap.put(beanKey, instanceInfo);
            }
        }

        T instance = instanceInfo.getContextualInstance();
        if (instance == null) {
            instance = bean.create(creationalContext);
            instanceInfo.setContextualInstance(instance);
            instanceInfo.setCreationalContext(creationalContext);
        }
        return proxy(instance, beanKey, instanceInfo);
    }

    private <T> T proxy(final T instance, final String beanKey, final ContextualInstanceInfo<T> instanceInfo) {
        final ASMProxyFactory factory = new ASMProxyFactory();
        return (T) factory.createInterceptorProxy(instance.getClass().getClassLoader(), instance, new Interceptor() {
            @Override
            public Object intercept(final Invocation invocation) throws Throwable {
                if (Serializable.class == invocation.getMethod().getDeclaringClass()) {
                    try {
                        return invocation.getMethod().invoke(instance, invocation.getArguments());
                    } catch (final InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                }

                try {
                    return invocation.proceed();
                } catch (final InvocationTargetException ite) {
                    throw ite.getCause();
                } finally {
                    storage().put(beanKey, instanceInfo);
                }
            }
        }, new Class<?>[] { instance.getClass(), Serializable.class });
    }

    private Cache<String, ContextualInstanceInfo<?>> storage() {
        if (storage == null) {
            storage = BeanProvider.getContextualReference(JCacheScopeExtension.class).getStorage();
        }
        return storage;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    public static <T> String getBeanKey(final Contextual<T> bean) {
        return PassivationCapable.class.cast(bean).getId();
    }
}
