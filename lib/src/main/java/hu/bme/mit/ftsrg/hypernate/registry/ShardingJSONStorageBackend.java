package hu.bme.mit.ftsrg.hypernate.registry;

public class ShardingJSONStorageBackend implements RegistryStorageBackend {

    @Override
    public <T> T read(Class<T> clazz, Object... keyParts) {
        return null;
    }

    @Override
    public <T> void write(final T entity) {
    }

}
