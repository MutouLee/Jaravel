@param app.JbContext ctx
${ctx.section("title", @`栈测试页`)}
${ctx.section("content", @`
    <h1>页面标题</h1>
    @template.ctxtest.deep(ctx = ctx, name = "A")
    @template.ctxtest.deep(ctx = ctx, name = "B")
`)}

@template.ctxtest.layout(ctx = ctx)
