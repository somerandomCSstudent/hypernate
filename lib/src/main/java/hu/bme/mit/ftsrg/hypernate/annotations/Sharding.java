package hu.bme.mit.ftsrg.hypernate.annotations;

import hu.bme.mit.ftsrg.hypernate.registry.ShardingDefinition;

public @interface Sharding {
    public Class<? extends ShardingDefinition> shardingClass();
}
