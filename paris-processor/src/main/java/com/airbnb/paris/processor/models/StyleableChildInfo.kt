package com.airbnb.paris.processor.models

import com.airbnb.paris.annotations.StyleableChild
import com.airbnb.paris.processor.android_resource_scanner.AndroidResourceId
import com.airbnb.paris.processor.framework.isPrivate
import com.airbnb.paris.processor.framework.isProtected
import com.airbnb.paris.processor.framework.logError
import com.airbnb.paris.processor.framework.models.SkyFieldModelFactory
import com.airbnb.paris.processor.framework.models.SkyPropertyModel
import com.airbnb.paris.processor.getResourceId
import java.lang.annotation.AnnotationTypeMismatchException
import javax.lang.model.element.Element

// TODO Forward Javadoc to the generated functions/methods

internal class StyleableChildInfoExtractor
    : SkyFieldModelFactory<StyleableChildInfo>(StyleableChild::class.java) {

    /**
     * @param element Represents a field annotated with @StyleableChild
     */
    override fun elementToModel(element: Element): StyleableChildInfo? {
        val attr = element.getAnnotation(StyleableChild::class.java)
        val styleableResId: AndroidResourceId
        try {
            styleableResId = getResourceId(StyleableChild::class.java, element, attr.value)
        } catch (e: AnnotationTypeMismatchException) {
            logError(element) {
                "Incorrectly typed @StyleableChild value parameter. (This usually happens when an R value doesn't exist.)"
            }
            return null
        }

        var defaultValueResId: AndroidResourceId? = null
        if (attr.defaultValue != -1) {
            try {
                defaultValueResId =
                        getResourceId(StyleableChild::class.java, element, attr.defaultValue)
            } catch (e: AnnotationTypeMismatchException) {
                logError(element) {
                    "Incorrectly typed @StyleableChild defaultValue parameter. (This usually happens when an R value doesn't exist.)"
                }
                return null
            }
        }

        val model = StyleableChildInfo(
            element,
            styleableResId,
            defaultValueResId
        )

        val fieldOrGetterElement: Element = if (model.isJava()) {
            model.element
        } else {
            model.kotlinGetterElement!!
        }

        if (fieldOrGetterElement.isPrivate() || fieldOrGetterElement.isProtected()) {
            logError(element) {
                "Fields and properties annotated with @StyleableChild can't be private or protected."
            }
            return null
        }

        return model
    }
}

internal class StyleableChildInfo(
    element: Element,
    val styleableResId: AndroidResourceId,
    val defaultValueResId: AndroidResourceId?
) : SkyPropertyModel(element)
