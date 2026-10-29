<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class StoreCommentRequest extends FormRequest
{
    public function rules(): array
    {
        return [
            'author_name' => 'required|string|max:80',
            'body' => 'required|string|min:2|max:1000',
        ];
    }

    public function attributes_(): array
    {
        return $this->safe()->only(['author_name', 'body']);
    }
}
