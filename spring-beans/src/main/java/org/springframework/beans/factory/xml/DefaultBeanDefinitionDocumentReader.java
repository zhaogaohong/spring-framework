/*
 * Copyright 2002-2016 the original author or authors.
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

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface that
 * reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements, and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). {@code <beans>} does not need to be the root
 * element of the XML document: this class will parse all bean definition elements
 * in the XML file, regardless of the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	public static final String NESTED_BEANS_ELEMENT = "beans";

	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";

	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";

	public static final String PROFILE_ATTRIBUTE = "profile";


	protected final Log logger = LogFactory.getLog(getClass());

	private XmlReaderContext readerContext;

	private BeanDefinitionParserDelegate delegate;


	/**
	 * This implementation parses bean definitions according to the "spring-beans" XSD
	 * (or DTD, historically).
	 * <p>Opens a DOM Document; then initializes the default settings
	 * specified at the {@code <beans/>} level; then parses the contained bean definitions.
	 */
	@Override
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		this.readerContext = readerContext;
		logger.debug("Loading bean definitions");
		//1.获取根节点
		Element root = doc.getDocumentElement();
		//2.从 xml 根节点开始解析文件
		doRegisterBeanDefinitions(root);
	}

	/**
	 * Return the descriptor for the XML resource that this parser works on.
	 */
	protected final XmlReaderContext getReaderContext() {
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor} to pull the
	 * source metadata from the supplied {@link Element}.
	 */
	protected Object extractSource(Element ele) {
		return getReaderContext().extractSource(ele);
	}


	/**
	 * Register each bean definition within the given root {@code <beans/>} element.
	 */
	protected void doRegisterBeanDefinitions(Element root) {
		//1. 我们看名字就知道，BeanDefinitionParserDelegate 必定是一个重要的类，它负责解析 Bean 定义，
		// 这里为什么要定义一个 parent? 看到后面就知道了，是递归问题，
		// 因为 <beans /> 内部是可以定义 <beans /> 的，所以这个方法的 root 其实不一定就是 xml 的根节点，也可以是嵌套在里面的 <beans /> 节点，
		// 从源码分析的角度，我们当做根节点就好了
		BeanDefinitionParserDelegate parent = this.delegate;
		this.delegate = createDelegate(getReaderContext(), root, parent);

		if (this.delegate.isDefaultNamespace(root)) {
			//2. 这块说的是根节点 <beans ... profile="dev" /> 中的 profile 是否是当前环境需要的，
			// 如果当前环境配置的 profile 不包含此 profile，那就直接 return 了，不对此 <beans /> 解析
			// 不熟悉 profile 为何物，不熟悉怎么配置 profile 读者的请移步附录区
			String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
			if (StringUtils.hasText(profileSpec)) {
				String[] specifiedProfiles = StringUtils.tokenizeToStringArray(
						profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
				if (!getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
					if (logger.isInfoEnabled()) {
						logger.info("Skipped XML bean definition file due to specified profiles [" + profileSpec +
								"] not matching: " + getReaderContext().getResource());
					}
					return;
				}
			}
		}
		//3.钩子 是给子类用的钩子方法
		preProcessXml(root);
		//4.解析document
		parseBeanDefinitions(root, this.delegate);
		//5.钩子
		postProcessXml(root);

		this.delegate = parent;
	}

	protected BeanDefinitionParserDelegate createDelegate(
			XmlReaderContext readerContext, Element root, BeanDefinitionParserDelegate parentDelegate) {

		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		delegate.initDefaults(root, parentDelegate);
		return delegate;
	}

	/**
	 * Parse the elements at the root level in the document:
	 * "import", "alias", "bean".
	 * @param root the DOM root element of the document
	 */
	// default namespace 涉及到的就四个标签 <import />、<alias />、<bean /> 和 <beans />，
	// 其他的属于 custom 的
	protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
		if (delegate.isDefaultNamespace(root)) {
			NodeList nl = root.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				if (node instanceof Element) {
					Element ele = (Element) node;
					if (delegate.isDefaultNamespace(ele)) {
						//1.解析 default namespace 下面的几个元素
						// 解析的节点是 <import />、<alias />、<bean />、<beans />
						//因为它们是处于这个 namespace 下定义的：http://www.springframework.org/schema/beans
						parseDefaultElement(ele, delegate);
					}
					else {
						//2.解析其他 namespace 的元素
						// <mvc />、<task />、<context />、<aop />等
						//这些属于扩展，如果需要使用上面这些 ”非 default“ 标签，那么上面的 xml 头部的地方也要引入相应的 namespace 和 .xsd 文件的路径，
						// 如下所示。同时代码中需要提供相应的 parser 来解析，如 MvcNamespaceHandler、TaskNamespaceHandler、ContextNamespaceHandler、AopNamespaceHandler 等。
//						<beans xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
//						xmlns="http://www.springframework.org/schema/beans"
//						xmlns:context="http://www.springframework.org/schema/context"
//						xmlns:mvc="http://www.springframework.org/schema/mvc"
//						xsi:schemaLocation="
//						http://www.springframework.org/schema/beans
//						http://www.springframework.org/schema/beans/spring-beans.xsd
//						http://www.springframework.org/schema/context
//						http://www.springframework.org/schema/context/spring-context.xsd
//						http://www.springframework.org/schema/mvc
//						http://www.springframework.org/schema/mvc/spring-mvc.xsd"
//						default-autowire="byName">
						//假如读者想分析 <context:property-placeholder location="classpath:xx.properties" /> 的实现原理，
						// 就应该到 ContextNamespaceHandler 中找答案。
//						1. 使用自定义标签
//						扩展 Spring 自定义标签配置一般需要以下几个步骤：
//						创建一个需要扩展的组件。
//						定义一个 XSD 文件，用于描述组件内容。
//						创建一个实现 org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser 接口的类，用来解析 XSD 文件中的定义和组件定义。
//						创建一个 Handler，继承 org.springframework.beans.factory.xml.NamespaceHandlerSupport 抽象类 ，用于将组件注册到 Spring 容器。
//						编写 spring.handlers 和 Spring.schemas 文件。

//						1.1 创建组件
//						该组件就是一个普通的 Java Bean，没有任何特别之处。代码如下：
//						public class User {
//							private String id;
//							private String userName;
//							private String email;
//						}
//						1.2 定义 XSD 文件
//<?xml version="1.0" encoding="UTF-8"?>
//<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns="http://www.cmsblogs.com/schema/user" targetNamespace="http://www.cmsblogs.com/schema/user" elementFormDefault="qualified">
//    <xsd:element name="user">
//        <xsd:complexType>
//            <xsd:attribute name="id" type="xsd:string" />
//            <xsd:attribute name="userName" type="xsd:string" />
//            <xsd:attribute name="email" type="xsd:string" />
//        </xsd:complexType>
//    </xsd:element>
//</xsd:schema>
//上面除了对 User 这个 Java Bean 进行了描述外，还定义了 xmlns="http://www.cmsblogs.com/schema/user" 和 targetNamespace="http://www.cmsblogs.com/schema/user" 这两个值，
//这两个值在后面是有大作用的。
//						1.3 定义 Parser 类
//						定义一个 Parser 类，该类继承 AbstractSingleBeanDefinitionParser ，并实现 #getBeanClass(Element element) 和 #doParse(Element element, BeanDefinitionBuilder builder) 两个方法。主要是用于解析 XSD 文件中的定义和组件定义。
//						public class UserDefinitionParser extends AbstractSingleBeanDefinitionParser {
//							@Override
//							protected Class<?> getBeanClass(Element element) {
//								return User.class;
//							}
//							@Override
//							protected void doParse(Element element, BeanDefinitionBuilder builder) {
//								String id = element.getAttribute("id");
//								String userName = element.getAttribute("userName");
//								String email = element.getAttribute("email");
//								if (StringUtils.hasText(id)) {
//									builder.addPropertyValue("id", id);
//								}
//								if (StringUtils.hasText(userName)) {
//									builder.addPropertyValue("userName", userName);
//								}
//								if (StringUtils.hasText(email)) {
//									builder.addPropertyValue("email", email);
//								}
//							}
//						}
//						1.4 定义 NamespaceHandler 类
//						定义 NamespaceHandler 类，继承 NamespaceHandlerSupport ,主要目的是将组件注册到 Spring 容器中。
//						public class UserNamespaceHandler extends NamespaceHandlerSupport {
//							@Override
//							public void init() {
//								registerBeanDefinitionParser("user", new UserDefinitionParser());
//							}
//						}
//						1.5 定义 spring.handlers 文件
//						http\://www.cmsblogs.com/schema/user=org.springframework.core.customelement.UserNamespaceHandler
//						1.6 定义 Spring.schemas 文件
//						http\://www.cmsblogs.com/schema/user.xsd=user.xsd

//						1.7 运行
//						经过上面几个步骤，就可以使用自定义的标签了。在 xml 配置文件中使用如下：
//				<?xml version="1.0" encoding="UTF-8"?>
//				<beans xmlns="http://www.springframework.org/schema/beans" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
//						xmlns:myTag="http://www.cmsblogs.com/schema/user"
//						xsi:schemaLocation="http://www.springframework.org/schema/beans
//						http://www.springframework.org/schema/beans/spring-beans.xsd
//						http://www.cmsblogs.com/schema/user http://www.cmsblogs.com/schema/user.xsd">
//					<myTag:user id="user" email="12233445566@qq.com" userName="chenssy" />
//				</beans>
//				运行测试：
//						public static void main(String[] args){
//							ApplicationContext context = new ClassPathXmlApplicationContext("spring.xml");
//							User user = (User) context.getBean("user");
//							System.out.println(user.getUserName() + "----" + user.getEmail());
//						}

						delegate.parseCustomElement(ele);
					}
				}
			}
		}
		else {
			//3.解析其他 namespace 的元素
			delegate.parseCustomElement(root);
		}
	}

	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
			//1.处理 <import /> 标签
//			<?xml version="1.0" encoding="UTF-8"?>
//<beans xmlns="http://www.springframework.org/schema/beans"
//			xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
//			xsi:schemaLocation="http://www.springframework.org/schema/beans
//			http://www.springframework.org/schema/beans/spring-beans.xsd">
//    <import resource="spring-student.xml"/>
//    <import resource="spring-student-dtd.xml"/>
//</beans>
			importBeanDefinitionResource(ele);
		}
		else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
			//2.处理 <alias /> 标签定义
			// <alias name="fromName" alias="toName"/>
			processAliasRegistration(ele);
		}
		else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
			//3.处理 <bean /> 标签定义，这也算是我们的重点吧
			processBeanDefinition(ele, delegate);
		}
		else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
			// recurse
			//4.如果碰到的是嵌套的 <beans /> 标签，需要递归
			doRegisterBeanDefinitions(ele);
		}
	}

	/**
	 * Parse an "import" element and load the bean definitions
	 * from the given resource into the bean factory.
	 */
	protected void importBeanDefinitionResource(Element ele) {
		// <1> 获取 resource 的属性值
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		// 为空，直接退出
		if (!StringUtils.hasText(location)) {
			getReaderContext().error("Resource location must not be empty", ele);
			// 使用 problemReporter 报错
			return;
		}
		// <2> 解析系统属性，格式如 ："${user.dir}"
		// Resolve system properties: e.g. "${user.dir}"
		location = getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);
		// 实际 Resource 集合，即 import 的地址，有哪些 Resource 资源
		Set<Resource> actualResources = new LinkedHashSet<Resource>(4);

		// <3> 判断 location 是相对路径还是绝对路径
		boolean absoluteLocation = false;
		try {
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		}
		catch (URISyntaxException ex) {
			// cannot convert to an URI, considering the location relative
			// unless it is the well-known Spring prefix "classpath*:"
		}

		// <4> 绝对路径
		if (absoluteLocation) {
			try {
				// 添加配置文件地址的 Resource 到 actualResources 中，并加载相应的 BeanDefinition 们
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isDebugEnabled()) {
					logger.debug("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		} // <5> 相对路径
		else {
			// No URL -> considering resource location as relative to the current file.
			try {
				int importCount;
				// 创建相对地址的 Resource
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				// 存在
				if (relativeResource.exists()) {
					// 加载 relativeResource 中的 BeanDefinition 们
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					// 添加到 actualResources 中
					actualResources.add(relativeResource);
				}
				else {
					// 不存在
					// 获得根路径地址
					String baseLocation = getReaderContext().getResource().getURL().toString();
					// 添加配置文件地址的 Resource 到 actualResources 中，并加载相应的 BeanDefinition 们
					importCount = getReaderContext().getReader().loadBeanDefinitions(
							StringUtils.applyRelativePath(baseLocation, location), actualResources);
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			}
			catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to import bean definitions from relative location [" + location + "]",
						ele, ex);
			}
		}
		// <6> 解析成功后，进行监听器激活处理
		Resource[] actResArray = actualResources.toArray(new Resource[actualResources.size()]);
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));
	}

	/**
	 * Process the given alias element, registering the alias with the registry.
	 */
	protected void processAliasRegistration(Element ele) {
		String name = ele.getAttribute(NAME_ATTRIBUTE);
		String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
		boolean valid = true;
		if (!StringUtils.hasText(name)) {
			getReaderContext().error("Name must not be empty", ele);
			valid = false;
		}
		if (!StringUtils.hasText(alias)) {
			getReaderContext().error("Alias must not be empty", ele);
			valid = false;
		}
		if (valid) {
			try {
				getReaderContext().getRegistry().registerAlias(name, alias);
			}
			catch (Exception ex) {
				getReaderContext().error("Failed to register alias '" + alias +
						"' for bean with name '" + name + "'", ele, ex);
			}
			getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
		}
	}

	/**
	 * Process the given bean element, parsing the bean definition
	 * and registering it with the registry.
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
		// 1.将 <bean /> 节点中的信息提取出来，然后封装到一个 BeanDefinitionHolder 中，我们先看下 <bean /> 标签中可以定义哪些属性：
//		Property
//		class	类的全限定名
//		name	可指定 id、name(用逗号、分号、空格分隔)
//		scope	作用域
//		constructor arguments	指定构造参数
//		properties	设置属性的值
//		autowiring mode	no(默认值)、byName、byType、 constructor
//		lazy-initialization mode	是否懒加载(如果被非懒加载的bean依赖了那么其实也就不能懒加载了)
//		initialization method	bean 属性设置完成后，会调用这个方法
//		destruction method	bean 销毁后的回调方法
		//上面表格中的内容我想大家都非常熟悉吧，如果不熟悉，那就是你不够了解 Spring 的配置了。
//			<bean id="exampleBean" name="name1, name2, name3" class="com.javadoop.ExampleBean" scope="singleton" lazy-init="true" init-method="init" destroy-method="cleanup">
//				<!-- 可以用下面三种形式指定构造参数 -->
//				<constructor-arg type="int" value="7500000"/>
//				<constructor-arg name="years" value="7500000"/>
//				<constructor-arg index="0" value="7500000"/>
//
//				<!-- property 的几种情况 -->
//				<property name="beanOne">
//					<ref bean="anotherExampleBean"/>
//				</property>
//				<property name="beanTwo" ref="yetAnotherBean"/>
//				<property name="integerProperty" value="1"/>
//			</bean>
	//	当然，除了上面举例出来的这些，还有 factory-bean、factory-method、<lockup-method />、<replaced-method />、<meta />、<qualifier />
		//	这几个，大家是不是熟悉呢？自己检验一下自己对 Spring 中 bean 的了解程度。
		//有了以上这些知识以后，我们再继续往里看怎么解析 bean 元素，是怎么转换到 BeanDefinitionHolder 的。

		//1.如果解析成功，则返回 BeanDefinitionHolder 对象。而 BeanDefinitionHolder 为 name 和 alias 的 BeanDefinition 对象
		BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
		// 下面的几行先不要看，跳过先，跳过先，跳过先，后面会继续说的
		if (bdHolder != null) {
			//2.如果有自定义属性的话，进行相应的解析，先忽略,进行自定义标签处理
			bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
			try {
				//3.我们把这步叫做 注册Bean 吧,进行 BeanDefinition 的注册
				BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to register bean definition with name '" +
						bdHolder.getBeanName() + "'", ele, ex);
			}
			//4.注册完成后，发送事件，本文不展开说这个,发出响应事件，通知相关的监听器，已完成该 Bean 标签的解析。
			getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
		}
	}


	/**
	 * Allow the XML to be extensible by processing any custom element types first,
	 * before we start to process the bean definitions. This method is a natural
	 * extension point for any other custom pre-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types last,
	 * after we finished processing the bean definitions. This method is a natural
	 * extension point for any other custom post-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
