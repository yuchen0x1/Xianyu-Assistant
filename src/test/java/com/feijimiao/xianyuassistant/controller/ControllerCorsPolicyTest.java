package com.feijimiao.xianyuassistant.controller;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerCorsPolicyTest {

    @Test
    void controllersDoNotAllowArbitraryWebOrigins() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> wildcardControllers = new ArrayList<>();
        for (var beanDefinition : scanner.findCandidateComponents(
                "com.feijimiao.xianyuassistant.controller")) {
            Class<?> controllerClass = Class.forName(beanDefinition.getBeanClassName());
            CrossOrigin crossOrigin = AnnotatedElementUtils.findMergedAnnotation(
                    controllerClass, CrossOrigin.class);
            if (crossOrigin == null) {
                continue;
            }
            boolean allowsAllOrigins = Arrays.asList(crossOrigin.origins()).contains("*")
                    || Arrays.asList(crossOrigin.value()).contains("*");
            if (allowsAllOrigins) {
                wildcardControllers.add(controllerClass.getName());
            }
        }

        assertThat(wildcardControllers)
                .as("controller classes with wildcard CORS")
                .isEmpty();
    }
}
