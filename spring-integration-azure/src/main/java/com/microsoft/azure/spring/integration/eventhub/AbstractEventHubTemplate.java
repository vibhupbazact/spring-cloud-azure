/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.spring.integration.eventhub;

import com.google.common.base.Strings;
import com.microsoft.azure.eventhubs.EventData;
import com.microsoft.azure.eventhubs.EventHubClient;
import com.microsoft.azure.eventhubs.EventPosition;
import com.microsoft.azure.eventprocessorhost.EventProcessorHost;
import com.microsoft.azure.eventprocessorhost.EventProcessorOptions;
import com.microsoft.azure.spring.cloud.context.core.util.Tuple;
import com.microsoft.azure.spring.integration.core.api.CheckpointMode;
import com.microsoft.azure.spring.integration.core.api.PartitionSupplier;
import com.microsoft.azure.spring.integration.core.api.StartPosition;
import com.microsoft.azure.spring.integration.eventhub.converter.EventHubMessageConverter;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Abstract base implementation of event hub template.
 *
 * <p>
 * The main event hub component for sending to and consuming from event hub
 *
 * @author Warren Zhu
 */
public class AbstractEventHubTemplate {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractEventHubTemplate.class);
    private final EventHubClientFactory clientFactory;

    @Getter
    @Setter
    private EventHubMessageConverter messageConverter = new EventHubMessageConverter();

    @Getter
    @Setter
    private StartPosition startPosition = StartPosition.LATEST;

    @Setter
    @Getter
    private CheckpointMode checkpointMode = CheckpointMode.BATCH;

    AbstractEventHubTemplate(EventHubClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    private static EventProcessorOptions buildEventProcessorOptions(StartPosition startPosition) {
        EventProcessorOptions options = EventProcessorOptions.getDefaultOptions();

        if (startPosition == StartPosition.EARLISET) {
            options.setInitialPositionProvider((s) -> EventPosition.fromStartOfStream());
        } else /* StartPosition.LATEST */ {
            options.setInitialPositionProvider((s) -> EventPosition.fromEndOfStream());
        }

        return options;
    }

    public <T> CompletableFuture<Void> sendAsync(String eventHubName, @NonNull Message<T> message,
            PartitionSupplier partitionSupplier) {
        return sendAsync(eventHubName, Collections.singleton(message), partitionSupplier);
    }

    public <T> CompletableFuture<Void> sendAsync(String eventHubName, Collection<Message<T>> messages,
            PartitionSupplier partitionSupplier) {
        Assert.hasText(eventHubName, "eventHubName can't be null or empty");
        List<EventData> eventData = messages.stream().map(m -> messageConverter.fromMessage(m, EventData.class))
                                            .collect(Collectors.toList());
        return doSend(eventHubName, partitionSupplier, eventData);
    }

    private CompletableFuture<Void> doSend(String eventHubName, PartitionSupplier partitionSupplier,
            List<EventData> eventData) {
        try {
            EventHubClient client = this.clientFactory.getEventHubClientCreator().apply(eventHubName);

            if (partitionSupplier == null) {
                return client.send(eventData);
            } else if (!Strings.isNullOrEmpty(partitionSupplier.getPartitionId())) {
                return this.clientFactory.getPartitionSenderCreator()
                                         .apply(Tuple.of(client, partitionSupplier.getPartitionId())).send(eventData);
            } else if (!Strings.isNullOrEmpty(partitionSupplier.getPartitionKey())) {
                return client.send(eventData, partitionSupplier.getPartitionKey());
            } else {
                return client.send(eventData);
            }
        } catch (EventHubRuntimeException e) {
            LOGGER.error(String.format("Failed to send to '%s' ", eventHubName), e);
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    protected void register(Tuple<String, String> nameAndGroup, EventHubProcessor eventProcessor) {
        EventProcessorHost host = this.clientFactory.getProcessorHostCreator().apply(nameAndGroup);
        host.registerEventProcessorFactory(context -> eventProcessor, buildEventProcessorOptions(startPosition));
    }

    protected void unregister(Tuple<String, String> nameAndGroup) {
        this.clientFactory.getProcessorHostCreator().apply(nameAndGroup).unregisterEventProcessor()
                          .whenComplete((s, t) -> {
                              if (t != null) {
                                  LOGGER.warn(String.format("Failed to unregister consumer '%s' with group '%s'",
                                          nameAndGroup.getFirst(), nameAndGroup.getSecond()), t);
                              }
                          });
    }

}
