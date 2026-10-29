package io.jaravel.benchmark;

import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** Jaravel 侧的被测模型 */
@Model(table = "bench_posts", timestamps = false)
public class JaravelPost extends JaravelPostBase {

    @Id public Long id;
    public String title;
    public String body;
    public int views;
}
