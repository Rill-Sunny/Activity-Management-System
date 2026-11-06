package com.limitactivitysystem.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

@Component
public class RedisCacheStore {
    private static final String GENERATION_PREFIX = "las:cache-generation:";
    private static final DefaultRedisScript<String> READ_CURRENT = new DefaultRedisScript<>("""
            local generation = redis.call('GET', KEYS[1])
            local cached = redis.call('GET', KEYS[2])
            if not generation or not cached then return nil end
            local marker = string.match(cached, '^([^|]+)|')
            if not marker or marker ~= generation then return nil end
            return string.sub(cached, string.len(marker) + 2)
            """, String.class);
    private static final DefaultRedisScript<Long> WRITE_IF_CURRENT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              redis.call('SET', KEYS[1], ARGV[1])
              current = ARGV[1]
            end
            if current ~= ARGV[1] then return 0 end
            local now = redis.call('TIME')
            local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            if tonumber(ARGV[3]) <= nowMillis then return 0 end
            redis.call('SET', KEYS[2], ARGV[1] .. '|' .. ARGV[2], 'PXAT', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and tonumber(current) > tonumber(ARGV[1]) then return 0 end
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    @Value("${cache.redis.key-prefix:}")
    private String keyPrefix = "";

    public RedisCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String read(String cacheIdentity) {
        return redis.execute(READ_CURRENT, List.of(generationKey(cacheIdentity), cacheKey(cacheIdentity)));
    }

    public boolean writeIfGenerationMatches(String cacheIdentity, long generation, String value,
                                            long expiresAtEpochMillis) {
        Long written = redis.execute(WRITE_IF_CURRENT,
                List.of(generationKey(cacheIdentity), cacheKey(cacheIdentity)),
                Long.toString(generation), value, Long.toString(expiresAtEpochMillis));
        return Long.valueOf(1L).equals(written);
    }

    public void invalidate(String cacheIdentity, long generation) {
        redis.execute(INVALIDATE, List.of(generationKey(cacheIdentity), cacheKey(cacheIdentity)), Long.toString(generation));
    }

    private String generationKey(String cacheIdentity) {
        return keyPrefix + GENERATION_PREFIX + cacheIdentity;
    }

    private String cacheKey(String cacheIdentity) {
        return keyPrefix + cacheIdentity;
    }
}
