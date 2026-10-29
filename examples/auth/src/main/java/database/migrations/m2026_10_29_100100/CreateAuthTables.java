package database.migrations.m2026_10_29_100100;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/**
 * install:auth 生成的迁移。
 *
 * <p>四张表:用户、API 令牌、密码重置令牌、笔记(用来演示 Policy)。
 */
public class CreateAuthTables implements Migration {

    @Override
    public void up() {
        Schema.create("users", t -> {
            t.id();
            t.string("name", 80);
            t.string("email").unique();

            // 存的是哈希,长度要留足:pbkdf2 的编码串比 bcrypt 的 60 字符长
            t.string("password", 255);

            // null = 未验证。用时间戳而不是布尔,这样「什么时候验证的」
            // 这个问题不需要再加一列
            t.timestamp("email_verified_at").nullable();

            // 存 SHA-256 的 hex(64 字符),100 够用
            t.rememberToken();
            t.timestamps();
        });

        Schema.create("personal_access_tokens", t -> {
            t.id();
            t.string("name", 100);
            t.foreignId("user_id").constrained("users").cascadeOnDelete();

            // SHA-256 hex。**unique**:同一个哈希不该出现两次,
            // 而唯一索引顺便让按哈希查变成索引查找
            t.string("token_hash", 64).unique();
            t.timestamp("expires_at").nullable();
            t.timestamp("last_used_at").nullable();
            t.timestamps();
        });

        Schema.create("password_reset_tokens", t -> {
            // email 作主键 —— 一个邮箱同时只有一个有效的重置令牌。
            // 新的重置请求覆盖旧的,于是「连点三次忘记密码」不会留下
            // 三个都能用的令牌
            t.string("email").primary();
            t.string("token", 64);
            t.timestamp("created_at");
        });

        Schema.create("notes", t -> {
            t.id();
            t.foreignId("user_id").constrained("users").cascadeOnDelete();
            t.string("title", 200);
            t.text("body");
            t.timestamps();
            t.index("user_id");
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("notes");
        Schema.dropIfExists("password_reset_tokens");
        Schema.dropIfExists("personal_access_tokens");
        Schema.dropIfExists("users");
    }
}
