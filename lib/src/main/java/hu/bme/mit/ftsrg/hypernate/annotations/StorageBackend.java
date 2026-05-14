package hu.bme.mit.ftsrg.hypernate.annotations;

import hu.bme.mit.ftsrg.hypernate.registry.RegistryStorageBackend;

public @interface StorageBackend {
    public Class<? extends RegistryStorageBackend> storageClass();
}
