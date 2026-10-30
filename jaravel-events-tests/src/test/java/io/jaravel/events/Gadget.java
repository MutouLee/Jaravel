package io.jaravel.events;

import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** 第二个模型 —— 用来验证横切监听器一次拿到所有模型的写入 */
@Model(table = "gadgets")
public class Gadget extends GadgetBase {

    @Id public Long id;
    public String label;
}
