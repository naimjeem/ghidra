/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.program.database.data;

import java.lang.ref.SoftReference;
import java.lang.reflect.Array;
import java.util.Map;

import ghidra.util.Lock;

/**
 * Instances of this class will provide a simple map interface to a cached set of key,value
 * pairs.  This class requires that the map can be generated from scratch at any time and
 * that adding/removing items from this map is just a mirroring of those changes elsewhere.
 * This map is lazy in that it won't load the data until needed and it will use a soft reference
 * to maintain the map until such time as the java garbage collector decides to reclaim it.
 * <p>
 * This class uses a ghidra Lock object to coordinate threaded access when loading the
 * underlying map data.  It manages both the lock and its own synchronization to avoid
 * race conditions and deadlocks.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 */
public abstract class LazyLoadingCachingMap<K, V> {

	private Lock lock;
	private SoftReference<Map<K, V>> softRef;
	private Class<V> valueClass;

	protected LazyLoadingCachingMap(Lock lock, Class<V> valueClass) {
		this.lock = lock;
		this.valueClass = valueClass;
	}

	/**
	 * This method will reload the map data from scratch.
	 * @return a map containing all current key, value pairs.
	 */
	protected abstract Map<K, V> loadMap();

	/**
	 * Adds the key,value pair to the map.  If the map is not loaded, this method will do nothing.
	 * @param key the key
	 * @param value the value that is associated with the key.
	 */
	public synchronized void put(K key, V value) {
		Map<K, V> map = getMap();
		if (map != null) {
			map.put(key, value);
		}
	}

	/**
	 * Removes the key,value pair from the map as specified by the given key.  If the map is
	 * currently not loaded, this method will do nothing.
	 * @param key the key to remove from the map.
	 */
	public synchronized void remove(K key) {
		Map<K, V> map = getMap();
		if (map != null) {
			map.remove(key);
		}
	}

	/**
	 * Removes any cached map of values and restores the map to its initial state.
	 */
	public synchronized void clear() {
		softRef = null;
	}

	/**
	 * Retrieves the value for the given key.  This will currently load the map if not already
	 * loaded.
	 * @param key the key for whose value to retrieve.
	 * @return the value for the given key.
	 */
	public V get(K key) {
		Map<K, V> map = getOrLoadMap();
		synchronized (this) {
			return map.get(key);
		}
	}

	public V[] valuesToArray() {
		Map<K, V> map = getOrLoadMap();
		synchronized (this) {
			@SuppressWarnings("unchecked")
			V[] array = (V[]) Array.newInstance(valueClass, map.size());
			return map.values().toArray(array);
		}
	}

	private Map<K, V> getOrLoadMap() {
		Map<K, V> map;
		synchronized (this) {
			map = getMap();
			if (map != null) {
				return map;
			}
		}
		lock.acquire();
		try {
			map = getMap();
			if (map == null) {
				map = loadMap();
				softRef = new SoftReference<>(map);
			}
			return map;
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Note: this map is always called from either a synchronized block or code holding the
	 * "lock".
	 * @return the underlying map of key,value pairs or null if it is currently not loaded.
	 */
	private Map<K, V> getMap() {
		if (softRef == null) {
			return null;
		}
		return softRef.get();
	}

}
