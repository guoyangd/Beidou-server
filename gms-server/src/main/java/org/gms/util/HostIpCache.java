package org.gms.util;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 域名 -> IPv4 的进程内缓存，面向 wan-host 等以 DDNS 域名配置的场景。
 *
 * 背景：选角/转服等入口需要把频道地址（InetAddress）下发给客户端。若每次现场对 DDNS 域名做
 * InetAddress.getByName，容器内 DNS 一旦抖动（UnknownHostException），该次登录会静默中断，
 * 且账号已被置为转场态，玩家紧接着的重试还会撞上"已登录"（错误码 7）。另外客户端封包中的
 * IP 字段只有 4 字节，必须下发 IPv4，因此这里从 getAllByName 结果中显式挑 IPv4。
 *
 * 行为约定：
 * - 热路径命中缓存立即返回，缓存过期也返回旧值，仅异步刷新（DNS 故障期间永远沿用上次成功 IP）；
 * - 首次未缓存才同步解析一次（正常情况下由启动预热兜住，不会在 Netty IO 线程上做 DNS）；
 * - 后台线程周期刷新全部已缓存域名，DDNS 变更最迟一个刷新周期内生效并打变更日志；
 * - 解析失败按"状态变化 + 降频"记日志（首次失败、每 20 次连续失败、恢复时），避免 30s 一次刷屏；
 * - 纯 IP 字面量入参只走缓存不打日志；
 * - 解析失败且从未成功过时返回 null，由调用方决定降级（getInetSocket 返回 null -> 客户端收错误包而非卡死）。
 */
@Slf4j
public final class HostIpCache {
    /** 缓存过期阈值，超过后热路径触发一次异步刷新 */
    private static final long STALE_MS = 30_000;
    /** 后台周期刷新间隔，即 DDNS 变更的最长生效延迟 */
    private static final long REFRESH_MS = 30_000;
    /** DNS 持续故障期间每 N 次失败补一条 WARN */
    private static final int FAIL_LOG_INTERVAL = 20;
    /** 解析到新IP时需连续 N 次一致才采纳（防双DDNS更新源互搏导致来回跳） */
    private static final int CHANGE_CONFIRM_REFRESHES = 2;

    private static final Map<String, CachedHost> CACHE = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REFRESHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "host-ip-cache-refresher");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean refresherStarted = false;

    private static final class CachedHost {
        volatile String ip;
        volatile long resolvedAt;
        /** 变更确认机制：新解析到的IP与当前不同时，需连续出现才采纳 */
        volatile String pendingIp;
        volatile int pendingCount;
        /** wan 域名路径为 false（拒收保留地址），localhost/lan-host 管理员配置路径为 true */
        volatile boolean allowReservedIp;
        final AtomicBoolean refreshing = new AtomicBoolean(false);
        final AtomicInteger failStreak = new AtomicInteger(0);
    }

    private HostIpCache() {
    }

    /**
     * 启动后台周期刷新，并同步预热指定 host。
     * 解析失败不抛异常（返回 null），不阻塞启动流程，后续后台任务会持续重试。
     */
    public static synchronized String prewarm(String host, boolean allowReservedIp) {
        if (host == null || host.isEmpty()) {
            return host;
        }
        if (!refresherStarted) {
            refresherStarted = true;
            REFRESHER.scheduleWithFixedDelay(HostIpCache::refreshAll, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS);
        }
        return resolve(host, allowReservedIp);
    }

    /**
     * 热路径取 IP 字面量。
     *
     * @param allowReservedIp true 用于管理员显式配置的地址（localhost/lan-host，私网地址是合法的）；
     *                        false 用于 wan 域名解析（解析出私网/保留地址视为记录被污染，拒收沿用缓存）。
     *                        IP 字面量入参不触发 DNS，行为与 InetAddress.getByName 一致。
     */
    public static String resolve(String host, boolean allowReservedIp) {
        if (host == null || host.isEmpty()) {
            return host;
        }
        CachedHost cached = CACHE.computeIfAbsent(host, k -> new CachedHost());
        cached.allowReservedIp |= allowReservedIp;
        String ip = cached.ip;
        if (ip != null) {
            if (System.currentTimeMillis() - cached.resolvedAt >= STALE_MS) {
                refreshAsync(host, cached);
            }
            return ip;
        }
        return refresh(host, cached);
    }

    private static String refresh(String host, CachedHost cached) {
        boolean isName = host.chars().anyMatch(Character::isLetter);
        try {
            String ip = resolveIpv4(host);
            if (!cached.allowReservedIp && isReservedIp(ip)) {
                // DDNS 记录被写坏成私网/保留地址（如实测出现过的 10.0.0.1）或解析被劫持时，
                // 拒绝采纳，按解析失败处理沿用缓存，避免把无效地址下发给客户端
                int streak = cached.failStreak.incrementAndGet();
                if (isName && (streak == 1 || streak % FAIL_LOG_INTERVAL == 0)) {
                    log.warn(I18nUtil.getLogMessage("HostIpCache.bogus"), host, ip, streak, Objects.toString(cached.ip));
                }
                return cached.ip;
            }
            String old = cached.ip;
            if (old == null) {
                cached.failStreak.set(0);
                cached.ip = ip;
                cached.resolvedAt = System.currentTimeMillis();
                cached.pendingIp = null;
                cached.pendingCount = 0;
                if (isName) {
                    log.info(I18nUtil.getLogMessage("HostIpCache.success"), host, ip);
                }
                return ip;
            }
            if (!ip.equals(old)) {
                // 变更需连续 CHANGE_CONFIRM_REFRESHES 次解析一致才采纳：
                // 域名在两个IP间互搏（如双DDNS更新源打架）时保持服务旧IP，不来回跳
                if (ip.equals(cached.pendingIp)) {
                    cached.pendingCount++;
                } else {
                    cached.pendingIp = ip;
                    cached.pendingCount = 1;
                }
                if (cached.pendingCount < CHANGE_CONFIRM_REFRESHES) {
                    cached.resolvedAt = System.currentTimeMillis();
                    return old;
                }
            }
            int prevFails = cached.failStreak.getAndSet(0);
            cached.ip = ip;
            cached.resolvedAt = System.currentTimeMillis();
            cached.pendingIp = null;
            cached.pendingCount = 0;
            if (isName) {
                if (!ip.equals(old)) {
                    log.info(I18nUtil.getLogMessage("HostIpCache.changed"), host, old, ip);
                } else if (prevFails > 0) {
                    log.info(I18nUtil.getLogMessage("HostIpCache.recovered"), host, ip, prevFails);
                }
            }
            return ip;
        } catch (UnknownHostException e) {
            int streak = cached.failStreak.incrementAndGet();
            if (isName && (streak == 1 || streak % FAIL_LOG_INTERVAL == 0)) {
                log.warn(I18nUtil.getLogMessage("HostIpCache.fail"), host, streak, e.getMessage(), Objects.toString(cached.ip));
            }
            return cached.ip;
        }
    }

    /** 私网/回环/链路本地/CGNAT/组播等不能作为公网 wan 地址的判断（IPv4） */
    private static boolean isReservedIp(String ip) {
        if (ip == null) {
            return true;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return true;
        }
        int a, b, c;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
            c = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return true;
        }
        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return true;
        }
        if (a == 169 && b == 254) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return true;
        }
        // RFC 6890 文档/基准段：TEST-NET(192.0.2/24、198.51.100/24、203.0.113/24)、
        // 基准测试(198.18/15)、IETF 协议赋值(192.0.0/24)，正常 DDNS 记录不会指到这些段
        if (a == 192 && b == 0 && (c == 0 || c == 2)) {
            return true;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return true;
        }
        if (a == 198 && b == 51 && c == 100) {
            return true;
        }
        return a == 203 && b == 0 && c == 113;
    }

    /** 客户端封包的 IP 字段只有 4 字节，从全部解析结果中优先挑 IPv4，避免拿到 AAAA 记录 */
    private static String resolveIpv4(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        InetAddress fallback = null;
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
            if (fallback == null) {
                fallback = address;
            }
        }
        if (fallback == null) {
            throw new UnknownHostException(host);
        }
        return fallback.getHostAddress();
    }

    private static void refreshAsync(String host, CachedHost cached) {
        if (!cached.refreshing.compareAndSet(false, true)) {
            return;
        }
        REFRESHER.execute(() -> {
            try {
                refresh(host, cached);
            } finally {
                cached.refreshing.set(false);
            }
        });
    }

    private static void refreshAll() {
        CACHE.forEach((host, cached) -> refresh(host, cached));
    }
}
