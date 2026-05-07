/* SPDX-License-Identifier: Apache-2.0 */
package hu.bme.mit.ftsrg.hypernate.registry;

import hu.bme.mit.ftsrg.hypernate.annotations.Sharding;
import hu.bme.mit.ftsrg.hypernate.registry.ShardingDefinition;
import hu.bme.mit.ftsrg.hypernate.annotations.AttributeInfo;
import hu.bme.mit.ftsrg.hypernate.annotations.PrimaryKey;
import hu.bme.mit.ftsrg.hypernate.util.JSON;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A storage backend for Hypernate that supports entity sharding.
 * Integrates logic from AssetRepository to split/reassemble entities.
 */
public class ShardingJSONStorageBackend implements RegistryStorageBackend {

    private static final Logger logger = LoggerFactory.getLogger(ShardingJSONStorageBackend.class);
    private static final String SHARD_PREFIX = "SHARD_";
    private static final String MASTER_MARKER = "MASTER";

    private final ChaincodeStub stub;

    public ShardingJSONStorageBackend(final ChaincodeStub stub) {
        this.stub = stub;
    }

    @Override
    public <T> T read(Class<T> clazz, Object... keyParts) throws EntityNotFoundException {
        final String key = constructKey(clazz, keyParts);
        final byte[] data = stub.getState(key);

        if (data == null || data.length == 0) {
            throw new EntityNotFoundException(key);
        }

        String stateString = new String(data, StandardCharsets.UTF_8);

        // Check if the entity class is configured for sharding
        Sharding shardingAnnot = clazz.getAnnotation(Sharding.class);
        if (shardingAnnot != null && MASTER_MARKER.equals(stateString)) {
            logger.debug("Reassembling sharded entity for key: {}", key);
            return reassembleFromShards(clazz, key, shardingAnnot, keyParts);
        }

        // Fallback to standard JSON deserialization if not sharded
        return JSON.deserialize(stateString, clazz);
    }

    @Override
    public <T> void write(final T entity) {
        final Class<?> clazz = entity.getClass();
        final String key = constructKeyFromEntity(entity);

        Sharding shardingAnnot = clazz.getAnnotation(Sharding.class);
        if (shardingAnnot != null) {
            logger.debug("Writing sharded entity for key: {}", key);
            writeShards(entity, key, shardingAnnot);
        } else {
            // Standard write logic for non-sharded entities
            final String data = JSON.serialize(entity);
            stub.putState(key, data.getBytes(StandardCharsets.UTF_8));
        }
    }

    /* --- Sharding Logic Integrated from AssetRepository --- */

    private <T> void writeShards(T entity, String key, Sharding shardingAnnot) {
        try {
            Class<? extends ShardingDefinition> defClass = shardingAnnot.shardingClass();
            ShardingDefinition definition = defClass.getDeclaredConstructor().newInstance();
            List<List<String>> shardConfig = definition.getShards();

            // Shatter the object into shards defined by the configuration
            for (int i = 0; i < shardConfig.size(); i++) {
                List<String> fieldNames = shardConfig.get(i);
                Map<String, Object> shardMap = new HashMap<>();
                for (String fieldName : fieldNames) {
                    shardMap.put(fieldName, getFieldValue(entity, fieldName));
                }

                String shardKey = key + "_" + SHARD_PREFIX + i;
                stub.putState(shardKey, JSON.serialize(shardMap).getBytes(StandardCharsets.UTF_8));
            }

            // Write a "MASTER" marker at the primary key to signal sharding
            stub.putState(key, MASTER_MARKER.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to write sharded entity: " + key, e);
        }
    }

    private <T> T reassembleFromShards(Class<T> clazz, String key, Sharding shardingAnnot, Object[] keyParts) {
        try {
            Class<? extends ShardingDefinition> defClass = shardingAnnot.shardingClass();
            ShardingDefinition definition = defClass.getDeclaredConstructor().newInstance();
            List<List<String>> shardConfig = definition.getShards();

            Map<String, Object> combinedMap = new HashMap<>();

            // Fetch each shard and merge their attributes
            for (int i = 0; i < shardConfig.size(); i++) {
                String shardKey = key + "_" + SHARD_PREFIX + i;
                byte[] shardData = stub.getState(shardKey);
                if (shardData != null && shardData.length > 0) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> shardMap = JSON.deserialize(new String(shardData, StandardCharsets.UTF_8),
                            Map.class);
                    combinedMap.putAll(shardMap);
                }
            }

            // Ensure Primary Key attributes are populated in the map from keyParts
            PrimaryKey pkAnnot = getPrimaryKeyAnnot(clazz);
            AttributeInfo[] attrs = pkAnnot.value();
            for (int i = 0; i < attrs.length; i++) {
                combinedMap.put(attrs[i].name(), keyParts[i]);
            }

            // Convert combined map to the final object using JSON utility
            return JSON.deserialize(JSON.serialize(combinedMap), clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reassemble sharded entity: " + key, e);
        }
    }

    private String constructKey(Class<?> clazz, Object... keyParts) {
        final PrimaryKey pkAnnot = getPrimaryKeyAnnot(clazz);
        final AttributeInfo[] attrInfos = pkAnnot.value();

        if (keyParts.length != attrInfos.length) {
            throw new MissingPrimaryKeysException(
                    String.format("Entity %s requires %d primary key parts, but %d were provided",
                            clazz.getSimpleName(), attrInfos.length, keyParts.length));
        }

        final String[] mappedParts = IntStream.range(0, attrInfos.length)
                .mapToObj(i -> applyAttrMapper(attrInfos[i], keyParts[i]))
                .toArray(String[]::new);

        return stub.createCompositeKey(clazz.getName().toUpperCase(), mappedParts).toString();
    }

    private <T> String constructKeyFromEntity(final T entity) {
        final Class<?> clazz = entity.getClass();
        final PrimaryKey pkAnnot = getPrimaryKeyAnnot(clazz);

        final String[] mappedParts = Arrays.stream(pkAnnot.value())
                .map(attr -> {
                    Object value = getFieldValue(entity, attr.name());
                    return applyAttrMapper(attr, value);
                })
                .toArray(String[]::new);

        return stub.createCompositeKey(clazz.getName().toUpperCase(), mappedParts).toString();
    }

    private PrimaryKey getPrimaryKeyAnnot(Class<?> clazz) {
        PrimaryKey pk = clazz.getAnnotation(PrimaryKey.class);
        if (pk == null) {
            throw new MissingPrimaryKeysException(clazz.getName() + " lacks @PrimaryKey");
        }
        return pk;
    }

    private String applyAttrMapper(AttributeInfo attrInfo, Object keyPart) {
        if (attrInfo.mapper() == Function.class) {
            return keyPart.toString();
        }
        try {
            Constructor<? extends Function<Object, String>> ctor = attrInfo.mapper().getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance().apply(keyPart);
        } catch (Exception e) {
            throw new RuntimeException("Failed to map attribute: " + attrInfo.name(), e);
        }
    }

    private Object getFieldValue(Object entity, String fieldName) {
        try {
            Field field;
            try {
                field = entity.getClass().getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // Fallback to lowercase for compatibility with some sharding configs
                field = entity.getClass().getDeclaredField(fieldName.toLowerCase());
            }
            field.setAccessible(true);
            return field.get(entity);
        } catch (Exception e) {
            throw new RuntimeException("Could not access field: " + fieldName, e);
        }
    }
}