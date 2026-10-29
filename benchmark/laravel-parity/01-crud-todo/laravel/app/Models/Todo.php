<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class Todo extends Model
{
    protected $fillable = ['title', 'done'];

    protected $casts = ['done' => 'boolean'];

    public function statusLabel(): string
    {
        return $this->done ? '已完成' : '待办';
    }
}
