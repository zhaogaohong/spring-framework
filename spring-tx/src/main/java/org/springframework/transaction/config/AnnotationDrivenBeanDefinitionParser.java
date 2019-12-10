/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.transaction.config;

import org.w3c.dom.Element;

import org.springframework.aop.config.AopNamespaceUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * {@link org.springframework.beans.factory.xml.BeanDefinitionParser
 * BeanDefinitionParser} implementation that allows users to easily configure
 * all the infrastructure beans required to enable annotation-driven transaction
 * demarcation.
 *
 * <p>By default, all proxies are created as JDK proxies. This may cause some
 * problems if you are injecting objects as concrete classes rather than
 * interfaces. To overcome this restriction you can set the
 * '{@code proxy-target-class}' attribute to '{@code true}', which
 * will result in class-based proxies being created.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Chris Beams
 * @author Stephane Nicoll
 * @since 2.0
 */
class AnnotationDrivenBeanDefinitionParser implements BeanDefinitionParser {

	/**
	 * Parses the {@code <tx:annotation-driven/>} tag. Will
	 * {@link AopNamespaceUtils#registerAutoProxyCreatorIfNecessary register an AutoProxyCreator}
	 * with the container as necessary.
	 * 解析annotation-driven开头的标签--> {@code <tx:annotation-driven/>} tag.
	 */
	@Override
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		// 1、注册TransactionalEventListenerFactory
		registerTransactionalEventListenerFactory(parserContext);
		// 2、解析标签的mode属性
		String mode = element.getAttribute("mode");
		if ("aspectj".equals(mode)) {
			// mode="aspectj" //提供对Aspectj方式进行事物切入的支持
			registerTransactionAspect(element, parserContext);
		}
		else {
			// mode="proxy" 动态代理
			AopAutoProxyConfigurer.configureAutoProxyCreator(element, parserContext);
		}
		return null;
	}

	private void registerTransactionAspect(Element element, ParserContext parserContext) {
		String txAspectBeanName = TransactionManagementConfigUtils.TRANSACTION_ASPECT_BEAN_NAME;
		String txAspectClassName = TransactionManagementConfigUtils.TRANSACTION_ASPECT_CLASS_NAME;
		if (!parserContext.getRegistry().containsBeanDefinition(txAspectBeanName)) {
			RootBeanDefinition def = new RootBeanDefinition();
			def.setBeanClassName(txAspectClassName);
			def.setFactoryMethodName("aspectOf");
			registerTransactionManager(element, def);
			parserContext.registerBeanComponent(new BeanComponentDefinition(def, txAspectBeanName));
		}
	}

	private static void registerTransactionManager(Element element, BeanDefinition def) {
		//当<tx:annotation-driven/>标签在不指定transaction-manager属性的时候，
		// 会默认寻找id固定名为transactionManager的bean作为事务管理器这个注意事项么，就是在这里实现的
		def.getPropertyValues().add("transactionManagerBeanName",
				TxNamespaceHandler.getTransactionManagerName(element));
	}

	private void registerTransactionalEventListenerFactory(ParserContext parserContext) {
		RootBeanDefinition def = new RootBeanDefinition();
		def.setBeanClass(TransactionalEventListenerFactory.class);
		parserContext.registerBeanComponent(new BeanComponentDefinition(def,
				TransactionManagementConfigUtils.TRANSACTIONAL_EVENT_LISTENER_FACTORY_BEAN_NAME));
	}


	/**
	 * Inner class to just introduce an AOP framework dependency when actually in proxy mode.
	 */
	private static class AopAutoProxyConfigurer {

		public static void configureAutoProxyCreator(Element element, ParserContext parserContext) {
			//最外围的if判断限制了<tx:annotation-driven/>标签只能被解析一次，所以只有第一次被解析的标签会生效
			//InfrastructureAdvisorAutoProxyCreator 解析tx事务的标签的核心全部在InfrastructureAdvisorAutoProxyCreator这个里面，
			// 1、注册InfrastructureAdvisorAutoProxyCreator  Infrastructure-->基础设施
			// 注意：区分@AspectJ：@AspectJ注册的是AnnotationAwareAspectJAutoProxyCreator
			AopNamespaceUtils.registerAutoProxyCreatorIfNecessary(parserContext, element);

			// TRANSACTION_ADVISOR_BEAN_NAME-->org.springframework.transaction.config.internalTransactionAdvisor
			String txAdvisorBeanName = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME;
			if (!parserContext.getRegistry().containsBeanDefinition(txAdvisorBeanName)) {
				Object eleSource = parserContext.extractSource(element);

				// 2、创建AnnotationTransactionAttributeSource的BeanDefinition
				//注册了三个BeanDefinition，分别为AnnotationTransactionAttributeSource、TransactionInterceptor和BeanFactoryTransactionAttributeSourceAdvisor，
				// 并将前两个BeanDefinition添加到第三个BeanDefinition的属性当中，这三个bean支撑了整个事务功能
				RootBeanDefinition sourceDef = new RootBeanDefinition(
						"org.springframework.transaction.annotation.AnnotationTransactionAttributeSource");
				sourceDef.setSource(eleSource);
				// 设置Role属性，ROLE_INFRASTRUCTURE表示Spring的内部bean
				sourceDef.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				// 生成bean名称
				String sourceName = parserContext.getReaderContext().registerWithGeneratedName(sourceDef);

				//2、 Create the TransactionInterceptor definition.创建事务,执行TransactionInterceptor中的invoke方法完成整个事务的逻辑
				// 3、创建TransactionInterceptor的BeanDefinition
				RootBeanDefinition interceptorDef = new RootBeanDefinition(TransactionInterceptor.class);
				interceptorDef.setSource(eleSource);
				// 设置Role属性，ROLE_INFRASTRUCTURE表示Spring的内部bean
				interceptorDef.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				//重点 注册事物管理器
				registerTransactionManager(element, interceptorDef);
				// 将AnnotationTransactionAttributeSource注入到TransactionInterceptor的transactionAttributeSource属性中
				interceptorDef.getPropertyValues().add("transactionAttributeSource", new RuntimeBeanReference(sourceName));
				// 生成bean名称
				String interceptorName = parserContext.getReaderContext().registerWithGeneratedName(interceptorDef);

				// 3、Create the TransactionAttributeSourceAdvisor definition.
				//当判断某个bean适用于事务增强时，也就是用于增强器BeanFactoryTransactionAttributeSourceAdvisor这个类，所以在自定义标签解析时，注入的类成为了整个事务功能的基础。
				// 4、创建BeanFactoryTransactionAttributeSourceAdvisor的BeanDefinition
				RootBeanDefinition advisorDef = new RootBeanDefinition(BeanFactoryTransactionAttributeSourceAdvisor.class);
				advisorDef.setSource(eleSource);
				advisorDef.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				// 将上一步创建的AnnotationTransactionAttributeSource注入到
				// BeanFactoryTransactionAttributeSourceAdvisor的transactionAttributeSource属性中
				advisorDef.getPropertyValues().add("transactionAttributeSource", new RuntimeBeanReference(sourceName));
				// 将上一步创建的TransactionInterceptor注入到
				// BeanFactoryTransactionAttributeSourceAdvisor的adviceBeanName属性中
				advisorDef.getPropertyValues().add("adviceBeanName", interceptorName);
				// 解析<tx:annotation-driven>的order属性标签
				if (element.hasAttribute("order")) {
					advisorDef.getPropertyValues().add("order", element.getAttribute("order"));
				}
				// 将TransactionAttributeSourceAdvisor以txAdvisorBeanName为名称注册到IOC容器中
				parserContext.getRegistry().registerBeanDefinition(txAdvisorBeanName, advisorDef);

				/**
				 * 通过上面的操作：
				 * 1、创建AnnotationTransactionAttributeSource的BeanDefinition
				 * 2、创建TransactionInterceptor的BeanDefinition
				 * 3、创建TransactionAttributeSourceAdvisor的BeanDefinition，
				 *    并将第一步，第二步创建的BeanDefinition注入到transactionAttributeSource和adviceBeanName中
				 *
				 *    BeanFactoryTransactionAttributeSourceAdvisor-->TransactionInterceptor
				 *    BeanFactoryTransactionAttributeSourceAdvisor-->AnnotationTransactionAttributeSource
				 */
				CompositeComponentDefinition compositeDef = new CompositeComponentDefinition(element.getTagName(), eleSource);
				compositeDef.addNestedComponent(new BeanComponentDefinition(sourceDef, sourceName));
				compositeDef.addNestedComponent(new BeanComponentDefinition(interceptorDef, interceptorName));
				compositeDef.addNestedComponent(new BeanComponentDefinition(advisorDef, txAdvisorBeanName));
				parserContext.registerComponent(compositeDef);
			}
		}
	}

}
