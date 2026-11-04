package io.jaravel.console.generators;

import io.jaravel.console.Command;

/** {@code jaravel make:seeder PostSeeder} —— 数据填充(docs/05 §6) */
@Command(name = "make:seeder", description = "新建一个 Seeder", offline = true)
public final class MakeSeeder extends SimpleGenerator {

    @Override protected String targetPackage() { return "database.seeders"; }

    @Override protected String label() { return "Seeder"; }
}
