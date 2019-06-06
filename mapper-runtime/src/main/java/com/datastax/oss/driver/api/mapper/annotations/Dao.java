/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.driver.api.mapper.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates an interface that defines a set of query methods, usually (but not necessarily) related
 * to a given entity class.
 *
 * <p>Example:
 *
 * <pre>
 * &#64;Dao
 * public interface ProductDao {
 *   &#64;Select
 *   Product findById(UUID productId);
 *
 *   &#64;Insert
 *   void save(Product product);
 *
 *   &#64;Delete
 *   void delete(Product product);
 * }
 * </pre>
 *
 * DAO instances are created via {@link DaoFactory} methods.
 *
 * <p>DAO interfaces can define the following methods:
 *
 * <ul>
 *   <li>{@link Delete}
 *   <li>{@link GetEntity}
 *   <li>{@link Insert}
 *   <li>{@link Query}
 *   <li>{@link Select}
 *   <li>{@link SetEntity}
 *       <!-- TODO list new ones as they get added -->
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Dao {

  /**
   * When the new instance of a class annotated with {@code @Dao} is created an automatic check for
   * schema validation is performed. It verifies if all {@code @Dao} entity fields are present in
   * CQL table. If not the exception is thrown. This check has startup overhead so once your app is
   * stable you may want to disable it. The schema Validation check is enabled by default.
   */
  boolean enableEntitySchemaValidation() default true;
}
