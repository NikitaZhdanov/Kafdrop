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

import tech.zhdanov.utils.kafdrop.model.TopicVO;
import tech.zhdanov.utils.kafdrop.model.BrokerVO;
import tech.zhdanov.utils.kafdrop.model.TopicPartitionVO;
import tech.zhdanov.utils.kafdrop.model.ConsumerTopicVO;
import tech.zhdanov.utils.kafdrop.model.ConsumerRegistrationVO;
import tech.zhdanov.utils.kafdrop.model.ConsumerSummaryOffsetsVO;
import tech.zhdanov.utils.kafdrop.model.ConsumerPartitionVO;
import tech.zhdanov.utils.kafdrop.model.GroupListVO;
import tech.zhdanov.utils.kafdrop.model.ConsumerVO;
import tech.zhdanov.utils.kafdrop.model.TopicRegistrationVO;
import tech.zhdanov.utils.kafdrop.model.TopicPartitionStateVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import tech.zhdanov.utils.kafdrop.util.BrokerChannel;
import tech.zhdanov.utils.kafdrop.util.Version;
//import kafka.api.ConsumerMetadataRequest;
import kafka.admin.AdminClient;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.common.ErrorMapping;
import kafka.common.TopicAndPartition;
import kafka.javaapi.*;
import kafka.network.BlockingChannel;
import kafka.utils.ZKGroupDirs;
import kafka.utils.ZKGroupTopicDirs;
import kafka.utils.ZkUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.*;
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import kafka.admin.AdminClient.ConsumerSummary;
import kafka.coordinator.group.GroupOverview;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import scala.collection.JavaConversions;
import scala.collection.JavaConverters;
import tech.zhdanov.utils.kafdrop.model.ClusterSummaryVO;

@Service
public class CuratorKafkaMonitor implements KafkaMonitor
{
   private final Logger LOG = LoggerFactory.getLogger(getClass());

   @Autowired
   private CuratorFramework curatorFramework;

   @Autowired
   private ObjectMapper objectMapper;
   
   @Autowired
   private AdminClient adminClient;
   
   @Autowired
   private KafkaConsumer kafkaConsumer;
    
    @Autowired
    private GroupListVO groupList;

   private PathChildrenCache brokerPathCache;
   private PathChildrenCache topicConfigPathCache;
   private TreeCache topicTreeCache;
   private TreeCache consumerTreeCache;
   //private ArrayList<GroupOverview> consumerGroupsOverview;
   //private ArrayList<ConsumerVO> consumerGroups;
   private NodeCache controllerNodeCache;

   private int controllerId = -1;

   private Map<Integer, BrokerVO> brokerCache = new TreeMap<>();

   private AtomicInteger cacheInitCounter = new AtomicInteger();

   private ForkJoinPool threadPool;

   @Autowired
   private CuratorKafkaMonitorProperties properties;
   private Version kafkaVersion;

   private RetryTemplate retryTemplate;

   @PostConstruct
   public void start() throws Exception
   {
      try
      {
         kafkaVersion = new Version(properties.getKafkaVersion());
      }
      catch (Exception ex)
      {
         throw new IllegalStateException("Invalid kafka version: " + properties.getKafkaVersion(), ex);
      }

      threadPool = new ForkJoinPool(properties.getThreadPoolSize());

      FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
      backOffPolicy.setBackOffPeriod(properties.getRetry().getBackoffMillis());

      final SimpleRetryPolicy retryPolicy =
              new SimpleRetryPolicy(properties.getRetry().getMaxAttempts(),
                      ImmutableMap.of(InterruptedException.class, false,
                              Exception.class, true));

      retryTemplate = new RetryTemplate();
      retryTemplate.setBackOffPolicy(backOffPolicy);
      retryTemplate.setRetryPolicy(retryPolicy);

      cacheInitCounter.set(4);

      brokerPathCache = new PathChildrenCache(curatorFramework, ZkUtils.BrokerIdsPath(), true);
      brokerPathCache.getListenable().addListener(new BrokerListener());
      brokerPathCache.getListenable().addListener((f, e) -> {
         if (e.getType() == PathChildrenCacheEvent.Type.INITIALIZED)
         {
            cacheInitCounter.decrementAndGet();
            LOG.info("Broker cache initialized");
         }
      });
      brokerPathCache.start(StartMode.POST_INITIALIZED_EVENT);

      topicConfigPathCache = new PathChildrenCache(curatorFramework, ZkUtils.ConfigPath(), true);
      topicConfigPathCache.getListenable().addListener((f, e) -> {
         if (e.getType() == PathChildrenCacheEvent.Type.INITIALIZED)
         {
            cacheInitCounter.decrementAndGet();
            LOG.info("Topic configuration cache initialized");
         }
      });
      topicConfigPathCache.start(StartMode.POST_INITIALIZED_EVENT);

      topicTreeCache = new TreeCache(curatorFramework, ZkUtils.BrokerTopicsPath());
      topicTreeCache.getListenable().addListener((client, event) -> {
         if (event.getType() == TreeCacheEvent.Type.INITIALIZED)
         {
            cacheInitCounter.decrementAndGet();
            LOG.info("Topic tree cache initialized");
         }
      });
      topicTreeCache.start();

      consumerTreeCache = new TreeCache(curatorFramework, ZkUtils.ConsumersPath());
      consumerTreeCache.getListenable().addListener((client, event) -> {
         if (event.getType() == TreeCacheEvent.Type.INITIALIZED)
         {
            cacheInitCounter.decrementAndGet();
            LOG.info("Consumer tree cache initialized");
         }
      });
      consumerTreeCache.start();

      controllerNodeCache = new NodeCache(curatorFramework, ZkUtils.ControllerPath());
      controllerNodeCache.getListenable().addListener(this::updateController);
      controllerNodeCache.start(true);
      updateController();
   };

   private String clientId()
   {
      return properties.getClientId();
   }

   private void updateController()
   {
      Optional.ofNullable(controllerNodeCache.getCurrentData())
              .map(data -> {
                 try
                 {
                    Map controllerData = objectMapper.reader(Map.class).readValue(data.getData());
                    return (Integer) controllerData.get("brokerid");
                 }
                 catch (IOException e)
                 {
                    LOG.error("Unable to read controller data", e);
                    return null;
                 }
              })
              .ifPresent(this::updateController);
   }

   private void updateController(int brokerId)
   {
      brokerCache.values()
              .forEach(broker -> broker.setController(broker.getId() == brokerId));
   }

   private void validateInitialized()
   {
      if (cacheInitCounter.get() > 0)
      {
         throw new NotInitializedException();
      }
   }


   @PreDestroy
   public void stop() throws IOException
   {
      consumerTreeCache.close();
      topicConfigPathCache.close();
      brokerPathCache.close();
      controllerNodeCache.close();
   }

   private int brokerId(ChildData input)
   {
      return Integer.parseInt(StringUtils.substringAfter(input.getPath(), ZkUtils.BrokerIdsPath() + "/"));
   }

   private BrokerVO addBroker(BrokerVO broker)
   {
      final BrokerVO oldBroker = brokerCache.put(broker.getId(), broker);
      LOG.info("Kafka broker {} was {}", broker.getId(), oldBroker == null ? "added" : "updated");
      return oldBroker;
   }

   private BrokerVO removeBroker(int brokerId)
   {
      final BrokerVO broker = brokerCache.remove(brokerId);
      LOG.info("Kafka broker {} was removed", broker.getId());
      return broker;
   }

   @Override
   public List<BrokerVO> getBrokers()
   {
      validateInitialized();
      return brokerCache.values().stream().collect(Collectors.toList());
   }

   @Override
   public Optional<BrokerVO> getBroker(int id)
   {
      validateInitialized();
      return Optional.ofNullable(brokerCache.get(id));
   }

   private BrokerChannel brokerChannel(Integer brokerId)
   {
      if (brokerId == null)
      {
         brokerId = randomBroker();
         if (brokerId == null)
         {
            throw new BrokerNotFoundException("No brokers available to select from");
         }
      }

      Integer finalBrokerId = brokerId;
      BrokerVO broker = getBroker(brokerId)
              .orElseThrow(() -> new BrokerNotFoundException("Broker " + finalBrokerId + " is not available"));

      return BrokerChannel.forBroker(broker.getHost(), broker.getPort());
   }

   private Integer randomBroker()
   {
      if (brokerCache.size() > 0)
      {
         List<Integer> brokerIds = brokerCache.keySet().stream().collect(Collectors.toList());
         Collections.shuffle(brokerIds);
         return brokerIds.get(0);
      }
      else
      {
         return null;
      }
   }
   
   public ClusterSummaryVO getClusterSummary()
   {
      return getClusterSummary(getTopics());
   }

   @Override
   public ClusterSummaryVO getClusterSummary(Collection<TopicVO> topics) {
      final ClusterSummaryVO topicSummary = topics.stream()
              .map(topic -> {
                 ClusterSummaryVO summary = new ClusterSummaryVO();
                 summary.setPartitionCount(topic.getPartitions().size());
                 summary.setUnderReplicatedCount(topic.getUnderReplicatedPartitions().size());
                 summary.setPreferredReplicaPercent(topic.getPreferredReplicaPercent());
                 topic.getPartitions()
                         .forEach(partition -> {
                            if (partition.getLeader() != null) {
                               summary.addBrokerLeaderPartition(partition.getLeader().getId());
                            }
                            if (partition.getPreferredLeader() != null) {
                               summary.addBrokerPreferredLeaderPartition(partition.getPreferredLeader().getId());
                            }
                            partition.getReplicas()
                                    .forEach(replica -> summary.addExpectedBrokerId(replica.getId()));
                         });
                 return summary;
              })
              .reduce((s1, s2) -> {
                 s1.setPartitionCount(s1.getPartitionCount() + s2.getPartitionCount());
                 s1.setUnderReplicatedCount(s1.getUnderReplicatedCount() + s2.getUnderReplicatedCount());
                 s1.setPreferredReplicaPercent(s1.getPreferredReplicaPercent() + s2.getPreferredReplicaPercent());
                 s2.getBrokerLeaderPartitionCount().forEach(s1::addBrokerLeaderPartition);
                 s2.getBrokerPreferredLeaderPartitionCount().forEach(s1::addBrokerPreferredLeaderPartition);
                 return s1;
              })
              .orElseGet(ClusterSummaryVO::new);
      topicSummary.setTopicCount(topics.size());
      topicSummary.setPreferredReplicaPercent(topicSummary.getPreferredReplicaPercent() / topics.size());
      return topicSummary;
   }

   @Override
   public List<TopicVO> getTopics()
   {
      validateInitialized();
      final List<TopicVO> topicVOlist;
      topicVOlist = getTopicMetadata().values().stream()
              .sorted(Comparator.comparing(TopicVO::getName))
              .collect(Collectors.toList());
      topicVOlist.forEach(
      vo -> {
                 getTopicPartitionSizes(vo, kafka.api.OffsetRequest.LatestTime())
                         .entrySet()
                         .forEach(entry -> vo.getPartition(entry.getKey()).ifPresent(p -> p.setSize(entry.getValue())));
                 getTopicPartitionSizes(vo, kafka.api.OffsetRequest.EarliestTime())
                         .entrySet()
                         .forEach(entry -> vo.getPartition(entry.getKey()).ifPresent(p -> p.setFirstOffset(entry.getValue())));
              }
      );
      return topicVOlist;
   }
   
   @Override
   public Optional<TopicVO> getTopic(String topic)
   {
      validateInitialized();
      final Optional<TopicVO> topicVO = Optional.ofNullable(getTopicMetadata(topic).get(topic));
      topicVO.ifPresent(
              vo -> {
                 getTopicPartitionSizes(vo, kafka.api.OffsetRequest.LatestTime())
                         .entrySet()
                         .forEach(entry -> vo.getPartition(entry.getKey()).ifPresent(p -> p.setSize(entry.getValue())));
                 getTopicPartitionSizes(vo, kafka.api.OffsetRequest.EarliestTime())
                         .entrySet()
                         .forEach(entry -> vo.getPartition(entry.getKey()).ifPresent(p -> p.setFirstOffset(entry.getValue())));
              }
      );
      return topicVO;
   }

   private Map<String, TopicVO> getTopicMetadata(String... topics)
   {
      if (kafkaVersion.compareTo(new Version(0, 9, 0)) >= 0)
      {
         return retryTemplate.execute(
                 context -> brokerChannel(null)
                         .execute(channel -> getTopicMetadata(channel, topics)));
      }
      else
      {
         Stream<String> topicStream;
         if (topics == null || topics.length == 0)
         {
            topicStream =
                    Optional.ofNullable(
                            topicTreeCache.getCurrentChildren(ZkUtils.BrokerTopicsPath()))
                            .map(Map::keySet)
                            .map(Collection::stream)
                            .orElse(Stream.empty());
         }
         else
         {
            topicStream = Stream.of(topics);
         }

         return topicStream
                 .map(this::getTopicZkData)
                 .filter(Objects::nonNull)
                 .collect(Collectors.toMap(TopicVO::getName, topic -> topic));
      }
   }
   
   private TopicVO getTopicZkData(String topic)
   {
      return Optional.ofNullable(topicTreeCache.getCurrentData(ZkUtils.getTopicPath(topic)))
              .map(this::parseZkTopic)
              .orElse(null);
   }

   public TopicVO parseZkTopic(ChildData input)
   {
      try
      {
         final TopicVO topic = new TopicVO(StringUtils.substringAfterLast(input.getPath(), "/"));

         final TopicRegistrationVO topicRegistration =
                 objectMapper.reader(TopicRegistrationVO.class).readValue(input.getData());

         topic.setConfig(
                 Optional.ofNullable(topicConfigPathCache.getCurrentData(ZkUtils.ConfigPath() + "/" + topic.getName()))
                         .map(this::readTopicConfig)
                         .orElse(Collections.emptyMap()));

         for (Map.Entry<Integer, List<Integer>> entry : topicRegistration.getReplicas().entrySet())
         {
            final int partitionId = entry.getKey();
            final List<Integer> partitionBrokerIds = entry.getValue();

            final TopicPartitionVO partition = new TopicPartitionVO(partitionId);

            final TopicPartitionStateVO partitionState = partitionState(topic.getName(), partition.getId());

            partitionBrokerIds.stream()
                    .map(brokerId -> {
                       TopicPartitionVO.PartitionReplica replica = new TopicPartitionVO.PartitionReplica();
                       replica.setId(brokerId);
                       replica.setInService(partitionState.getIsr().contains(brokerId));
                       replica.setLeader(brokerId == partitionState.getLeader());
                       return replica;
                    })
                    .forEach(partition::addReplica);

            topic.addPartition(partition);
         }

         // todo: get partition sizes here as single bulk request?

         return topic;
      }
      catch (IOException e)
      {
         throw Throwables.propagate(e);
      }
   }

   private Map<String, TopicVO> getTopicMetadata(BlockingChannel channel, String... topics)
   {
      final TopicMetadataRequest request =
              new TopicMetadataRequest((short) 0, 0, clientId(), Arrays.asList(topics));

      LOG.debug("Sending topic metadata request: {}", request);

      channel.send(request);
      final kafka.api.TopicMetadataResponse underlyingResponse =
              kafka.api.TopicMetadataResponse.readFrom(channel.receive().payload());

      LOG.debug("Received topic metadata response: {}", underlyingResponse);

      TopicMetadataResponse response = new TopicMetadataResponse(underlyingResponse);
      return response.topicsMetadata().stream()
              .filter(tmd -> tmd.errorCode() == ErrorMapping.NoError())
              .map(this::processTopicMetadata)
              .collect(Collectors.toMap(TopicVO::getName, t -> t));
   }

   private TopicVO processTopicMetadata(TopicMetadata tmd)
   {
      TopicVO topic = new TopicVO(tmd.topic());

      topic.setConfig(
              Optional.ofNullable(topicConfigPathCache.getCurrentData(ZkUtils.ConfigPath() + "/" + topic.getName()))
                      .map(this::readTopicConfig)
                      .orElse(Collections.emptyMap()));

      topic.setPartitions(
              tmd.partitionsMetadata().stream()
                      .map((pmd) -> parsePartitionMetadata(tmd.topic(), pmd))
                      .collect(Collectors.toMap(TopicPartitionVO::getId, p -> p))
      );
      return topic;
   }

   private TopicPartitionVO parsePartitionMetadata(String topic, PartitionMetadata pmd)
   {
      TopicPartitionVO partition = new TopicPartitionVO(pmd.partitionId());
      if (pmd.leader() != null)
      {
         partition.addReplica(new TopicPartitionVO.PartitionReplica(pmd.leader().id(), true, true));
      }

      /*final List<Integer> isr = getIsr(topic, pmd);*/
      pmd.replicas().stream()
              .map(replica -> new TopicPartitionVO.PartitionReplica(replica.id(), pmd.isr().stream().anyMatch(t -> t.id()==replica.id()), false))
              .forEach(partition::addReplica);
      return partition;
   }

   /*private List<Integer> getIsr(String topic, PartitionMetadata pmd)
   {
      return pmd.isr().stream().map(Broker::id).collect(Collectors.toList());
   }*/

   private Map<String, Object> readTopicConfig(ChildData d)
   {
      try
      {
         final Map<String, Object> configData = objectMapper.reader(Map.class).readValue(d.getData());
         return (Map<String, Object>) configData.get("config");
      }
      catch (IOException e)
      {
         throw Throwables.propagate(e);
      }
   }


   private TopicPartitionStateVO partitionState(String topicName, int partitionId)
           throws IOException
   {
      return objectMapper.reader(TopicPartitionStateVO.class).readValue(
              topicTreeCache.getCurrentData(
                      ZkUtils.getTopicPartitionLeaderAndIsrPath(topicName, partitionId))
                      .getData());
   }

   @Override
   public List<ConsumerVO> getConsumers()
   {
      validateInitialized();
      return getConsumerStream(null).collect(Collectors.toList());
   }

    @Override
    public List<ConsumerVO> getConsumers(final TopicVO topic) {
        
        validateInitialized();
        Stream<ConsumerVO> consumerVOStream = getConsumerStream(topic);
        List<ConsumerVO> consumerVOS = consumerVOStream.collect(Collectors.toList());
        return consumerVOS;
    }

    @Override
    public List<ConsumerVO> getConsumers(final String topic)
    {
        return getConsumers(getTopic(topic).get());
    }

    private Stream<ConsumerVO> getConsumerStream(TopicVO topic)
    {
        List<ConsumerVO> consumerGroups = new ArrayList<>();
        
        List<GroupOverview> consumerGroupsOverview = new ArrayList(groupList.getConsumers().values());
        
        consumerGroupsOverview.forEach((GroupOverview cgo) -> {
            LOG.debug("Get all consumers of the consumer group");
            // Get all consumers of the consumer group
            scala.collection.immutable.List<ConsumerSummary> scsl = 
                adminClient.describeConsumerGroup(cgo.groupId(), 60000).consumers().get();
            Collection<ConsumerSummary> css = 
                    JavaConverters.asJavaCollectionConverter(scsl).
                            asJavaCollection();
            LOG.debug("ConsumerSummary collection size: " + Integer.toString(css.size()));
            // Get all offsets for the group
            Map<TopicPartition, Object> consumerOffsets = 
                    JavaConverters.mapAsJavaMapConverter(
                            adminClient.listGroupOffsets(cgo.groupId())).asJava();
            // Merge consumers and offsets by partition
            Map<TopicPartition, ConsumerSummaryOffsetsVO> consumersSummary = new HashMap();
            List<ConsumerRegistrationVO> consumerRegistrations = new ArrayList<>();
            for (ConsumerSummary cs:css) {
                // Get topic partitions
                Collection<TopicPartition> tps = JavaConversions.asJavaCollection(cs.assignment());
                // Add active instances
                LOG.debug("Add active instances");
                ConsumerRegistrationVO consumerRegistration = 
                        new ConsumerRegistrationVO(
                                cs.consumerId());
                consumerRegistration.setSubscriptions(
                        tps.stream().collect(Collectors.toMap(k -> k.topic() + k.partition(), v -> v.partition())));
                consumerRegistrations.add(consumerRegistration);
                for (TopicPartition tp:tps) {
                    if (topic == null || topic.getName().equals(tp.topic())) {
                        ConsumerSummaryOffsetsVO cso = new ConsumerSummaryOffsetsVO();
                        cso.setConsumerSummary(cs);
                        consumersSummary.put(tp, cso);
                    }
                }
            }
            for (Map.Entry<TopicPartition, Object> o:consumerOffsets.entrySet()) {
                if (topic == null || topic.getName().equals(o.getKey().topic())) {
                    ConsumerSummaryOffsetsVO cso = consumersSummary.get(o.getKey());
                    if (cso == null) {
                        cso = new ConsumerSummaryOffsetsVO();
                    }
                    cso.setOffset((Long) o.getValue());
                    consumersSummary.put(o.getKey(), cso);
                }
            }
            LOG.debug("ConsumersSummary merged size: " + consumersSummary.size());
            if (consumersSummary.size() > 0) {
                // Create new ConsumerGroup
                ConsumerVO consumer = new ConsumerVO(cgo.groupId());
                // New TopicPartitionVO List
                ArrayList<ConsumerTopicVO> consumerTopics = new ArrayList<>();
                // For each topicPartition
                // Getting beggining and end offsets
                Map<TopicPartition, Long> begginingOffsets = 
                        kafkaConsumer.beginningOffsets(consumersSummary.keySet());
                Map<TopicPartition, Long> endOffsets = 
                        kafkaConsumer.endOffsets(consumersSummary.keySet());
                consumersSummary.entrySet().forEach(consumerSummary -> {
                    // Filling the ConsumerVO structure
                    // Create new ConsumerPartitionVO
                    ConsumerPartitionVO consumerPartition = 
                            new ConsumerPartitionVO(
                                    cgo.groupId(), 
                                    consumerSummary.getKey().topic(), 
                                    consumerSummary.getKey().partition());
                    LOG.debug("Current topic partition: ", consumerSummary.getKey().partition());
                    // Check if topic already exists in TopicPartitionVO List
                    if (!consumerTopics.stream().filter(
                            t -> t.getTopic().equals(consumerSummary.getKey().topic())).
                            findAny().isPresent()) {
                        // If not then add one
                        LOG.debug("Adding ConsumerTopicVO to list");
                        ConsumerTopicVO ct = new ConsumerTopicVO(consumerSummary.getKey().topic());
                        /*Map<Integer, Long> consumerOffsets = 
                                getConsumerOffsets(cgo.groupId(), 
                                            new TopicVO(topicPartition.topic()));*/
                        consumerTopics.add(ct);
                    }
                    Long firstOffset = begginingOffsets.getOrDefault(consumerSummary.getKey(), 0L);
                    LOG.debug("First offset: " + firstOffset);
                    // Set consumer's offset of the partition
                    LOG.debug("Consumer partition offset: " + consumerSummary.getValue().getOffset());
                    if (consumerSummary.getValue().getOffset() != null) {
                        consumerPartition.setOffset(consumerSummary.getValue().getOffset());
                    }
                    else {
                        consumerPartition.setOffset(firstOffset);
                    }
                    // Set the beggining and end offsets
                    consumerPartition.setFirstOffset(firstOffset);
                    LOG.debug("End offset: " + endOffsets.getOrDefault(consumerSummary.getKey(), firstOffset));
                    consumerPartition.setSize(endOffsets.getOrDefault(consumerSummary.getKey(), firstOffset));
                    LOG.debug("Lag: " + consumerPartition.getLag());
                    consumerTopics.stream().filter(
                            t -> t.getTopic().equals(consumerSummary.getKey().topic())).
                            forEach(t -> {
                                t.addOffset(consumerPartition);
                            });
                });
                // Add ConsumerTopicVO list to ConsumerVO
                consumerTopics.stream().forEach(ct -> {
                    consumer.addTopic(ct);
                });
                // Add active instances
                consumerRegistrations.forEach(cr -> {
                    consumer.addActiveInstance(cr);
                });
                LOG.debug("Consumer Topics size: " + consumer.getTopics().stream().count());
                // Add ConsumerVO to list
                consumerGroups.add(consumer);
            }
        });
        
        LOG.debug("consumerGroups: " + Integer.toString(consumerGroups.size()));
       
        return consumerGroups.stream();
        
        /*return consumerTreeCache.getCurrentChildren(ZkUtils.ConsumersPath()).keySet().stream()
                .map(g -> getConsumerByTopic(g, topic))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(ConsumerVO::getGroupId));*/
    }

   @Override
   public Optional<ConsumerVO> getConsumer(String groupId)
   {
      validateInitialized();
      return getConsumerByTopic(groupId, null);
   }

   @Override
   public Optional<ConsumerVO> getConsumerByTopicName(String groupId, String topicName)
   {
      return getConsumerByTopic(groupId, Optional.of(topicName).flatMap(this::getTopic).orElse(null));
   }

   @Override
   public Optional<ConsumerVO> getConsumerByTopic(String groupId, TopicVO topic)
   {
      final ConsumerVO consumer = new ConsumerVO(groupId);
      final ZKGroupDirs groupDirs = new ZKGroupDirs(groupId);

      //if (consumerTreeCache.getCurrentData(groupDirs.consumerGroupDir()) == null) return Optional.empty();

      // todo: get number of threads in each instance (subscription -> topic -> # threads)
      
      /*Optional.ofNullable(consumerTreeCache.getCurrentChildren(groupDirs.consumerRegistryDir()))
              .ifPresent(
                      children ->
                              children.keySet().stream()
                                      .map(id -> readConsumerRegistration(groupDirs, id))
                                      .forEach(consumer::addActiveInstance));*/
      
        // Getting all consumers of the consumer group (all nodes of consumer)
        scala.collection.immutable.List<ConsumerSummary> scsl = 
            adminClient.describeConsumerGroup(groupId, 60000).consumers().
                getOrElse(null);
        JavaConverters.asJavaIterableConverter(scsl).asJava().
                forEach(consumerSummary -> {
                    ConsumerRegistrationVO id = new ConsumerRegistrationVO(consumerSummary.clientId());
                    consumer.addActiveInstance(id);
        });

        Stream<String> topicStream = null;
        
        Map<TopicPartition, Object> groupOffsets = (Map) JavaConverters.mapAsJavaMapConverter(
                    adminClient.listGroupOffsets(groupId)).asJava();
        
        if (topic != null)
        {
            Stream<Map.Entry<TopicPartition, Object>> filter = groupOffsets.entrySet().stream().filter(map -> map.getKey().topic().equals(topic));
            //if (consumerTreeCache.getCurrentData(groupDirs.consumerGroupDir() + "/owners/" + topic.getName()) != null)
            if (filter.count() > 0)
            {
                topicStream = Stream.of(topic.getName());
            }
            else
            {
                topicStream = Stream.empty();
            }
        }
        else
        {
            /*topicStream = Optional.ofNullable(
                 consumerTreeCache.getCurrentChildren(groupDirs.consumerGroupDir() + "/owners"))
                 .map(Map::keySet)
                 .map(Collection::stream)
                 .orElse(Stream.empty());*/
            if (!groupOffsets.isEmpty()) {
                ArrayList<String> topics = new ArrayList<>();
                groupOffsets.keySet().forEach((topicPartition) -> {
                    topics.add(topicPartition.topic());
                });
                topicStream = topics.stream();
            }
            else {
                Stream.empty();
            }
        }

      Stream<ConsumerTopicVO> consumerTopicVOStream;
      consumerTopicVOStream = topicStream.map(topic1 -> new ConsumerTopicVO(topic1));
      for(ConsumerTopicVO cc : consumerTopicVOStream.collect(Collectors.toList())){
         getConsumerPartitionStream(groupId, cc.getTopic(), topic).forEach(cc::addOffset);
         consumer.addTopic(cc);
      }
/*      topicStream.map(ConsumerTopicVO::new).forEach(consumerTopic -> {
                 getConsumerPartitionStream(groupId, consumerTopic.getTopic(), topic).forEach(consumerTopic::addOffset);
                 consumer.addTopic(consumerTopic);
              });*/

      return Optional.of(consumer);
   }

   /*private ConsumerRegistrationVO readConsumerRegistration(ZKGroupDirs groupDirs, String id)
   {
      try
      {
         ChildData data = consumerTreeCache.getCurrentData(groupDirs.consumerRegistryDir() + "/" + id);
         final Map<String, Object> consumerData = objectMapper.reader(Map.class).readValue(data.getData());
         Map<String, Integer> subscriptions = (Map<String, Integer>) consumerData.get("subscription");

         ConsumerRegistrationVO vo = new ConsumerRegistrationVO(id);
         vo.setSubscriptions(subscriptions);
         return vo;
      }
      catch (IOException ex)
      {
         throw Throwables.propagate(ex);
      }
   }*/

    private Stream<ConsumerPartitionVO> getConsumerPartitionStream(String groupId,
                                                                  String topicName,
                                                                  TopicVO topicOpt)
    {
        ZKGroupTopicDirs groupTopicDirs = new ZKGroupTopicDirs(groupId, topicName);

        if (topicOpt == null || topicOpt.getName().equals(topicName))
        {
            topicOpt = getTopic(topicName).orElse(null);
        }

        if (topicOpt != null)
        {
            final TopicVO topic = topicOpt;
            
            // Get consumer's offsets from all topics
            Map<TopicPartition, Object> allConsumerOffsets = 
                    JavaConverters.mapAsJavaMapConverter(
                            adminClient.listGroupOffsets(groupId)).asJava();
            // Filter offsets by topic
            Map<Integer, Long> consumerOffsets = allConsumerOffsets.entrySet().stream().
                    filter(co -> co.getKey().topic().equals(topic.getName())).
                    collect(Collectors.toMap(p -> p.getKey().partition(), p -> (Long) p.getValue()));

            return topic.getPartitions().stream().map(partition -> {
                int partitionId = partition.getId();
                
                final ConsumerPartitionVO consumerPartition = new ConsumerPartitionVO(groupId, topicName, partitionId);
                consumerPartition.setOwner(groupId);

                consumerPartition.setOffset(consumerOffsets.getOrDefault(partitionId, -1L));

                final Optional<TopicPartitionVO> topicPartition = topic.getPartition(partitionId);
                consumerPartition.setSize(topicPartition.map(TopicPartitionVO::getSize).orElse(-1L));
                consumerPartition.setFirstOffset(topicPartition.map(TopicPartitionVO::getFirstOffset).orElse(-1L));

                return consumerPartition;
            });
        }
        else
        {
            return Stream.empty();
        }
    }

   private Map<Integer, Long> getTopicPartitionSizes(TopicVO topic)
   {
      return getTopicPartitionSizes(topic, kafka.api.OffsetRequest.LatestTime());
   }

   private Map<Integer, Long> getTopicPartitionSizes(TopicVO topic, long time)
   {
      try
      {
         PartitionOffsetRequestInfo requestInfo = new PartitionOffsetRequestInfo(time, 1);

         return threadPool.submit(() ->
                 topic.getPartitions().parallelStream()
                         .filter(p -> p.getLeader() != null)
                         .collect(Collectors.groupingBy(p -> p.getLeader().getId())) // Group partitions by leader broker id
                         .entrySet().parallelStream()
                         .map(entry -> {
                            final Integer brokerId = entry.getKey();
                            final List<TopicPartitionVO> brokerPartitions = entry.getValue();
                            try
                            {
                               // Get the size of the partitions for a topic from the leader.
                               final OffsetResponse offsetResponse =
                                       sendOffsetRequest(brokerId, topic, requestInfo, brokerPartitions);


                               // Build a map of partitionId -> topic size from the response
                               return brokerPartitions.stream()
                                       .collect(Collectors.toMap(TopicPartitionVO::getId,
                                               partition -> Optional.ofNullable(
                                                       offsetResponse.offsets(topic.getName(), partition.getId()))
                                                       .map(Arrays::stream)
                                                       .orElse(LongStream.empty())
                                                       .findFirst()
                                                       .orElse(-1L)));
                            }
                            catch (Exception ex)
                            {
                               LOG.error("Unable to get partition log size for topic {} partitions ({})",
                                       topic.getName(),
                                       brokerPartitions.stream()
                                               .map(TopicPartitionVO::getId)
                                               .map(String::valueOf)
                                               .collect(Collectors.joining(",")),
                                       ex);

                               // Map each partition to -1, indicating we got an error
                               return brokerPartitions.stream().collect(Collectors.toMap(TopicPartitionVO::getId, tp -> -1L));
                            }
                         })
                         .map(Map::entrySet)
                         .flatMap(Collection::stream)
                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                 .get();
      }
      catch (InterruptedException e)
      {
         Thread.currentThread().interrupt();
         throw Throwables.propagate(e);
      }
      catch (ExecutionException e)
      {
         throw Throwables.propagate(e.getCause());
      }
   }

   private OffsetResponse sendOffsetRequest(Integer brokerId, TopicVO topic,
                                            PartitionOffsetRequestInfo requestInfo,
                                            List<TopicPartitionVO> brokerPartitions)
   {
      final OffsetRequest offsetRequest = new OffsetRequest(
              brokerPartitions.stream()
                      .collect(Collectors.toMap(
                              partition -> new TopicAndPartition(topic.getName(), partition.getId()),
                              partition -> requestInfo)),
              (short) 0, clientId());

      LOG.debug("Sending offset request: {}", offsetRequest);

      return retryTemplate.execute(
              context ->
                      brokerChannel(brokerId)
                              .execute(channel ->
                              {
                                 channel.send(offsetRequest.underlying());
                                 final kafka.api.OffsetResponse underlyingResponse =
                                         kafka.api.OffsetResponse.readFrom(channel.receive().payload());

                                 LOG.debug("Received offset response: {}", underlyingResponse);

                                 return new OffsetResponse(underlyingResponse);
                              }));
   }

   private class BrokerListener implements PathChildrenCacheListener
   {
      @Override
      public void childEvent(CuratorFramework framework, PathChildrenCacheEvent event) throws Exception
      {
         switch (event.getType())
         {
            case CHILD_REMOVED:
            {
               BrokerVO broker = removeBroker(brokerId(event.getData()));
               break;
            }

            case CHILD_ADDED:
            case CHILD_UPDATED:
            {
               addBroker(parseBroker(event.getData()));
               break;
            }

            case INITIALIZED:
            {
               brokerPathCache.getCurrentData().stream()
                       .map(BrokerListener.this::parseBroker)
                       .forEach(CuratorKafkaMonitor.this::addBroker);
               break;
            }
         }
         updateController();
      }

      private int brokerId(ChildData input)
      {
         return Integer.parseInt(StringUtils.substringAfter(input.getPath(), ZkUtils.BrokerIdsPath() + "/"));
      }


      private BrokerVO parseBroker(ChildData input)
      {
         try
         {
            final BrokerVO broker = objectMapper.reader(BrokerVO.class).readValue(input.getData());
            broker.setId(brokerId(input));
            return broker;
         }
         catch (IOException e)
         {
            throw Throwables.propagate(e);
         }
      }
   }

}
