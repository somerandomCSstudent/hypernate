/* SPDX-License-Identifier: Apache-2.0 */
package hu.bme.mit.ftsrg.hypernate.registry;

import hu.bme.mit.ftsrg.hypernate.annotations.AttributeInfo;
import hu.bme.mit.ftsrg.hypernate.annotations.PrimaryKey;
import hu.bme.mit.ftsrg.hypernate.util.JSON;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleJSONStorageBackend implements RegistryStorageBackend {

    private static final Logger logger = LoggerFactory.getLogger(SimpleJSONStorageBackend.class);
    private final ChaincodeStub stub;

    public SimpleJSONStorageBackend(final ChaincodeStub stub) {
        this.stub = stub;
    }

    /**
     * Read an existing entity.
     *
     * @param clazz    the class of the entity
     * @param keyParts the list of primary keys identifying the entity
     * @return the entity read and deserialized from the ledger
     * @param <T> the entity type
     * @throws EntityNotFoundException if an entity with the given primary keys was
     *                                 not found
     */
    @Override
    public <T> T read(Class<T> clazz, Object... keyParts) throws EntityNotFoundException {
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

        final String key = stub.createCompositeKey(clazz.getName().toUpperCase(), mappedParts).toString();

        final byte[] data = stub.getState(key);
        if (data == null || data.length == 0) {
            throw new EntityNotFoundException(key);
        }

        return JSON.deserialize(new String(data, StandardCharsets.UTF_8), clazz);
    }

    /**
     * Write an entity to the ledger.
     *
     * @param entity the entity to write
     * @param <T>    the entity type
     */
    @Override
    public <T> void write(final T entity) {
        final Class<?> clazz = entity.getClass();
        final PrimaryKey pkAnnot = getPrimaryKeyAnnot(clazz);

        final String[] mappedParts = Arrays.stream(pkAnnot.value())
                .map(attr -> {
                    Object value = getFieldValue(entity, attr.name());
                    return applyAttrMapper(attr, value);
                })
                .toArray(String[]::new);

        final String key = stub.createCompositeKey(clazz.getName().toUpperCase(), mappedParts).toString();
        final String data = JSON.serialize(entity);

        stub.putState(key, data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Get the primary key annotation for a class.
     *
     * @param clazz the class to check
     * @return the primary key annotation
     * @throws MissingPrimaryKeysException if the class lacks a primary key
     */
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
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(entity);
        } catch (Exception e) {
            throw new RuntimeException("Could not access field: " + fieldName, e);
        }
    }
}