/*
 * Copyright 2018 Nikita Zhdanov.
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
package tech.zhdanov.utils.kafdrop.config;

import tech.zhdanov.utils.kafdrop.model.GroupListVO;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import kafka.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.hibernate.validator.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 *
 * @author NZhdanov
 */
@Configuration
public class KafkaConfiguration {
    
    private final Logger LOG = LoggerFactory.getLogger(getClass());

    private GroupListVO groups;
    
    @Autowired
    private KafkaProperties kafkaProperties;
    
    @Bean
    public AdminClient adminClient() {
        Properties acProps = new Properties();
        acProps.put("bootstrap.servers", kafkaProperties.getConnect());
        return AdminClient.create(acProps);
    }
    
    @Bean
    public KafkaConsumer utilityConsumer() {
        Properties cProps = new Properties();
        cProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getConnect());
        cProps.put(ConsumerConfig.GROUP_ID_CONFIG, "UtilityConsumer");
        cProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        return new KafkaConsumer(cProps);
    }
    
    @Component
    @ConfigurationProperties(prefix = "kafka")
    public static class KafkaProperties {
        @NotBlank
        private String connect;
        private Integer consumingThreads;
        
        public String getConnect()
        {
            return connect;
        }
        public void setConnect(String connect)
        {
            this.connect = connect;
        }
        
    }
    
    @Bean
    public GroupListVO getConsumers() {
        if (groups == null) groups = new GroupListVO();
        return groups;
    }
    
}
