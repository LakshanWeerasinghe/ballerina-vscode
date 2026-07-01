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

package io.ballerina.servicemodelgenerator.extension.util;

import io.ballerina.centralconnector.CentralAPI;
import io.ballerina.centralconnector.response.PackageResponse;
import io.ballerina.servicemodelgenerator.extension.model.TriggerBasicInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Discovers event-integration <b>trigger</b> packages on Ballerina Central for the "Search more"
 * flow. This complements the locally-bundled trigger index ({@code getTriggerModels}) with a live
 * Central search, so a connector that ships its trigger models can be found and added without a
 * language-server release.
 *
 * <p>Central returns generic package results; a package is treated as a trigger when its keywords
 * signal a listener/trigger, or its module name uses the {@code trigger.*} convention. The pure
 * predicate/mapping helpers are package-visible for unit testing without a network call.
 *
 * @since 1.8.0
 */
public final class TriggerSearchUtil {

    private static final int DEFAULT_LIMIT = 30;
    private static final String EVENT_TYPE = "event";
    private static final String DEFAULT_QUERY = "trigger";
    // Keyword/name signals that a Central package is an event-integration trigger.
    private static final Set<String> TRIGGER_KEYWORDS = Set.of("trigger", "listener", "event");
    private static final String TRIGGER_MODULE_PREFIX = "trigger.";

    private TriggerSearchUtil() {
    }

    /**
     * Searches Central for trigger packages matching {@code query}, excluding those already known
     * locally ({@code existingKeys}, each {@code org/name}). Returns an empty list on any failure
     * (e.g. offline) so the caller degrades gracefully to the local list.
     */
    public static List<TriggerBasicInfo> searchCentral(CentralAPI central, String query, Integer limit,
                                                       Integer offset, Set<String> existingKeys) {
        try {
            Map<String, String> queryMap = new HashMap<>();
            queryMap.put("q", (query == null || query.isBlank()) ? DEFAULT_QUERY : query.trim());
            queryMap.put("limit", String.valueOf(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit));
            queryMap.put("offset", String.valueOf(offset == null || offset < 0 ? 0 : offset));
            PackageResponse response = central.searchPackages(queryMap);
            return toTriggerResults(response, existingKeys);
        } catch (Throwable e) {
            return List.of();
        }
    }

    /**
     * Filters a Central package response to trigger packages and maps them to {@link TriggerBasicInfo},
     * skipping any already present locally. Pure; unit-testable without a network call.
     */
    static List<TriggerBasicInfo> toTriggerResults(PackageResponse response, Set<String> existingKeys) {
        List<TriggerBasicInfo> results = new ArrayList<>();
        if (response == null || response.packages() == null) {
            return results;
        }
        Set<String> known = existingKeys == null ? Set.of() : existingKeys;
        for (PackageResponse.Package pkg : response.packages()) {
            if (pkg == null || pkg.isDeprecated()) {
                continue;
            }
            if (!isTriggerPackage(pkg.keywords(), pkg.name())) {
                continue;
            }
            if (known.contains(key(pkg.organization(), pkg.name()))) {
                continue;
            }
            results.add(toTriggerBasicInfo(pkg));
        }
        return results;
    }

    /**
     * Whether a Central package is an event-integration trigger, based on its keywords or the
     * {@code trigger.*} module-name convention.
     */
    static boolean isTriggerPackage(List<String> keywords, String name) {
        if (name != null && name.toLowerCase(Locale.US).startsWith(TRIGGER_MODULE_PREFIX)) {
            return true;
        }
        if (keywords == null) {
            return false;
        }
        return keywords.stream()
                .filter(Objects::nonNull)
                .map(k -> k.toLowerCase(Locale.US))
                .anyMatch(TRIGGER_KEYWORDS::contains);
    }

    static TriggerBasicInfo toTriggerBasicInfo(PackageResponse.Package pkg) {
        String protocol = ServiceModelUtils.getProtocol(pkg.name());
        return new TriggerBasicInfo(
                pkg.id(),
                pkg.name(),
                pkg.organization(),
                pkg.name(),
                pkg.name(),
                pkg.version(),
                EVENT_TYPE,
                displayName(pkg.name()),
                pkg.summary() == null ? "" : pkg.summary(),
                protocol,
                pkg.icon() == null ? "" : pkg.icon());
    }

    private static String key(String org, String name) {
        return org + "/" + name;
    }

    private static String displayName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String last = name.substring(name.lastIndexOf('.') + 1);
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }
}
