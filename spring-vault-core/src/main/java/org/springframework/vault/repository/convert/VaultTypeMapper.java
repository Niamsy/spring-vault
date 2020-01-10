/*
 * Copyright 2017-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.repository.convert;

import java.util.Map;

import org.springframework.data.convert.TypeMapper;

/**
 * Vault-specific {@link TypeMapper} exposing that {@link SecretDocument}s might contain a
 * type key.
 *
 * @author Mark Paluch
 * @since 2.0
 */
public interface VaultTypeMapper extends TypeMapper<Map<String, Object>> {

	/**
	 * Returns whether the given {@code key} is the type key.
	 *
	 * @return {@literal true} if the {@code key} is the type key.
	 */
	boolean isTypeKey(String key);
}
