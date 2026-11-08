package com.example.productservice.config;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@TestConfiguration
@EnableCaching
@Profile("test")
public class TestCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(RedisConfig.PRODUCT_CACHE);
    }

    /**
     * Cung cấp Mock RedissonClient giả lập Distributed Lock bằng Java ReentrantLock
     * cho phép test concurrency 100 threads trong môi trường unit test tự động.
     */
    @Bean
    public RedissonClient redissonClient() {
        RedissonClient client = mock(RedissonClient.class);
        Map<String, RLock> lockMap = new ConcurrentHashMap<>();

        when(client.getLock(anyString())).thenAnswer(invocation -> {
            String lockName = invocation.getArgument(0);
            return lockMap.computeIfAbsent(lockName, k -> {
                ReentrantLock internalLock = new ReentrantLock();
                RLock rLock = mock(RLock.class);
                try {
                    when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                            .thenAnswer(inv -> internalLock.tryLock(inv.getArgument(0), inv.getArgument(2)));
                    when(rLock.tryLock(anyLong(), any(TimeUnit.class)))
                            .thenAnswer(inv -> internalLock.tryLock(inv.getArgument(0), inv.getArgument(1)));
                    when(rLock.tryLock())
                            .thenAnswer(inv -> internalLock.tryLock());
                    when(rLock.isHeldByCurrentThread())
                            .thenAnswer(inv -> internalLock.isHeldByCurrentThread());
                    doAnswer(inv -> {
                        if (internalLock.isHeldByCurrentThread()) {
                            internalLock.unlock();
                        }
                        return null;
                    }).when(rLock).unlock();
                } catch (Exception ignored) {
                }
                return rLock;
            });
        });

        return client;
    }
}
