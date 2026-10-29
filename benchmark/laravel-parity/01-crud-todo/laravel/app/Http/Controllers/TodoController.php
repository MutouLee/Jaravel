<?php

namespace App\Http\Controllers;

use App\Models\Todo;
use Illuminate\Http\Request;

class TodoController extends Controller
{
    public function index()
    {
        return view('todos.index', [
            'todos' => Todo::latest('id')->get(),
            'pending' => Todo::where('done', false)->count(),
        ]);
    }

    public function store(Request $request)
    {
        $validated = $request->validate([
            'title' => 'required|string|max:100',
        ]);

        Todo::create($validated);

        return redirect('/')->with('status', '已添加');
    }

    public function toggle(int $id)
    {
        $todo = Todo::findOrFail($id);
        $todo->done = ! $todo->done;
        $todo->save();

        return redirect('/');
    }

    public function destroy(int $id)
    {
        Todo::findOrFail($id)->delete();

        return redirect('/')->with('status', '已删除');
    }

    public function apiIndex()
    {
        return Todo::latest('id')->get();
    }

    public function apiShow(int $id)
    {
        return Todo::findOrFail($id);
    }
}
