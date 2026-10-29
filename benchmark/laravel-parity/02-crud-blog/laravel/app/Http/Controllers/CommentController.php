<?php

namespace App\Http\Controllers;

use App\Http\Requests\StoreCommentRequest;
use App\Models\Post;

class CommentController extends Controller
{
    public function store(int $id, StoreCommentRequest $request)
    {
        $post = Post::findOrFail($id);
        $post->comments()->create($request->attributes_());

        return redirect('/posts/'.$post->slug)->with('status', '评论已提交,待审核');
    }
}
