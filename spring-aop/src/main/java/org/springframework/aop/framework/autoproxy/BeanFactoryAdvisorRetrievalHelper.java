/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.framework.autoproxy;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.util.Assert;

/**
 * Helper for retrieving standard Spring Advisors from a BeanFactory,
 * for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AbstractAdvisorAutoProxyCreator
 */
public class BeanFactoryAdvisorRetrievalHelper {

	private static final Log logger = LogFactory.getLog(BeanFactoryAdvisorRetrievalHelper.class);

	private final ConfigurableListableBeanFactory beanFactory;

	private volatile String[] cachedAdvisorBeanNames;


	/**
	 * Create a new BeanFactoryAdvisorRetrievalHelper for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAdvisorRetrievalHelper(ConfigurableListableBeanFactory beanFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		this.beanFactory = beanFactory;
	}


	/**
	 * Find all eligible Advisor beans in the current bean factory,
	 * ignoring FactoryBeans and excluding beans that are currently in creation.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> findAdvisorBeans() {
		//1.cachedAdvisorBeanNames 是 advisor 名称的缓存,获取缓存的增强
		String[] advisorNames = this.cachedAdvisorBeanNames;
		//2.如果 cachedAdvisorBeanNames 为空，这里到容器BeanFactory中查找，并设置缓存，后续直接使用缓存即可
		if (advisorNames == null) {
			// 从容器中查找 Advisor 类型 bean 的名称从当前BeanFactory中获取所有类型为Advisor的bean
			advisorNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
					this.beanFactory, Advisor.class, true, false);
			// 设置缓存
			this.cachedAdvisorBeanNames = advisorNames;
		}
		//3.当前容器BeanFactory中没有类型为Advisor的bean则返回一个空的集合
		if (advisorNames.length == 0) {
			return new ArrayList<Advisor>();
		}
		//4.循环所有获取到的bean
		List<Advisor> advisors = new ArrayList<Advisor>();
		// 遍历 advisorNames
		for (String name : advisorNames) {
			if (isEligibleBean(name)) {
				// 忽略正在创建中的 advisor bean ，跳过正在创建的增强
				if (this.beanFactory.isCurrentlyInCreation(name)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Skipping currently created advisor '" + name + "'");
					}
				}
				else {
					try {
						//调用 getBean 方法从容器中获取名称为 name 的 bean，并将 bean 添加到 advisors 中
						advisors.add(this.beanFactory.getBean(name, Advisor.class));
					}
					catch (BeanCreationException ex) {
						Throwable rootCause = ex.getMostSpecificCause();
						if (rootCause instanceof BeanCurrentlyInCreationException) {
							BeanCreationException bce = (BeanCreationException) rootCause;
							if (this.beanFactory.isCurrentlyInCreation(bce.getBeanName())) {
								if (logger.isDebugEnabled()) {
									logger.debug("Skipping advisor '" + name +
											"' with dependency on currently created bean: " + ex.getMessage());
								}
								continue;
							}
						}
						throw ex;
					}
				}
			}
		}

//		以上就是从容器中查找 Advisor 类型的 bean 所有的逻辑，代码虽然有点长，但并不复杂。主要做了两件事情：
//		从容器中查找所有类型为 Advisor 的 bean 对应的名称
//		遍历 advisorNames，并从容器中获取对应的 bean
		return advisors;
	}

	/**
	 * Determine whether the aspect bean with the given name is eligible.
	 * <p>The default implementation always returns {@code true}.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
