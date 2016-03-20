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

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;


/**
 * Dispatches events to listeners, and provides ways for listeners to register themselves.
 * <p/>
 * <p>The Bus allows publish-subscribe-style communication between components without requiring the components to
 * explicitly register with one another (and thus be aware of each other).  It is designed exclusively to replace
 * traditional Android in-process event distribution using explicit registration or listeners. It is <em>not</em> a
 * general-purpose publish-subscribe system, nor is it intended for interprocess communication.
 * <p/>
 * <h2>Receiving Events</h2>
 * To receive events, an object should:
 * <ol>
 * <li>Expose a public method, known as the <i>event handler</i>, which accepts a single argument of the type of event
 * desired;</li>
 * <li>Mark it with a {@link com.squareup.otto.Subscribe} annotation;</li>
 * <li>Pass itself to an Bus instance's {@link #register(Object)} method.
 * </li>
 * </ol>
 * <p/>
 * <h2>Posting Events</h2>
 * To post an event, simply provide the event object to the {@link #post(Object)} method.  The Bus instance will
 * determine the type of event and route it to all registered listeners.
 * <p/>
 * <p>Events are routed based on their type &mdash; an event will be delivered to any handler for any type to which the
 * event is <em>assignable.</em>  This includes implemented interfaces, all superclasses, and all interfaces implemented
 * by superclasses.
 * <p/>
 * <p>When {@code post} is called, all registered handlers for an event are run in sequence, so handlers should be
 * reasonably quick.  If an event may trigger an extended process (such as a database load), spawn a thread or queue it
 * for later.
 * <p/>
 * <h2>Handler Methods</h2>
 * Event handler methods must accept only one argument: the event.
 * <p/>
 * <p>Handlers should not, in general, throw.  If they do, the Bus will wrap the exception and
 * re-throw it.
 * <p/>
 * <p>The Bus by default enforces that all interactions occur on the main thread.  You can provide an alternate
 * enforcement by passing a {@link ThreadEnforcer} to the constructor.
 * <p/>
 * <h2>Producer Methods</h2>
 * Producer methods should accept no arguments and return their event type. When a subscriber is registered for a type
 * that a producer is also already registered for, the subscriber will be called with the return value from the
 * producer.
 * <p/>
 * <h2>Dead Events</h2>
 * If an event is posted, but no registered handlers can accept it, it is considered "dead."  To give the system a
 * second chance to handle dead events, they are wrapped in an instance of {@link com.squareup.otto.DeadEvent} and
 * reposted.
 * <p/>
 * <p>This class is safe for concurrent use.
 *
 * @author Cliff Biffle
 * @author Jake Wharton
 *
 *
 * 有几点需要注意:
 * 1. 有两个注解: @Subscribe @Produce
 * 		@Subscribe  表示是订阅者注解
 * 		@Produce	生产者注解
 *
 * 2. 当 一个对象 register的时候
 *	如果 有Produce 方法
 * 	那么 他会去找 已经注册的 相关订阅在方法
 * 	然后执行 Produce 方法  和 相关的订阅方法
 *
 * 	如果有 Subscribe 方法
 * 	那么回去 找相关的 记忆中的额 生产者方法
 * 	然后执行  相关的Produce 方法  和  Subscribe 方法
 */
public class Bus {
	public static final String DEFAULT_IDENTIFIER = "default";

	/**
	 * All registered event handlers, indexed by event type.
	 *
	 * map<事件类型, 事件订阅者 方法集合>
	 *
	 * 一个事件 对应多个方法
	 */
	private final ConcurrentMap<Class<?>, Set<EventHandler>> handlersByType =
			new ConcurrentHashMap<Class<?>, Set<EventHandler>>();

	/**
	 * All registered event producers, index by event type.
	 *
	 * 一种事件类型 只能 有一种生产者
	 * Map<事件类型,  生产者封装对象>
	 */
	private final ConcurrentMap<Class<?>, EventProducer> producersByType =
			new ConcurrentHashMap<Class<?>, EventProducer>();

	/**
	 * Identifier used to differentiate the event bus instance.
	 */
	private final String identifier;

	/**
	 * Thread enforcer for register, unregister, and posting events.
	 */
	private final ThreadEnforcer enforcer;

	/**
	 * Used to find handler methods in register and unregister.
	 */
	private final HandlerFinder handlerFinder;

	/**
	 * Queues of events for the current thread to dispatch.
	 *
	 * 一个 每个线程 都独立的 一个变量
	 *
	 * 通过get 取值  返回的是一个  ConcurrentLinkedQueue<EventWithHandler>()
	 *
	 * 就是 执行 订阅者方法的任务队列
	 */
	private final ThreadLocal<ConcurrentLinkedQueue<EventWithHandler>> eventsToDispatch =
			new ThreadLocal<ConcurrentLinkedQueue<EventWithHandler>>() {
				@Override
				protected ConcurrentLinkedQueue<EventWithHandler> initialValue() {
					return new ConcurrentLinkedQueue<EventWithHandler>();
				}
			};

	/**
	 * True if the current thread is currently dispatching an event.
	 *
	 * 同样 也是每个现场 都独立的一个 bool 数据
	 */
	private final ThreadLocal<Boolean> isDispatching = new ThreadLocal<Boolean>() {
		@Override
		protected Boolean initialValue() {
			return false;
		}
	};

	/**
	 * Creates a new Bus named "default" that enforces actions on the main thread.
	 *
	 * 构造一个默认的 Bus
	 */
	public Bus() {
		this(DEFAULT_IDENTIFIER);
	}

	/**
	 * Creates a new Bus with the given {@code identifier} that enforces actions on the main thread.
	 *
	 * @param identifier a brief name for this bus, for debugging purposes.  Should be a valid Java identifier.
	 *                   bus 的名字
	 */
	public Bus(String identifier) {
		this(ThreadEnforcer.MAIN, identifier);
	}

	/**
	 * Creates a new Bus named "default" with the given {@code enforcer} for actions.
	 *
	 * @param enforcer Thread enforcer for register, unregister, and post actions.
	 *                 是一个 限定的 线程的东西
	 */
	public Bus(ThreadEnforcer enforcer) {
		this(enforcer, DEFAULT_IDENTIFIER);
	}

	/**
	 * Creates a new Bus with the given {@code enforcer} for actions and the given {@code identifier}.
	 *
	 * @param enforcer   Thread enforcer for register, unregister, and post actions.
	 *                   约定 订阅者执行的线程
	 * @param identifier A brief name for this bus, for debugging purposes.  Should be a valid Java identifier.
	 *                   bus 名字
	 */
	public Bus(ThreadEnforcer enforcer, String identifier) {
		this(enforcer, identifier, HandlerFinder.ANNOTATED);
	}

	/**
	 * Test constructor which allows replacing the default {@code HandlerFinder}.
	 *
	 * @param enforcer      Thread enforcer for register, unregister, and post actions.
	 *                      约定订阅者 方法执行的线程
	 * @param identifier    A brief name for this bus, for debugging purposes.  Should be a valid Java identifier.
	 *                      bus 的名字
	 * @param handlerFinder Used to discover event handlers and producers when registering/unregistering an object.
	 *                      寻找注解的一个 帮助对象
	 */
	Bus(ThreadEnforcer enforcer, String identifier, HandlerFinder handlerFinder) {
		this.enforcer = enforcer;
		this.identifier = identifier;
		this.handlerFinder = handlerFinder;
	}

	@Override
	public String toString() {
		return "[Bus \"" + identifier + "\"]";
	}

	/**
	 * Registers all handler methods on {@code object} to receive events and producer methods to provide events.
	 * <p/>
	 * If any subscribers are registering for types which already have a producer they will be called immediately
	 * with the result of calling that producer.
	 * <p/>
	 * If any producers are registering for types which already have subscribers, each subscriber will be called with
	 * the value from the result of calling the producer.
	 *
	 * 注册
	 *
	 *  有几点需要注意:
	 * 1. 有两个注解: @Subscribe @Produce
	 * 		@Subscribe  表示是订阅者注解
	 * 		@Produce	生产者注解
	 *
	 * 2. 如果 有Produce 方法
	 * 	那么 他会去找 已经注册的 相关订阅在方法
	 * 	然后执行 Produce 方法  和 相关的订阅方法
	 *
	 * 3. 如果有 Subscribe 方法
	 * 	那么回去 找相关的 记忆中的额 生产者方法
	 * 	然后执行  相关的Produce 方法  和  Subscribe 方法
	 *
	 * @param object object whose handler methods should be registered.
	 * @throws NullPointerException if the object is null.
	 */
	public void register(Object object) {
		if (object == null) {
			throw new NullPointerException("Object to register must not be null.");
		}
		// 校验线程 默认的是 必须在主线程 不然 报错
		enforcer.enforce(this);

		// 获取这个类 所有的生产者 方法
		Map<Class<?>, EventProducer> foundProducers = handlerFinder.findAllProducers(object);
		for (Class<?> type : foundProducers.keySet()) {

			final EventProducer producer = foundProducers.get(type);
			// putIfAbsent ConcurrentMap 一个方法
			// 大概功能是 如果 存在 map 中有这个key  那么 就返回 这个key 对应的值
			// 如果没有这个key 那么就 把对应的key value 注入到map中
			EventProducer previousProducer = producersByType.putIfAbsent(type, producer);
			//checking if the previous producer existed
			if (previousProducer != null) {
				// 如果 之前 有值 那么这里抛出异常
				// 抛出 多次注册的异常

				// 生产者 方法 只能是一个
				throw new IllegalArgumentException("Producer method for type " + type
						+ " found on type " + producer.target.getClass()
						+ ", but already registered by type " + previousProducer.target.getClass() + ".");
			}
			// 获取 这个 事件 对应的 订阅者 方法
			Set<EventHandler> handlers = handlersByType.get(type);
			if (handlers != null && !handlers.isEmpty()) {
				for (EventHandler handler : handlers) {
					// 把这个事件 分发出去
					dispatchProducerResultToHandler(handler, producer);
				}
			}
		}

		// 获取所有的 订阅者 方法
		Map<Class<?>, Set<EventHandler>> foundHandlersMap = handlerFinder.findAllSubscribers(object);
		// 遍历 所有的事件类型 , 每一个事件类型 对应一个 Set 方法集合
		for (Class<?> type : foundHandlersMap.keySet()) {
			Set<EventHandler> handlers = handlersByType.get(type);
			// 获取 这个事件 对应的生产者集合
			// 咩有则创建
			if (handlers == null) {
				//concurrent put if absent
				Set<EventHandler> handlersCreation = new CopyOnWriteArraySet<EventHandler>();
				// 理论啊还是那个 这里 handlersByType 肯定没有对应 key == type 因为上面就判断过
				// 但是由于 可能有多线程的问题 这里再次判断下
				handlers = handlersByType.putIfAbsent(type, handlersCreation);
				if (handlers == null) {
					handlers = handlersCreation;
				}
			}

			final Set<EventHandler> foundHandlers = foundHandlersMap.get(type);
			// 吧当前 事件类型的 订阅者方法 全部加入集合
			if (!handlers.addAll(foundHandlers)) {
				// 由于是个 set 集合 所有如果加入失败, 说明已经存在了 已经订阅 抛出异常
				throw new IllegalArgumentException("Object already registered.");
			}
		}

		// 遍历 所有的事件类型 , 每一个事件类型 对应一个 Set 方法集合
		for (Map.Entry<Class<?>, Set<EventHandler>> entry : foundHandlersMap.entrySet()) {
			Class<?> type = entry.getKey();
			// 获取该事件的 生产者
			EventProducer producer = producersByType.get(type);
			if (producer != null && producer.isValid()) {
				//生产者 有效
				Set<EventHandler> foundHandlers = entry.getValue();
				for (EventHandler foundHandler : foundHandlers) {
					if (!producer.isValid()) {
						break;
					}
					if (foundHandler.isValid()) {
						// 如果生产者  和 订阅者 都有效 那么 分发事件
						// 执行 生产者 方法 和订阅者方法
						dispatchProducerResultToHandler(foundHandler, producer);
					}
				}
			}
		}
	}

	/**
	 * 处理分发 事件
	 * @param handler 事件 处理独享
	 * @param producer 事件产生对象
	 */
	private void dispatchProducerResultToHandler(EventHandler handler, EventProducer producer) {
		Object event = null;
		try {
			// 生成事件
			event = producer.produceEvent();
		} catch (InvocationTargetException e) {
			throwRuntimeException("Producer " + producer + " threw an exception.", e);
		}
		if (event == null) {
			return;
		}
		//
		dispatch(event, handler);
	}

	/**
	 * Unregisters all producer and handler methods on a registered {@code object}.
	 *
	 * 解除注册
	 *
	 * @param object object whose producer and handler methods should be unregistered.
	 * @throws IllegalArgumentException if the object was not previously registered.
	 * @throws NullPointerException     if the object is null.
	 */
	public void unregister(Object object) {
		if (object == null) {
			throw new NullPointerException("Object to unregister must not be null.");
		}
		// 校验线程
		enforcer.enforce(this);

		// 获取这个类所有的生产者
		Map<Class<?>, EventProducer> producersInListener = handlerFinder.findAllProducers(object);
		for (Map.Entry<Class<?>, EventProducer> entry : producersInListener.entrySet()) {
			final Class<?> key = entry.getKey();
			EventProducer producer = getProducerForEventType(key);
			EventProducer value = entry.getValue();

			if (value == null || !value.equals(producer)) {
				throw new IllegalArgumentException(
						"Missing event producer for an annotated method. Is " + object.getClass()
								+ " registered?");
			}
			// 想出 相应的 生产者 且设置为 无效
			// 这里可以看到 invalidate 这个变量的好处,
			// 如果这里仅仅是移除, 其实这对象 短时间 还是存在的 那么就可能导致出现一些问题
			// 加上 invalidate 约束就好了
			producersByType.remove(key).invalidate();
		}

		// 获取所有的订阅者
		Map<Class<?>, Set<EventHandler>> handlersInListener = handlerFinder.findAllSubscribers(object);
		for (Map.Entry<Class<?>, Set<EventHandler>> entry : handlersInListener.entrySet()) {
			Set<EventHandler> currentHandlers = getHandlersForEventType(entry.getKey());
			Collection<EventHandler> eventMethodsInListener = entry.getValue();

			if (currentHandlers == null || !currentHandlers.containsAll(eventMethodsInListener)) {
				throw new IllegalArgumentException(
						"Missing event handler for an annotated method. Is " + object.getClass()
								+ " registered?");
			}

			for (EventHandler handler : currentHandlers) {
				if (eventMethodsInListener.contains(handler)) {
					//订阅者 设置为 无效
					handler.invalidate();
				}
			}
			// 移除所有的订阅者
			currentHandlers.removeAll(eventMethodsInListener);
		}
	}

	/**
	 * Posts an event to all registered handlers.  This method will return successfully after the event has been posted to
	 * all handlers, and regardless of any exceptions thrown by handlers.
	 * <p/>
	 * <p>If no handlers have been subscribed for {@code event}'s class, and {@code event} is not already a
	 * {@link DeadEvent}, it will be wrapped in a DeadEvent and reposted.
	 *
	 * 发送一个事件
	 *
	 * @param event event to post.
	 * @throws NullPointerException if the event is null.
	 */
	public void post(Object event) {
		if (event == null) {
			throw new NullPointerException("Event to post must not be null.");
		}
		// 校验线程
		enforcer.enforce(this);

		// 获取这个事件 所有的 父类 接口 集合 也包括自己
		// 由于 泛型 他们的父类 也可能是 其他 订阅者的事件
		Set<Class<?>> dispatchTypes = flattenHierarchy(event.getClass());

		// 是否有订阅者的标记
		boolean dispatched = false;
		// 遍历所有的 事件
		for (Class<?> eventType : dispatchTypes) {
			// 找到 该事件对应的方法
			Set<EventHandler> wrappers = getHandlersForEventType(eventType);

			if (wrappers != null && !wrappers.isEmpty()) {
				// 表示有订阅者
				dispatched = true;
				for (EventHandler wrapper : wrappers) {
					// 依次  放入队列
					enqueueEvent(event, wrapper);
				}
			}
		}

		if (!dispatched && !(event instanceof DeadEvent)) {
			// 如果没有订阅者 且时间 不是 DeadEvent
			// 则发送一个 表示没有订阅者的事件
			post(new DeadEvent(this, event));
		}

		// 分发队列中的 任务
		dispatchQueuedEvents();
	}

	/**
	 *
	 * Queue the {@code event} for dispatch during {@link #dispatchQueuedEvents()}. Events are queued in-order of
	 * occurrence so they can be dispatched in the same order.
	 *
	 * @param event 事件类型
	 * @param handler 订阅者 方法包装对象
	 */
	protected void enqueueEvent(Object event, EventHandler handler) {
		// eventsToDispatch.get()  是 ConcurrentLinkedQueue<EventWithHandler>()
		// offer 方法是 加入到 队列 尾部
		eventsToDispatch.get().offer(new EventWithHandler(event, handler));
	}

	/**
	 * Drain the queue of events to be dispatched. As the queue is being drained, new events may be posted to the end of
	 * the queue.
	 *
	 * 分发 队列中的 事件
	 *
	 */
	protected void dispatchQueuedEvents() {
		// don't dispatch if we're already dispatching, that would allow reentrancy and out-of-order events. Instead, leave
		// the events to be dispatched after the in-progress dispatch is complete.
		if (isDispatching.get()) {
			// 如果正在分发 处理队列
			return;
		}

		// 标记正在处理队列
		isDispatching.set(true);
		try {
			while (true) {
				// 获取队列中的数据
				EventWithHandler eventWithHandler = eventsToDispatch.get().poll();
				if (eventWithHandler == null) {
					// 队列中没有数据了
					break;
				}

				if (eventWithHandler.handler.isValid()) {
					// 订阅方法 还有效的话 执行 订阅方法
					dispatch(eventWithHandler.event, eventWithHandler.handler);
				}
			}
		} finally {
			isDispatching.set(false);
		}
	}

	/**
	 * Dispatches {@code event} to the handler in {@code wrapper}.  This method is an appropriate override point for
	 * subclasses that wish to make event delivery asynchronous.
	 *
	 * @param event   event to dispatch. 事件
	 * @param wrapper wrapper that will call the handler.  订阅者 封装独享
	 */
	protected void dispatch(Object event, EventHandler wrapper) {
		try {
			// 执行 订阅者方法
			wrapper.handleEvent(event);
		} catch (InvocationTargetException e) {
			throwRuntimeException(
					"Could not dispatch event: " + event.getClass() + " to handler " + wrapper, e);
		}
	}

	/**
	 * Retrieves the currently registered producer for {@code type}.  If no producer is currently registered for
	 * {@code type}, this method will return {@code null}.
	 *
	 * 更具事件类型  获取 生产者
	 *
	 * @param type type of producer to retrieve.
	 * @return currently registered producer, or {@code null}.
	 */
	EventProducer getProducerForEventType(Class<?> type) {
		return producersByType.get(type);
	}

	/**
	 * Retrieves a mutable set of the currently registered handlers for {@code type}.  If no handlers are currently
	 * registered for {@code type}, this method may either return {@code null} or an empty set.
	 *
	 * 获取 事件类型对应到 订阅方法包装对象 集合
	 * @param type type of handlers to retrieve.
	 * @return currently registered handlers, or {@code null}.
	 */
	Set<EventHandler> getHandlersForEventType(Class<?> type) {
		return handlersByType.get(type);
	}

	/**
	 * Flattens a class's type hierarchy into a set of Class objects.  The set will include all superclasses
	 * (transitively), and all interfaces implemented by these superclasses.
	 *
	 * 更具 一个事件类型
	 * 获取 搜友 相应的  父类 接口 集合
	 * 由于 泛型 他们 也可能是 其他 订阅者的事件
	 *
	 * 这里不像 EventBus 可以自己定义 是否考虑 事件的 父类
	 *
	 * @param concreteClass class whose type hierarchy will be retrieved. 事件类型
	 * @return {@code concreteClass}'s complete type hierarchy, flattened and uniqued.
	 */
	Set<Class<?>> flattenHierarchy(Class<?> concreteClass) {
		// 从缓存中获取
		Set<Class<?>> classes = flattenHierarchyCache.get(concreteClass);
		if (classes == null) {
			Set<Class<?>> classesCreation = getClassesFor(concreteClass);
			classes = flattenHierarchyCache.putIfAbsent(concreteClass, classesCreation);
			if (classes == null) {
				classes = classesCreation;
			}
		}

		return classes;
	}

	/**
	 * 获取 所有的 父类 接口
	 * @param concreteClass
	 * @return
	 */
	private Set<Class<?>> getClassesFor(Class<?> concreteClass) {
		List<Class<?>> parents = new LinkedList<Class<?>>();
		Set<Class<?>> classes = new HashSet<Class<?>>();

		parents.add(concreteClass);

		// 向上循环父类
		while (!parents.isEmpty()) {
			// 这个算法 是以 父类 集合为空作为 判断条件
			Class<?> clazz = parents.remove(0);
			classes.add(clazz);

			Class<?> parent = clazz.getSuperclass();
			if (parent != null) {
				parents.add(parent);
			}
		}
		return classes;
	}

	/**
	 * Throw a {@link RuntimeException} with given message and cause lifted from an {@link
	 * InvocationTargetException}. If the specified {@link InvocationTargetException} does not have a
	 * cause, neither will the {@link RuntimeException}.
	 */
	private static void throwRuntimeException(String msg, InvocationTargetException e) {
		Throwable cause = e.getCause();
		if (cause != null) {
			throw new RuntimeException(msg + ": " + cause.getMessage(), cause);
		} else {
			throw new RuntimeException(msg + ": " + e.getMessage(), e);
		}
	}

	private final ConcurrentMap<Class<?>, Set<Class<?>>> flattenHierarchyCache =
			new ConcurrentHashMap<Class<?>, Set<Class<?>>>();

	/**
	 * Simple struct representing an event and its handler.
	 *
	 * 事件 和 订阅者 的一个简单封装
	 */
	static class EventWithHandler {
		final Object event;
		final EventHandler handler;

		public EventWithHandler(Object event, EventHandler handler) {
			this.event = event;
			this.handler = handler;
		}
	}
}
