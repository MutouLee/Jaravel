@param app.Post post
@param boolean highlighted = false
<article class="${highlighted ? "featured" : ""}">
  <h3>${post.title()}</h3>
  <p>${post.body()}</p>
</article>
