<?php

namespace App\Http\Controllers;

use App\Http\Requests\StorePostRequest;
use App\Models\Author;
use App\Models\Post;
use App\Models\Tag;
use Illuminate\Http\Request;

class PostController extends Controller
{
    public function index(Request $request)
    {
        $query = Post::published()
            ->with(['author', 'tags'])
            ->latest('id');

        if ($request->filled('q')) {
            $query->search($request->input('q'));
        }

        return view('posts.index', ['posts' => $query->get()]);
    }

    public function show(string $slug)
    {
        $post = Post::where('slug', $slug)
            ->with(['author', 'tags', 'comments' => fn ($q) => $q->where('approved', true)->latest('id')])
            ->firstOrFail();

        $post->increment('views');

        return view('posts.show', ['post' => $post]);
    }

    public function create()
    {
        return view('posts.form', [
            'authors' => Author::all(),
            'tags' => Tag::all(),
        ]);
    }

    public function store(StorePostRequest $request)
    {
        $post = Post::create($request->attributes_());
        $post->tags()->sync($request->tagIds());

        return redirect('/posts/'.$post->slug)->with('status', '已发布');
    }

    public function destroy(int $id)
    {
        Post::findOrFail($id)->delete();

        return redirect('/')->with('status', '已移入回收站');
    }

    public function restore(int $id)
    {
        Post::withTrashed()->findOrFail($id)->restore();

        return redirect('/')->with('status', '已恢复');
    }

    public function apiIndex()
    {
        return Post::published()->with('author')->get();
    }

    public function apiShow(int $id)
    {
        return Post::with(['author', 'comments'])->findOrFail($id);
    }
}
