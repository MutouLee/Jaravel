package io.jaravel.authit.models;

import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** 授权测试的目标模型 */
@Model(timestamps = false)
public class Article extends ArticleBase {

    @Id public Long id;
    public String title;
    public String body;
    public Long authorId;

    /** 刻意会抛 NPE 的方法 —— 用来测「规则里抛异常」的报错 */
    public java.util.Optional<String> nonExistent() {
        throw new NullPointerException("关联未加载");
    }
}
