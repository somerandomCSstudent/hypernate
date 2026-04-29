package hu.bme.mit.ftsrg.hypernate.annotations;

import hu.bme.mit.ftsrg.hypernate.registry.RegistryInterceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

//** defines annotation for registry interceptor information */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RegistryInterceptorInfo {
    String name();

    Class<? extends RegistryInterceptor> func();
}
