/**
 * Created on  13-10-17 23:01
 */
package com.alicp.jetcache.embedded;

import com.alicp.jetcache.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author huangli
 */
public abstract class AbstractEmbeddedCache<K, V> extends AbstractCache<K, V> {
    protected EmbeddedCacheConfig<K, V> config;
    protected InnerMap innerMap;

    protected abstract InnerMap createAreaCache();

    private final ReentrantLock lock = new ReentrantLock();

    public AbstractEmbeddedCache(EmbeddedCacheConfig<K, V> config) {
        this.config = config;
        innerMap = createAreaCache();
    }

    @Override
    public CacheConfig<K, V> config() {
        return config;
    }

    public Object buildKey(K key) {
        Object newKey = key;
        Function<K, Object> keyConvertor = config.getKeyConvertor();
        if (keyConvertor != null) {
            newKey = keyConvertor.apply(key);
        }
        return newKey;
    }

    @Override
    protected CacheGetResult<V> do_GET(K key) {
        Object newKey = buildKey(key);
        CacheValueHolder<V> holder = (CacheValueHolder<V>) innerMap.getValue(newKey);
        return parseHolderResult(holder);
    }

    protected CacheGetResult<V> parseHolderResult(CacheValueHolder<V> holder) {
        long now = System.currentTimeMillis();
        if (holder == null) {
            return CacheGetResult.NOT_EXISTS_WITHOUT_MSG;
        } else if (now >= holder.getExpireTime()) {
            return CacheGetResult.EXPIRED_WITHOUT_MSG;
        } else {
            lock.lock();
            try {
                long accessTime = holder.getAccessTime();
                if (config.isExpireAfterAccess()) {
                    long expireAfterAccess = config.getExpireAfterAccessInMillis();
                    if (now >= accessTime + expireAfterAccess) {
                        return CacheGetResult.EXPIRED_WITHOUT_MSG;
                    }
                }
                holder.setAccessTime(now);
            } finally {
                lock.unlock();
            }

            return new CacheGetResult(CacheResultCode.SUCCESS, null, holder);
        }
    }

    @Override
    protected MultiGetResult<K, V> do_GET_ALL(Set<? extends K> keys) {
        ArrayList<K> keyList = new ArrayList<K>(keys.size());
        ArrayList<Object> newKeyList = new ArrayList<Object>(keys.size());
        keys.stream().forEach((k) -> {
            Object newKey = buildKey(k);
            keyList.add(k);
            newKeyList.add(newKey);
        });
        Map<Object, CacheValueHolder<V>> innerResultMap = innerMap.getAllValues(newKeyList);
        Map<K, CacheGetResult<V>> resultMap = new HashMap<>();
        for (int i = 0; i < keyList.size(); i++) {
            K key = keyList.get(i);
            Object newKey = newKeyList.get(i);
            CacheValueHolder<V> holder = innerResultMap.get(newKey);
            resultMap.put(key, parseHolderResult(holder));
        }
        MultiGetResult<K, V> result = new MultiGetResult<>(CacheResultCode.SUCCESS, null, resultMap);
        return result;
    }

    @Override
    protected CacheResult do_PUT(K key, V value, long expireAfterWrite, TimeUnit timeUnit) {
        CacheValueHolder<V> cacheObject = new CacheValueHolder(value, timeUnit.toMillis(expireAfterWrite));
        innerMap.putValue(buildKey(key), cacheObject);
        return CacheResult.SUCCESS_WITHOUT_MSG;
    }

    @Override
    protected CacheResult do_PUT_ALL(Map<? extends K, ? extends V> map, long expireAfterWrite, TimeUnit timeUnit) {
        HashMap newKeyMap = new HashMap();
        for (Map.Entry<? extends K, ? extends V> en : map.entrySet()) {
            CacheValueHolder<V> cacheObject = new CacheValueHolder(en.getValue(), timeUnit.toMillis(expireAfterWrite));
            newKeyMap.put(buildKey(en.getKey()), cacheObject);
        }
        innerMap.putAllValues(newKeyMap);
        return CacheResult.SUCCESS_WITHOUT_MSG;
    }

    @Override
    protected CacheResult do_REMOVE(K key) {
        innerMap.removeValue(buildKey(key));
        return CacheResult.SUCCESS_WITHOUT_MSG;
    }

    @Override
    protected CacheResult do_REMOVE_ALL(Set<? extends K> keys) {
        Set newKeys = keys.stream().map((key) -> buildKey(key)).collect(Collectors.toSet());
        innerMap.removeAllValues(newKeys);

        return CacheResult.SUCCESS_WITHOUT_MSG;
    }

    // internal method
    public void __removeAll(Set<? extends K> keys) {
        innerMap.removeAllValues(keys);
    }

    @Override
    protected CacheResult do_PUT_IF_ABSENT(K key, V value, long expireAfterWrite, TimeUnit timeUnit) {
        CacheValueHolder<V> cacheObject = new CacheValueHolder(value, timeUnit.toMillis(expireAfterWrite));
        if (innerMap.putIfAbsentValue(buildKey(key), cacheObject)) {
            return CacheResult.SUCCESS_WITHOUT_MSG;
        } else {
            return CacheResult.EXISTS_WITHOUT_MSG;
        }
    }
}
