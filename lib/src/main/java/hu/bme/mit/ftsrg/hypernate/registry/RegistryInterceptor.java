package hu.bme.mit.ftsrg.hypernate.registry;

import com.jcabi.aspects.Loggable;

/** defines the interface for registry interceptors */
@Loggable(Loggable.DEBUG)
public interface RegistryInterceptor {

    void mustCreate();

    void mustRead();

    void mustWrite();

}
