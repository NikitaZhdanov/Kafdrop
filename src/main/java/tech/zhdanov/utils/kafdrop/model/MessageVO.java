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

package tech.zhdanov.utils.kafdrop.model;

import java.util.Date;

public class MessageVO
{
   private String message;
   private String key;
   private Boolean valid;
   private Long checksum;
   private Long computedChecksum;
   private String compressionCodec;
   private Long offset;
   private Long timestamp;
   private Integer partition;

   public Boolean isValid()
   {
      return valid;
   }

   public void setValid(Boolean valid)
   {
      this.valid = valid;
   }

   public String getMessage()
   {
      return message;
   }

   public void setMessage(String message)
   {
      this.message = message;
   }

   public String getKey()
   {
      return key;
   }

   public void setKey(String key)
   {
      this.key = key;
   }

   public Long getChecksum()
   {
      return checksum;
   }

   public void setChecksum(Long checksum)
   {
      this.checksum = checksum;
   }

   public Long getComputedChecksum()
   {
      return computedChecksum;
   }

   public void setComputedChecksum(Long computedChecksum)
   {
      this.computedChecksum = computedChecksum;
   }

   public String getCompressionCodec()
   {
      return compressionCodec;
   }

   public void setCompressionCodec(String compressionCodec)
   {
      this.compressionCodec = compressionCodec;
   }
   
   public Long getOffset() {
       return offset;
   }
   public void setOffset(Long offset) {
       this.offset = offset;
   }
   
   public Long getTimestamp() {
       return timestamp;
   }
   public void setTimestamp(Long timestamp) {
       this.timestamp = timestamp;
   }
   
   public Date getDateTime() {
       if (!timestamp.equals(-1)) {
           return new Date(timestamp);
       }
       else {
           return null;
       }
   }
   
   public Integer getPartition() {
       return partition;
   }
   public void setPartition(Integer partition) {
       this.partition = partition;
   }
}
