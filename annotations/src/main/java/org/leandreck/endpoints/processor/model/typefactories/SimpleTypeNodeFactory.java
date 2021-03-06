/**
 * Copyright © 2016 Mathias Kowalzik (Mathias.Kowalzik@leandreck.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.leandreck.endpoints.processor.model.typefactories;

import org.leandreck.endpoints.annotations.TypeScriptType;
import org.leandreck.endpoints.processor.config.TemplateConfiguration;
import org.leandreck.endpoints.processor.model.TypeNode;
import org.leandreck.endpoints.processor.model.TypeNodeFactory;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.leandreck.endpoints.processor.model.typefactories.TypeNodeUtils.UNDEFINED_TYPE_NAME;

/**
 */
class SimpleTypeNodeFactory implements ConcreteTypeNodeFactory {

    private final Types typeUtils;
    private final Elements elementUtils;

    private final TemplateConfiguration configuration;
    private final TypeNodeFactory typeNodeFactory;
    private final TypeMirror objectMirror;

    /**
     * Prototype Constructor for Registration in {@link TypeNodeKind}.
     */
    SimpleTypeNodeFactory() {
        typeUtils = null;
        configuration = null;
        typeNodeFactory = null;
        elementUtils = null;
        objectMirror = null;
    }

    private SimpleTypeNodeFactory(final TypeNodeFactory typeNodeFactory, final Types typeUtils, final Elements elementUtils, final TemplateConfiguration configuration) {
        this.typeNodeFactory = typeNodeFactory;
        this.configuration = configuration;
        this.typeUtils = typeUtils;
        this.elementUtils = elementUtils;
        objectMirror = TypeNodeUtils.getObjectMirror(elementUtils);
    }

    @Override
    public ConcreteTypeNodeFactory newConfiguredInstance(final TypeNodeFactory typeNodeFactory, final TemplateConfiguration configuration, final Types typeUtils, final Elements elementUtils) {
        return new SimpleTypeNodeFactory(typeNodeFactory, typeUtils, elementUtils, configuration);
    }

    @Override
    public TypeNode createTypeNode(final String fieldName, final String parameterName, final boolean optional, final TypeMirror typeMirror, final DeclaredType containingType) {
        final TypeElement typeElement = (TypeElement) typeUtils.asElement(typeMirror);
        final TypeScriptType typeScriptTypeAnnotation = TypeNodeUtils.getAnnotationForClass(typeMirror, TypeScriptType.class, typeUtils);
        final String typeName = TypeNodeUtils.defineName(typeMirror, typeScriptTypeAnnotation, this::defineNameFromSimpleType);

        final String doc = elementUtils.getDocComment(typeElement);

        return new SimpleTypeNode(
                optional,
                fieldName,
                parameterName,
                typeName,
                defineVariableType(typeName, typeMirror),
                defineTypeParameters(typeMirror),
                TypeNodeUtils.defineTemplate(configuration.getInterfaceTemplate(), typeScriptTypeAnnotation, typeElement),
                typeNodeFactory.defineChildren(typeElement, (DeclaredType) typeMirror), doc);
    }

    private List<TypeNode> defineTypeParameters(final TypeMirror typeMirror) {
        final DeclaredType declaredType = (DeclaredType) typeMirror;
        final List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();

        return typeArguments.stream()
                .map(t -> t.getKind().equals(TypeKind.WILDCARD) ? objectMirror : t)
                .map(typeNodeFactory::createTypeNode)
                .collect(toList());
    }

    private String defineVariableType(final String typeName, final TypeMirror typeMirror) {
        final DeclaredType declaredType = (DeclaredType) typeMirror;
        final List<? extends TypeMirror> variableTypeArguments = ((DeclaredType) declaredType.asElement().asType()).getTypeArguments();

        final String variableType;
        if (variableTypeArguments.isEmpty()) {
            variableType = typeName;
        } else {
            variableType = typeName + variableTypeArguments.stream().map(it -> ((TypeVariable) it).asElement().getSimpleName().toString())
                    .collect(joining(", ", "<", ">"));
        }
        return variableType;
    }

    private String defineNameFromSimpleType(final TypeMirror typeMirror) {
        final TypeKind kind = typeMirror.getKind();
        final String typeName;
        if (TypeKind.DECLARED.equals(kind)) {
            typeName = typeUtils.asElement(typeMirror).getSimpleName().toString();
        } else {
            typeName = UNDEFINED_TYPE_NAME;
        }
        return typeName;
    }

    class SimpleTypeNode extends TypeNode {

        private final String fieldName;
        private final String parameterName;
        private final String typeName;
        private final String type;
        private final String variableType;
        private final String template;
        private final List<TypeNode> typeParameters;
        private final List<TypeNode> children;
        private final String doc;

        //lazy
        private Set<TypeNode> imports;
        private Set<TypeNode> types;

        SimpleTypeNode(final boolean optional,
                       final String fieldName,
                       final String parameterName,
                       final String typeName,
                       final String variableType,
                       final List<TypeNode> typeParameters,
                       final String template,
                       final List<TypeNode> children,
                       final String doc) {
            super(optional);
            this.fieldName = fieldName;
            this.parameterName = parameterName;
            this.typeName = typeName;
            this.variableType = variableType;
            this.typeParameters = typeParameters;
            this.template = template;
            this.children = children;
            this.doc = doc;
            type = defineType();
        }

        private String defineType() {
            final String name;
            if (typeParameters.isEmpty()) {
                name = typeName;
            } else {
                final String parameters = typeParameters.stream()
                        .map(TypeNode::getType)
                        .collect(joining(", ", "<", ">"));

                name = typeName + parameters;
            }
            return name;
        }

        private Set<TypeNode> collectImports() {
            final Set<TypeNode> nodesSet = new HashSet<>(children.size() + 5);
            children.stream()
                    .filter(it -> !it.isMappedType())
                    .filter(it -> !this.getTypeName().equals(it.getTypeName()))
                    .flatMap(it -> it.getTypes().stream())
                    .forEach(nodesSet::add);
            return Collections.unmodifiableSet(nodesSet);
        }

        private Set<TypeNode> collectTypes() {
            final Set<TypeNode> nodesSet = new HashSet<>(getImports().size() + 5);
            nodesSet.add(this);
            nodesSet.addAll(getImports());
            return nodesSet;
        }

        @Override
        public String getFieldName() {
            return fieldName;
        }

        @Override
        public String getTypeName() {
            return typeName;
        }

        @Override
        public String getParameterName() {
            return parameterName;
        }

        @Override
        public String getTemplate() {
            return template;
        }

        @Override
        public List<TypeNode> getChildren() {
            return Collections.unmodifiableList(children);
        }

        @Override
        public TypeNodeKind getKind() {
            return TypeNodeKind.SIMPLE;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public List<TypeNode> getTypeParameters() {
            return typeParameters;
        }

        @Override
        public Set<TypeNode> getTypes() {

            if (types == null) {
                types = collectTypes();
            }

            return types;
        }

        @Override
        public Set<TypeNode> getImports() {

            if (imports == null) {
                imports = collectImports();
            }


            return imports;
        }

        @Override
        public String getVariableType() {
            return variableType;
        }

        @Override
        public String getDoc() {
            return doc;
        }
    }
}
