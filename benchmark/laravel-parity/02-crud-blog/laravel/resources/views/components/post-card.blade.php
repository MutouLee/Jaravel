<article>
  <h2><a href="/posts/{{ $post->slug }}">{{ $post->title }}</a></h2>
  <p class="muted">
    {{ $post->author->name ?? '佚名' }}
    · {{ $post->views }} 次浏览
    @foreach($post->tags as $tag)
      · <span class="tag">{{ $tag->name }}</span>
    @endforeach
  </p>
  <p>{{ $post->excerpt() }}</p>
</article>
