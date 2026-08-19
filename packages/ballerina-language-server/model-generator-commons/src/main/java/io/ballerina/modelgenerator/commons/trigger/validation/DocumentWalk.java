/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.modelgenerator.commons.trigger.validation;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.List;

/**
 * The traversal every check shares: null-safe list access and the path strings a finding is reported
 * against.
 *
 * <p>Owned by nobody, like the pipeline's own shared services. Each check states one invariant; none of
 * them should also own an opinion about how a {@code null} array differs from an empty one, or about how a
 * position is named in a diagnostic — those answers must be identical across all of them or two findings
 * about the same slot would point at different places.
 *
 * @since 1.10.0
 */
final class DocumentWalk {

    private DocumentWalk() {
        // Prevent instantiation
    }

    /**
     * A {@code null} list read as empty.
     *
     * @param <T>  the element type
     * @param list the list; may be {@code null}
     * @return the list, or an empty one
     */
    static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * The path of one service type.
     *
     * @param index its position
     * @return the path
     */
    static String serviceTypePath(int index) {
        return "serviceTypes[" + index + "]";
    }

    /**
     * The path of one handler option.
     *
     * @param serviceTypeIndex the enclosing service type's position
     * @param optionIndex      the option's position
     * @return the path
     */
    static String optionPath(int serviceTypeIndex, int optionIndex) {
        return serviceTypePath(serviceTypeIndex) + ".handlers.options[" + optionIndex + "]";
    }

    /**
     * The path of one handler parameter.
     *
     * @param serviceTypeIndex the enclosing service type's position
     * @param optionIndex      the enclosing option's position
     * @param paramIndex       the parameter's position
     * @return the path
     */
    static String paramPath(int serviceTypeIndex, int optionIndex, int paramIndex) {
        return optionPath(serviceTypeIndex, optionIndex) + ".params[" + paramIndex + "]";
    }

    /**
     * The handler options of one service type, never {@code null}.
     *
     * @param serviceType the service type; may be {@code null}
     * @return its options
     */
    static List<TriggerMetadataModel.ServiceType.HandlerOption> options(
            TriggerMetadataModel.ServiceType serviceType) {
        if (serviceType == null || serviceType.handlers() == null) {
            return List.of();
        }
        return safe(serviceType.handlers().options());
    }
}
