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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an instance producer, as used by {@link AnnotatedHandlerFinder} and {@link Bus}.
 * <p/>
 * Otto infers the instance type from the annotated method's return type. Producer methods may return null when there is
 * no appropriate value to share. The calling {@link Bus} ignores such returns and posts nothing.
 *
 * @author Jake Wharton
 *
 * otto 有两种注解
 *
 * 有这种注解的 方法,  当执行这个方法时, 他的返回值 就是 事件类型
 *
 *
 * 这种 注解 一个进程中
 * 一个类型  只能 有一个 被注册
 *
 * 不能出现 两个 类 都注册了
 *
 * 也不能出现 一个类中 注册多次
 *
 * 可以吧 Produce 和 粘性事件相类比
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Produce {
}
