@extends('layouts.app')
@section('title', $post->title)

@section('content')
  <h1>{{ $post->title }}</h1>
  <p class="muted">
    {{ $post->author->name ?? '佚名' }}
    · {{ $post->views }} 次浏览
    @foreach($post->tags as $tag)
      · <span class="tag">{{ $tag->name }}</span>
    @endforeach
  </p>

  <div>{{ $post->body }}</div>

  <h2>评论({{ $post->comments->count() }})</h2>
  @forelse($post->comments as $comment)
    <div class="comment">
      <strong>{{ $comment->author_name }}</strong>
      <p>{{ $comment->body }}</p>
    </div>
  @empty
    <p class="muted">还没有评论。</p>
  @endforelse

  <form class="add" method="post" action="/posts/{{ $post->id }}/comments">
    <input type="text" name="author_name" placeholder="你的名字">
    <input type="text" name="body" placeholder="说点什么…">
    <button type="submit">提交</button>
  </form>
@endsection
