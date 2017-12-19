package com.airbnb.paris.processor.writers

import com.airbnb.paris.processor.*
import com.airbnb.paris.processor.android_resource_scanner.*
import com.airbnb.paris.processor.framework.*
import com.airbnb.paris.processor.models.*
import com.airbnb.paris.processor.utils.VIEW_TYPE
import com.squareup.javapoet.*
import java.io.*
import javax.lang.model.element.*
import kotlin.check

internal class StyleAppliersWriter(processor: ParisProcessor) : ParisHelper(processor) {

    val styleablesTree = StyleablesTree(this)

    lateinit var styleablesInfo: List<StyleableInfo>
    lateinit var allStyleablesInfo: List<BaseStyleableInfo>

    @Throws(IOException::class)
    fun writeFrom(styleablesInfo: List<StyleableInfo>, externalStyleablesInfo: List<BaseStyleableInfo>) {
        this.styleablesInfo = styleablesInfo
        allStyleablesInfo = styleablesInfo + externalStyleablesInfo

        for (styleableInfo in styleablesInfo) {
            writeStyleApplier(styleableInfo)
        }
    }

    private fun writeStyleApplier(styleableInfo: StyleableInfo) {
        val styleApplierClassName = styleableInfo.styleApplierClassName()

        val styleTypeBuilder = TypeSpec.classBuilder(styleApplierClassName)
                .addAnnotation(AndroidClassNames.UI_THREAD)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(STYLE_APPLIER_CLASS_NAME, TypeName.get(styleableInfo.elementType), TypeName.get(styleableInfo.viewElementType)))
                .addMethod(buildConstructorMethod(styleableInfo))

        // If the view type is "View" then there is no parent
        var parentStyleApplierClassName: ClassName? = null
        if (!isSameType(elements.VIEW_TYPE.asType(), styleableInfo.viewElementType)) {
            parentStyleApplierClassName = styleablesTree.findStyleApplier(
                    allStyleablesInfo,
                    styleableInfo.viewElementType.asTypeElement().superclass.asTypeElement())
            styleTypeBuilder.addMethod(buildApplyParentMethod(parentStyleApplierClassName))
        }

        var rClassName: ClassName? = null
        if (!styleableInfo.styleableResourceName.isEmpty()) {
            // Use an arbitrary AndroidResourceId to get R's ClassName. Per the StyleableInfo doc
            // it's safe to assume that either styleableChildren or attrs won't be empty if
            // styleableResourceName isn't either
            val arbitraryResId = if (!styleableInfo.styleableChildren.isEmpty()) {
                styleableInfo.styleableChildren[0].styleableResId
            } else {
                styleableInfo.attrs[0].styleableResId
            }
            rClassName = arbitraryResId.className!!.enclosingClassName()

            val attributesWithDefaultValueMethod = buildAttributesWithDefaultValueMethod(styleableInfo.attrs)
            styleTypeBuilder.apply {
                addMethod(buildAttributesMethod(rClassName!!, styleableInfo.styleableResourceName))
                attributesWithDefaultValueMethod?.let {
                    addMethod(attributesWithDefaultValueMethod)
                }
                addMethod(buildProcessStyleableFieldsMethod(styleableInfo.styleableChildren))
                addMethod(buildProcessAttributesMethod(styleableInfo.beforeStyles, styleableInfo.afterStyles, styleableInfo.attrs))
            }

        }

        val styleBuilderClassName = styleApplierClassName.nestedClass("StyleBuilder")
        addStyleBuilderInnerClass(styleTypeBuilder, styleApplierClassName, rClassName, styleableInfo, parentStyleApplierClassName)

        for (styleableFieldInfo in styleableInfo.styleableChildren) {
            val subStyleApplierClassName = styleablesTree.findStyleApplier(
                    allStyleablesInfo,
                    styleableFieldInfo.elementType.asTypeElement())
            styleTypeBuilder.addMethod(buildSubMethod(styleableFieldInfo, subStyleApplierClassName))
        }

        for (styleInfo in styleableInfo.styles) {
            styleTypeBuilder.addMethod(buildApplyStyleMethod(styleBuilderClassName, styleInfo))
        }

        JavaFile.builder(styleApplierClassName.packageName(), styleTypeBuilder.build())
                .build()
                .writeTo(filer)
    }

    private fun buildConstructorMethod(classInfo: StyleableInfo): MethodSpec {
        val builder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(classInfo.viewElementType), "view")
        if (classInfo.elementType == classInfo.viewElementType) {
            builder.addStatement("super(view)")
        } else {
            // Different types means this style applier uses a proxy
            builder.addStatement("super(new \$T(view))", classInfo.elementType)
        }
        return builder.build()
    }

    private fun buildApplyParentMethod(parentStyleApplierClassName: ClassName): MethodSpec {
        return MethodSpec.methodBuilder("applyParent")
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PROTECTED)
                .addParameter(ParameterSpec.builder(STYLE_CLASS_NAME, "style").build())
                .addStatement("\$T applier = new \$T(getView())", parentStyleApplierClassName, parentStyleApplierClassName)
                .addStatement("applier.setDebugListener(getDebugListener())")
                .addStatement("applier.apply(style)")
                .build()
    }

    private fun buildAttributesMethod(rClassName: ClassName, resourceName: String): MethodSpec {
        return MethodSpec.methodBuilder("attributes")
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PROTECTED)
                .returns(ArrayTypeName.of(Integer.TYPE))
                .addStatement("return \$T.styleable.\$L", rClassName, resourceName)
                .build()
    }

    private fun buildAttributesWithDefaultValueMethod(attrs: List<AttrInfo>): MethodSpec? {
        val attrsWithDefaultValue = attrs
                .filter { it.defaultValueResId != null }
                .map { it.styleableResId}
                .toSet()
        if (attrsWithDefaultValue.isNotEmpty()) {
            val builder = MethodSpec.methodBuilder("attributesWithDefaultValue")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ArrayTypeName.of(Integer.TYPE))
                    .addCode("return new int[] {")
            for (attr in attrsWithDefaultValue) {
                builder.addCode("\$L,", attr.code)
            }
            return builder.addCode("};\n")
                    .build()
        } else {
            return null
        }
    }

    private fun buildProcessStyleableFieldsMethod(styleableChildren: List<StyleableChildInfo>): MethodSpec {
        val methodBuilder = MethodSpec.methodBuilder("processStyleableFields")
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PROTECTED)
                .addParameter(ParameterSpec.builder(STYLE_CLASS_NAME, "style").build())
                .addParameter(ParameterSpec.builder(TYPED_ARRAY_WRAPPER_CLASS_NAME, "a").build())
                .addStatement("\$T res = getView().getContext().getResources()", AndroidClassNames.RESOURCES)

        for (styleableField in styleableChildren) {
            addControlFlow(methodBuilder, Format.STYLE, styleableField.elementName,
                    styleableField.styleableResId, styleableField.defaultValueResId, true)
        }

        return methodBuilder.build()
    }

    private fun buildProcessAttributesMethod(beforeStyles: List<BeforeStyleInfo>,
                                             afterStyles: List<AfterStyleInfo>,
                                             attrs: List<AttrInfo>): MethodSpec {
        val methodBuilder = MethodSpec.methodBuilder("processAttributes")
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PROTECTED)
                .addParameter(ParameterSpec.builder(STYLE_CLASS_NAME, "style").build())
                .addParameter(ParameterSpec.builder(TYPED_ARRAY_WRAPPER_CLASS_NAME, "a").build())
                .addStatement("\$T res = getView().getContext().getResources()", AndroidClassNames.RESOURCES)

        for (beforeStyle in beforeStyles) {
            methodBuilder.addStatement("getProxy().\$N(style)", beforeStyle.elementName)
        }

        for (attr in attrs) {
            addControlFlow(methodBuilder, attr.targetFormat, attr.elementName,
                    attr.styleableResId, attr.defaultValueResId, false)
        }

        for (afterStyle in afterStyles) {
            methodBuilder.addStatement("getProxy().\$N(style)", afterStyle.elementName)
        }

        return methodBuilder.build()
    }

    private fun addControlFlow(methodBuilder: MethodSpec.Builder, format: Format,
                               elementName: String, styleableResId: AndroidResourceId,
                               defaultValueResId: AndroidResourceId?, isElementStyleable: Boolean) {
        methodBuilder.beginControlFlow("if (a.hasValue(\$L))", styleableResId.code)
        addStatement(methodBuilder, format.typedArrayMethodCode("a", styleableResId.code), elementName, isElementStyleable)
        methodBuilder.endControlFlow()

        if (defaultValueResId != null) {
            methodBuilder.beginControlFlow("else")
            addStatement(methodBuilder, format.resourcesMethodCode("res", defaultValueResId.code), elementName, isElementStyleable)
            methodBuilder.endControlFlow()
        }
    }

    private fun addStatement(methodSpecBuilder: MethodSpec.Builder, valueCode: CodeBlock,
                             elementName: String, isElementStyleable: Boolean) {
        if (isElementStyleable) {
            methodSpecBuilder.addStatement("\$N().apply(\$L)", elementName, valueCode)
        } else {
            methodSpecBuilder.addStatement("getProxy().\$N(\$L)", elementName, valueCode)
        }
    }

    private fun buildSubMethod(styleableChildInfo: StyleableChildInfo, styleApplierClassName: ClassName): MethodSpec {
        return MethodSpec.methodBuilder(styleableChildInfo.elementName)
                .addModifiers(Modifier.PUBLIC)
                .returns(styleApplierClassName)
                .addStatement("\$T subApplier = new \$T(getProxy().\$N)", styleApplierClassName, styleApplierClassName, styleableChildInfo.elementName)
                .addStatement("subApplier.setDebugListener(getDebugListener())")
                .addStatement("return subApplier", styleApplierClassName, styleableChildInfo.elementName)
                .build()
    }

    private fun buildApplyStyleMethod(styleBuilderClassName: ClassName, styleInfo: StyleInfo): MethodSpec {
        val builder = MethodSpec.methodBuilder("apply${styleInfo.formattedName}").apply {
            addJavadoc(styleInfo.javadoc)
            addModifiers(Modifier.PUBLIC)
        }
        when (styleInfo.elementKind) {
            StyleInfo.Kind.FIELD -> {
                builder.addStatement("apply(\$T.\$L)", styleInfo.enclosingElement, styleInfo.elementName)
            }
            StyleInfo.Kind.METHOD -> {
                builder.addStatement("\$T builder = new \$T()", styleBuilderClassName, styleBuilderClassName)
                        .addStatement("\$T.\$L(builder)", styleInfo.enclosingElement, styleInfo.elementName)
                        .addStatement("apply(builder.build())")
            }
            StyleInfo.Kind.STYLE_RES -> {
                builder.addStatement("apply(\$L)", styleInfo.styleResourceCode)
            }
            StyleInfo.Kind.EMPTY -> {
                // Do nothing!
            }
        }
        return builder.build()
    }

    private fun addStyleBuilderInnerClass(styleApplierTypeBuilder: TypeSpec.Builder,
                                          styleApplierClassName: ClassName,
                                          rClassName: ClassName?,
                                          styleableInfo: StyleableInfo,
                                          parentStyleApplierClassName: ClassName?) {
        // BaseStyleBuilder inner class
        val baseStyleBuilderClassName: ClassName?
        if (parentStyleApplierClassName != null) {
            baseStyleBuilderClassName = parentStyleApplierClassName.nestedClass("BaseStyleBuilder")
        } else {
            baseStyleBuilderClassName = STYLE_BUILDER_CLASS_NAME
        }
        val wildcardTypeName = WildcardTypeName.subtypeOf(Object::class.java)
        val baseClassName = ClassName.get(styleApplierClassName.packageName(), styleApplierClassName.simpleName(), "BaseStyleBuilder")
        val baseStyleBuilderTypeBuilder = TypeSpec.classBuilder(baseClassName)
                .addTypeVariable(TypeVariableName.get("B", ParameterizedTypeName.get(baseClassName, TypeVariableName.get("B"), TypeVariableName.get("A"))))
                .addTypeVariable(TypeVariableName.get("A", ParameterizedTypeName.get(STYLE_APPLIER_CLASS_NAME, wildcardTypeName, wildcardTypeName)))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.ABSTRACT)
                .superclass(ParameterizedTypeName.get(baseStyleBuilderClassName, TypeVariableName.get("B"), TypeVariableName.get("A")))
                .addMethod(buildStyleBuilderApplierConstructorMethod(TypeVariableName.get("A")))
                .addMethod(buildStyleBuilderEmptyConstructorMethod())

        styleableInfo.styleableChildren
                .distinctBy { it.styleableResId.resourceName }
                .forEach { styleableFieldInfo ->
                    baseStyleBuilderTypeBuilder.addMethod(buildStyleBuilderAddSubResMethod(rClassName!!, styleableInfo.styleableResourceName, styleableFieldInfo))
                    baseStyleBuilderTypeBuilder.addMethod(buildStyleBuilderAddSubMethod(rClassName, styleableInfo.styleableResourceName, styleableFieldInfo))
                    baseStyleBuilderTypeBuilder.addMethod(buildStyleBuilderAddSubBuilderMethod(rClassName, styleableInfo.styleableResourceName, styleableFieldInfo))
                }

        styleableInfo.attrs
                // Multiple @Attr can have the same value
                .groupBy { it.styleableResId.resourceName }
                .flatMap { (_, attrInfos) ->
                    buildAttributeSetterMethods(rClassName!!, styleableInfo.styleableResourceName, attrInfos)
                }
                .forEach { baseStyleBuilderTypeBuilder.addMethod(it) }

        baseStyleBuilderTypeBuilder.addMethod(buildApplyToMethod(styleableInfo, styleApplierClassName))

        styleApplierTypeBuilder.addType(baseStyleBuilderTypeBuilder.build())

        // StyleBuilder inner class
        val styleBuilderClassName = styleApplierClassName.nestedClass("StyleBuilder")
        val styleBuilderTypeBuilder = TypeSpec.classBuilder(styleBuilderClassName)
                .addAnnotation(AndroidClassNames.UI_THREAD)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(baseClassName, styleBuilderClassName, styleApplierClassName))
                .addMethod(buildStyleBuilderApplierConstructorMethod(styleApplierClassName))
                .addMethod(buildStyleBuilderEmptyConstructorMethod())

        styleableInfo.styles.forEach {
            styleBuilderTypeBuilder.addMethod(buildStyleBuilderAddMethod(styleBuilderClassName, it))
        }

        styleApplierTypeBuilder.addType(styleBuilderTypeBuilder.build())

        // builder() method
        styleApplierTypeBuilder.addMethod(MethodSpec.methodBuilder("builder")
                .addModifiers(Modifier.PUBLIC)
                .returns(styleBuilderClassName)
                .addStatement("return new \$T(this)", styleBuilderClassName)
                .build())
    }

    private fun buildStyleBuilderApplierConstructorMethod(parameterTypeName: TypeName): MethodSpec {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(parameterTypeName, "applier")
                .addStatement("super(applier)")
                .build()
    }

    private fun buildStyleBuilderEmptyConstructorMethod(): MethodSpec {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .build()
    }

    private fun buildStyleBuilderAddMethod(styleBuilderClassName: ClassName, styleInfo: StyleInfo): MethodSpec {
        val builder = MethodSpec.methodBuilder("add${styleInfo.formattedName}").apply {
            addJavadoc(styleInfo.javadoc)
            addModifiers(Modifier.PUBLIC)
            returns(styleBuilderClassName)
        }
        when (styleInfo.elementKind) {
            StyleInfo.Kind.FIELD -> builder.addStatement("add(\$T.\$L)", styleInfo.enclosingElement, styleInfo.elementName)
            StyleInfo.Kind.METHOD -> {
                builder
                        .addStatement("consumeProgrammaticStyleBuilder()")
                        .addStatement("debugName(\$S)", styleInfo.formattedName)
                        .addStatement("\$T.\$L(this)", styleInfo.enclosingElement, styleInfo.elementName)
                        .addStatement("consumeProgrammaticStyleBuilder()")
            }
            StyleInfo.Kind.STYLE_RES -> {
                builder.addStatement("add(\$L)", styleInfo.styleResourceCode)
            }
            StyleInfo.Kind.EMPTY -> {
                // Do nothing!
            }
        }
        return builder
                .addStatement("return this")
                .build()
    }

    private fun buildStyleBuilderAddSubResMethod(rClassName: ClassName, styleableResourceName: String, styleableChildInfo: StyleableChildInfo): MethodSpec {
        return MethodSpec.methodBuilder(styleableAttrResourceNameToCamelCase(styleableResourceName, styleableChildInfo.styleableResId.resourceName!!))
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(Integer.TYPE, "resId")
                        .addAnnotation(AndroidClassNames.STYLE_RES)
                        .build())
                .returns(TypeVariableName.get("B"))
                .addStatement("getBuilder().putRes(\$T.styleable.\$L[\$L], resId)", rClassName, styleableResourceName, styleableChildInfo.styleableResId.code)
                .addStatement("return (B) this")
                .build()
    }

    private fun buildStyleBuilderAddSubMethod(rClassName: ClassName, styleableResourceName: String, styleableChildInfo: StyleableChildInfo): MethodSpec {
        return MethodSpec.methodBuilder(styleableAttrResourceNameToCamelCase(styleableResourceName, styleableChildInfo.styleableResId.resourceName!!))
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(STYLE_CLASS_NAME, "style").build())
                .returns(TypeVariableName.get("B"))
                .addStatement("getBuilder().put(\$T.styleable.\$L[\$L], style)", rClassName, styleableResourceName, styleableChildInfo.styleableResId.code)
                .addStatement("return (B) this")
                .build()
    }

    private fun buildStyleBuilderAddSubBuilderMethod(rClassName: ClassName, styleableResourceName: String, styleableChildInfo: StyleableChildInfo): MethodSpec {
        val styleApplierClassName = styleablesTree.findStyleApplier(
                allStyleablesInfo,
                styleableChildInfo.elementType.asTypeElement())
        val styleBuilderClassName = styleApplierClassName.nestedClass("StyleBuilder")
        return MethodSpec.methodBuilder(styleableAttrResourceNameToCamelCase(styleableResourceName, styleableChildInfo.styleableResId.resourceName!!))
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(ParameterizedTypeName.get(STYLE_BUILDER_FUNCTION_CLASS_NAME, styleBuilderClassName), "function").build())
                .returns(TypeVariableName.get("B"))
                .addStatement("\$T subBuilder = new \$T()", styleBuilderClassName, styleBuilderClassName)
                .addStatement("function.invoke(subBuilder)")
                .addStatement("getBuilder().put(\$T.styleable.\$L[\$L], subBuilder.build())", rClassName, styleableResourceName, styleableChildInfo.styleableResId.code)
                .addStatement("return (B) this")
                .build()
    }

    /**
     * @param groupedAttrs Grouped by styleable index resource name
     */
    private fun buildAttributeSetterMethods(rClassName: ClassName, styleableResourceName: String, groupedAttrs: List<AttrInfo>): List<MethodSpec> {
        val nonResTargetAttrs = groupedAttrs.filter { it.targetFormat != Format.RESOURCE_ID }

        check(nonResTargetAttrs.isEmpty() || nonResTargetAttrs.distinctBy { it.targetType }.size == 1) {
            "The same @Attr value can't be used on methods with different parameter types (excluding resource id types)"
        }

        val isTargetDimensionType = nonResTargetAttrs.any { it.targetFormat.isDimensionType }
        val isTargetColorStateListType = nonResTargetAttrs.any { it.targetFormat.isColorStateListType }

        val attr = if (nonResTargetAttrs.isNotEmpty()) nonResTargetAttrs.first() else groupedAttrs.first()
        val attrResourceName = attr.styleableResId.resourceName!!
        val baseMethodName = styleableAttrResourceNameToCamelCase(styleableResourceName, attrResourceName)
        val methodSpecs = ArrayList<MethodSpec>()

        if (nonResTargetAttrs.isNotEmpty()) {
            methodSpecs.add(MethodSpec.methodBuilder(baseMethodName).apply {
                addJavadoc(attr.javadoc)

                val valueParameterBuilder = ParameterSpec.builder(TypeName.get(attr.targetType), "value")
                attr.targetFormat.valueAnnotation?.let {
                    valueParameterBuilder.addAnnotation(it)
                }

                addModifiers(Modifier.PUBLIC)
                addParameter(valueParameterBuilder.build())
                returns(TypeVariableName.get("B"))
                addStatement("getBuilder().put(\$T.styleable.\$L[\$L], value)", rClassName, styleableResourceName, attr.styleableResId.code)
                addStatement("return (B) this")
            }.build())
        }

        methodSpecs.add(MethodSpec.methodBuilder("${baseMethodName}Res").apply {
            addJavadoc(attr.javadoc)
            addModifiers(Modifier.PUBLIC)
            addParameter(ParameterSpec.builder(Integer.TYPE, "resId")
                    .addAnnotation(attr.targetFormat.resAnnotation)
                    .build())
            returns(TypeVariableName.get("B"))
            addStatement("getBuilder().putRes(\$T.styleable.\$L[\$L], resId)", rClassName, styleableResourceName, attr.styleableResId.code)
            addStatement("return (B) this")
        }.build())

        // Adds a special <attribute>Dp method that automatically convert a dp value to pixels for dimensions
        if (isTargetDimensionType) {
            methodSpecs.add(MethodSpec.methodBuilder("${baseMethodName}Dp").apply {
                addJavadoc(attr.javadoc)
                addModifiers(Modifier.PUBLIC)
                addParameter(ParameterSpec.builder(Integer.TYPE, "value")
                        .addAnnotation(AnnotationSpec.builder(AndroidClassNames.DIMENSION)
                                .addMember("unit", "\$T.DP", AndroidClassNames.DIMENSION)
                                .build())
                        .build())
                returns(TypeVariableName.get("B"))
                addStatement("getBuilder().putDp(\$T.styleable.\$L[\$L], value)", rClassName, styleableResourceName, attr.styleableResId.code)
                addStatement("return (B) this")
            }.build())
        }

        // Adds a special <attribute> method that automatically convert a @ColorInt to a ColorStateList
        if (isTargetColorStateListType) {
            methodSpecs.add(MethodSpec.methodBuilder(baseMethodName).apply {
                addJavadoc(attr.javadoc)
                addModifiers(Modifier.PUBLIC)
                addParameter(ParameterSpec.builder(Integer.TYPE, "color")
                        .addAnnotation(AndroidClassNames.COLOR_INT)
                        .build())
                returns(TypeVariableName.get("B"))
                addStatement("getBuilder().putColor(\$T.styleable.\$L[\$L], color)", rClassName, styleableResourceName, attr.styleableResId.code)
                addStatement("return (B) this")
            }.build())
        }

        return methodSpecs
    }

    private fun buildApplyToMethod(styleableInfo: StyleableInfo, styleApplierClassName: ClassName): MethodSpec? {
        val methodBuilder = MethodSpec.methodBuilder("applyTo")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(TypeName.get(styleableInfo.viewElementType), "view").build())
                .returns(TypeVariableName.get("B"))
                .addStatement("new \$T(view).apply(build())", styleApplierClassName)
                .addStatement("return (B) this")
        return methodBuilder.build()
    }

    /**
     * Applies lower camel case formatting
     */
    private fun styleableAttrResourceNameToCamelCase(styleableResourceName: String, name: String): String {
        var formattedName = name.removePrefix("${styleableResourceName}_")
        formattedName = formattedName.removePrefix("android_")
        formattedName = formattedName.foldRightIndexed("") { index, c, acc ->
            if (c == '_') {
                acc
            } else {
                if (index == 0 || formattedName[index - 1] != '_') {
                    c + acc
                } else {
                    c.toUpperCase() + acc
                }
            }
        }
        formattedName = formattedName.first().toLowerCase() + formattedName.drop(1)
        return formattedName
    }
}