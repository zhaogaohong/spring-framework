package org.springframework.context.support;

/**
 * @author zhaogaohong
 * @version 1.0.0
 * @ClassName MessageServiceImpl.java
 * @Description
 * @createTime 2019年12月06日 11:29:00
 */
public class MessageServiceImpl implements MessageService {

    @Override
    public String getMessage() {
        return "hello world";
    }
}
