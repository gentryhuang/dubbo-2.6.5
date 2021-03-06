/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config.spring.context.annotation;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.spring.beans.factory.annotation.ReferenceAnnotationBeanPostProcessor;
import com.alibaba.dubbo.config.spring.beans.factory.annotation.ServiceAnnotationBeanPostProcessor;
import com.alibaba.dubbo.config.spring.util.BeanRegistrar;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;

/**
 * Dubbo {@link DubboComponentScan} Bean Registrar
 *
 * @see Service
 * @see DubboComponentScan
 * @see ImportBeanDefinitionRegistrar
 * @see ServiceAnnotationBeanPostProcessor
 * @see ReferenceAnnotationBeanPostProcessor
 * @since 2.5.7
 */
public class DubboComponentScanRegistrar implements ImportBeanDefinitionRegistrar {

    /**
     * @param importingClassMetadata @DubboComponentScan ???????????????
     * @param registry               Bean????????????
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        /**
         * ?????????????????????
         */
        Set<String> packagesToScan = getPackagesToScan(importingClassMetadata);
        /**
         * ?????? ServiceAnnotationBeanPostProcessor Bean ????????????????????? `@Service` ?????????????????????????????? Service Bean ??????
         */
        registerServiceAnnotationBeanPostProcessor(packagesToScan, registry);
        /**
         * ?????? ReferenceAnnotationBeanPostProcessor Bean ????????????????????? `@Reference` ?????????????????????????????? Reference Bean ??????
         */
        registerReferenceAnnotationBeanPostProcessor(registry);

    }

    /**
     * Registers {@link ServiceAnnotationBeanPostProcessor}
     *
     * @param packagesToScan packages to scan without resolving placeholders
     * @param registry       {@link BeanDefinitionRegistry}
     * @since 2.5.8
     * <p>
     * ?????? ServiceAnnotationBeanPostProcessor Bean ????????????????????? @Service ?????????????????????????????? Service Bean ??????
     */
    private void registerServiceAnnotationBeanPostProcessor(Set<String> packagesToScan, BeanDefinitionRegistry registry) {
        // ??????ServiceAnnotationBeanPostProcessor???BeanDefinitionBuilder ??????
        BeanDefinitionBuilder builder = rootBeanDefinition(ServiceAnnotationBeanPostProcessor.class);
        // ??????????????????????????? packagesToScan??????ServiceAnnotationBeanPostProcessor???BeanDefinitionBuilder ????????????
        builder.addConstructorArgValue(packagesToScan);
        // ?????? role ??????
        builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        // ?????? AbstractBeanDefinition ??????
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        // ?????????????????????
        BeanDefinitionReaderUtils.registerWithGeneratedName(beanDefinition, registry);

    }

    /**
     * Registers {@link ReferenceAnnotationBeanPostProcessor} into {@link BeanFactory}
     *
     * @param registry {@link BeanDefinitionRegistry}
     *                 <ul>
     *                  <li>1 ?????? ReferenceAnnotationBeanPostProcessor Bean ????????????????????? @Reference ?????????????????????????????? Reference Bean ?????????</li>
     *                  <li>  2 ReferenceAnnotationBeanPostProcessor???????????????BeanDefinitionBuilder?????????????????????????????????????????? ServiceAnnotationBeanPostProcessor???BeanDefinitionBuilder?????????????????????????????????BeanDefinition?????????????????????</li>
     *                 </ul>
     */
    private void registerReferenceAnnotationBeanPostProcessor(BeanDefinitionRegistry registry) {

        // Register @Reference Annotation Bean Processor
        BeanRegistrar.registerInfrastructureBean(registry, ReferenceAnnotationBeanPostProcessor.BEAN_NAME, ReferenceAnnotationBeanPostProcessor.class);

    }

    /**
     * ?????? DubboComponentScan??????????????????
     *
     * @param metadata
     * @return
     */
    private Set<String> getPackagesToScan(AnnotationMetadata metadata) {
        // ?????? @DubboComponentScan ??????
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(DubboComponentScan.class.getName()));
        // ??????basePackages ?????????
        String[] basePackages = attributes.getStringArray("basePackages");
        // ??????basePackageClasses?????????
        Class<?>[] basePackageClasses = attributes.getClassArray("basePackageClasses");
        // ?????????????????????basePackages??????????????????
        String[] value = attributes.getStringArray("value");
        // ?????????????????? packagesToScan ?????????
        Set<String> packagesToScan = new LinkedHashSet<String>(Arrays.asList(value));
        packagesToScan.addAll(Arrays.asList(basePackages));
        // ?????? ????????????????????? ????????????????????????????????????????????? ??????????????????
        for (Class<?> basePackageClass : basePackageClasses) {
            packagesToScan.add(ClassUtils.getPackageName(basePackageClass));
        }

        // packagesToScan ??????????????????????????????DubboComponentScan????????????????????????????????????
        if (packagesToScan.isEmpty()) {
            return Collections.singleton(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packagesToScan;
    }

}
