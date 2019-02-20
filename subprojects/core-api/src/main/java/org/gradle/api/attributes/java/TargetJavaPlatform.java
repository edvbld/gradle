/*
 * Copyright 2019 the original author or authors.
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
package org.gradle.api.attributes.java;

import org.gradle.api.Incubating;
import org.gradle.api.attributes.Attribute;

/**
 * Represents the target platform of a Java library or platform. The target level is expected to correspond
 * to a Java platform version number (integer). For example, "5" for Java 5, "8" for Java 8, or "11" for Java 11.
 *
 * @since 5.3
 */
@Incubating
public interface TargetJavaPlatform {

    /**
     * The minimal target platform for a Java library. Any consumer below this version would not be able to consume it.
     */
    Attribute<Integer> TARGET_PLATFORM_ATTRIBUTE = Attribute.of("org.gradle.jvm.platform", Integer.class);
}
