@extends('layouts.app')
@section('title', '文章列表')

@section('content')
  <h1>文章</h1>
  <p class="muted">共 {{ $posts->count() }} 篇</p>

  <form class="add" method="get" action="/">
    <input type="text" name="q" placeholder="搜索标题…">
    <button type="submit">搜索</button>
  </form>

  @forelse($posts as $post)
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
  @empty
    <p class="muted">还没有文章。</p>
  @endforelse
@endsection
