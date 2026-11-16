<?php

namespace App\View\Components;

use App\Models\Post;
use Illuminate\View\Component;
use Illuminate\View\View;

class PostCard extends Component
{
    public function __construct(public Post $post)
    {
    }

    public function render(): View
    {
        return view('components.post-card');
    }
}
