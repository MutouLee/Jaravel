package io.jaravel.migration.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** DDL 生成 —— 不碰数据库,只断言 SQL 文本 */
class SchemaGrammarTest {

    private static List<String> compile(SchemaGrammar grammar, String table,
                                        Consumer<Blueprint> definition) {
        var blueprint = new Blueprint(table);
        definition.accept(blueprint);
        return grammar.compileCreate(blueprint);
    }

    @Nested
    @DisplayName("SQLite")
    class Sqlite {

        private final SchemaGrammar g = new SqliteSchemaGrammar();

        @Test
        @DisplayName("自增主键写在列上")
        void autoIncrementIsInline() {
            var sql = compile(g, "posts", t -> {
                t.id();
                t.string("title", 200);
            });

            assertThat(sql.get(0))
                    .contains("\"id\" integer primary key autoincrement")
                    .contains("\"title\" varchar(200) not null")
                    // 不该再额外写一个 primary key(...)
                    .doesNotContain("primary key (");
        }

        @Test
        @DisplayName("枚举降级成带 CHECK 的 varchar,而不是裸 text")
        void enumBecomesCheckConstraint() {
            var sql = compile(g, "posts", t ->
                    t.enum_("status", List.of("draft", "published")));

            // 降级成 text 会让枚举彻底失去约束,
            // 「本地 SQLite 过、线上 MySQL 报非法值」是最糟的组合
            assertThat(sql.get(0))
                    .contains("check (\"status\" in ('draft', 'published'))");
        }

        @Test
        @DisplayName("时间列存 text —— 字典序等于时间序")
        void temporalIsText() {
            var sql = compile(g, "posts", t -> t.timestamps());
            assertThat(sql.get(0))
                    .contains("\"created_at\" text null")
                    .contains("\"updated_at\" text null");
        }

        @Test
        @DisplayName("外键内联在 CREATE TABLE 里")
        void foreignKeyInline() {
            var sql = compile(g, "posts", t -> {
                t.id();
                t.foreignId("user_id").constrained().cascadeOnDelete();
            });

            assertThat(sql.get(0))
                    .contains("constraint \"posts_user_id_foreign\" foreign key (\"user_id\")")
                    .contains("references \"users\" (\"id\")")
                    .contains("on delete cascade");
        }

        @Test
        @DisplayName("改列类型时报错并给出重建表的四步")
        void changeColumnExplainsWorkaround() {
            var blueprint = new Blueprint("posts");
            blueprint.string("title", 300).change();

            assertThatThrownBy(() -> g.compileAlter(blueprint))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("SQLite 不支持修改已有列")
                    .hasMessageContaining("posts_new")
                    .hasMessageContaining("Schema.rename");
        }

        @Test
        @DisplayName("删外键时说明原因,而不是生成跑不通的 SQL")
        void dropForeignIsRejected() {
            var blueprint = new Blueprint("posts");
            blueprint.dropForeign("posts_user_id_foreign");

            assertThatThrownBy(() -> g.compileAlter(blueprint))
                    .hasMessageContaining("把外键存在表定义里");
        }

        @Test
        @DisplayName("外键开关用 pragma")
        void foreignKeyPragma() {
            assertThat(g.compileDisableForeignKeys()).isEqualTo("pragma foreign_keys = OFF");
        }
    }

    @Nested
    @DisplayName("MySQL")
    class MySql {

        private final SchemaGrammar g = new MySqlSchemaGrammar();

        @Test
        @DisplayName("反引号 + auto_increment + unsigned 紧跟类型")
        void basics() {
            var sql = compile(g, "posts", t -> {
                t.id();
                t.integer("views").unsigned().default_(0);
            });

            assertThat(sql.get(0))
                    .contains("`id` bigint unsigned not null auto_increment")
                    .contains("`views` int unsigned not null default 0")
                    .contains("primary key (`id`)");
        }

        @Test
        @DisplayName("enum 用原生类型")
        void nativeEnum() {
            var sql = compile(g, "posts", t -> t.enum_("status", List.of("draft", "published")));
            assertThat(sql.get(0)).contains("`status` enum('draft', 'published')");
        }

        @Test
        @DisplayName("表级选项")
        void tableOptions() {
            var sql = compile(g, "posts", t -> {
                t.id();
                t.engine("InnoDB").charset("utf8mb4").collation("utf8mb4_unicode_ci")
                        .comment("文章表");
            });

            assertThat(sql.get(0))
                    .contains("engine = InnoDB")
                    .contains("default character set utf8mb4")
                    .contains("collate utf8mb4_unicode_ci")
                    .contains("comment = '文章表'");
        }

        @Test
        @DisplayName("改列用 modify")
        void changeColumnUsesModify() {
            var blueprint = new Blueprint("posts");
            blueprint.string("title", 300).change();

            assertThat(g.compileAlter(blueprint))
                    .containsExactly("alter table `posts` modify `title` varchar(300) not null");
        }

        @Test
        @DisplayName("一句删多列")
        void dropMultipleColumnsInOneStatement() {
            var blueprint = new Blueprint("posts");
            blueprint.dropColumn("a", "b");

            assertThat(g.compileAlter(blueprint))
                    .containsExactly("alter table `posts` drop column `a`, drop column `b`");
        }

        @Test
        @DisplayName("全文索引")
        void fullText() {
            var sql = compile(g, "posts", t -> {
                t.string("title");
                t.text("body");
                t.fullText(List.of("title", "body"));
            });

            assertThat(sql).anyMatch(s -> s.contains("add fulltext"));
        }

        @Test
        @DisplayName("after() 控制列位置")
        void columnPositioning() {
            var blueprint = new Blueprint("posts");
            blueprint.string("subtitle").nullable().after("title");

            assertThat(g.compileAlter(blueprint).get(0)).endsWith("after `title`");
        }
    }

    @Nested
    @DisplayName("PostgreSQL")
    class Postgres {

        private final SchemaGrammar g = new PostgresSchemaGrammar();

        @Test
        @DisplayName("自增用 bigserial,不写 auto_increment")
        void serial() {
            var sql = compile(g, "posts", t -> t.id());

            assertThat(sql.get(0))
                    .contains("\"id\" bigserial primary key")
                    .doesNotContain("auto_increment");
        }

        @Test
        @DisplayName("布尔是真布尔,默认值写 true/false")
        void realBoolean() {
            var sql = compile(g, "posts", t -> t.boolean_("published").default_(false));
            assertThat(sql.get(0)).contains("\"published\" boolean not null default false");
        }

        @Test
        @DisplayName("json 默认落到 jsonb —— 它可索引")
        void jsonPrefersJsonb() {
            var sql = compile(g, "posts", t -> t.json("meta"));
            assertThat(sql.get(0)).contains("\"meta\" jsonb");
        }

        @Test
        @DisplayName("注释是独立语句")
        void commentsAreSeparateStatements() {
            var sql = compile(g, "posts", t -> {
                t.string("title").comment("标题");
                t.comment("文章表");
            });

            assertThat(sql).anyMatch(s -> s.startsWith("comment on column \"posts\".\"title\""));
            assertThat(sql).anyMatch(s -> s.startsWith("comment on table \"posts\""));
        }

        @Test
        @DisplayName("改列拆成 type / not null / default 三句")
        void changeColumnIsSplit() {
            var blueprint = new Blueprint("posts");
            blueprint.string("title", 300).nullable().default_("无标题").change();

            assertThat(g.compileAlter(blueprint)).containsExactly(
                    "alter table \"posts\" alter column \"title\" type varchar(300)",
                    "alter table \"posts\" alter column \"title\" drop not null",
                    "alter table \"posts\" alter column \"title\" set default '无标题'");
        }

        @Test
        @DisplayName("没有全局外键开关时如实报错,而不是假装成功")
        void noGlobalForeignKeySwitch() {
            assertThatThrownBy(g::compileDisableForeignKeys)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("没有全局的外键开关")
                    .hasMessageContaining("调整 Seeder 的插入顺序");
        }

        @Test
        @DisplayName("全文索引用 GIN + to_tsvector")
        void fullTextUsesGin() {
            var sql = compile(g, "posts", t -> {
                t.string("title");
                t.text("body");
                t.fullText(List.of("title", "body"));
            });

            assertThat(sql).anyMatch(s -> s.contains("using gin (to_tsvector"));
        }
    }

    @Nested
    @DisplayName("跨方言的共性")
    class Common {

        @Test
        @DisplayName("morphs() 自动建联合索引 —— 少了它多态查询就是全表扫")
        void morphsCreatesIndex() {
            for (SchemaGrammar g : List.of(new SqliteSchemaGrammar(),
                                           new MySqlSchemaGrammar(),
                                           new PostgresSchemaGrammar())) {
                var sql = compile(g, "images", t -> {
                    t.id();
                    t.morphs("imageable");
                });

                assertThat(sql).as(g.name())
                        .anyMatch(s -> s.contains("imageable_index"));
            }
        }

        @Test
        @DisplayName("标识符里带引号字符时拒绝生成 DDL")
        void rejectsQuoteInIdentifier() {
            var g = new MySqlSchemaGrammar();
            assertThatThrownBy(() -> g.wrap("tab`le"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("拒绝生成 DDL");
        }

        @Test
        @DisplayName("不支持的默认值类型报错,而不是悄悄 toString()")
        void rejectsUnsupportedDefault() {
            var blueprint = new Blueprint("posts");
            blueprint.string("x").default_(new Object());

            assertThatThrownBy(() -> new MySqlSchemaGrammar().compileCreate(blueprint))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能用占位符");
        }

        @Test
        @DisplayName("默认值里的单引号被转义")
        void escapesQuotesInDefault() {
            var sql = compile(new MySqlSchemaGrammar(), "posts",
                    t -> t.string("x").default_("it's"));
            assertThat(sql.get(0)).contains("default 'it''s'");
        }

        @Test
        @DisplayName("重复声明同名列时立刻报错")
        void duplicateColumnRejected() {
            var blueprint = new Blueprint("posts");
            blueprint.timestamps();

            assertThatThrownBy(() -> blueprint.timestamp("created_at"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("重复声明")
                    .hasMessageContaining("timestamps()");
        }

        @Test
        @DisplayName("constrained() 按约定推断表名")
        void constrainedInfersTable() {
            var blueprint = new Blueprint("posts");
            blueprint.foreignId("category_id").constrained();

            assertThat(blueprint.foreignKeys().get(0).referencedTable()).isEqualTo("categories");
        }

        @Test
        @DisplayName("只写 cascadeOnDelete 不写 constrained 时给出正确写法")
        void cascadeWithoutConstrainedIsActionable() {
            var blueprint = new Blueprint("posts");
            assertThatThrownBy(() -> blueprint.foreignId("user_id").cascadeOnDelete())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("constrained()");
        }

        @Test
        @DisplayName("外键没指定被引用表时报错")
        void foreignKeyNeedsTable() {
            var blueprint = new Blueprint("posts");
            blueprint.foreign("user_id");

            assertThatThrownBy(() -> new MySqlSchemaGrammar().compileCreate(blueprint))
                    .hasMessageContaining("没有指定被引用的表");
        }

        @Test
        @DisplayName("索引名可预测 —— dropIndex 要靠它")
        void indexNameIsPredictable() {
            var sql = compile(new MySqlSchemaGrammar(), "posts", t -> {
                t.string("slug");
                t.index(List.of("slug"));
            });

            assertThat(sql).anyMatch(s -> s.contains("`posts_slug_index`"));
        }

        @Test
        @DisplayName("空枚举被拒")
        void emptyEnumRejected() {
            var blueprint = new Blueprint("posts");
            assertThatThrownBy(() -> blueprint.enum_("status", List.of()))
                    .hasMessageContaining("候选值不能为空");
        }
    }
}
