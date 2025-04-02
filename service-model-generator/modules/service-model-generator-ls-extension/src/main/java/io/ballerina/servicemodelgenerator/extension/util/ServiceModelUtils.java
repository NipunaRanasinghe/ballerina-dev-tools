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

package io.ballerina.servicemodelgenerator.extension.util;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.AnnotationAttachPoint;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.modelgenerator.commons.AnnotationAttachment;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.ServiceDatabaseManager;
import io.ballerina.modelgenerator.commons.ServiceDeclaration;
import io.ballerina.modelgenerator.commons.ServiceTypeFunction;
import io.ballerina.projects.Project;
import io.ballerina.servicemodelgenerator.extension.ServiceModelGeneratorConstants;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.DisplayAnnotation;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.FunctionReturnType;
import io.ballerina.servicemodelgenerator.extension.model.MetaData;
import io.ballerina.servicemodelgenerator.extension.model.Parameter;
import io.ballerina.servicemodelgenerator.extension.model.PropertyTypeMemberInfo;
import io.ballerina.servicemodelgenerator.extension.model.Service;
import io.ballerina.servicemodelgenerator.extension.model.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.ballerina.servicemodelgenerator.extension.util.Utils.getFunctionModel;
import static io.ballerina.servicemodelgenerator.extension.util.Utils.getPath;
import static io.ballerina.servicemodelgenerator.extension.util.Utils.getResourceFunctionModel;
import static io.ballerina.servicemodelgenerator.extension.util.Utils.isPresent;
import static io.ballerina.servicemodelgenerator.extension.util.Utils.populateListenerInfo;
import static io.ballerina.servicemodelgenerator.extension.util.Utils.updateAnnotationAttachmentProperty;
import static io.ballerina.servicemodelgenerator.extension.util.Utils.updateFunction;
import static io.ballerina.servicemodelgenerator.extension.util.Utils.updateFunctionInfo;

public class ServiceModelUtils {

    /**
     * Update the service model of services other than http.
     *
     * @param serviceModel service model initially populated using the database
     * @param serviceNode service declaration node
     * @param semanticModel semantic model of the source
     */
    public static void updateGenericServiceModel(Service serviceModel, ServiceDeclarationNode serviceNode,
                                                 SemanticModel semanticModel) {
        // handle base path and string literal
        String attachPoint = getPath(serviceNode.absoluteResourcePath());
        if (!attachPoint.isEmpty()) {
            boolean isStringLiteral = attachPoint.startsWith("\"") && attachPoint.endsWith("\"");
            if (isStringLiteral) {
                Value stringLiteralProperty = serviceModel.getStringLiteralProperty();
                if (Objects.nonNull(stringLiteralProperty)) {
                    stringLiteralProperty.setValue(attachPoint);
                } else {
                    serviceModel.setStringLiteral(ServiceModelUtils.getStringLiteralProperty(attachPoint));
                }
            } else {
                Value basePathProperty = serviceModel.getBasePath();
                if (Objects.nonNull(basePathProperty)) {
                    basePathProperty.setValue(attachPoint);
                } else {
                    serviceModel.setBasePath(ServiceModelUtils.getBasePathProperty(attachPoint));
                }
            }
        }

        boolean isGraphql = serviceModel.getModuleName().equals(ServiceModelGeneratorConstants.GRAPHQL);
        List<Function> functionsInSource = serviceNode.members().stream()
                .filter(member -> member instanceof FunctionDefinitionNode)
                .map(member -> getFunctionModel((FunctionDefinitionNode) member, semanticModel, false,
                        isGraphql, Map.of()))
                .toList();

        updateServiceInfoNew(serviceModel, functionsInSource);
        serviceModel.setCodedata(new Codedata(serviceNode.lineRange()));
        populateListenerInfo(serviceModel, serviceNode);
        updateAnnotationAttachmentProperty(serviceNode, serviceModel);
    }

    private static void updateServiceInfoNew(Service serviceModel, List<Function> functionsInSource) {
        Utils.populateRequiredFunctions(serviceModel);

        // mark the enabled functions as true if they present in the source
        serviceModel.getFunctions().forEach(functionModel -> {
            Optional<Function> function = functionsInSource.stream()
                    .filter(newFunction -> isPresent(functionModel, newFunction)
                            && newFunction.getKind().equals(functionModel.getKind()))
                    .findFirst();
            function.ifPresentOrElse(
                    func -> updateFunction(functionModel, func, serviceModel),
                    () -> functionModel.setEnabled(false)
            );
        });

        // functions contains in source but not enforced using the service contract type
        functionsInSource.forEach(funcInSource -> {
            if (serviceModel.getFunctions().stream().noneMatch(newFunction -> isPresent(funcInSource, newFunction))) {
                if (serviceModel.getModuleName().equals(ServiceModelGeneratorConstants.HTTP) &&
                        funcInSource.getKind().equals(ServiceModelGeneratorConstants.KIND_RESOURCE)) {
                    getResourceFunctionModel().ifPresentOrElse(
                            resourceFunction -> {
                                updateFunctionInfo(resourceFunction, funcInSource);
                                serviceModel.addFunction(resourceFunction);
                            },
                            () -> serviceModel.addFunction(funcInSource)
                    );
                } else if (serviceModel.getModuleName().equals(ServiceModelGeneratorConstants.GRAPHQL)) {
                    GraphqlUtil.updateGraphqlFunctionMetaData(funcInSource);
                    serviceModel.addFunction(funcInSource);
                } else {
                    serviceModel.addFunction(funcInSource);
                }
            }
        });
    }


    /**
     * Get the service model of the given module without the function list.
     *
     * @param moduleName module name
     * @return {@link Optional<Service>} service model
     */
    public static Optional<Service> getEmptyServiceModel(String moduleName) {
        if (moduleName.equals("http")) {
            return getHttpService();
        }
        Optional<ServiceDeclaration> serviceDeclaration = ServiceDatabaseManager.getInstance()
                .getServiceDeclaration(moduleName);
        if (serviceDeclaration.isEmpty()) {
            return Optional.empty();
        }
        ServiceDeclaration serviceTemplate = serviceDeclaration.get();
        ServiceDeclaration.Package pkg = serviceTemplate.packageInfo();

        String protocol = getProtocol(moduleName);

        String label = serviceTemplate.displayName();
        String documentation = "Add the service documentation";
        String icon = CommonUtils.generateIcon(pkg.org(), pkg.name(), pkg.version());

        Map<String, Value> properties = new LinkedHashMap<>();

        Service.ServiceModelBuilder serviceBuilder = new Service.ServiceModelBuilder();
        serviceBuilder
                .setId(String.valueOf(pkg.packageId()))
                .setName(label)
                .setType(moduleName)
                .setDisplayName(label)
                .setDescription(documentation)
                .setDisplayAnnotation(new DisplayAnnotation(label, icon))
                .setModuleName(moduleName)
                .setOrgName(pkg.org())
                .setVersion(pkg.version())
                .setPackageName(pkg.name())
                .setListenerProtocol(protocol)
                .setIcon(icon)
                .setProperties(properties)
                .setFunctions(new ArrayList<>());

        Service service = serviceBuilder.build();
        properties.put("listener", getListenersProperty(protocol, serviceTemplate.listenerKind()));

        // type descriptor
        properties.put("serviceType", getTypeDescriptorProperty(serviceTemplate, pkg.packageId()));

        // base path
        if (serviceTemplate.optionalAbsoluteResourcePath() == 0) {
            properties.put("basePath", getBasePathProperty(serviceTemplate));
        }

        // string literal
        if (serviceTemplate.optionalStringLiteral() == 0) {
            properties.put("stringLiteral", getStringLiteral(serviceTemplate));
        }

        List<AnnotationAttachment> annotationAttachments = ServiceDatabaseManager.getInstance()
                .getAnnotationAttachments(pkg.packageId());
        for (AnnotationAttachment annotationAttachment : annotationAttachments) {
            if (annotationAttachment.attachmentPoints().contains(AnnotationAttachPoint.SERVICE)) {
                String key = "annot" + annotationAttachment.annotName();
                properties.put(key, getAnnotationAttachmentProperty(annotationAttachment));
            }
        }

        return Optional.of(service);
    }

    /**
     * Get the service model for a give service type including its functions.
     *
     * @param moduleName module name
     * @param serviceType service type
     * @return {@link Optional<Service>} service model
     */
    public static Optional<Service> getServiceModelWithFunctions(String moduleName, String serviceType) {
        Optional<Service> serviceOptional = getEmptyServiceModel(moduleName);
        if (serviceOptional.isEmpty() || serviceOptional.get().getPackageName().equals("http")) {
            return serviceOptional;
        }
        Service service = serviceOptional.get();
        int packageId = Integer.parseInt(service.getId());
        ServiceDatabaseManager.getInstance().getMatchingServiceTypeFunctions(packageId, serviceType)
                .forEach(function -> service.getFunctions().add(getFunction(function)));
        service.getServiceType().setValue(serviceType);
        return Optional.of(service);
    }

    public static void updateFunctionList(Service service) {
        int packageId = Integer.parseInt(service.getId());
        String serviceTypeName = Objects.nonNull(service.getServiceType()) ? service.getServiceType().getValue()
                : "Service";
        ServiceDatabaseManager.getInstance().getMatchingServiceTypeFunctions(packageId, serviceTypeName)
                .forEach(function -> service.getFunctions().add(getFunction(function)));
    }

    private static Function getFunction(ServiceTypeFunction function) {

        List<Parameter> parameters = new ArrayList<>();
        for (ServiceTypeFunction.ServiceTypeFunctionParameter parameter : function.parameters()) {
            parameters.add(getParameter(parameter));
        }

        Value.ValueBuilder functionName = new Value.ValueBuilder();
        functionName
                .setMetadata(new MetaData(function.name(), function.description()))
                .setCodedata(new Codedata("FUNCTION_NAME"))
                .setValue(function.name())
                .setValueType("IDENTIFIER")
                .setValueTypeConstraint("string")
                .setPlaceholder(function.name())
                .setEnabled(true)
                .setEditable(false)
                .setType(false)
                .setOptional(false)
                .setAdvanced(false);

        Value.ValueBuilder functionReturnType = new Value.ValueBuilder();
        functionReturnType
                .setMetadata(new MetaData("Return Type", "The return type of the function"))
                .setValue(function.returnType())
                .setValueType("TYPE")
                .setValueTypeConstraint("string")
                .setPlaceholder(function.returnType())
                .setEnabled(true)
                .setEditable(true)
                .setType(true)
                .setOptional(true)
                .setAdvanced(false);

        Function.FunctionBuilder functionBuilder = new Function.FunctionBuilder();
        functionBuilder
                .setMetadata(new MetaData(function.name(), function.description()))
                .setKind(function.kind())
                .setEnabled(function.enable() == 1)
                .setOptional(false)
                .setEditable(true)
                .setName(functionName.build())
                .setReturnType(new FunctionReturnType(functionReturnType.build()))
                .setParameters(parameters);

        if (function.kind().equals(ServiceModelGeneratorConstants.KIND_RESOURCE)) {
            Value.ValueBuilder accessor = new Value.ValueBuilder()
                    .setMetadata(new MetaData("Accessor", "The accessor of the resource function"))
                    .setCodedata(new Codedata("ACCESSOR"))
                    .setValue(function.accessor())
                    .setValueType("IDENTIFIER")
                    .setValueTypeConstraint("string")
                    .setPlaceholder(function.accessor())
                    .setEnabled(true)
                    .setEditable(false)
                    .setType(false)
                    .setOptional(false)
                    .setAdvanced(false);
            functionBuilder.setAccessor(accessor.build());
        }

        return functionBuilder.build();
    }

    private static Parameter getParameter(ServiceTypeFunction.ServiceTypeFunctionParameter parameter) {
        Value.ValueBuilder parameterName = new Value.ValueBuilder();
        parameterName
                .setMetadata(new MetaData(parameter.name(), parameter.description()))
                .setCodedata(new Codedata("PARAMETER_NAME"))
                .setValue(parameter.name())
                .setValueType("IDENTIFIER")
                .setValueTypeConstraint("string")
                .setPlaceholder(parameter.name())
                .setEnabled(true)
                .setEditable(false)
                .setType(false)
                .setOptional(false)
                .setAdvanced(false);

        Value.ValueBuilder parameterType = new Value.ValueBuilder();
        parameterType
                .setMetadata(new MetaData("Type", "The type of the parameter"))
                .setValue(parameter.type())
                .setValueType("TYPE")
                .setValueTypeConstraint("string")
                .setPlaceholder(parameter.type())
                .setEnabled(true)
                .setEditable(true)
                .setType(true)
                .setOptional(true)
                .setAdvanced(false);

        Value.ValueBuilder parameterDefaultValue = new Value.ValueBuilder();
        parameterDefaultValue
                .setMetadata(new MetaData("Default Value", "The default value of the parameter"))
                .setValue(parameter.defaultValue())
                .setValueType("EXPRESSION")
                .setValueTypeConstraint("string")
                .setPlaceholder(parameter.defaultValue())
                .setEnabled(true)
                .setEditable(true)
                .setType(true)
                .setOptional(true)
                .setAdvanced(false);

        Parameter.Builder parameterBuilder = new Parameter.Builder();
        parameterBuilder
                .metadata(new MetaData(parameter.name(), parameter.description()))
                .kind(parameter.kind())
                .type(parameterType.build())
                .name(parameterName.build())
                .defaultValue(parameterDefaultValue.build())
                .enabled(true)
                .editable(true)
                .optional(true)
                .advanced(false)
                .httpParamType(null);

        return parameterBuilder.build();
    }

    private static Value getTypeDescriptorProperty(ServiceDeclaration template, int packageId) {
        List<String> serviceTypes = ServiceDatabaseManager.getInstance().getServiceTypes(packageId);
        String value = "";
        if (serviceTypes.size() == 1) {
            value = serviceTypes.getFirst();
        }
        List<String> items = new ArrayList<>();
        items.add("");
        items.addAll(serviceTypes);

        Value.ValueBuilder valueBuilder = new Value.ValueBuilder();
        valueBuilder
                .setMetadata(new MetaData(template.typeDescriptorLabel(), template.typeDescriptorDescription()))
                .setCodedata(new Codedata("SERVICE_TYPE"))
                .setValue(value)
                .setItems(items)
                .setValueType("SINGLE_SELECT")
                .setValueTypeConstraint("string")
                .setPlaceholder(template.typeDescriptorDefaultValue())
                .setOptional(false)
                .setAdvanced(false)
                .setEnabled(template.optionalTypeDescriptor() == 0)
                .setEditable(true)
                .setType(false)
                .setAddNewButton(false);

        return valueBuilder.build();
    }

    public static Value getStringLiteralProperty(String value) {
        Value.ValueBuilder valueBuilder = new Value.ValueBuilder();
        valueBuilder
                .setMetadata(new MetaData("String Literal", "The string literal of the service"))
                .setCodedata(new Codedata("STRING_LITERAL"))
                .setValue(value)
                .setValues(new ArrayList<>())
                .setValueType("EXPRESSION")
                .setValueTypeConstraint("string")
                .setPlaceholder("\"/path\"")
                .setOptional(false)
                .setAdvanced(false)
                .setEnabled(true)
                .setEditable(true)
                .setType(false)
                .setAddNewButton(false);

        return valueBuilder.build();
    }

    private static Value getStringLiteral(ServiceDeclaration template) {
        Value.ValueBuilder valueBuilder = new Value.ValueBuilder();
        valueBuilder
                .setMetadata(new MetaData(template.stringLiteralLabel(), template.stringLiteralDescription()))
                .setCodedata(new Codedata("STRING_LITERAL"))
                .setValue("")
                .setValues(new ArrayList<>())
                .setValueType("EXPRESSION")
                .setValueTypeConstraint("string")
                .setPlaceholder(template.stringLiteralDefaultValue())
                .setOptional(false)
                .setAdvanced(false)
                .setEnabled(true)
                .setEditable(true)
                .setType(false)
                .setAddNewButton(false);

        return valueBuilder.build();
    }

    public static Value getBasePathProperty(String value) {
        Value.ValueBuilder valueBuilder = new Value.ValueBuilder();
        valueBuilder
                .setMetadata(new MetaData("Base Path", "The base path of the service"))
                .setCodedata(new Codedata("SERVICE_BASE_PATH"))
                .setValue(value)
                .setValues(new ArrayList<>())
                .setValueType("IDENTIFIER")
                .setValueTypeConstraint("string")
                .setPlaceholder("/")
                .setOptional(false)
                .setAdvanced(false)
                .setEnabled(true)
                .setEditable(true)
                .setType(false)
                .setAddNewButton(false);

        return valueBuilder.build();
    }

    private static Value getBasePathProperty(ServiceDeclaration template) {
        Value.ValueBuilder valueBuilder = new Value.ValueBuilder();
        valueBuilder
                .setMetadata(new MetaData(template.absoluteResourcePathLabel(),
                        template.absoluteResourcePathDescription()))
                .setCodedata(new Codedata("SERVICE_BASE_PATH"))
                .setValue(template.absoluteResourcePathDefaultValue())
                .setValues(new ArrayList<>())
                .setValueType("IDENTIFIER")
                .setValueTypeConstraint("string")
                .setPlaceholder(template.absoluteResourcePathDefaultValue())
                .setOptional(false)
                .setAdvanced(false)
                .setEnabled(true)
                .setEditable(true)
                .setType(false)
                .setAddNewButton(false);

        return valueBuilder.build();
    }

    private static Value getAnnotationAttachmentProperty(AnnotationAttachment attachment) {
        String typeName = attachment.typeName();
        String[] split = typeName.split(":");
        if (split.length > 1) {
            typeName = split[1];
        }
        PropertyTypeMemberInfo propertyTypeMemberInfo = new PropertyTypeMemberInfo(typeName, attachment.packageInfo(),
                "RECORD_TYPE", true);
        Codedata codedata = new Codedata("ANNOTATION_ATTACHMENT");
        codedata.setOriginalName(attachment.annotName());

        Value.ValueBuilder valueBuilder = new Value.ValueBuilder()
                .setMetadata(new MetaData(attachment.displayName(), attachment.description()))
                .setCodedata(codedata)
                .setValue("")
                .setValues(new ArrayList<>())
                .setValueType("EXPRESSION")
                .setValueTypeConstraint(attachment.typeName())
                .setPlaceholder("{}")
                .setOptional(true)
                .setAdvanced(true)
                .setEnabled(true)
                .setEditable(true)
                .setType(false)
                .setAddNewButton(false)
                .setMembers(List.of(propertyTypeMemberInfo));

        return valueBuilder.build();
    }

    private static Value getListenersProperty(String protocol, String valueType) {
        boolean isMultiple = valueType.equals("MULTIPLE_SELECT");
        MetaData metaData = isMultiple ?
                new MetaData("Listeners", "The Listeners to be bound with the service")
                : new MetaData("Listener", "The Listener to be bound with the service");

        Value.ValueBuilder valueBuilder = new Value.ValueBuilder();
        valueBuilder
                .setMetadata(metaData)
                .setCodedata(new Codedata("LISTENER"))
                .setValue("")
                .setValues(new ArrayList<>())
                .setValueType(valueType)
                .setValueTypeConstraint(protocol + ":" + "Listener")
                .setPlaceholder("")
                .setOptional(false)
                .setAdvanced(false)
                .setEnabled(true)
                .setEditable(true)
                .setType(false)
                .setAddNewButton(isMultiple);

        return valueBuilder.build();
    }

    public static String getProtocol(String moduleName) {
        String[] split = moduleName.split("\\.");
        return split[split.length - 1];
    }


    public static Optional<Service> getHttpService() {
        InputStream resourceStream = ServiceModelUtils.class.getClassLoader()
                .getResourceAsStream("services/http.json");
        if (resourceStream == null) {
            return Optional.empty();
        }

        try (JsonReader reader = new JsonReader(new InputStreamReader(resourceStream, StandardCharsets.UTF_8))) {
            return Optional.of(new Gson().fromJson(reader, Service.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static void updateListenerItems(String moduleName, SemanticModel semanticModel, Project project,
                                           Service serviceModel) {
        Set<String> listeners = ListenerUtil.getCompatibleListeners(moduleName, semanticModel, project);
        List<String> allValues = serviceModel.getListener().getValues();
        if (Objects.isNull(allValues) || allValues.isEmpty()) {
            listeners.add(serviceModel.getListener().getValue());
        } else {
            listeners.addAll(allValues);
        }
        Value listener = serviceModel.getListener();
        if (!listeners.isEmpty()) {
            listener.setItems(listeners.stream().toList());
        }
    }
}
