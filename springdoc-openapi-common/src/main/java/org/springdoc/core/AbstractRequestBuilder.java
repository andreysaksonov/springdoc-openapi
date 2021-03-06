/*
 *
 *  *
 *  *  * Copyright 2019-2020 the original author or authors.
 *  *  *
 *  *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  *  * you may not use this file except in compliance with the License.
 *  *  * You may obtain a copy of the License at
 *  *  *
 *  *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *  *
 *  *  * Unless required by applicable law or agreed to in writing, software
 *  *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  * See the License for the specific language governing permissions and
 *  *  * limitations under the License.
 *  *
 *
 */

package org.springdoc.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.customizers.ParameterCustomizer;

import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.UriComponentsBuilder;

import static org.springdoc.core.Constants.OPENAPI_ARRAY_TYPE;
import static org.springdoc.core.Constants.OPENAPI_STRING_TYPE;
import static org.springdoc.core.Constants.QUERY_PARAM;
import static org.springdoc.core.converters.SchemaPropertyDeprecatingConverter.containsDeprecatedAnnotation;

public abstract class AbstractRequestBuilder {

	private static final List<Class> PARAM_TYPES_TO_IGNORE = new ArrayList<>();

	// using string litterals to support both validation-api v1 and v2
	private static final String[] ANNOTATIONS_FOR_REQUIRED = { NotNull.class.getName(), "javax.validation.constraints.NotBlank", "javax.validation.constraints.NotEmpty" };

	private static final String POSITIVE_OR_ZERO = "javax.validation.constraints.PositiveOrZero";

	private static final String NEGATIVE_OR_ZERO = "javax.validation.constraints.NegativeOrZero";

	static {
		PARAM_TYPES_TO_IGNORE.add(WebRequest.class);
		PARAM_TYPES_TO_IGNORE.add(NativeWebRequest.class);
		PARAM_TYPES_TO_IGNORE.add(java.security.Principal.class);
		PARAM_TYPES_TO_IGNORE.add(HttpMethod.class);
		PARAM_TYPES_TO_IGNORE.add(java.util.Locale.class);
		PARAM_TYPES_TO_IGNORE.add(java.util.TimeZone.class);
		PARAM_TYPES_TO_IGNORE.add(java.io.InputStream.class);
		PARAM_TYPES_TO_IGNORE.add(java.time.ZoneId.class);
		PARAM_TYPES_TO_IGNORE.add(java.io.Reader.class);
		PARAM_TYPES_TO_IGNORE.add(java.io.OutputStream.class);
		PARAM_TYPES_TO_IGNORE.add(java.io.Writer.class);
		PARAM_TYPES_TO_IGNORE.add(java.util.Map.class);
		PARAM_TYPES_TO_IGNORE.add(org.springframework.ui.Model.class);
		PARAM_TYPES_TO_IGNORE.add(org.springframework.ui.ModelMap.class);
		PARAM_TYPES_TO_IGNORE.add(Errors.class);
		PARAM_TYPES_TO_IGNORE.add(BindingResult.class);
		PARAM_TYPES_TO_IGNORE.add(SessionStatus.class);
		PARAM_TYPES_TO_IGNORE.add(UriComponentsBuilder.class);
		PARAM_TYPES_TO_IGNORE.add(RequestAttribute.class);
	}

	private final GenericParameterBuilder parameterBuilder;

	private final RequestBodyBuilder requestBodyBuilder;

	private final OperationBuilder operationBuilder;

	private final LocalVariableTableParameterNameDiscoverer localSpringDocParameterNameDiscoverer;

	private final Optional<List<ParameterCustomizer>> parameterCustomizers;

	protected AbstractRequestBuilder(GenericParameterBuilder parameterBuilder, RequestBodyBuilder requestBodyBuilder,
			OperationBuilder operationBuilder,Optional<List<ParameterCustomizer>> parameterCustomizers,
			LocalVariableTableParameterNameDiscoverer localSpringDocParameterNameDiscoverer) {
		super();
		this.parameterBuilder = parameterBuilder;
		this.requestBodyBuilder = requestBodyBuilder;
		this.operationBuilder = operationBuilder;
		if (parameterCustomizers.isPresent())
			parameterCustomizers.get().removeIf(Objects::isNull);
		this.parameterCustomizers = parameterCustomizers;
		this.localSpringDocParameterNameDiscoverer = localSpringDocParameterNameDiscoverer;
	}

	public static void addRequestWrapperToIgnore(Class<?>... classes) {
		PARAM_TYPES_TO_IGNORE.addAll(Arrays.asList(classes));
	}

	public static void removeRequestWrapperToIgnore(Class<?>... classes) {
		List classesToIgnore = Arrays.asList(classes);
		if (PARAM_TYPES_TO_IGNORE.containsAll(classesToIgnore))
			PARAM_TYPES_TO_IGNORE.removeAll(Arrays.asList(classes));
	}

	public static boolean isRequestTypeToIgnore(Class<?> rawClass) {
		return PARAM_TYPES_TO_IGNORE.stream().anyMatch(clazz -> clazz.isAssignableFrom(rawClass));
	}

	public Operation build(HandlerMethod handlerMethod, RequestMethod requestMethod,
			Operation operation, MethodAttributes methodAttributes, OpenAPI openAPI) {
		// Documentation
		String operationId = operationBuilder.getOperationId(handlerMethod.getMethod().getName(),
				operation.getOperationId(), openAPI);
		operation.setOperationId(operationId);
		// requests
		String[] pNames = this.localSpringDocParameterNameDiscoverer.getParameterNames(handlerMethod.getMethod());
		MethodParameter[] parameters = handlerMethod.getMethodParameters();
		String[] reflectionParametersNames = Arrays.stream(handlerMethod.getMethod().getParameters()).map(java.lang.reflect.Parameter::getName).toArray(String[]::new);
		if (pNames == null || Arrays.stream(pNames).anyMatch(Objects::isNull))
			pNames = reflectionParametersNames;
		parameters = DelegatingMethodParameter.customize(pNames, parameters);
		RequestBodyInfo requestBodyInfo = new RequestBodyInfo();
		List<Parameter> operationParameters = (operation.getParameters() != null) ? operation.getParameters() : new ArrayList<>();
		Map<String, io.swagger.v3.oas.annotations.Parameter> parametersDocMap = getApiParameters(handlerMethod.getMethod());
		Components components = openAPI.getComponents();

		for (MethodParameter methodParameter : parameters) {
			// check if query param
			Parameter parameter = null;
			io.swagger.v3.oas.annotations.Parameter parameterDoc = methodParameter.getParameterAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
			final String pName = methodParameter.getParameterName();
			ParameterInfo parameterInfo = new ParameterInfo(pName, methodParameter);

			if (parameterDoc == null)
				parameterDoc = parametersDocMap.get(parameterInfo.getpName());
			// use documentation as reference
			if (parameterDoc != null) {
				if (parameterDoc.hidden() || parameterDoc.schema().hidden())
					continue;
				parameter = parameterBuilder.buildParameterFromDoc(parameterDoc, components, methodAttributes.getJsonViewAnnotation());
				parameterInfo.setParameterModel(parameter);
			}

			if (!isParamToIgnore(methodParameter)) {
				parameter = buildParams(parameterInfo, components, requestMethod, methodAttributes.getJsonViewAnnotation());
				// Merge with the operation parameters
				parameter = parameterBuilder.mergeParameter(operationParameters, parameter);
				List<Annotation> parameterAnnotations = Arrays.asList(methodParameter.getParameterAnnotations());
				if (isValidParameter(parameter))
					applyBeanValidatorAnnotations(parameter, parameterAnnotations);
				else if (!RequestMethod.GET.equals(requestMethod)) {
					if (operation.getRequestBody() != null)
						requestBodyInfo.setRequestBody(operation.getRequestBody());
					requestBodyBuilder.calculateRequestBodyInfo(components, methodAttributes,
							parameterInfo, requestBodyInfo);
					applyBeanValidatorAnnotations(requestBodyInfo.getRequestBody(), parameterAnnotations, methodParameter.isOptional());
				}
				customiseParameter(parameter, parameterInfo);
			}
		}

		LinkedHashMap<String, Parameter> map = getParameterLinkedHashMap(components, methodAttributes, operationParameters, parametersDocMap);
		setParams(operation, new ArrayList<>(map.values()), requestBodyInfo);
		return operation;
	}

	private LinkedHashMap<String, Parameter> getParameterLinkedHashMap(Components components, MethodAttributes methodAttributes, List<Parameter> operationParameters, Map<String, io.swagger.v3.oas.annotations.Parameter> parametersDocMap) {
		LinkedHashMap<String, Parameter> map = operationParameters.stream()
				.collect(Collectors.toMap(
						parameter -> parameter.getName() != null ? parameter.getName() : Integer.toString(parameter.hashCode()),
						parameter -> parameter,
						(u, v) -> {
							throw new IllegalStateException(String.format("Duplicate key %s", u));
						},
						LinkedHashMap::new
				));

		for (Map.Entry<String, io.swagger.v3.oas.annotations.Parameter> entry : parametersDocMap.entrySet()) {
			if (entry.getKey() != null && !map.containsKey(entry.getKey()) && !entry.getValue().hidden()) {
				//Convert
				Parameter parameter = parameterBuilder.buildParameterFromDoc(entry.getValue(), components,
						methodAttributes.getJsonViewAnnotation());
				map.put(entry.getKey(), parameter);
			}
		}

		for (Map.Entry<String, String> entry : methodAttributes.getHeaders().entrySet()) {
			Parameter parameter = new Parameter().in(ParameterIn.HEADER.toString()).name(entry.getKey()).schema(new StringSchema().addEnumItem(entry.getValue()));
			if (map.containsKey(entry.getKey())) {
				parameter = map.get(entry.getKey());
				parameter.getSchema().addEnumItemObject(entry.getValue());
				parameter.setSchema(parameter.getSchema());
			}
			map.put(entry.getKey(), parameter);
		}
		return map;
	}

	protected Parameter customiseParameter(Parameter parameter, ParameterInfo parameterInfo) {
		parameterCustomizers.ifPresent(customizers -> customizers.forEach(customizer -> customizer.customize(parameter, parameterInfo.getMethodParameter())));
		return parameter;
	}

	protected boolean isParamToIgnore(MethodParameter parameter) {
		if (parameterBuilder.isAnnotationToIgnore(parameter))
			return true;
		if ((parameter.getParameterAnnotation(PathVariable.class) != null && parameter.getParameterAnnotation(PathVariable.class).required())
				|| (parameter.getParameterAnnotation(RequestParam.class) != null && parameter.getParameterAnnotation(RequestParam.class).required())
				|| (parameter.getParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class) != null && parameter.getParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class).required()))
			return false;
		return isRequestTypeToIgnore(parameter.getParameterType());
	}

	private void setParams(Operation operation, List<Parameter> operationParameters, RequestBodyInfo requestBodyInfo) {
		if (!CollectionUtils.isEmpty(operationParameters))
			operation.setParameters(operationParameters);
		if (requestBodyInfo.getRequestBody() != null)
			operation.setRequestBody(requestBodyInfo.getRequestBody());
	}

	private boolean isValidParameter(Parameter parameter) {
		return parameter != null && (parameter.getName() != null || parameter.get$ref() != null);
	}

	private Parameter buildParams(ParameterInfo parameterInfo, Components components,
			RequestMethod requestMethod, JsonView jsonView) {
		MethodParameter methodParameter = parameterInfo.getMethodParameter();
		RequestHeader requestHeader = parameterInfo.getRequestHeader();
		RequestParam requestParam = parameterInfo.getRequestParam();
		PathVariable pathVar = parameterInfo.getPathVar();
		CookieValue cookieValue = parameterInfo.getCookieValue();

		Parameter parameter = null;
		RequestInfo requestInfo;

		if (requestHeader != null) {
			requestInfo = new RequestInfo(ParameterIn.HEADER.toString(), parameterInfo.getpName(), requestHeader.required(),
					requestHeader.defaultValue());
			parameter = buildParam(parameterInfo, components, requestInfo, jsonView);

		}
		else if (requestParam != null && !parameterBuilder.isFile(parameterInfo.getMethodParameter())) {
			requestInfo = new RequestInfo(ParameterIn.QUERY.toString(), parameterInfo.getpName(), requestParam.required() && !methodParameter.isOptional(),
					requestParam.defaultValue());
			parameter = buildParam(parameterInfo, components, requestInfo, jsonView);
		}
		else if (pathVar != null) {
			requestInfo = new RequestInfo(ParameterIn.PATH.toString(), parameterInfo.getpName(), !methodParameter.isOptional(), null);
			parameter = buildParam(parameterInfo, components, requestInfo, jsonView);
		}
		else if (cookieValue != null) {
			requestInfo = new RequestInfo(ParameterIn.COOKIE.toString(), parameterInfo.getpName(), cookieValue.required(),
					cookieValue.defaultValue());
			parameter = buildParam(parameterInfo, components, requestInfo, jsonView);
		}
		// By default
		if (RequestMethod.GET.equals(requestMethod) || (parameterInfo.getParameterModel() != null && ParameterIn.PATH.toString().equals(parameterInfo.getParameterModel().getIn())))
			parameter = this.buildParam(QUERY_PARAM, components, parameterInfo, !methodParameter.isOptional(), null, jsonView);

		return parameter;
	}

	private Parameter buildParam(ParameterInfo parameterInfo, Components components, RequestInfo requestInfo,
			JsonView jsonView) {
		Parameter parameter;
		String pName = parameterInfo.getpName();
		String name = StringUtils.isBlank(requestInfo.value()) ? pName : requestInfo.value();
		parameterInfo.setpName(name);

		if (!ValueConstants.DEFAULT_NONE.equals(requestInfo.defaultValue()))
			parameter = this.buildParam(requestInfo.type(), components, parameterInfo, false,
					requestInfo.defaultValue(), jsonView);
		else
			parameter = this.buildParam(requestInfo.type(), components, parameterInfo, requestInfo.required(), null,
					jsonView);
		return parameter;
	}

	private Parameter buildParam(String in, Components components, ParameterInfo parameterInfo, Boolean required,
			String defaultValue, JsonView jsonView) {
		Parameter parameter = parameterInfo.getParameterModel();
		String name = parameterInfo.getpName();

		if (parameter == null) {
			parameter = new Parameter();
			parameterInfo.setParameterModel(parameter);
		}

		if (StringUtils.isBlank(parameter.getName()))
			parameter.setName(name);

		if (StringUtils.isBlank(parameter.getIn()))
			parameter.setIn(in);

		if (required != null && parameter.getRequired() == null)
			parameter.setRequired(required);

		if (containsDeprecatedAnnotation(parameterInfo.getMethodParameter().getParameterAnnotations()))
			parameter.setDeprecated(true);

		if (parameter.getSchema() == null) {
			Schema<?> schema = parameterBuilder.calculateSchema(components, parameterInfo, null,
					jsonView);
			if (defaultValue != null)
				schema.setDefault(defaultValue);
			parameter.setSchema(schema);
		}
		return parameter;
	}

	private void applyBeanValidatorAnnotations(final Parameter parameter, final List<Annotation> annotations) {
		Map<String, Annotation> annos = new HashMap<>();
		if (annotations != null)
			annotations.forEach(annotation -> annos.put(annotation.annotationType().getName(), annotation));
		boolean annotationExists = Arrays.stream(ANNOTATIONS_FOR_REQUIRED).anyMatch(annos::containsKey);
		if (annotationExists)
			parameter.setRequired(true);
		Schema<?> schema = parameter.getSchema();
		applyValidationsToSchema(annos, schema);
	}

	private void applyBeanValidatorAnnotations(final RequestBody requestBody, final List<Annotation> annotations, boolean isOptional) {
		Map<String, Annotation> annos = new HashMap<>();
		boolean requestBodyRequired = false;
		if (!CollectionUtils.isEmpty(annotations)) {
			annotations.forEach(annotation -> annos.put(annotation.annotationType().getName(), annotation));
			requestBodyRequired = annotations.stream()
					.filter(annotation -> org.springframework.web.bind.annotation.RequestBody.class.equals(annotation.annotationType()))
					.anyMatch(annotation -> ((org.springframework.web.bind.annotation.RequestBody) annotation).required());
		}
		boolean validationExists = Arrays.stream(ANNOTATIONS_FOR_REQUIRED).anyMatch(annos::containsKey);

		if (validationExists || (!isOptional && requestBodyRequired))
			requestBody.setRequired(true);
		Content content = requestBody.getContent();
		for (MediaType mediaType : content.values()) {
			Schema schema = mediaType.getSchema();
			applyValidationsToSchema(annos, schema);
		}
	}

	private void calculateSize(Map<String, Annotation> annos, Schema<?> schema) {
		if (annos.containsKey(Size.class.getName())) {
			Size size = (Size) annos.get(Size.class.getName());
			if (OPENAPI_ARRAY_TYPE.equals(schema.getType())) {
				schema.setMinItems(size.min());
				schema.setMaxItems(size.max());
			}
			else if (OPENAPI_STRING_TYPE.equals(schema.getType())) {
				schema.setMinLength(size.min());
				schema.setMaxLength(size.max());
			}
		}
	}

	public RequestBodyBuilder getRequestBodyBuilder() {
		return requestBodyBuilder;
	}

	private Map<String, io.swagger.v3.oas.annotations.Parameter> getApiParameters(Method method) {
		Class<?> declaringClass = method.getDeclaringClass();

		Set<io.swagger.v3.oas.annotations.Parameters> apiParametersDoc = AnnotatedElementUtils
				.findAllMergedAnnotations(method, io.swagger.v3.oas.annotations.Parameters.class);
		LinkedHashMap<String, io.swagger.v3.oas.annotations.Parameter> apiParametersMap = apiParametersDoc.stream()
				.flatMap(x -> Stream.of(x.value())).collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x, (e1, e2) -> e2,
						LinkedHashMap::new));

		Set<io.swagger.v3.oas.annotations.Parameters> apiParametersDocDeclaringClass = AnnotatedElementUtils
				.findAllMergedAnnotations(declaringClass, io.swagger.v3.oas.annotations.Parameters.class);
		LinkedHashMap<String, io.swagger.v3.oas.annotations.Parameter> apiParametersDocDeclaringClassMap = apiParametersDocDeclaringClass.stream()
				.flatMap(x -> Stream.of(x.value())).collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x, (e1, e2) -> e2,
						LinkedHashMap::new));
		apiParametersMap.putAll(apiParametersDocDeclaringClassMap);

		Set<io.swagger.v3.oas.annotations.Parameter> apiParameterDoc = AnnotatedElementUtils
				.findAllMergedAnnotations(method, io.swagger.v3.oas.annotations.Parameter.class);
		LinkedHashMap<String, io.swagger.v3.oas.annotations.Parameter> apiParameterDocMap = apiParameterDoc.stream()
				.collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x, (e1, e2) -> e2,
						LinkedHashMap::new));
		apiParametersMap.putAll(apiParameterDocMap);

		Set<io.swagger.v3.oas.annotations.Parameter> apiParameterDocDeclaringClass = AnnotatedElementUtils
				.findAllMergedAnnotations(declaringClass, io.swagger.v3.oas.annotations.Parameter.class);
		LinkedHashMap<String, io.swagger.v3.oas.annotations.Parameter> apiParameterDocDeclaringClassMap = apiParameterDocDeclaringClass.stream()
				.collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x, (e1, e2) -> e2,
						LinkedHashMap::new));
		apiParametersMap.putAll(apiParameterDocDeclaringClassMap);

		return apiParametersMap;
	}

	private void applyValidationsToSchema(Map<String, Annotation> annos, Schema<?> schema) {
		if (annos.containsKey(Min.class.getName())) {
			Min min = (Min) annos.get(Min.class.getName());
			schema.setMinimum(BigDecimal.valueOf(min.value()));
		}
		if (annos.containsKey(Max.class.getName())) {
			Max max = (Max) annos.get(Max.class.getName());
			schema.setMaximum(BigDecimal.valueOf(max.value()));
		}
		calculateSize(annos, schema);
		if (annos.containsKey(DecimalMin.class.getName())) {
			DecimalMin min = (DecimalMin) annos.get(DecimalMin.class.getName());
			if (min.inclusive())
				schema.setMinimum(BigDecimal.valueOf(Double.parseDouble(min.value())));
			else
				schema.setExclusiveMinimum(!min.inclusive());
		}
		if (annos.containsKey(DecimalMax.class.getName())) {
			DecimalMax max = (DecimalMax) annos.get(DecimalMax.class.getName());
			if (max.inclusive())
				schema.setMaximum(BigDecimal.valueOf(Double.parseDouble(max.value())));
			else
				schema.setExclusiveMaximum(!max.inclusive());
		}
		if (annos.containsKey(POSITIVE_OR_ZERO))
			schema.setMinimum(BigDecimal.ZERO);
		if (annos.containsKey(NEGATIVE_OR_ZERO))
			schema.setMaximum(BigDecimal.ZERO);
		if (annos.containsKey(Pattern.class.getName())) {
			Pattern pattern = (Pattern) annos.get(Pattern.class.getName());
			schema.setPattern(pattern.regexp());
		}
	}

}
