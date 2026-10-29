@extends('layouts.app')

@section('title', 'Todo 列表')

@section('content')
  <h1>Todo</h1>
  <p class="muted">共 {{ $todos->count() }} 项,{{ $pending }} 项待办</p>

  <form class="add" method="post" action="/todos">
    @csrf
    <input type="text" name="title" placeholder="要做什么?" maxlength="100" autofocus>
    <button type="submit">添加</button>
  </form>

  @forelse ($todos as $todo)
    <ul>
      <li class="{{ $todo->done ? 'done' : '' }}">
        <span class="title">{{ $todo->title }}</span>
        <span class="badge">{{ $todo->statusLabel() }}</span>
        <form method="post" action="/todos/{{ $todo->id }}/toggle" style="display:inline">
          @csrf
          <button class="ghost" type="submit">{{ $todo->done ? '撤销' : '完成' }}</button>
        </form>
        <form method="post" action="/todos/{{ $todo->id }}/delete" style="display:inline">
          @csrf
          <button class="ghost" type="submit">删除</button>
        </form>
      </li>
    </ul>
  @empty
    <p class="empty">还没有任何待办。加一条试试。</p>
  @endforelse
@endsection

@push('scripts')
  <script>
    document.querySelector('input[name=title]')?.focus();
  </script>
@endpush
