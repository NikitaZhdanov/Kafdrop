package tech.zhdanov.utils.kafdrop.scheduler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import kafka.admin.AdminClient;
import kafka.coordinator.group.GroupOverview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import scala.collection.JavaConversions;
import tech.zhdanov.utils.kafdrop.model.GroupListVO;

/**
 *
 * @author NZhdanov
 */
@Component
public class ConsumerGroupsScheduler {
    
    private final Logger LOG = LoggerFactory.getLogger(getClass());
    
    @Autowired
    private ApplicationContext context;
    @Autowired
    private AdminClient adminClient;
    
    @Scheduled(fixedRate = 5000)
    public void updateGroupsScheduler() {
        
        // Getting the bean context
        GroupListVO groups  = context.getBean(GroupListVO.class);
        // Get current kafka consumers list
        Collection<GroupOverview> goc = 
                JavaConversions.asJavaCollection(
                        adminClient.listAllConsumerGroupsFlattened());
        // Collect to map for easy merge
        Map<String, GroupOverview> newGroups = 
                goc.stream().collect(
                        Collectors.toMap(GroupOverview::groupId, go -> go));
        // Initialize the result map
        Map<String, GroupOverview> mergedGroups;
        if (groups.getConsumers() == null) {
            mergedGroups = new HashMap<>();
        }
        else {
            mergedGroups = groups.getConsumers();
        }
        for (Map.Entry<String, GroupOverview> newGroup:newGroups.entrySet()) {
            mergedGroups.putIfAbsent(newGroup.getKey(), newGroup.getValue());
        }
        groups.setConsumers(mergedGroups);
        LOG.debug("Updated consumers list, total consumers: " + mergedGroups);
    }
    
}
