@param app.JbContext ctx
<!DOCTYPE html>
<html>
<head>
  <title>${ctx.yield_("title")}</title>
  ${ctx.stack("styles")}
</head>
<body>
  <main>${ctx.yield_("content")}</main>
  ${ctx.stack("scripts")}
</body>
</html>
