@param String title
@param gg.jte.Content content
@param gg.jte.Content styles = null
@param gg.jte.Content scripts = null
<!DOCTYPE html>
<html>
<head>
  <title>${title}</title>
  @if(styles != null)${styles}@endif
</head>
<body>
  <main>${content}</main>
  @if(scripts != null)${scripts}@endif
</body>
</html>
