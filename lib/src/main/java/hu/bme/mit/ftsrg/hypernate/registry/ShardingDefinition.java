package hu.bme.mit.ftsrg.hypernate.registry;

import java.util.List;

public interface ShardingDefinition {

    List<List<String>> getShards();
}
