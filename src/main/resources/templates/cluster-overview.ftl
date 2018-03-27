<#import "lib/template.ftl" as template>
<@template.header "Broker List"/>

<#setting number_format="0">
<div>
    <h2>Kafka Cluster Overview</h2>

    <div id="zookeeper">
        <b>Zookeeper Hosts:</b> <#list zookeeper.connectList as z>${z}<#if z_has_next>, </#if></#list>
    </div>

    <div id="brokers">
        <h3>Brokers</h3>
        <table class="bs-table default">
            <thead>
                <tr>
                    <th>ID</th>
                    <th>Host</th>
                    <th>Port</th>
                    <th>JMX Port</th>
                    <th>Version</th>
                    <th>Start Time</th>
                    <th>Controller?</th>
                </tr>
            </thead>
            <tbody>
                <#if brokers?size == 0>
                    <tr>
                        <td class="error" colspan="7">No brokers available!</td>
                    </tr>
                </#if>
                <#list brokers as b>
                    <tr>
                        <td><a href="/broker/${b.id}"><i class="fa fa-info-circle fa-lg"></i> ${b.id}</a></td>
                        <td>${b.host}</td>
                        <td>${b.port?string}</td>
                        <td>${b.jmxPort?string}</td>
                        <td>${b.version}</td>
                        <td>${b.timestamp?string["yyyy-MM-dd HH:mm:ss.SSSZ"]}</td>
                        <td><@template.yn b.controller/></td>
                    </tr>
                </#list>
            </tbody>
        </table>
    </div>

    <div id="topics">
        <h3>Topics</h3>
        <table class="bs-table default">
            <thead>
                <tr>
                    <th>Name</th>
                    <th>Total Size</th>
                    <th>Current Size</th>
                    <th>Partitions</th>
                    <th>% Preferred</th>
                    <th># Under Replicated</th>
                    <th>Custom Config?</th>
                <#--<th>Consumers</th>-->
                </tr>
            </thead>
            <tbody>
                <#if topics?size == 0>
                    <tr>
                        <td colspan="5">No topics available</td>
                    </tr>
                </#if>
            <#list topics as t>
                <tr>
                    <td><a href="/topic/${t.name}">${t.name}</a></td>
                    <td>${t.totalSize}</td>
                    <td>${t.availableSize}</td>
                    <td>${t.partitions?size}</td>
                    <td <#if t.preferredReplicaPercent lt 1.0>class="warn"</#if>>${t.preferredReplicaPercent?string.percent}</td>
                    <td <#if t.underReplicatedPartitions?size gt 0>class="warn"</#if>>${t.underReplicatedPartitions?size}</td>
                    <td><@template.yn t.config?size gt 0/></td>
                <#--<td>${t.consumers![]?size}</td>-->
                </tr>
            </#list>
            </tbody>
        </table>
    </div>
    
    <div id="consumers" class="col sevencol">
        <h2>Consumers</h2>
        <table id="consumers-table" class="bs-table small">
            <thead>
            <tr>
                <th>Group Id</th>
                <th>Lag</th>
                <th>Consuming Topics</th>
                <th>Active Instances</th>
            </tr>
            </thead>
            <tbody>
            <#list consumers![] as c>
                <tr>
                    <#assign rowSpan=c.topics?size>
                    <td rowspan="${rowSpan}"><a href="/consumer/${c.groupId}">${c.groupId}</a></td>
                    <#list c.topics as ct>
                        <#if ct?index==0>
                            <td>${ct.lag}</td>
                            <td><a href="/topic/${ct.topic}">${ct.topic}</td>
                            <td>
                                <ul class="bs-list flat">
                                    <#list c.getActiveInstancesForTopic(ct.topic) as ai>
                                        <li>${ai.id}</li>
                                    </#list>
                                </ul>
                            </td>
                        <#else>
                            <tr>
                                <td>${ct.lag}</td>
                                <td><a href="/topic/${ct.topic}">${ct.topic}</td>
                                <td>
                                    <ul class="bs-list flat">
                                        <#list c.getActiveInstancesForTopic(ct.topic) as ai>
                                            <li>${ai.id}</li>
                                        </#list>
                                    </ul>
                                </td>
                            </tr>
                        </#if>
                    </#list>
                </tr>
            </#list>
            </tbody>
        </table>
    </div> <!-- consumers -->
    
</div>

<@template.footer/>
