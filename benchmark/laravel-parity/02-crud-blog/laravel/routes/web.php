<?php

use App\Http\Controllers\CommentController;
use App\Http\Controllers\PostController;
use Illuminate\Support\Facades\Route;

Route::get('/', [PostController::class, 'index'])->name('posts.index');
Route::get('/posts/create', [PostController::class, 'create'])->name('posts.create');
Route::get('/posts/{slug}', [PostController::class, 'show'])->name('posts.show');
Route::post('/posts', [PostController::class, 'store'])->name('posts.store');
Route::post('/posts/{id}/delete', [PostController::class, 'destroy'])->whereNumber('id')->name('posts.destroy');
Route::post('/posts/{id}/restore', [PostController::class, 'restore'])->whereNumber('id')->name('posts.restore');
Route::post('/posts/{id}/comments', [CommentController::class, 'store'])->whereNumber('id')->name('comments.store');

Route::get('/api/posts', [PostController::class, 'apiIndex']);
Route::get('/api/posts/{id}', [PostController::class, 'apiShow'])->whereNumber('id');
