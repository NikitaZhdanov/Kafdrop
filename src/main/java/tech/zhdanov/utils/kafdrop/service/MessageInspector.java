/*
 * Copyright 2017 HomeAdvisor, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package tech.zhdanov.utils.kafdrop.service;

import tech.zhdanov.utils.kafdrop.model.BrokerVO;
import tech.zhdanov.utils.kafdrop.model.MessageVO;
import tech.zhdanov.utils.kafdrop.model.TopicPartitionVO;
import tech.zhdanov.utils.kafdrop.model.TopicVO;
import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import kafka.message.MessageAndOffset;

@Service
public class MessageInspector
{
   private final Logger LOG = LoggerFactory.getLogger(getClass());

   @Autowired
   private KafkaMonitor kafkaMonitor;

   public List<MessageVO> getMessages(String topicName, String key, String body, long msgLimit)
   {
      final TopicVO topic = kafkaMonitor.getTopic(topicName).orElseThrow(TopicNotFoundException::new);
      
      List<MessageVO> allMessages = new ArrayList<>();
      
      for (TopicPartitionVO partition : topic.getPartitions()) {
          
          long size = partition.getSize() - partition.getFirstOffset();
          
          if (size > 0) {
          
            BrokerVO broker = kafkaMonitor.getBroker(partition.getLeader().getId()).get();
            
            SimpleConsumer consumer = new SimpleConsumer(broker.getHost(), broker.getPort(), 10000, 100000, "");
            
            try {
                final FetchRequestBuilder fetchRequestBuilder = new FetchRequestBuilder()
                       .clientId("KafDrop")
                       .maxWait(5000) // todo: make configurable
                       .minBytes(1);

                long currentOffset = partition.getFirstOffset();

                List<MessageVO> messages = new ArrayList<>();

                Long fetchedMsgsCount = 0L;

                while (fetchedMsgsCount < size && messages.size() < msgLimit) {
                    final FetchRequest fetchRequest =
                        fetchRequestBuilder
                             .addFetch(topicName, partition.getId(), currentOffset, 1024 * 1024)
                             .build();

                    FetchResponse fetchResponse = consumer.fetch(fetchRequest);

                    ByteBufferMessageSet messageSet = fetchResponse.messageSet(topicName, partition.getId());
                    if (messageSet.validBytes() <= 0) break;
                    fetchedMsgsCount = fetchedMsgsCount + StreamSupport.stream(messageSet.spliterator(), false).count();

                    int oldSize = messages.size();
                    List<MessageAndOffset> messageAndOffsetList = 
                            StreamSupport.stream(messageSet.spliterator(), false).collect(Collectors.toList());
                    if (!key.isEmpty()) {
                        // Filter messages by key
                        messageAndOffsetList = messageAndOffsetList.stream().
                                filter(ms -> {
                                    if (ms.message().hasKey()) {
                                        return readString(ms.message().key()).equals(key);
                                    }
                                    else {
                                        return false;
                                    }
                                }).collect(Collectors.toList());
                    }

                    if (!body.isEmpty()) {
                        messageAndOffsetList = messageAndOffsetList.stream().
                                filter(ms -> {
                                    if (!ms.message().isNull()) {
                                        Boolean result = false;
                                        try {
                                            result = readString(ms.message().payload()).matches(body);
                                        }
                                        catch (Exception ex) {
                                            LOG.warn(ex.getMessage(), ex);
                                        }
                                        finally {
                                            return result;
                                        }
                                    }
                                    else {
                                        return false;
                                    }
                                }).collect(Collectors.toList());
                    }

                    messageAndOffsetList = messageAndOffsetList.stream().
                            limit(Long.min(size, msgLimit) - messages.size()).
                            collect(Collectors.toList());

                    messageAndOffsetList.forEach(messageAndOffset -> {
                           messages.add(
                                   createMessage(
                                           messageAndOffset.message(),
                                           messageAndOffset.offset(),
                                           partition.getId()
                                   ));
                       });
                    currentOffset += messages.size() - oldSize;
                 }

                allMessages.addAll(messages);
            }
            catch (Exception ex) {
                LOG.error("Exception when fetching messages: ", ex);
            }
            finally {
                consumer.close();
            }
          }
        
        /*allMessages.addAll(kafkaMonitor.getBroker(partition.getLeader().getId())
           .map(broker -> {
              SimpleConsumer consumer = new SimpleConsumer(broker.getHost(), broker.getPort(), 10000, 100000, "");

              final FetchRequestBuilder fetchRequestBuilder = new FetchRequestBuilder()
                 .clientId("KafDrop")
                 .maxWait(5000) // todo: make configurable
                 .minBytes(1);

              List<MessageVO> messages = new ArrayList<>();
              long currentOffset = offset;
              while (messages.size() < count)
              {
                 final FetchRequest fetchRequest =
                    fetchRequestBuilder
                       .addFetch(topicName, partition.getId(), currentOffset, 1024 * 1024)
                       .build();

                 FetchResponse fetchResponse = consumer.fetch(fetchRequest);

                 final ByteBufferMessageSet messageSet = fetchResponse.messageSet(topicName, partition.getId());
                 if (messageSet.validBytes() <= 0) break;


                 int oldSize = messages.size();
                 StreamSupport.stream(messageSet.spliterator(), false)
                    .limit(count - messages.size())
                    .map(MessageAndOffset::message)
                    .map(this::createMessage)
                    .forEach(messages::add);
                 currentOffset += messages.size() - oldSize;
              }
              return messages;
           })
           .orElseGet(Collections::emptyList)
        );*/
      }
      return allMessages;
   }

   private MessageVO createMessage(Message message, Long offset, Integer partition)
   {
      MessageVO vo = new MessageVO();
      if (message.hasKey())
      {
         vo.setKey(readString(message.key()));
      }
      if (!message.isNull())
      {
         vo.setMessage(readString(message.payload()));
      }

      vo.setValid(message.isValid());
      vo.setCompressionCodec(message.compressionCodec().name());
      vo.setChecksum(message.checksum());
      vo.setComputedChecksum(message.computeChecksum());
      vo.setOffset(offset);
      vo.setTimestamp(message.timestamp());
      vo.setPartition(partition);

      return vo;
   }

   private String readString(ByteBuffer buffer)
   {
      try
      {
         return new String(readBytes(buffer), "UTF-8");
      }
      catch (UnsupportedEncodingException e)
      {
         return "<unsupported encoding>";
      }
   }

   private byte[] readBytes(ByteBuffer buffer)
   {
      return readBytes(buffer, 0, buffer.limit());
   }

   private byte[] readBytes(ByteBuffer buffer, int offset, int size)
   {
      byte[] dest = new byte[size];
      if (buffer.hasArray())
      {
         System.arraycopy(buffer.array(), buffer.arrayOffset() + offset, dest, 0, size);
      }
      else
      {
         buffer.mark();
         buffer.get(dest);
         buffer.reset();
      }
      return dest;
   }

}
