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
package tech.zhdanov.utils.kafdrop.model;

import kafka.admin.AdminClient.ConsumerSummary;

/**
 *
 * @author NZhdanov
 */
public class ConsumerSummaryOffsetsVO {
    
    private ConsumerSummary consumerSummary;
    
    private Long offset;
    
    public ConsumerSummary getConsumerSummary() {
        return consumerSummary;
    }
    
    public void setConsumerSummary(ConsumerSummary consumerSummary) {
        this.consumerSummary = consumerSummary;
    }
    
    public Long getOffset() {
        return offset;
    }
    
    public void setOffset(Long offset) {
        this.offset = offset;
    }
    
}
