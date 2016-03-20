/*
 * Copyright (C) 2012 Square, Inc.
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
import java.lang.reflect.Method;

/**
 * Wraps a 'producer' method on a specific object.
 * <p/>
 * <p> This class only verifies the suitability of the method and event type if something fails.  Callers are expected
 * to verify their uses of this class.
 *
 * 事件生产者
 * Produce 注解 方法的一个封装
 *
 * @author Jake Wharton
 */
class EventProducer {

	/**
	 * Object sporting the producer method.
	 * 订阅对象
	 */
	final Object target;
	/**
	 * Producer method.
	 * 订阅方法
	 */
	private final Method method;
	/**
	 * Object hash code.
	 */
	private final int hashCode;
	/**
	 * Should this producer produce events?
	 * 这个值 决定 是否能够执行 生产者方法  是够能够 产生事件
	 */
	private boolean valid = true;

	/**
	 *
	 * @param target 注册 类对象
	 * @param method 生产者 方法
	 */
	EventProducer(Object target, Method method) {
		if (target == null) {
			throw new NullPointerException("EventProducer target cannot be null.");
		}
		if (method == null) {
			throw new NullPointerException("EventProducer method cannot be null.");
		}

		this.target = target;
		this.method = method;
		// 提高反射速率
		method.setAccessible(true);

		// Compute hash code eagerly since we know it will be used frequently and we cannot estimate the runtime of the
		// target's hashCode call.
		final int prime = 31;
		// 生成 hashcode  用于 比较时使用 节省事件
		hashCode = (prime + method.hashCode()) * prime + target.hashCode();
	}

	public boolean isValid() {
		return valid;
	}

	/**
	 * If invalidated, will subsequently refuse to produce events.
	 * <p/>
	 * Should be called when the wrapped object is unregistered from the Bus.
	 */
	public void invalidate() {
		valid = false;
	}

	/**
	 * Invokes the wrapped producer method.
	 *
	 * 执行 Produce 注解方法
	 *
	 * @throws IllegalStateException     if previously invalidated.
	 * @throws InvocationTargetException if the wrapped method throws any {@link Throwable} that is not
	 *                                   an {@link Error} ({@code Error}s are propagated as-is).
	 *
	 * @return 返回事件
	 */
	public Object produceEvent() throws InvocationTargetException {
		if (!valid) {
			// 如果 无效
			throw new IllegalStateException(toString() + " has been invalidated and can no longer produce events.");
		}
		try {
			// 执行方法
			return method.invoke(target);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Error) {
				throw (Error) e.getCause();
			}
			throw e;
		}
	}

	@Override
	public String toString() {
		return "[EventProducer " + method + "]";
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (obj == null) {
			return false;
		}

		if (getClass() != obj.getClass()) {
			return false;
		}

		final EventProducer other = (EventProducer) obj;

		return method.equals(other.method) && target == other.target;
	}
}
