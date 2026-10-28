@param app.JbContext ctx
@param String name
<div class="widget">组件 ${name}</div>
${ctx.push("scripts", @`<script src="/js/${name}.js"></script>`)}
${ctx.push("styles", @`<link rel="stylesheet" href="/css/${name}.css">`)}
