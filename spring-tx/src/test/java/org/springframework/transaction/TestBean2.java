package org.springframework.transaction;

import org.springframework.tests.sample.beans.TestBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author zhaogaohong
 * @version 1.0.0
 * @ClassName TestBean2.java
 * @Description
 * @createTime 2019年12月06日 19:25:00
 */
public class TestBean2 extends TestBean {

    @Override
    @Transactional // <2>
    public Object returnsThis() {
        return super.returnsThis();
    }
}
