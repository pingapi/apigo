package cn.crabc.core.app.filter;

import cn.crabc.core.app.entity.BaseApiLog;
import cn.crabc.core.app.entity.BaseApp;
import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.app.service.system.IBaseApiLogService;
import cn.crabc.core.app.service.system.impl.ApiRateLimitService;
import cn.crabc.core.app.util.ApiContext;
import cn.crabc.core.app.util.RequestUtils;
import cn.crabc.core.app.util.Result;
import cn.crabc.core.app.util.SM3Util;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API开放接口鉴权过滤 拦截器
 *
 * @author yuqf
 */

public class AuthInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    // API开放接口前缀
    private static final String API_PRE = "/api/web/";

    // 日志异步处理默认参数，可被 crabc.api-log.* 覆盖
    private static final int DEFAULT_BATCH_SIZE = 500; // 每次消费500条
    private static final int DEFAULT_QUEUE_CAPACITY = 2000; // 条数上限，防止大量小日志占满队列
    /** 队列默认字节预算：按字节而非条数限流，避免大响应体把堆打满 */
    private static final long DEFAULT_QUEUE_BYTE_BUDGET = 32L * 1024 * 1024;
    /** 单条日志 body 上限（字符数）：超长截断后仍保留记录，而不是整条丢弃 */
    private static final int MAX_LOG_BODY_CHARS = 128 * 1024;
    /** 单条日志固定开销估算（对象头 + 字段引用等） */
    private static final long LOG_ENTRY_OVERHEAD_BYTES = 512L;
    /** 丢弃计数上报间隔：高负载下每批一条 WARN 会把日志本身变成噪声源 */
    private static final long DROP_REPORT_INTERVAL_MS = 10_000L;
    /** 写入失败退避：数据库故障时不让消费线程空转 */
    private static final long WRITE_FAILURE_BACKOFF_MS = 500L;
    /** 关闭时等待消费线程退出的上限 */
    private static final long CONSUMER_JOIN_TIMEOUT_MS = 2_000L;
    /** 关闭时冲刷剩余日志的时间预算 */
    private static final long SHUTDOWN_FLUSH_BUDGET_MS = 5_000L;

    // 有界日志缓冲队列（按条数 + 字节预算双重限制，超出则丢弃）；容量可配置，在 init() 中创建
    private BlockingQueue<LogEntry> logQueue;

    // 当前队列占用字节数
    private final AtomicLong queuedBytes = new AtomicLong(0);

    // 丢弃日志计数器
    private final AtomicInteger droppedLogCount = new AtomicInteger(0);

    // 上次丢弃计数上报时间
    private final AtomicLong lastDropReportAt = new AtomicLong(0);

    // 单批落库条数
    @Value("${crabc.api-log.batch-size:500}")
    private int batchSize = DEFAULT_BATCH_SIZE;

    // 队列条数上限
    @Value("${crabc.api-log.queue-capacity:2000}")
    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

    // 队列字节预算，可通过 crabc.api-log.queue-byte-budget 调整
    @Value("${crabc.api-log.queue-byte-budget:33554432}")
    private long queueByteBudget = DEFAULT_QUEUE_BYTE_BUDGET;

    // 消费线程运行状态
    private volatile boolean running;

    // 日志消费线程
    private volatile Thread consumerThread;

    // 落库互斥：保证消费线程与关闭冲刷不会并发写入
    private final Object flushLock = new Object();

    @Autowired
    private IBaseApiLogService iBaseApiLogService;
    @Autowired
    private IBaseApiInfoService iBaseApiInfoService;
    @Value("${crabc.auth.expiresTime:10}")
    private Integer expiresTime;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    @Qualifier("apiCache")
    private Cache<String, ApiInfoDTO> apiCache;
    @Autowired
    @Qualifier("apiMissCache")
    private Cache<String, Boolean> apiMissCache;
    @Autowired
    private ApiRateLimitService apiRateLimitService;

    /**
     * 创建日志队列并启动消费线程。
     * <p>
     * 本拦截器由 {@code InterceptorConfig} 的 @Bean 方法直接 new，构造函数里读不到注入值，
     * 因此队列容量与线程启动都放在字段注入完成之后的 {@code @PostConstruct}。
     */
    @PostConstruct
    public void init() {
        if (queueCapacity <= 0) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        // 单批不能超过队列容量，否则配置写错时 drainTo 只是白跑一趟
        batchSize = Math.min(batchSize, queueCapacity);
        logQueue = new LinkedBlockingQueue<>(queueCapacity);
        lastDropReportAt.set(System.currentTimeMillis());
        running = true;
        consumerThread = Thread.ofPlatform().daemon(true).name("api-log-consumer").unstarted(this::consumeLoop);
        consumerThread.start();
    }

    /**
     * 应用关闭时停止消费线程，并把队列里剩余的日志同步冲刷出去。
     * <p>
     * 只在有限窗口内等待：消费线程仍卡在数据库里时不再追加等待，交给守护线程随 JVM 退出，
     * 避免发布/容器重启被一次慢插入拖长。
     */
    @PreDestroy
    public void shutdown() {
        running = false;
        Thread consumer = consumerThread;
        if (consumer != null) {
            consumer.interrupt();
            try {
                consumer.join(CONSUMER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (consumer.isAlive()) {
                log.warn("API日志消费线程未在 {} 毫秒内退出，跳过剩余日志冲刷，待写入：{} 条",
                        CONSUMER_JOIN_TIMEOUT_MS, logQueue == null ? 0 : logQueue.size());
                return;
            }
        }
        flushRemaining();
        reportDrops(true);
    }

    /**
     * 关闭冲刷：消费线程已退出，逐批写空队列，超出预算则放弃剩余日志（由丢弃计数体现）。
     */
    private void flushRemaining() {
        BlockingQueue<LogEntry> queue = logQueue;
        if (queue == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + SHUTDOWN_FLUSH_BUDGET_MS;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            List<LogEntry> batch = new ArrayList<>(batchSize);
            queue.drainTo(batch, batchSize);
            flushBatch(batch, false);
        }
    }

    /**
     * 消费循环：取到第一条立刻凑批落库，成功后继续排空队列。
     * <p>
     * 取代原先固定 500ms 节拍的定时任务——节拍把吞吐钉死在「批大小 ÷ 节拍」，
     * 实测约 700 行/秒，高 QPS 下八成以上日志被丢弃；同时定时任务还会让空闲关闭
     * 多等一个完整节拍。
     */
    private void consumeLoop() {
        while (running) {
            try {
                List<LogEntry> batch = new ArrayList<>(batchSize);
                batch.add(logQueue.take());
                logQueue.drainTo(batch, batchSize - 1);
                flushBatch(batch, true);
                reportDrops(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // 消费线程不能因单次异常退出，否则日志会静默积压到全部丢弃
                log.error("API日志消费线程异常", t);
            }
        }
    }

    /**
     * 批量落库。
     * <p>
     * 先把额度还给生产者再落库：一次慢插入不应该让新日志全部被丢弃。
     *
     * @param allowRequeue 失败时是否回队重试；关闭冲刷不再回队，避免拖长关闭
     */
    private void flushBatch(List<LogEntry> batch, boolean allowRequeue) {
        if (batch.isEmpty()) {
            return;
        }
        List<BaseApiLog> logs = new ArrayList<>(batch.size());
        long releasedBytes = 0;
        for (LogEntry entry : batch) {
            logs.add(entry.log);
            releasedBytes += entry.bytes;
        }
        queuedBytes.addAndGet(-releasedBytes);

        try {
            synchronized (flushLock) {
                iBaseApiLogService.batchAddLog(logs);
            }
            log.debug("批量插入API日志成功，数量：{}", logs.size());
        } catch (Exception e) {
            if (!allowRequeue) {
                droppedLogCount.addAndGet(batch.size());
                log.error("关闭时批量写入API日志失败，丢弃日志数：{}", batch.size(), e);
                return;
            }
            log.error("批量写入API日志失败，回队一次后放弃，数量：{}", logs.size(), e);
            requeue(batch);
            backoff();
        }
    }

    /**
     * 数据库异常后的可中断退避。
     */
    private void backoff() {
        if (!running) {
            // 关闭阶段被中断的插入已回队，交给冲刷流程立刻重试，别再白等一个退避窗口
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(WRITE_FAILURE_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 丢弃计数上报：常规路径最多每 {@value #DROP_REPORT_INTERVAL_MS} 毫秒一条，关闭时强制上报。
     *
     * @param force 是否跳过间隔限制
     */
    private void reportDrops(boolean force) {
        long now = System.currentTimeMillis();
        long last = lastDropReportAt.get();
        if (!force) {
            if (now - last < DROP_REPORT_INTERVAL_MS || !lastDropReportAt.compareAndSet(last, now)) {
                return;
            }
        }
        int dropped = droppedLogCount.getAndSet(0);
        if (dropped > 0) {
            log.warn("日志队列超出容量或字节预算，丢弃日志数：{}，当前占用：{}KB",
                    dropped, queuedBytes.get() / 1024);
        }
    }

    /**
     * 写入失败时回队一次；已重试过的条目直接丢弃，避免故障期间无限重试。
     *
     * @param batch 写入失败的批次
     */
    private void requeue(List<LogEntry> batch) {
        for (LogEntry entry : batch) {
            if (entry.retried || !reserveBytes(entry.bytes)) {
                droppedLogCount.incrementAndGet();
                continue;
            }
            if (!logQueue.offer(entry.markRetried())) {
                queuedBytes.addAndGet(-entry.bytes);
                droppedLogCount.incrementAndGet();
            }
        }
    }

    /**
     * 按字节预算预占额度（CAS 保证并发安全）。
     *
     * @param bytes 本条日志估算字节数
     * @return true 表示额度预占成功
     */
    private boolean reserveBytes(long bytes) {
        long budget = effectiveByteBudget();
        if (bytes <= 0 || bytes > budget) {
            return false;
        }
        while (true) {
            long current = queuedBytes.get();
            if (current + bytes > budget) {
                return false;
            }
            if (queuedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    private long effectiveByteBudget() {
        return queueByteBudget > 0 ? queueByteBudget : DEFAULT_QUEUE_BYTE_BUDGET;
    }

    /**
     * 估算日志占用字节：主体按 UTF-8 最坏情况（每字符 2 字节）估算，避免额外编码开销。
     *
     * @param apiLog 日志对象
     * @return 估算字节数
     */
    private long estimateLogBytes(BaseApiLog apiLog) {
        return LOG_ENTRY_OVERHEAD_BYTES
                + estimate(apiLog.getApiPath())
                + estimate(apiLog.getQueryParam())
                + estimate(apiLog.getRequestBody())
                + estimate(apiLog.getResponseBody());
    }

    private long estimate(String text) {
        return text == null ? 0 : (long) text.length() * 2;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String apiPath = IBaseApiInfoService.normalizeApiPath(path.replace(API_PRE, ""));
        String cacheKey = IBaseApiInfoService.buildCacheKey(method, apiPath);

        // 1. 负缓存：路径不存在的请求短 TTL 记忆，避免任意非法路径每请求都打库
        if (apiMissCache.getIfPresent(cacheKey) != null) {
            setErrorResponse(request, response, ErrorStatusEnum.API_INVALID.getCode(), ErrorStatusEnum.API_INVALID.getMassage());
            return false;
        }

        // 2. 获取API配置
        ApiInfoDTO apiInfo = apiCache.getIfPresent(cacheKey);
        if (apiInfo == null) {
            apiInfo = iBaseApiInfoService.getApiInfoCache(method, apiPath);
            if (apiInfo != null) {
                apiCache.put(cacheKey, apiInfo);
            } else {
                apiMissCache.put(cacheKey, Boolean.TRUE);
            }
        }

        // 3. API存在性验证
        if (apiInfo == null) {
            setErrorResponse(request, response,ErrorStatusEnum.API_INVALID.getCode(),ErrorStatusEnum.API_INVALID.getMassage());
            return false;
        }

        // 4. 存入上下文：使用只读快照，避免并发请求互写缓存中的共享对象（耗时统计串号）
        ApiInfoDTO requestApi = copyForRequest(apiInfo);
        requestApi.setRequestDate(new Date());
        requestApi.setRequestTime(System.currentTimeMillis());
        ApiContext.set(requestApi);

        // 5. API启用状态检查
        if (apiInfo.getEnabled() == 0) {
            setErrorResponse(request, response,ErrorStatusEnum.API_OFFLINE.getCode(),ErrorStatusEnum.API_OFFLINE.getMassage());
            return false;
        }

        // 6. 限流检查（防止API被恶意频繁调用）
        if (!apiRateLimitService.tryConsume(apiInfo)) {
            setErrorResponse(request, response, ErrorStatusEnum.API_LIMIT.getCode(), ErrorStatusEnum.API_LIMIT.getMassage());
            return false;
        }

        try {
            // 7. 根据认证类型进行鉴权
            List<BaseApp> appList = apiInfo.getAppList();
            String rawAuthType = apiInfo.getAuthType();
            if (rawAuthType == null) {
                // 未配置鉴权类型：保持与历史行为一致（拒绝），由管理员补齐配置
                log.warn("API 未配置鉴权类型（auth_type 为空），已拒绝访问：{}", apiInfo.getApiPath());
                setErrorResponse(request, response, ErrorStatusEnum.API_UN_AUTH.getCode(), ErrorStatusEnum.API_UN_AUTH.getMassage());
                return false;
            }
            String authType = rawAuthType.trim().toUpperCase();
            // 历史别名（code/secret）就地归一；未知值一律拒绝（fail-closed），不再静默放行
            return switch (authType) {
                case "", "NONE" -> true; // 显式声明为无认证
                case "CODE", "APP_CODE" -> checkAppCode(request, response, appList);
                case "APP_KEY" -> checkAppKey(request, response, appList);
                case "SECRET", "APP_SECRET" -> checkSM3(request, response, appList);
                default -> {
                    log.warn("API 配置了未知鉴权类型，已拒绝访问：{}，authType: {}", apiInfo.getApiPath(), rawAuthType);
                    setErrorResponse(request, response, ErrorStatusEnum.API_UN_AUTH.getCode(), ErrorStatusEnum.API_UN_AUTH.getMassage());
                    yield false;
                }
            };
        }catch (Exception e) {
            log.error("API认证异常: {}", e.getMessage(), e);
            setErrorResponse(request,response,ErrorStatusEnum.API_UN_AUTH.getCode(),ErrorStatusEnum.API_UN_AUTH.getMassage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        addLogAsync(request, response, "");
    }

    /**
     * 复制请求级快照：缓存中的 {@link ApiInfoDTO} 被所有并发请求共享，
     * 请求时间等可变字段只能写在快照上，否则耗时统计会互相覆盖。
     *
     * @param apiInfo 缓存中的 API 配置
     * @return 本次请求专属的副本（appList 等集合按引用共享，均为只读用途）
     */
    private ApiInfoDTO copyForRequest(ApiInfoDTO apiInfo) {
        ApiInfoDTO snapshot = new ApiInfoDTO();
        BeanUtils.copyProperties(apiInfo, snapshot);
        return snapshot;
    }

    /**
     * 异常返回
     * @param response
     * @param status
     * @param message
     * @throws Exception
     */
    private void setErrorResponse(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws Exception {        // 指定错误码
        response.setStatus(400);
        response.setContentType("application/json;charset=UTF-8");
        Result result = Result.error(status, message);
        String json = jsonMapper.writeValueAsString(result);
        // 日记记录
        addLogAsync(request, response, json);
        response.getWriter().write(json);
    }

    /**
     * 异步记录访问日志（将日志加入队列）
     *
     * @param request
     * @param response
     */
    private void addLogAsync(HttpServletRequest request, HttpServletResponse response, String msg) {
        try {
            BaseApiLog apiLog = buildApiLog(request, response, msg);
            this.offerLog(apiLog);
        } catch (Exception e) {
            log.error("构建API日志失败", e);
        }
    }

    /**
     * 按字节预算入队：单条超预算、队列已满或额度不足时丢弃并计数。
     * <p>
     * 相比"只按 2000 条"的限制，字节预算能反映真实内存占用：
     * 2000 条 10MB 响应体与 2000 条 1KB 响应体对堆的压力完全不同。
     *
     * @param apiLog 待入队日志
     */
    private void offerLog(BaseApiLog apiLog) {
        truncateBodies(apiLog);
        long bytes = estimateLogBytes(apiLog);
        // 补齐一直未赋值的 body_size：按请求体 + 响应体的字符数估算
        long bodyChars = (long) lengthOf(apiLog.getRequestBody()) + lengthOf(apiLog.getResponseBody());
        apiLog.setBodySize((int) Math.min(Integer.MAX_VALUE, bodyChars));

        if (!reserveBytes(bytes)) {
            droppedLogCount.incrementAndGet();
            return;
        }
        if (!logQueue.offer(new LogEntry(apiLog, bytes, false))) {
            queuedBytes.addAndGet(-bytes);
            droppedLogCount.incrementAndGet();
        }
    }

    /**
     * 单条日志 body 超长时截断，保证大响应体也有日志记录，而不是整条被字节预算拒收。
     *
     * @param apiLog 日志对象
     */
    private void truncateBodies(BaseApiLog apiLog) {
        apiLog.setQueryParam(truncate(apiLog.getQueryParam()));
        apiLog.setRequestBody(truncate(apiLog.getRequestBody()));
        apiLog.setResponseBody(truncate(apiLog.getResponseBody()));
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_LOG_BODY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_LOG_BODY_CHARS) + "...(已截断，原长度 " + text.length() + ")";
    }

    private int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 队列条目：记录预占的字节额度与是否已重试过。
     */
    private static final class LogEntry {

        private final BaseApiLog log;
        private final long bytes;
        private final boolean retried;

        private LogEntry(BaseApiLog log, long bytes, boolean retried) {
            this.log = log;
            this.bytes = bytes;
            this.retried = retried;
        }

        private LogEntry markRetried() {
            return new LogEntry(log, bytes, true);
        }
    }

    /**
     * 构建API日志对象
     *
     * @param request
     * @param response
     * @param msg
     * @return
     */
    private BaseApiLog buildApiLog(HttpServletRequest request, HttpServletResponse response, String msg) throws Exception {
        ContentCachingResponseWrapper responseWrapper = null;
        BaseApiLog apiLog = new BaseApiLog();
        long endTime = System.currentTimeMillis();
        ApiInfoDTO apiInfo = ApiContext.get();
        Date date = new Date();
        if (apiInfo != null) {
            apiLog.setApiId(apiInfo.getApiId());
            apiLog.setApiName(apiInfo.getApiName());
            apiLog.setApiMethod(apiInfo.getApiMethod());
            apiLog.setAuthType(apiInfo.getAuthType());
            apiLog.setRequestTime(apiInfo.getRequestDate());
            apiLog.setCostTime(endTime - apiInfo.getRequestTime());
        }else{
            apiLog.setRequestTime(date);
            apiLog.setApiPath(request.getRequestURI());
            apiLog.setApiMethod(request.getMethod());
        }
        int status = response.getStatus();
        apiLog.setAppName(RequestUtils.getAppKey(request));
        apiLog.setApiPath(request.getRequestURI());
        apiLog.setRequestIp(RequestUtils.getIp(request));
        apiLog.setResponseTime(date);
        apiLog.setQueryParam(request.getQueryString());
        apiLog.setResponseCode(status);
        apiLog.setRequestStatus(status == 200 ? "success" : "fail");
        try {
            if (request instanceof BaseRequestWrapper) {
                String requestBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
                apiLog.setRequestBody(requestBody);
            }
            if (response instanceof ContentCachingResponseWrapper) {
                responseWrapper = (ContentCachingResponseWrapper) response;
                byte[] content = responseWrapper.getContentAsByteArray();
                String responseBody = new String(content, StandardCharsets.UTF_8);
                apiLog.setResponseBody(responseBody);
            }
            if (status == 400) {
                apiLog.setResponseBody(msg);
            }
        } catch (Exception e) {
            log.error("响应结果转换异常", e);
        } finally {
            if (responseWrapper != null) {
                responseWrapper.copyBodyToResponse();
            }
        }
        return apiLog;
    }

    /**
     * 验证接口访问权限APP_CODE
     *
     * @param request
     * @param appList
     * @return
     */
    private boolean checkAppCode(HttpServletRequest request,HttpServletResponse response, List<BaseApp> appList) throws Exception {
        String appCode = RequestUtils.getAppCode(request);
        if (appCode == null || appCode.isEmpty()) {
            setErrorResponse(request,response,ErrorStatusEnum.API_UN_AUTH.getCode(),ErrorStatusEnum.API_UN_AUTH.getMassage());
            return false;
        }
        boolean check = appList.stream().anyMatch(app -> app.getAppCode().equals(appCode));
        if (!check) {
            setErrorResponse(request,response,ErrorStatusEnum.API_UN_AUTH.getCode(),ErrorStatusEnum.API_UN_AUTH.getMassage());
        }
        return check;
    }
    /**
     * 验证接口访问权限APP_KEY
     *
     * @param request
     * @param appList
     * @return
     */
    private boolean checkAppKey(HttpServletRequest request, HttpServletResponse response, List<BaseApp> appList) throws Exception {
        String appKey = RequestUtils.getAppKey(request);
        if (appKey == null || appKey.isEmpty()) {
            setErrorResponse(request,response,ErrorStatusEnum.APP_UN_AUTH.getCode(),ErrorStatusEnum.APP_UN_AUTH.getMassage());
            return false;
        }
        boolean check = appList.stream().anyMatch(app -> app.getAppKey().equals(appKey));
        if (!check) {
            setErrorResponse(request,response,ErrorStatusEnum.API_AUTH_ERROR.getCode(),ErrorStatusEnum.API_AUTH_ERROR.getMassage());
        }
        return check;
    }

    /**
     * 国密签名认证
     *
     * @param request
     * @param appList
     * @return
     * @throws Exception
     */
    public boolean checkSM3(HttpServletRequest request, HttpServletResponse response, List<BaseApp> appList) throws Exception {
        // 1. 获取认证参数（支持X-前缀和无前缀两种header）
        String sign = Optional.ofNullable(request.getHeader("X-Sign")).orElse(request.getHeader("sign"));
        String timeStamp = Optional.ofNullable(request.getHeader("X-Timestamp")).orElse(request.getHeader("timestamp"));
        String appKey = Optional.ofNullable(request.getHeader("X-AppKey")).orElse(request.getHeader("appkey"));
        String nonce = Optional.ofNullable(request.getHeader("X-Nonce")).orElse(request.getHeader("nonce"));

        // 2. 必填参数检查
        if (appKey == null || sign == null || timeStamp == null) {
            setErrorResponse(request,response,ErrorStatusEnum.SHA_PARAM_NOT_FOUNT.getCode(), ErrorStatusEnum.PARAM_NOT_FOUNT.getMassage());
            return false;
        }

        // 3. 时间戳有效性验证（防止过期请求）
        long authTime;
        try {
            authTime = Long.parseLong(timeStamp);
        } catch (NumberFormatException e) {
            setErrorResponse(request,response,ErrorStatusEnum.SHA_TIMESTAMP_EXPIRE.getCode(), "时间戳格式错误");
            return false;
        }

        long nowTime = System.currentTimeMillis() - authTime;
        // 检查时间戳是否在有效期内（默认10分钟）
        if (nowTime > expiresTime * 60 * 1000L || nowTime < 0) {
            setErrorResponse(request,response,ErrorStatusEnum.SHA_TIMESTAMP_EXPIRE.getCode(), ErrorStatusEnum.SHA_TIMESTAMP_EXPIRE.getMassage());
            return false;
        }


        // 4. 获取应用密钥
        String appSecret = appList.stream()
                .filter(app -> app.getAppKey().equals(appKey))
                .map(BaseApp::getAppSecret)
                .findFirst()
                .orElse("");

        if (appSecret.isEmpty()) {
            setErrorResponse(request,response,ErrorStatusEnum.API_AUTH_ERROR.getCode(),"AppKey不存在");
            return false;
        }

        // 5. 构建签名数据并验证
        String buildData = this.buildData(request, appKey, timeStamp, nonce);
        boolean verify = SM3Util.verify(buildData, appSecret, sign);

        if (!verify) {
            log.warn("签名验证失败 - AppKey: {}, IP: {}, 签名数据: {}",
                    appKey, RequestUtils.getIp(request), buildData);
            setErrorResponse(request,response,ErrorStatusEnum.API_AUTH_ERROR.getCode(),ErrorStatusEnum.API_AUTH_ERROR.getMassage());
        }
        return verify;
    }

    /**
     * 构建签名数据
     * @return
     */
    private String buildData(HttpServletRequest request, String appKey, String timestamp, String nonce) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return method + "_" + path + "_" + appKey + "_" + timestamp + "_" + nonce;
    }
}
