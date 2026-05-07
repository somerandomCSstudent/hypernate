package hu.bme.mit.ftsrg.hypernate.registry;

import com.jcabi.aspects.Loggable;

/** defines the interface for registry storage backends */
@Loggable(Loggable.DEBUG)
public interface RegistryStorageBackend {

    <T> T read(Class<T> clazz, Object... keyParts);

    <T> void write(final T entity);

}
