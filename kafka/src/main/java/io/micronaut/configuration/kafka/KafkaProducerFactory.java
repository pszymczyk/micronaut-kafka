/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.configuration.kafka;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.config.AbstractKafkaProducerConfiguration;
import io.micronaut.configuration.kafka.config.DefaultKafkaProducerConfiguration;
import io.micronaut.configuration.kafka.serde.SerdeRegistry;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Any;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ArgumentInjectionPoint;
import io.micronaut.inject.FieldInjectionPoint;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A registry class for Kafka {@link org.apache.kafka.clients.producer.Producer} instances.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Factory
public class KafkaProducerFactory implements ProducerRegistry, TransactionalProducerRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaProducerFactory.class);
    private final ThreadLocal<Map<ClientKey, Producer>> transactionalClients = ThreadLocal.withInitial(() -> new HashMap<>());
    private final AtomicInteger transactionIdSuffix = new AtomicInteger();
    private final Map<ClientKey, Producer> clients = new ConcurrentHashMap<>();
    private final BeanContext beanContext;
    private final SerdeRegistry serdeRegistry;
    private final ProducerFactory producerFactory;

    /**
     * Default constructor.
     *
     * @param beanContext     The bean context
     * @param serdeRegistry   The serde registry
     * @param producerFactory The producer factory
     */
    public KafkaProducerFactory(
        BeanContext beanContext,
        SerdeRegistry serdeRegistry,
        ProducerFactory producerFactory
    ) {
        this.beanContext = beanContext;
        this.serdeRegistry = serdeRegistry;
        this.producerFactory = producerFactory;
    }

    /**
     * Creates a new {@link KafkaProducer} for the given configuration.
     *
     * @param injectionPoint        The injection point used to create the bean
     * @param producerConfiguration An optional producer configuration
     * @param <K>                   The key type
     * @param <V>                   The value type
     * @return The consumer
     */
    @Bean
    @Any
    public <K, V> Producer<K, V> getProducer(
        @Nullable InjectionPoint<KafkaProducer<K, V>> injectionPoint,
        @Nullable @Parameter AbstractKafkaProducerConfiguration<K, V> producerConfiguration) {
        if (injectionPoint == null) {
            if (producerConfiguration != null) {
                Optional<Serializer<K>> keySerializer = producerConfiguration.getKeySerializer();
                Optional<Serializer<V>> valueSerializer = producerConfiguration.getValueSerializer();

                Properties config = producerConfiguration.getConfig();
                if (keySerializer.isPresent() && valueSerializer.isPresent()) {
                    Serializer<K> ks = keySerializer.get();
                    Serializer<V> vs = valueSerializer.get();
                    return producerFactory.createProducer(config, ks, vs);
                } else if (keySerializer.isPresent() || valueSerializer.isPresent()) {
                    throw new ConfigurationException("Both the [keySerializer] and [valueSerializer] must be set when setting either");
                } else {
                    return producerFactory.createProducer(config, null, null);
                }
            } else {
                throw new ConfigurationException("No Kafka configuration specified when using direct instantiation");
            }
        }

        Argument<?> argument;
        if (injectionPoint instanceof FieldInjectionPoint) {
            argument = ((FieldInjectionPoint<?, ?>) injectionPoint).asArgument();
        } else if (injectionPoint instanceof ArgumentInjectionPoint) {
            argument = ((ArgumentInjectionPoint<?, ?>) injectionPoint).getArgument();
        } else {
            throw new ConfigurationException("Cannot directly retrieve KafkaProducer instances. Use @Inject or constructor injection");
        }

        Argument<?> k = argument.getTypeVariable("K").orElse(null);
        Argument<?> v = argument.getTypeVariable("V").orElse(null);

        if (k == null || v == null) {
            throw new ConfigurationException("@KafkaClient used on type missing generic argument values for Key and Value: " + injectionPoint);
        }

        AnnotationMetadata annotationMetadata = injectionPoint.getAnnotationMetadata();
        final String id = annotationMetadata.stringValue(KafkaClient.class).orElse(null);

        Map<String, String> properties = new HashMap<>();

        annotationMetadata.findAnnotation(KafkaClient.class)
            .map(ann -> ann.getProperties("properties", "name"))
            .ifPresent(properties::putAll);

        annotationMetadata.getValue(KafkaClient.class, "maxBlock", Duration.class)
            .ifPresent(duration -> properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(duration.toMillis())));

        int acks = annotationMetadata.intValue(KafkaClient.class, "acks").orElse(KafkaClient.Acknowledge.DEFAULT);
        if (acks != KafkaClient.Acknowledge.DEFAULT) {
            String acksValue = acks == -1 ? "all" : String.valueOf(acks);
            properties.put(ProducerConfig.ACKS_CONFIG, acksValue);
        }

        return getKafkaProducer(id, null, k, v, properties);
    }

    @SuppressWarnings("unchecked")
    private <T> T getKafkaProducer(@Nullable String id, @Nullable String transactionalId, Argument<?> keyType, Argument<?> valueType, @Nullable Map<String, String> props) {
        ClientKey key = new ClientKey(
            id,
            keyType.getType(),
            valueType.getType(),
            transactionalId);

        if (StringUtils.isNotEmpty(transactionalId)) {
            Map<ClientKey, Producer> clientKeyProducerMap = transactionalClients.get();

            if (clientKeyProducerMap.containsKey(key)) {
                return (T) clientKeyProducerMap.get(key);
            } else {
                Supplier<AbstractKafkaProducerConfiguration> defaultResolver = () -> beanContext.getBean(AbstractKafkaProducerConfiguration.class);
                AbstractKafkaProducerConfiguration config;
                boolean hasId = StringUtils.isNotEmpty(id);
                if (hasId) {
                    config = beanContext.findBean(
                        AbstractKafkaProducerConfiguration.class,
                        Qualifiers.byName(id)
                    ).orElseGet(defaultResolver);
                } else {
                    config = defaultResolver.get();
                }

                DefaultKafkaProducerConfiguration newConfig = new DefaultKafkaProducerConfiguration(config);

                Properties properties = newConfig.getConfig();
                if (!properties.containsKey(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)) {
                    Serializer<?> keySerializer = serdeRegistry.pickSerializer(keyType);
                    newConfig.setKeySerializer(keySerializer);
                }

                if (!properties.containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)) {
                    Serializer<?> valueSerializer = serdeRegistry.pickSerializer(valueType);
                    newConfig.setValueSerializer(valueSerializer);
                }


                properties.putIfAbsent(ProducerConfig.TRANSACTIONAL_ID_CONFIG, String.format("%s-%s", transactionalId, transactionIdSuffix.incrementAndGet()));
                properties.putIfAbsent(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

                if (hasId) {
                    properties.putIfAbsent(ProducerConfig.CLIENT_ID_CONFIG, id);
                }

                if (CollectionUtils.isNotEmpty(props)) {
                    properties.putAll(props);
                }

                Producer producer = beanContext.createBean(Producer.class, newConfig);
                producer.initTransactions();
                clientKeyProducerMap.put(key, producer);
                return (T) producer;
            }
        }

        return (T) clients.computeIfAbsent(key, clientKey -> {
            Supplier<AbstractKafkaProducerConfiguration> defaultResolver = () -> beanContext.getBean(AbstractKafkaProducerConfiguration.class);
            AbstractKafkaProducerConfiguration config;
            boolean hasId = StringUtils.isNotEmpty(id);
            if (hasId) {
                config = beanContext.findBean(
                    AbstractKafkaProducerConfiguration.class,
                    Qualifiers.byName(id)
                ).orElseGet(defaultResolver);
            } else {
                config = defaultResolver.get();
            }

            DefaultKafkaProducerConfiguration newConfig = new DefaultKafkaProducerConfiguration(config);

            Properties properties = newConfig.getConfig();
            if (!properties.containsKey(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)) {
                Serializer<?> keySerializer = serdeRegistry.pickSerializer(keyType);
                newConfig.setKeySerializer(keySerializer);
            }

            if (!properties.containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)) {
                Serializer<?> valueSerializer = serdeRegistry.pickSerializer(valueType);
                newConfig.setValueSerializer(valueSerializer);
            }

            if (hasId) {
                properties.putIfAbsent(ProducerConfig.CLIENT_ID_CONFIG, id);
            }

            if (CollectionUtils.isNotEmpty(props)) {
                properties.putAll(props);
            }

            return beanContext.createBean(Producer.class, newConfig);
        });
    }

    /**
     * Shuts down any existing clients.
     */
    @PreDestroy
    protected void stop() {
        stop(clients.values());
        clients.clear();
        stop(transactionalClients.get().values());
        transactionalClients.get().clear();
    }

    private  void stop(Collection<Producer> clients) {
        for (Producer producer : clients) {
            try {
                producer.close();
            } catch (Exception e) {
                LOG.warn("Error shutting down Kafka producer: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public <K, V> Producer<K, V> getProducer(String id, Argument<K> keyType, Argument<V> valueType) {
        return getKafkaProducer(id, null, keyType, valueType, null);
    }

    @Override
    public <K, V> Producer<K, V> getTransactionalProducer(String id, String transactionalId, Argument<K> keyType, Argument<V> valueType) {
        return getKafkaProducer(id, transactionalId, keyType, valueType, null);
    }

    @Override
    public void close(Producer<?, ?> producer) {
        close(producer, clients.entrySet());
        close(producer, transactionalClients.get().entrySet());
    }

    private void close(Producer<?, ?> producer, Set<Map.Entry<ClientKey, Producer>> clients) {
        for (Map.Entry<ClientKey, Producer> e : clients) {
            if (e.getValue() == producer) {
                clients.remove(e.getKey());
                break;
            }
        }
    }

    /**
     * key for retrieving built producers.
     *
     * @author Graeme Rocher
     * @since 1.0
     */
    private static final class ClientKey {
        private final String id;
        private final Class<?> keyType;
        private final Class<?> valueType;
        private final String transactionalId;

        ClientKey(String id, Class<?> keyType, Class<?> valueType, String transactionalId) {
            this.id = id;
            this.keyType = keyType;
            this.valueType = valueType;
            this.transactionalId = transactionalId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ClientKey clientKey = (ClientKey) o;
            return Objects.equals(id, clientKey.id) &&
                Objects.equals(keyType, clientKey.keyType) &&
                Objects.equals(valueType, clientKey.valueType) &&
                Objects.equals(transactionalId, clientKey.transactionalId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, keyType, valueType, transactionalId);
        }
    }
}
