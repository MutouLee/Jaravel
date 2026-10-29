<?php

use App\Http\Controllers\TodoController;
use Illuminate\Support\Facades\Route;

Route::get('/', [TodoController::class, 'index'])->name('todos.index');

Route::post('/todos', [TodoController::class, 'store'])->name('todos.store');
Route::post('/todos/{id}/toggle', [TodoController::class, 'toggle'])
    ->whereNumber('id')->name('todos.toggle');
Route::post('/todos/{id}/delete', [TodoController::class, 'destroy'])
    ->whereNumber('id')->name('todos.destroy');

Route::view('/about', 'todos.about');

Route::prefix('api')->group(function () {
    Route::get('/todos', [TodoController::class, 'apiIndex'])->name('api.todos.index');
    Route::get('/todos/{id}', [TodoController::class, 'apiShow'])
        ->whereNumber('id')->name('api.todos.show');
});
