/* Copyright 2017 HomeAdvisor, Inc. Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License. */
package tech.zhdanov.utils.kafdrop.controller;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import tech.zhdanov.utils.kafdrop.model.MessageVO;
import tech.zhdanov.utils.kafdrop.model.TopicPartitionVO;
import tech.zhdanov.utils.kafdrop.model.TopicVO;
import tech.zhdanov.utils.kafdrop.service.KafkaMonitor;
import tech.zhdanov.utils.kafdrop.service.NewMessageInspector;
import tech.zhdanov.utils.kafdrop.service.TopicNotFoundException;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;

@Controller
public class MessageController {
    @Autowired
    private KafkaMonitor kafkaMonitor;
    @Autowired
    private NewMessageInspector messageInspector;

    /**
     * Human friendly view of reading messages. @param topicName   Name of topic @param messageForm Message form for submitting requests to view messages. @param errors @param model @return View for seeing messages in a partition.
     */
    @RequestMapping(method = RequestMethod.GET, value = "/topic/{name:.+}/messages")
    public String viewMessageForm(@PathVariable("name") String topicName,
                                  @Valid @ModelAttribute("messageForm") PartitionOffsetInfo messageForm,
                                  BindingResult errors, Model model) {
        if (messageForm.isEmpty()) {
            final PartitionOffsetInfo defaultForm = new PartitionOffsetInfo();
            defaultForm.setCount(100L);
            defaultForm.setOffset(0L);
            model.addAttribute("messageForm", defaultForm);
        }
        final TopicVO topic = kafkaMonitor.getTopic(topicName).orElseThrow(() -> new TopicNotFoundException(topicName));
        model.addAttribute("topic", topic);

        if (!messageForm.isEmpty() && !errors.hasErrors())
            model.addAttribute("messages", messageInspector.getMessages(
                    topicName, 
                    messageForm.getKey(), 
                    messageForm.getValue(),
                    messageForm.getCount(),
                    messageForm.getOffset()));
        return "message-inspector";
    }

    /**
     * Return a JSON list of all partition offset info for the given topic. If specific partition and offset parameters are given, then this returns actual kafka messages from that partition (if the offsets are valid; if invalid offsets are passed then the message list is empty). @param topicName Name of topic. @return Offset or message data.
     * @param topicName
     * @param key
     * @param value
     * @param count
     * @param offset
     * @return 
     */
    @ApiOperation(value = "getPartitionOrMessages", notes = "Get offset or message data for a topic. Without query params returns all partitions with offset data. With query params, returns actual messages (if valid offsets are provided).")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "Success", response = List.class), @ApiResponse(code = 404, message = "Invalid topic name")})
    @RequestMapping(method = RequestMethod.GET, value = "/topic/{name:.+}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody
    List<Object> getPartitionOrMessages(@PathVariable("name") String topicName,
                                        @RequestParam(name = "key", required = false) String key,
                                        @RequestParam(name = "value", required = false) String value,
                                        @RequestParam(name = "count", required = false) Long count,
                                        @RequestParam(name = "offset", required = false) Long offset) {
        if (key == null && count == null) {
            final TopicVO topic = kafkaMonitor.getTopic(topicName).orElseThrow(() -> new TopicNotFoundException(topicName));
            List<Object> partitionList = new ArrayList<>();
            for (TopicPartitionVO vo : topic.getPartitions()) {
                partitionList.add(new PartitionOffsetInfo(vo.getId(), vo.getFirstOffset(), vo.getSize()));
            }
            return partitionList;
        } else {
            List<Object> messages = new ArrayList<>();
            List<MessageVO> vos = messageInspector.getMessages(topicName, key, value, count, offset);
            if (vos != null) vos.stream().forEach(vo -> messages.add(vo));
            return messages;
        }
    }

    /**
     * Encapsulates offset data for a single partition.
     */
    public static class PartitionOffsetInfo {
        @Min(0)
        private Integer partition;
        /**
         * Need to clean this up. We're re-using this form for the JSON message API and it's a bit confusing to have the Java variable and JSON field named differently.
         */

        @Min(0)
        @JsonProperty("offset")
        private Long offset;
        /**
         * Need to clean this up. We're re-using this form for the JSON message API and it's a bit confusing to have the Java variable and JSON field named differently.
         */

        @Min(1)
        @Max(100000)
        @JsonProperty("count")
        private Long count;
        
        private String key;
        
        private String value;

        public PartitionOffsetInfo(int partition, long offset, long count) {
            this.partition = partition;
            this.offset = offset;
            this.count = count;
        }

        public PartitionOffsetInfo() {
        }

        @JsonIgnore
        public boolean isEmpty() {
            return count == null && key == null && value == null;
        }

        public Integer getPartition() {
            return partition;
        }

        public void setPartition(Integer partition) {
            this.partition = partition;
        }

        public Long getOffset() {
            return offset;
        }

        public void setOffset(Long offset) {
            this.offset = offset;
        }

        public Long getCount() {
            return count;
        }

        public void setCount(Long count) {
            this.count = count;
        }
        
        public String getKey() {
            return key;
        }
        public void setKey(String key) {
            this.key = key;
        }
        
        public String getValue() {
            return value;
        }
        public void setValue(String value) {
            this.value = value;
        }
    }
}
