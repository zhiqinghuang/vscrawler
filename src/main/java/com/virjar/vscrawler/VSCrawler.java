package com.virjar.vscrawler;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.virjar.dungproxy.client.util.CommonUtil;
import com.virjar.vscrawler.event.EventLoop;
import com.virjar.vscrawler.net.session.CrawlerSession;
import com.virjar.vscrawler.net.session.CrawlerSessionPool;
import com.virjar.vscrawler.processor.CrawlResult;
import com.virjar.vscrawler.processor.HtmlDownLoadProcessor;
import com.virjar.vscrawler.processor.IProcessor;
import com.virjar.vscrawler.seed.SeedManager;
import com.virjar.vscrawler.serialize.ConsolePipline;
import com.virjar.vscrawler.serialize.Pipline;

/**
 * Created by virjar on 17/4/16. <br/>
 * 爬虫入口,目前很多逻辑参考了webmagic
 */
public class VSCrawler implements Runnable {

    private CrawlerSessionPool crawlerSessionPool;
    private SeedManager seedManager;
    private IProcessor iProcessor;
    private List<Pipline> pipline = Lists.newArrayList();
    private int threadNumber;

    protected CountableThreadPool threadPool;
    protected ExecutorService executorService;
    private Date startTime;

    protected AtomicInteger stat = new AtomicInteger(STAT_INIT);

    protected final static int STAT_INIT = 0;

    protected final static int STAT_RUNNING = 1;

    protected final static int STAT_STOPPED = 2;

    protected boolean exitWhenComplete = true;

    /**
     * 慢启动,默认为true,慢启动打开后,爬虫启动的时候线程不会瞬间变到最大,否则这个时候并发应该是最大的,因为这个时候没有线程阻塞, 另外考虑有些 资源分配问题,慢启动避免初始化的时候初始化资源请求qps过高
     */
    protected boolean slowStart = true;

    /**
     * 慢启动过程是10分钟默认
     */
    protected long slowStartDuration = 5 * 60 * 1000;

    private int slowStartTimes = 0;

    private static final Logger logger = LoggerFactory.getLogger(VSCrawler.class);

    public VSCrawler() {

    }

    public void start() {
        new Thread(this).start();
    }

    @Override
    public void run() {
        checkRunningStat();
        initComponent();
        logger.info("Spider  started!");
        while (!Thread.currentThread().isInterrupted() && stat.get() == STAT_RUNNING) {
            final String request = seedManager.consumeSeed();
            if (request == null) {
                if (threadPool.getThreadAlive() == 0 && exitWhenComplete) {
                    break;
                }
                // wait until new url added
                // waitNewUrl();
            } else {
                threadPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            processRequest(request);
                        } catch (Exception e) {
                            logger.error("process request {} error", request, e);
                        } finally {

                        }
                    }
                });
                if (slowStart && slowStartTimes < threadNumber - 1) {
                    CommonUtil.sleep(slowStartDuration / threadNumber);
                    slowStartTimes++;
                }
            }
        }
        stat.set(STAT_STOPPED);
    }

    protected void processRequest(String request) {

        CrawlerSession session = null;
        while (true) {
            // 暂时死循环等待,对于一个完善产品不应该这样
            // 从session池里面获取一个session,如果需要登录,那么得到的session必然是登录成功的
            session = crawlerSessionPool.borrowOne();
            if (session != null) {
                break;
            }
            CommonUtil.sleep(500);
        }
        boolean sessionEnabled = true;
        try {
            CrawlResult process = iProcessor.process(request, session);
            List<String> newSeed = process.getNewSeed();
            if (newSeed != null) {
                for (String seed : newSeed) {
                    seedManager.addSeed(seed);
                }
            }
            if (process.getResult() != null) {
                for (Pipline p : pipline) {
                    p.saveItem(process.getResult());
                }
            }
            if (process.isRetry()) {
                seedManager.addSeedFoce(request);
            }
            if (!process.isSessionEnable()) {
                sessionEnabled = false;
            }
        } finally {
            // 归还一个session,session有并发控制,feedback之后session才能被其他任务复用
            // 如果标记session失效,则会停止分发此session,同时异步触发登录逻辑
            session.feedback(sessionEnabled);
        }
    }

    private void checkRunningStat() {
        while (true) {
            int statNow = stat.get();
            if (statNow == STAT_RUNNING) {
                throw new IllegalStateException("Spider is already running!");
            }
            if (stat.compareAndSet(statNow, STAT_RUNNING)) {
                break;
            }
        }
    }

    protected void initComponent() {

        if (threadNumber == 0) {
            threadNumber = 10;
        }

        if (crawlerSessionPool == null) {
            crawlerSessionPool = new CrawlerSessionPool(threadNumber);
        }

        if (seedManager == null) {
            seedManager = new SeedManager("seedConfig.properties");
        }

        if (iProcessor == null) {
            iProcessor = new HtmlDownLoadProcessor();
        }

        if (pipline.size() == 0) {
            pipline.add(new ConsolePipline());
        }

        if (threadPool == null || threadPool.isShutdown()) {
            if (executorService != null && !executorService.isShutdown()) {
                threadPool = new CountableThreadPool(threadNumber, executorService);
            } else {
                threadPool = new CountableThreadPool(threadNumber);
            }
        }

        startTime = new Date();
        // 开启事件循环
        EventLoop.getInstance().loop();
    }

    public VSCrawler setThreadNumber(int threadNumber) {
        this.threadNumber = threadNumber;
        return this;
    }

    public static VSCrawler create() {
        return new VSCrawler();
    }

    public VSCrawler setCrawlerSessionPool(CrawlerSessionPool crawlerSessionPool) {
        this.crawlerSessionPool = crawlerSessionPool;
        return this;
    }

    public VSCrawler setiProcessor(IProcessor iProcessor) {
        this.iProcessor = iProcessor;
        return this;
    }

    public VSCrawler addPipline(Pipline pipline) {
        this.pipline.add(pipline);
        return this;
    }

    public VSCrawler setSeedManager(SeedManager seedManager) {
        this.seedManager = seedManager;
        return this;
    }

}
