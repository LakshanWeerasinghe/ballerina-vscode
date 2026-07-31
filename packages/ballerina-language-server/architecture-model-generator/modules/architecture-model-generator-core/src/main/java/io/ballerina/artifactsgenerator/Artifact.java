/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com)
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

package io.ballerina.artifactsgenerator;

import io.ballerina.compiler.api.ModuleID;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.designmodelgenerator.core.CommonUtils;
import io.ballerina.modelgenerator.commons.IconDescriptor;
import io.ballerina.modelgenerator.commons.trigger.utils.TriggerArtifactResolver;
import io.ballerina.runtime.api.utils.IdentifierUtils;
import io.ballerina.tools.text.LineRange;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents an artifact in the project tree.
 *
 * @param id         unique identifier for the artifact
 * @param location   location information of the artifact
 * @param type       type of the artifact
 * @param name       symbol name of the artifact
 * @param accessor   accessor of the artifact
 * @param scope      lexical scope of the artifact (global/local/object)
 * @param visibility visibility of the artifact (public/module/private)
 * @param icon       resolved icon descriptor for the artifact (url/glyph/color/kind/source)
 * @param children   map of child artifacts (id -> child)
 * @param module     module name of the artifact
 * @param metadata   metadata about the artifact
 * @since 1.0.0
 */
public record Artifact(String id, LineRange location, String type, String name, String accessor,
                       String scope, String visibility, IconDescriptor icon, String module,
                       Map<String, Artifact> children, Map<String, Object> metadata) {

    private static final String CATEGORY_ENTRY_POINTS = "Entry Points";
    private static final String CATEGORY_RESOURCES = "Resources";
    private static final String CATEGORY_REMOTE_METHODS = "Remote Methods";
    private static final String CATEGORY_FUNCTIONS = "Functions";
    private static final String CATEGORY_NATURAL_FUNCTIONS = "Natural Functions";
    private static final String CATEGORY_DATA_MAPPERS = "Data Mappers";
    private static final String CATEGORY_LISTENERS = "Listeners";
    private static final String CATEGORY_CONFIGURATIONS = "Configurations";
    private static final String CATEGORY_TYPES = "Types";
    private static final String CATEGORY_CONNECTIONS = "Connections";
    private static final String CATEGORY_VARIABLES = "Variables";
    private static final String CATEGORY_WORKFLOWS = "Workflows";
    private static final String CATEGORY_DEFAULT = "Others";

    private static final Map<String, String> typeCategoryMap = Map.ofEntries(
            Map.entry(Type.SERVICE.name(), CATEGORY_ENTRY_POINTS),
            Map.entry(Type.AUTOMATION.name(), CATEGORY_ENTRY_POINTS),
            Map.entry(Type.RESOURCE.name(), CATEGORY_RESOURCES),
            Map.entry(Type.REMOTE.name(), CATEGORY_REMOTE_METHODS),
            Map.entry(Type.FUNCTION.name(), CATEGORY_FUNCTIONS),
            Map.entry(Type.NP_FUNCTION.name(), CATEGORY_NATURAL_FUNCTIONS),
            Map.entry(Type.DATA_MAPPER.name(), CATEGORY_DATA_MAPPERS),
            Map.entry(Type.LISTENER.name(), CATEGORY_LISTENERS),
            Map.entry(Type.CONFIGURABLE.name(), CATEGORY_CONFIGURATIONS),
            Map.entry(Type.TYPE.name(), CATEGORY_TYPES),
            Map.entry(Type.CONNECTION.name(), CATEGORY_CONNECTIONS),
            Map.entry(Type.VARIABLE.name(), CATEGORY_VARIABLES),
            Map.entry(Type.WORKFLOW.name(), CATEGORY_WORKFLOWS),
            Map.entry(Type.ACTIVITY.name(), CATEGORY_WORKFLOWS));

    public static String getCategory(String type) {
        return typeCategoryMap.getOrDefault(type, CATEGORY_DEFAULT);
    }

    public static Artifact emptyArtifact(String id) {
        return new Artifact(id, null, null, null, null, null, null, null, null, null, null);
    }

    public Artifact {
        children = children == null ? Collections.emptyMap() : Collections.unmodifiableMap(children);
    }

    /**
     * Represents the different types of artifacts.
     */
    public enum Type {
        SERVICE,
        AUTOMATION,
        RESOURCE,
        REMOTE,
        FUNCTION,
        NP_FUNCTION,
        DATA_MAPPER,
        LISTENER,
        CONFIGURABLE,
        TYPE,
        CONNECTION,
        VARIABLE,
        WORKFLOW,
        ACTIVITY
    }

    public enum Scope {
        GLOBAL("Global"),
        LOCAL("Local"),
        OBJECT("Object");

        private final String value;

        Scope(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * Represents the Ballerina visibility levels of an artifact.
     */
    public enum Visibility {
        PUBLIC("public"),
        MODULE("module"),
        PRIVATE("private");

        private final String value;

        Visibility(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * Builder class for creating Artifact instances.
     */
    public static class Builder {

        private String id;
        private LineRange location;
        private Type type;
        private String name;
        private String accessor;
        private Scope scope = Scope.GLOBAL;
        private Visibility visibility = null;
        private IconDescriptor icon;
        private String module;
        private ModuleID moduleId;
        private final Map<String, Artifact> children = new HashMap<>();
        private Map<String, Object> metadata = null;

        public Builder(Node node) {
            this.location = node.lineRange();
        }

        public Builder node(Node node) {
            this.location = node.lineRange();
            return this;
        }

        public Builder locationId() {
            if (location == null) {
                return this;
            }
            this.id = String.valueOf(Objects.hash(location.fileName(), location.startLine(), location.endLine()));
            return this;
        }

        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder accessor(String accessor) {
            this.accessor = accessor;
            return this;
        }

        public Builder scope(Scope scope) {
            this.scope = scope;
            return this;
        }

        public Builder visibility(Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder icon(Symbol symbol) {
            Optional<ModuleSymbol> moduleSymbol = symbol.getModule();
            if (moduleSymbol.isEmpty()) {
                return this;
            }
            ModuleID moduleId = moduleSymbol.get().id();
            this.moduleId = moduleId;
            this.icon = TriggerArtifactResolver.resolveIcon(moduleId);
            this.module = moduleId.moduleName();
            return this;
        }

        public Builder child(Artifact child) {
            if (child != null && child.id() != null) {
                this.children.put(child.id(), child);
            }
            return this;
        }

        public Builder serviceNameWithPath(String path) {
            Optional<String> displayName = moduleId == null
                    ? Optional.empty() : TriggerArtifactResolver.resolveDisplayName(moduleId);
            this.name = displayName.map(dn -> dn + " - " + path).orElse(path);
            return this;
        }

        public Builder serviceName(String name) {
            Optional<String> displayName = moduleId == null
                    ? Optional.empty() : TriggerArtifactResolver.resolveDisplayName(moduleId);
            this.name = displayName.orElse(name);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Attempts to set the service name from annotations if applicable for the module.
         *
         * @param serviceNode the service declaration node
         * @return true if name was successfully extracted from annotation, false otherwise
         */
        public boolean trySetNameFromAnnotation(ServiceDeclarationNode serviceNode) {
            if (moduleId == null) {
                return false;
            }
            List<String> fieldNames = TriggerArtifactResolver.resolveLabelFields(moduleId);
            for (String fieldName : fieldNames) {
                Optional<String> extractedValue = CommonUtils.extractServiceAnnotationField(serviceNode, fieldName);
                if (extractedValue.isPresent()) {
                    String displayName = TriggerArtifactResolver.resolveDisplayName(moduleId).orElse("");
                    this.name = displayName + " - " + extractedValue.get();
                    return true;
                }
            }
            return false;
        }

        public Artifact build() {
            if (accessor != null) {
                id = id == null ? accessor + "#" + name : id;
                accessor = IdentifierUtils.unescapeBallerina(accessor);
            } else {
                id = id == null ? name : id;
            }
            name = IdentifierUtils.unescapeBallerina(name);
            return new Artifact(id, location, type == null ? null : type.name(), name, accessor, scope.getValue(),
                    visibility == null ? null : visibility.getValue(), icon,
                    module, new HashMap<>(children), metadata == null ? null : new HashMap<>(metadata));
        }
    }
}
