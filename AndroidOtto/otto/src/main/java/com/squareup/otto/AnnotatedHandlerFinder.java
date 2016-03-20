/*
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.squareup.otto;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Helper methods for finding methods annotated with {@link Produce} and {@link Subscribe}.
 *
 * @author Cliff Biffle
 * @author Louis Wasserman
 * @author Jake Wharton
 */
final class AnnotatedHandlerFinder {

	/**
	 * Cache event bus producer methods for each class.
	 *
	 * 订阅对象的类   对应 map<事件类型 , Produce 生产者  注解 方法 >
	 */
	private static final ConcurrentMap<Class<?>, Map<Class<?>, Method>> PRODUCERS_CACHE =
			new ConcurrentHashMap<Class<?>, Map<Class<?>, Method>>();

	/**
	 * Cache event bus subscriber methods for each class.
	 *
	 * 订阅对象的类   对应 map<事件类型 , Subscribe 订阅者 注解 方法 集合>
	 *
	 *     存在 一个类中 有多个方法订阅同一个事件
	 */
	private static final ConcurrentMap<Class<?>, Map<Class<?>, Set<Method>>> SUBSCRIBERS_CACHE =
			new ConcurrentHashMap<Class<?>, Map<Class<?>, Set<Method>>>();

	/**
	 *
	 * @param listenerClass 注册者的 类
	 * @param producerMethods map<事件类型 , Produce 生产者  注解 方法 >
	 */
	private static void loadAnnotatedProducerMethods(Class<?> listenerClass,
													 Map<Class<?>, Method> producerMethods) {
		Map<Class<?>, Set<Method>> subscriberMethods = new HashMap<Class<?>, Set<Method>>();
		loadAnnotatedMethods(listenerClass, producerMethods, subscriberMethods);
	}

	/**
	 * @param listenerClass 注册者的 类
	 * @param subscriberMethods map<事件类型 , Subscribe 订阅者 注解 方法 >
	 */
	private static void loadAnnotatedSubscriberMethods(Class<?> listenerClass,
													   Map<Class<?>, Set<Method>> subscriberMethods) {
		Map<Class<?>, Method> producerMethods = new HashMap<Class<?>, Method>();
		loadAnnotatedMethods(listenerClass, producerMethods, subscriberMethods);
	}

	/**
	 *
	 * Load all methods annotated with {@link Produce} or {@link Subscribe} into their respective caches for the
	 * specified class.
	 * @param listenerClass 注册者的 类
	 * @param producerMethods map<事件类型 ,  Produce 生产者  注解 方法 >
	 * @param subscriberMethods map<事件类型 , Subscribe 订阅者 注解 方法 >
	 */
	private static void loadAnnotatedMethods(Class<?> listenerClass,
											 Map<Class<?>, Method> producerMethods, Map<Class<?>, Set<Method>> subscriberMethods) {

		// 遍历  注册者 的所有方法
		for (Method method : listenerClass.getDeclaredMethods()) {
			// The compiler sometimes creates synthetic bridge methods as part of the
			// type erasure process. As of JDK8 these methods now include the same
			// annotations as the original declarations. They should be ignored for
			// subscribe/produce.
			// 一线编译器 产生的 桥接方法 应该被护绿
			if (method.isBridge()) {
				continue;
			}
			if (method.isAnnotationPresent(Subscribe.class)) {
				// 订阅者的 注解
				Class<?>[] parameterTypes = method.getParameterTypes();
				if (parameterTypes.length != 1) {
					// 只能哟一个参数
					throw new IllegalArgumentException("Method " + method + " has @Subscribe annotation but requires "
							+ parameterTypes.length + " arguments.  Methods must require a single argument.");
				}

				Class<?> eventType = parameterTypes[0];
				if (eventType.isInterface()) {
					// 事件类型不能是接口
					throw new IllegalArgumentException("Method " + method + " has @Subscribe annotation on " + eventType
							+ " which is an interface.  Subscription must be on a concrete class type.");
				}

				if ((method.getModifiers() & Modifier.PUBLIC) == 0) {
					// 方法必须是public
					throw new IllegalArgumentException("Method " + method + " has @Subscribe annotation on " + eventType
							+ " but is not 'public'.");
				}

				// 加入 这个种 事件类型的对应的 集合
				// 这里加入 集合 没有其他特殊的判断,  不像 EventBus 只能有一种类型的订阅者 方法
				// 这里可以有多个 方法  订阅 同一个事件
				Set<Method> methods = subscriberMethods.get(eventType);
				if (methods == null) {
					methods = new HashSet<Method>();
					subscriberMethods.put(eventType, methods);
				}
				methods.add(method);
			} else if (method.isAnnotationPresent(Produce.class)) {
				// 生产者注解
				Class<?>[] parameterTypes = method.getParameterTypes();
				if (parameterTypes.length != 0) {
					// 生产者注解方法 必须没有参数
					throw new IllegalArgumentException("Method " + method + "has @Produce annotation but requires "
							+ parameterTypes.length + " arguments.  Methods must require zero arguments.");
				}
				if (method.getReturnType() == Void.class) {
					// 返回值不能为空
					throw new IllegalArgumentException("Method " + method
							+ " has a return type of void.  Must declare a non-void type.");
				}

				Class<?> eventType = method.getReturnType();
				if (eventType.isInterface()) {
					// 返回值不能是接口
					throw new IllegalArgumentException("Method " + method + " has @Produce annotation on " + eventType
							+ " which is an interface.  Producers must return a concrete class type.");
				}
				if (eventType.equals(Void.TYPE)) {
					// 返回值 不能死 Void 类型
					throw new IllegalArgumentException("Method " + method + " has @Produce annotation but has no return type.");
				}

				if ((method.getModifiers() & Modifier.PUBLIC) == 0) {
					// 必须是 public 方法
					throw new IllegalArgumentException("Method " + method + " has @Produce annotation on " + eventType
							+ " but is not 'public'.");
				}

				if (producerMethods.containsKey(eventType)) {
					// 生产者 集合中 已经有这种方法,  表示 一个类中 不能 多个方法 使用 Produce 注册同一个类型
					throw new IllegalArgumentException("Producer for type " + eventType + " has already been registered.");
				}
				// 加入集合
				producerMethods.put(eventType, method);
			}
		}

		// 加入缓存
		PRODUCERS_CACHE.put(listenerClass, producerMethods);
		SUBSCRIBERS_CACHE.put(listenerClass, subscriberMethods);
	}

	/**
	 * This implementation finds all methods marked with a {@link Produce} annotation.
	 *
	 * 更具注解 找出所有的生产者 方法
	 */
	static Map<Class<?>, EventProducer> findAllProducers(Object listener) {
		final Class<?> listenerClass = listener.getClass();
		Map<Class<?>, EventProducer> handlersInMethod = new HashMap<Class<?>, EventProducer>();

		// 先从缓存中 获取  该类 对应 map<事件类型 , 订阅方法 >
		// 这里的map 是  map<事件类型 , 订阅方法 >
		Map<Class<?>, Method> methods = PRODUCERS_CACHE.get(listenerClass);
		if (null == methods) {
			methods = new HashMap<Class<?>, Method>();
			// 通过注解 获取 map<事件类型 , 订阅方法 >
			loadAnnotatedProducerMethods(listenerClass, methods);
		}
		if (!methods.isEmpty()) {
			for (Map.Entry<Class<?>, Method> e : methods.entrySet()) {
				// 更具事件类型   生产者方法 勾着一个 事件生产者
				EventProducer producer = new EventProducer(listener, e.getValue());
				handlersInMethod.put(e.getKey(), producer);
			}
		}

		return handlersInMethod;
	}

	/**
	 * This implementation finds all methods marked with a {@link Subscribe} annotation.
	 * 找到所有的 订阅者 方法
	 */
	static Map<Class<?>, Set<EventHandler>> findAllSubscribers(Object listener) {
		Class<?> listenerClass = listener.getClass();
		Map<Class<?>, Set<EventHandler>> handlersInMethod = new HashMap<Class<?>, Set<EventHandler>>();

		// 先从缓存张昭 , 离乱上 只要执行了一遍  findAllProducers 或 findAllSubscribers
		// 那么  缓存 SUBSCRIBERS_CACHE PRODUCERS_CACHE 都会有相应的值了  就不需要 再次遍历方法了
		Map<Class<?>, Set<Method>> methods = SUBSCRIBERS_CACHE.get(listenerClass);
		if (null == methods) {
			methods = new HashMap<Class<?>, Set<Method>>();
			loadAnnotatedSubscriberMethods(listenerClass, methods);
		}
		if (!methods.isEmpty()) {
			for (Map.Entry<Class<?>, Set<Method>> e : methods.entrySet()) {
				// 存在 一个类中有 有多个订阅方法的 情况
				Set<EventHandler> handlers = new HashSet<EventHandler>();
				for (Method m : e.getValue()) {
					handlers.add(new EventHandler(listener, m));
				}
				handlersInMethod.put(e.getKey(), handlers);
			}
		}

		return handlersInMethod;
	}

	private AnnotatedHandlerFinder() {
		// No instances.
	}

}
