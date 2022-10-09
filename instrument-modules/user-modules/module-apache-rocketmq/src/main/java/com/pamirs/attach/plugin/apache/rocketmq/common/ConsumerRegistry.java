/**
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pamirs.attach.plugin.apache.rocketmq.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pamirs.pradar.ErrorTypeEnum;
import com.pamirs.pradar.Pradar;
import com.pamirs.pradar.PradarSwitcher;
import com.pamirs.pradar.pressurement.agent.event.IEvent;
import com.pamirs.pradar.pressurement.agent.event.impl.ClusterTestSwitchOffEvent;
import com.pamirs.pradar.pressurement.agent.event.impl.ClusterTestSwitchOnEvent;
import com.pamirs.pradar.pressurement.agent.event.impl.ShadowConsumerDisableEvent;
import com.pamirs.pradar.pressurement.agent.event.impl.SilenceSwitchOnEvent;
import com.pamirs.pradar.pressurement.agent.listener.EventResult;
import com.pamirs.pradar.pressurement.agent.listener.PradarEventListener;
import com.pamirs.pradar.pressurement.agent.listener.model.ShadowConsumerDisableInfo;
import com.pamirs.pradar.pressurement.agent.shared.service.ErrorReporter;
import com.pamirs.pradar.pressurement.agent.shared.service.EventRouter;
import com.pamirs.pradar.pressurement.agent.shared.service.GlobalConfig;
import com.shulie.instrument.simulator.message.ConcurrentWeakHashMap;
import com.shulie.instrument.simulator.message.DestroyHook;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListener;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.consumer.RebalanceImpl;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2020/11/26 10:56 下午
 */
public class ConsumerRegistry {
    private final static Logger logger = LoggerFactory.getLogger(ConsumerRegistry.class);
    private final static Object EMPTY = new Object();

    private static ConcurrentWeakHashMap<DefaultMQPushConsumer/*business consumer*/, DefaultMQPushConsumer/*shadow
    consumer*/>
        caches = new ConcurrentWeakHashMap<DefaultMQPushConsumer, DefaultMQPushConsumer>(
        new DestroyHook<DefaultMQPushConsumer, DefaultMQPushConsumer>() {
            @Override
            public void destroy(DefaultMQPushConsumer businessConsumer, DefaultMQPushConsumer defaultMQPushConsumer) {
                removeListener(businessConsumer);
                defaultMQPushConsumer.shutdown();
            }
        });

    private static ConcurrentWeakHashMap<DefaultMQPushConsumer/*shadow consumer*/, Object> shadowConsumers
        = new ConcurrentWeakHashMap<DefaultMQPushConsumer/*shadow consumer*/, Object>();
    private static ConcurrentHashMap<DefaultMQPushConsumer, PradarEventListener> listeners
        = new ConcurrentHashMap<DefaultMQPushConsumer, PradarEventListener>();

    public static void destroy() {
        for (Map.Entry<DefaultMQPushConsumer, DefaultMQPushConsumer> entry : caches.entrySet()) {
            try {
                removeListener(entry.getKey());
                entry.getValue().shutdown();
            } catch (Throwable e) {
                logger.error("Apache-RocketMQ: shutdown rocketmq consumer err! {}",
                    entry.getValue().getDefaultMQPushConsumerImpl().getSubscriptionInner().toString());
            }
        }
        caches.clear();
        shadowConsumers.clear();
        listeners.clear();
    }

    public static DefaultMQPushConsumer getConsumer(Object target) {
        return caches.get(target);
    }

    /**
     * 判断是否是影子消费者
     *
     * @param defaultMQPushConsumer
     * @return
     */
    public static boolean isShadowConsumer(DefaultMQPushConsumer defaultMQPushConsumer) {
        return shadowConsumers.containsKey(defaultMQPushConsumer);
    }

    /**
     * 关闭业务消费者对应的影子消费者
     *
     * @param businessConsumer
     */
    public static void shutdownShadowConsumerByBusinessConsumer(DefaultMQPushConsumer businessConsumer) {
        removeListener(businessConsumer);
        DefaultMQPushConsumer shadowConsumer = caches.remove(businessConsumer);
        if (shadowConsumer != null) {
            shadowConsumer.shutdown();
        }
    }

    /**
     * 是否注册过
     *
     * @param defaultMQPushConsumer
     * @return
     */
    public static boolean hasRegistered(DefaultMQPushConsumer defaultMQPushConsumer) {
        if (!PradarSwitcher.clusterTestSwitchOn() || PradarSwitcher.silenceSwitchOn()) {
            return true;
        }
        if (caches.containsKey(defaultMQPushConsumer)) {
            return true;
        }
        return false;
    }

    /**
     * 注册 Consumer
     *
     * @param businessConsumer 业务消费者
     * @return 返回注册是否成功, 如果
     */
    public static boolean registerConsumer(DefaultMQPushConsumer businessConsumer) {

        DefaultMQPushConsumer shadowConsumer = caches.get(businessConsumer);
        if (shadowConsumer != null) {
            return false;
        }

        shadowConsumer = buildMQPushConsumer(businessConsumer);
        if (shadowConsumer == null) {
            return false;
        }

        DefaultMQPushConsumer oldShadowConsumer = caches.putIfAbsent(businessConsumer, shadowConsumer);
        if (oldShadowConsumer != null) {
            return false;
        } else {
            try {
                shadowConsumer.start();
            } catch (Throwable e) {
                ErrorReporter.buildError()
                    .setErrorType(ErrorTypeEnum.MQ)
                    .setErrorCode("MQ-0001")
                    .setMessage("Apache-RocketMQ消费端启动失败！")
                    .setDetail("subscription:" + shadowConsumer.getDefaultMQPushConsumerImpl().getSubscriptionInner() + "||"
                        + e.getMessage())
                    .report();
                logger.error("Apache-RocketMQ: start shadow DefaultMQPushConsumer err! subscription:{}",
                    shadowConsumer.getDefaultMQPushConsumerImpl().getSubscriptionInner(), e);
                caches.remove(businessConsumer);
                return false;
            }
            /*
             * 将注册的影子消费者添加到列表中，用于检测消费者是否是影子消费者
             */
            shadowConsumers.put(shadowConsumer, EMPTY);
            addListener(businessConsumer);
        }
        return true;
    }

    private static String getInstanceName() {
        String instanceName = System.getProperty("rocketmq.client.name", "DEFAULT");
        if (instanceName.equals("DEFAULT")) {
            instanceName = String.valueOf(UtilAll.getPid());
        }
        return instanceName;
    }

    /**
     * 构建 DefaultMQPushConsumer
     * 如果后续支持影子 server 模式，则直接修改此方法即可
     *
     * @param businessConsumer 业务消费者
     * @return 返回注册的影子消费者，如果初始化失败会返回 null
     */
    private synchronized static DefaultMQPushConsumer buildMQPushConsumer(DefaultMQPushConsumer businessConsumer) {
        DefaultMQPushConsumer defaultMQPushConsumer = new DefaultMQPushConsumer();

        String instanceName = getInstanceName();
        if (instanceName != null && !instanceName.equals("DEFAULT")) {
            defaultMQPushConsumer.setInstanceName(Pradar.CLUSTER_TEST_PREFIX + instanceName);
        } else {
            defaultMQPushConsumer.setInstanceName(
                Pradar.addClusterTestPrefix(businessConsumer.getConsumerGroup() + instanceName));
        }

        defaultMQPushConsumer.setNamesrvAddr(businessConsumer.getNamesrvAddr());
        defaultMQPushConsumer.setConsumerGroup(Pradar.addClusterTestPrefix(businessConsumer.getConsumerGroup()));
        defaultMQPushConsumer.setConsumeFromWhere(businessConsumer.getConsumeFromWhere());
        defaultMQPushConsumer.setPullThresholdForQueue(businessConsumer.getPullThresholdForQueue());
        final List<String> missFields = new ArrayList<String>();
        try {
            defaultMQPushConsumer.setPullThresholdSizeForTopic(businessConsumer.getPullThresholdSizeForTopic());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setPullThresholdSizeForQueue(businessConsumer.getPullThresholdSizeForQueue());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setPullBatchSize(businessConsumer.getPullBatchSize());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setConsumeMessageBatchMaxSize(businessConsumer.getConsumeMessageBatchMaxSize());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setConsumeThreadMax(businessConsumer.getConsumeThreadMax());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setConsumeThreadMin(businessConsumer.getConsumeThreadMin());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setInstanceName(Pradar.addClusterTestPrefix(businessConsumer.getInstanceName()));
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setAdjustThreadPoolNumsThreshold(businessConsumer.getAdjustThreadPoolNumsThreshold());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setAllocateMessageQueueStrategy(businessConsumer.getAllocateMessageQueueStrategy());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setConsumeConcurrentlyMaxSpan(businessConsumer.getConsumeConcurrentlyMaxSpan());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setConsumeTimestamp(businessConsumer.getConsumeTimestamp());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setMessageModel(businessConsumer.getMessageModel());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setMessageListener(businessConsumer.getMessageListener());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setOffsetStore(businessConsumer.getOffsetStore());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setPullInterval(businessConsumer.getPullInterval());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setSubscription(businessConsumer.getSubscription());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setUnitMode(businessConsumer.isUnitMode());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setClientCallbackExecutorThreads(businessConsumer.getClientCallbackExecutorThreads());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setClientIP(businessConsumer.getClientIP());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setHeartbeatBrokerInterval(businessConsumer.getHeartbeatBrokerInterval());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setPersistConsumerOffsetInterval(businessConsumer.getPersistConsumerOffsetInterval());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setPostSubscriptionWhenPull(businessConsumer.isPostSubscriptionWhenPull());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setUnitName(businessConsumer.getUnitName());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setUnitMode(businessConsumer.isUnitMode());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setMaxReconsumeTimes(businessConsumer.getMaxReconsumeTimes());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setSuspendCurrentQueueTimeMillis(businessConsumer.getSuspendCurrentQueueTimeMillis());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setConsumeTimeout(businessConsumer.getConsumeTimeout());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setUseTLS(businessConsumer.isUseTLS());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setLanguage(businessConsumer.getLanguage());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }
        try {
            defaultMQPushConsumer.setVipChannelEnabled(businessConsumer.isVipChannelEnabled());
        } catch (Throwable e){
            missFields.add(e.getMessage());
        }

        MessageListener messageListener = businessConsumer.getMessageListener();
        if (messageListener != null) {
            if (messageListener instanceof MessageListenerConcurrently) {
                defaultMQPushConsumer.registerMessageListener((MessageListenerConcurrently)messageListener);
            } else if (messageListener instanceof MessageListenerOrderly) {
                defaultMQPushConsumer.registerMessageListener((MessageListenerOrderly)messageListener);
            }
        }

        if(!missFields.isEmpty()){
            logger.warn("[RocketMQ] miss some fields: {}", Arrays.toString(missFields.toArray()));
        }

        ConcurrentMap<String, SubscriptionData> map = businessConsumer.getDefaultMQPushConsumerImpl().getSubscriptionInner();

        boolean hasSubscribe = false;
        if (map != null) {
            for (Map.Entry<String, SubscriptionData> entry : map.entrySet()) {
                SubscriptionData subscriptionData = entry.getValue();
                String topic = entry.getKey();

                if (!isPermitInitConsumer(businessConsumer, topic)) {
                    continue;
                }

                String subString = subscriptionData.getSubString();
                String filterClassSource = subscriptionData.getFilterClassSource();
                if (filterClassSource != null) {
                    try {
                        defaultMQPushConsumer.subscribe(Pradar.addClusterTestPrefix(topic), subString, filterClassSource);
                    } catch (MQClientException e) {
                        ErrorReporter.buildError()
                            .setErrorType(ErrorTypeEnum.MQ)
                            .setErrorCode("MQ-0001")
                            .setMessage("Apache-RocketMQ消费端subscribe失败！")
                            .setDetail(
                                "topic:" + topic + " fullClassName:" + subString + " filterClassSource:" + filterClassSource
                                    + "||" + e.getMessage())
                            .report();
                        logger.error(
                            "Apache-RocketMQ: subscribe shadow DefaultMQPushConsumer err! topic:{} fullClassName:{} "
                                + "filterClassSource:{}",
                            topic, subString, filterClassSource, e);
                        return null;
                    }
                } else {
                    try {
                        defaultMQPushConsumer.subscribe(Pradar.addClusterTestPrefix(topic), subString);
                    } catch (MQClientException e) {
                        ErrorReporter.buildError()
                            .setErrorType(ErrorTypeEnum.MQ)
                            .setErrorCode("MQ-0001")
                            .setMessage("Apache-RocketMQ消费端subscribe失败！")
                            .setDetail("topic:" + topic + " subExpression:" + subString + "||" + e.getMessage())
                            .report();
                        logger.error(
                            "Apache-RocketMQ: subscribe shadow DefaultMQPushConsumer err! topic:{} subExpression:{}",
                            topic, subString, e);
                        return null;
                    }
                }
                hasSubscribe = true;
            }

            if (hasSubscribe) {
                return defaultMQPushConsumer;
            }
        }
        return null;
    }

    /**
     * 校验控制台是否允许开启影子消费者
     *
     * @param businessConsumer 业务消费者对象
     * @param topic            消费队列名称
     * @return true 允许添加消费者
     */
    private static boolean isPermitInitConsumer(DefaultMQPushConsumer businessConsumer, String topic) {
        Set<String> mqWhiteList = GlobalConfig.getInstance().getMqWhiteList();
        String key = topic + "#" + businessConsumer.getConsumerGroup();
        if (PradarSwitcher.whiteListSwitchOn() && !mqWhiteList.contains(key) && !mqWhiteList.contains(topic)) {
//            logger.warn("[RockemtMQ] {} not in WhiteList:{}", key, Arrays.toString(mqWhiteList.toArray()));
            return false;
        }
        return true;
    }

    private static void removeListener(final DefaultMQPushConsumer businessConsumer) {
        if (businessConsumer == null) {
            return;
        }
        PradarEventListener listener = listeners.remove(businessConsumer);
        if (listener != null) {
            EventRouter.router().removeListener(listener);
        }
    }

    private static void addListener(final DefaultMQPushConsumer businessConsumer) {
        RebalanceImpl rebalance = businessConsumer.getDefaultMQPushConsumerImpl().getRebalanceImpl();
        final Set<String> topics = rebalance.getSubscriptionInner().keySet();
        final PradarEventListener listener = new PradarEventListener() {
            @Override
            public EventResult onEvent(IEvent event) {
                if (event instanceof ClusterTestSwitchOnEvent) {
                    try {
                        //取出配置创建影子消费者
                        DefaultMQPushConsumer defaultMQPushConsumer = buildMQPushConsumer(businessConsumer);
                        if (defaultMQPushConsumer != null) {
                            defaultMQPushConsumer.start();
                        }
                    } catch (MQClientException e) {
                        logger.error(e.getMessage());
                        return EventResult.error("apache-rocketmq-plugin-open",
                            "Apache-RocketMQ PT Consumer start failed: " + e.getMessage());
                    }
                    return EventResult.success("apache-rocketmq-plugin-open");
                } else if (event instanceof ClusterTestSwitchOffEvent || event instanceof SilenceSwitchOnEvent) {
                    return shutdownShadowConsumer(businessConsumer);
                } else if (event instanceof ShadowConsumerDisableEvent) {
                    String group = businessConsumer.getConsumerGroup();
                    for (String topic : topics) {
                        List<ShadowConsumerDisableInfo> disableInfos = ((ShadowConsumerDisableEvent)event).getTarget();
                        for (ShadowConsumerDisableInfo disableInfo : disableInfos) {
                            if (topic.equals(disableInfo.getTopic()) && group.equals(disableInfo.getConsumerGroup())) {
                                return shutdownShadowConsumer(businessConsumer);
                            }
                        }
                    }
                }
                return EventResult.IGNORE;
            }

            @Override
            public int order() {
                return 4;
            }
        };
        PradarEventListener old = listeners.putIfAbsent(businessConsumer, listener);
        if (old == null) {
            EventRouter.router().addListener(listener);
        }
    }

    private static EventResult shutdownShadowConsumer(DefaultMQPushConsumer businessConsumer) {
        try {
            //从配置中取出消费者关闭
            DefaultMQPushConsumer consumer = caches.remove(businessConsumer);
            if (consumer != null) {
                consumer.shutdown();
                shadowConsumers.remove(consumer);
            }
        } catch (Throwable e) {
            logger.error("", e);
            return EventResult.error("apache-rocketmq-plugin-close",
                "Apache-RocketMQ PT Consumer close failed: " + e.getMessage());
        }
        return EventResult.success("apache-rocketmq-plugin-close");
    }
}
