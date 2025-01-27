package com.iisquare.fs.base.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iisquare.fs.base.core.util.ApiUtil;
import com.iisquare.fs.base.core.util.DPUtil;
import com.iisquare.fs.base.core.util.FileUtil;
import com.iisquare.fs.base.core.util.HttpUtil;
import com.iisquare.fs.base.web.mvc.ServiceBase;
import com.iisquare.fs.base.worker.core.Task;
import com.iisquare.fs.base.worker.core.WatchListener;
import com.iisquare.fs.base.worker.core.ZooKeeperClient;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class TaskService extends ServiceBase implements InitializingBean, DisposableBean, ApplicationListener<WebServerInitializedEvent> {

    @Value("${fs.worker.zookeeper.host}")
    private String zhHost;
    @Value("${fs.worker.zookeeper.timeout}")
    private int zhTimeout;
    @Autowired
    public ContainerService containerService;

    private int maxConsumer;
    private ZooKeeperClient zookeeper;
    public static final Map<String, String> header = new LinkedHashMap(){{
        put("Content-Type", "application/json");
    }};

    @Value("${fs.worker.maxConsumer:-1}")
    public void setMaxConsumer(int maxConsumer) {
        this.maxConsumer = maxConsumer < 0 ? Math.max(Runtime.getRuntime().availableProcessors() - 1, 0) : maxConsumer;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        String nodeAddress = InetAddress.getLoopbackAddress().getHostAddress();
        int nodePort = event.getWebServer().getPort();
        String nodeName = nodeAddress + ":" + nodePort;
        zookeeper = new ZooKeeperClient(zhHost, zhTimeout, nodeName);
        zookeeper.listen(new WatchListener(this));
        zookeeper.open();
        this.rebalance(null);
    }

    @Override
    public void destroy() throws Exception {
        FileUtil.close(zookeeper);
        zookeeper = null;
    }

    public List<String> participants() {
        return zookeeper.participants();
    }

    public ObjectNode node() {
        ObjectNode data = DPUtil.objectNode();
        data.put("id", zookeeper.nodeId());
        data.put("state", zookeeper.state());
        data.put("leader", zookeeper.leaderId());
        data.put("leadership", zookeeper.isLeader());
        data.put("maxConsumer", maxConsumer);
        return data;
    }

    public Map<String, Object> start(String queueName) {
        Task task = zookeeper.task(queueName);
        if (null == task) return ApiUtil.result(1001, "获取任务失败", queueName);
        if (null == save(task.start(), false, true)) return ApiUtil.result(500, "保存任务失败", task);
        return ApiUtil.result(0, null, task);
    }

    public Map<String, Object> stop(String queueName) {
        Task task = zookeeper.task(queueName);
        if (null == task) return ApiUtil.result(1001, "获取任务失败", queueName);
        if (null == save(task.stop(), false, false)) return ApiUtil.result(500, "保存任务失败", task);
        return ApiUtil.result(0, null, task);
    }

    public Map<String, Object> startAll() {
        Map<String, Task> tasks = zookeeper.tasks();
        if (null == tasks) return ApiUtil.result(1001, "获取任务列表失败", tasks);
        for (Map.Entry<String, Task> entry : tasks.entrySet()) {
            Task task = entry.getValue();
            if (task.isRunning()) continue;
            if (null == save(task.start(), false, false)) return ApiUtil.result(500, "更新任务状态失败", task);
        }
        rebalance(null);
        return ApiUtil.result(0, null, tasks);
    }

    public Map<String, Object> stopAll() {
        Map<String, Task> tasks = zookeeper.tasks();
        if (null == tasks) return ApiUtil.result(1001, "获取任务列表失败", tasks);
        for (Map.Entry<String, Task> entry : tasks.entrySet()) {
            Task task = entry.getValue();
            if (!task.isRunning()) continue;
            if (null == save(task.stop(), false, false)) return ApiUtil.result(500, "更新任务状态失败", task);
        }
        return ApiUtil.result(0, null, tasks);
    }

    public Map<String, Object> rebalance(String queueSingle) {
        String url = "http://" + zookeeper.nodeId() + "/task/nodes";
        JsonNode nodes = DPUtil.parseJSON(HttpUtil.get(url, DPUtil.buildMap(String.class, String.class, "withQueueKey", "1")));
        if (null == nodes) return ApiUtil.result(5001, "载入节点信息失败", url);
        nodes = nodes.at("/data/nodes");
        if (null == nodes || nodes.isNull()) return ApiUtil.result(5002, "节点信息异常", url);
        Map<String, Task> tasks = zookeeper.tasks();
        if (!DPUtil.empty(queueSingle)) {
            if (!tasks.containsKey(queueSingle)) return ApiUtil.result(5003, "节点不存在", url);
            tasks = DPUtil.buildMap(String.class, Task.class, queueSingle, tasks.get(queueSingle));
        }
        InterProcessMutex lock = zookeeper.lock();
        try {
            if (!lock.acquire(30, TimeUnit.MILLISECONDS)) {
                return ApiUtil.result(1001, "获取锁失败", null);
            }
            ObjectNode result = DPUtil.objectNode();
            for (Map.Entry<String, Task> entry : tasks.entrySet()) {
                Task task = entry.getValue();
                String queueName = task.getQueueName();
                ObjectNode items = result.putObject(queueName);
                int targetCount = task.getConsumerCount();
                if (task.isRunning() && targetCount > 0) {
                    int currentCount = 0;
                    Map<String, Integer> statistic = new LinkedHashMap<>();
                    Map<String, Integer> resource = new LinkedHashMap<>();
                    Iterator<JsonNode> iterator = nodes.iterator();
                    while (iterator.hasNext()) {
                        JsonNode node = iterator.next();
                        String id = node.get("id").asText();
                        int consumerCount = node.at("/containers/" + queueName + "/consumerCount").asInt(0);
                        statistic.put(id, consumerCount);
                        resource.put(id, node.get("maxConsumer").asInt(0));
                        currentCount += consumerCount;
                    }
                    if (currentCount == targetCount) continue;
                    iterator = nodes.iterator();
                    while (iterator.hasNext()) {
                        JsonNode node = iterator.next();
                        String id = node.get("id").asText();
                        int count;
                        if (targetCount > currentCount) { // 增加消费者
                            if (targetCount - currentCount < 1) break;
                            int release = Math.min(resource.get(id) - statistic.get(id), targetCount - currentCount);
                            if (release < 1) continue; // 无可用资源
                            count = release + statistic.get(id);
                        } else { // 减少消费者
                            if (currentCount - targetCount < 1) break;
                            int release = Math.min(statistic.get(id), currentCount - targetCount);
                            if (release < 1) continue; // 无可释放资源
                            count = statistic.get(id) - release;
                        }
                        url = "http://" + id + "/container/submit";
                        ObjectNode data = DPUtil.objectNode()
                                .put("queueName", queueName)
                                .put("handlerName", task.getHandlerName())
                                .put("prefetchCount", task.getPrefetchCount())
                                .put("consumerCount", count);
                        String content = HttpUtil.post(url, DPUtil.stringify(data), header);
                        items.replace(id, DPUtil.parseJSON(content));
                    }
                } else {
                    Iterator<JsonNode> iterator = nodes.iterator();
                    while (iterator.hasNext()) {
                        JsonNode node = iterator.next();
                        String id = node.get("id").asText();
                        url = "http://" + id + "/container/stop";
                        ObjectNode data = DPUtil.objectNode().put("queueName", queueName);
                        String content = HttpUtil.post(url, DPUtil.stringify(data), header);
                        items.replace(id, DPUtil.parseJSON(content));
                    }
                }
            }
            return ApiUtil.result(0, null, result);
        } catch (Exception e) {
            return ApiUtil.result(5001, "获取锁异常", e.getMessage());
        } finally {
            zookeeper.release(lock);
        }
    }

    public Task save(Task task, boolean bKeepStatus, boolean bRebalance) {
        if (bKeepStatus) {
            Task info = zookeeper.task(task.getQueueName());
            if (null != info) {
                task.setHandlerName(info.getHandlerName());
                task.setStatus(info.getStatus());
            }
        }
        boolean result = zookeeper.save("/task/" + task.getQueueName(), Task.encode(task));
        if (!result) return null;
        if (bRebalance && task.isRunning()) {
            rebalance(task.getQueueName());
        }
        return task;
    }

    public boolean remove(String type, String id) {
        if (!Arrays.asList("task").contains(type)) return false;
        return zookeeper.save("/" + type + "/" + id, null);
    }

    public Map<String, Task> tasks() {
        return zookeeper.tasks();
    }

}
