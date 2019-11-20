package tech.zhdanov.utils.kafdrop.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tech.zhdanov.utils.kafdrop.config.KafkaConfiguration;
import tech.zhdanov.utils.kafdrop.model.MessageVO;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
public class NewMessageInspector {
    private final Logger LOG = LoggerFactory.getLogger(getClass());

    private Properties props = new Properties();

    @Autowired
    public NewMessageInspector(KafkaConfiguration.KafkaProperties kafkaProperties) {
        props.put("bootstrap.servers", kafkaProperties.getConnect());
        props.put("group.id", "KafdropMessageInspector");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    }

    public List<MessageVO> getMessages(String topicName, String key, String body, long msgLimit, Long offset) {

        offset = offset == null ? 0L : offset;

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);

        List<TopicPartition> partitions = consumer.partitionsFor(topicName)
                .stream().map(p -> new TopicPartition(topicName, p.partition()))
                .collect(Collectors.toList());

        Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
        consumer.assign(partitions);


        consumer.poll(10);// because assign is a fuckin lazy peace of shit

        long msgCount = 0L;

        for (TopicPartition partition : partitions) {
            Long bo = beginningOffsets.get(partition);
            Long eo = endOffsets.get(partition);
            long partitionMsgCount = 0L;
            if (offset < bo) {
                consumer.seekToBeginning(Collections.singletonList(partition));
                partitionMsgCount = eo - bo;
            } else if (offset > eo) {
                consumer.seekToEnd(Collections.singletonList(partition));
            } else {
                consumer.seek(partition, offset);
                partitionMsgCount = eo - offset;
            }
            msgCount += partitionMsgCount;
        }

        List<MessageVO> messages = new ArrayList<>();
        if (msgCount>0L) {
            if (StringUtils.isEmpty(key) && StringUtils.isEmpty(body)) {
                messages = findAllMessages(consumer, msgLimit, partitions, endOffsets);
            } else {
                if (!StringUtils.isEmpty(key))
                    messages = findMessageByKey(key, consumer, msgLimit, partitions, endOffsets);
                else {
                    messages = findMessagesByBody(body, consumer, msgLimit, partitions, endOffsets);
                }
            }
        }

        consumer.unsubscribe();
        consumer.close();
        return messages;
    }

    private List<MessageVO> findAllMessages(KafkaConsumer<String, String> consumer, long msgLimit,
                                            List<TopicPartition> partitions, Map<TopicPartition, Long> endOffsets) {
        List<MessageVO> allMessages = new ArrayList<>();
        while (true) {
            final ConsumerRecords<String, String> records = consumer.poll(60000);

            if (records.count() == 0) {
                return allMessages;
            }

            final Stream<ConsumerRecord<String, String>> recordStream = StreamSupport.stream(records.spliterator(), false);

            final List<MessageVO> messages = recordStream
                    .map(this::createMessage)
                    .collect(Collectors.toList());

            allMessages.addAll(messages);
            if (checkPartitionsReachedTheEnd(partitions, endOffsets, consumer)) {
                return allMessages;
            }
            if (allMessages.size() >= msgLimit) {
                return allMessages;
            }
        }
    }

    private List<MessageVO> findMessagesByBody(String body, KafkaConsumer<String, String> consumer, long msgLimit,
                                               List<TopicPartition> partitions, Map<TopicPartition, Long> endOffsets) {

        List<MessageVO> allMessages = new ArrayList<>();
        while (true) {

            final ConsumerRecords<String, String> records = consumer.poll(60000);

            if (records.count() == 0) {
                return allMessages;
            }

            final Stream<ConsumerRecord<String, String>> recordStream = StreamSupport.stream(records.spliterator(), false);

            final List<MessageVO> messages = recordStream
                    .filter(r -> r.value().contains(body))
                    .map(this::createMessage)
                    .collect(Collectors.toList());
            allMessages.addAll(messages);
            if (checkPartitionsReachedTheEnd(partitions, endOffsets, consumer)) {
                return allMessages;
            }
            if (allMessages.size() >= msgLimit) {
                return allMessages;
            }
        }
    }

    private List<MessageVO> findMessageByKey(String key, KafkaConsumer<String, String> consumer, long msgLimit, List<TopicPartition> partitions, Map<TopicPartition, Long> endOffsets) {
        while (true) {

            final ConsumerRecords<String, String> records = consumer.poll(300000);

            if (records.count() == 0)
                return Collections.emptyList();

            final Stream<ConsumerRecord<String, String>> recordStream = StreamSupport.stream(records.spliterator(), false);

            final List<MessageVO> messages = recordStream
                    .filter(r -> r.key().equals(key))
                    .map(this::createMessage)
                    .collect(Collectors.toList());

            if (messages.size() > 0)
                return messages;

            if (checkPartitionsReachedTheEnd(partitions, endOffsets, consumer)) {
                return messages;
            }
        }

    }

    private boolean checkPartitionsReachedTheEnd(List<TopicPartition> partitions, Map<TopicPartition, Long> endOffsets,
                                                 KafkaConsumer<String, String> consumer) {
        int partitionNum = partitions.size();
        for (TopicPartition partition : partitions) {
            if (consumer.position(partition) >= endOffsets.get(partition)) {
                partitionNum--;
            }
        }
        return (partitionNum == 0);
    }

    private MessageVO createMessage(ConsumerRecord<String, String> record) {

        MessageVO message = new MessageVO();
        message.setKey(record.key());
        message.setMessage(record.value());
        message.setOffset(record.offset());
        message.setPartition(record.partition());
        message.setTimestamp(record.timestamp());
        record.headers().forEach(header -> message.getHeaders().add(new MessageVO.Header(header.key(), new String(header.value()))));

        return message;
    }

}
