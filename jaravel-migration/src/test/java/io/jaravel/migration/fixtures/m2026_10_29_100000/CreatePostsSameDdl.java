package io.jaravel.migration.fixtures.m2026_10_29_100000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/**
 * 与 {@link CreatePosts} 产生**完全相同**的 DDL,但代码写法不同 ——
 * 用来验证 checksum 哈希的是「产生的结构」而不是源码文本。
 */
public class CreatePostsSameDdl implements Migration {

    @Override
    public void up() {
        Schema.create("posts", t -> {
            t.id();
            t.string("title", 200);
            t.text("body").nullable();
            // 不用 timestamps(),手写两列 —— 产出的 DDL 一字不差
            t.timestamp("created_at").nullable();
            t.timestamp("updated_at").nullable();
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("posts");
    }
}
