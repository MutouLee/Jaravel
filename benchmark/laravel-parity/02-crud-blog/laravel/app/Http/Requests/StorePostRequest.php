<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Support\Str;

class StorePostRequest extends FormRequest
{
    public function rules(): array
    {
        return [
            'title' => 'required|string|max:200',
            'slug' => 'required|string|unique:posts,slug',
            'body' => 'required|string|min:10',
            'author_id' => 'required|integer|exists:authors,id',
            'published' => 'nullable|boolean',
            'tags' => 'nullable|array|max:5',
            'tags.*' => 'integer|exists:tags,id',
        ];
    }

    public function messages(): array
    {
        return ['slug.unique' => '该 slug 已被占用,换一个'];
    }

    public function attributes(): array
    {
        return ['title' => '标题', 'body' => '正文', 'author_id' => '作者'];
    }

    protected function prepareForValidation(): void
    {
        $slug = Str::slug($this->input('title', ''));
        $this->merge(['slug' => $slug !== '' ? $slug : 'post-'.crc32($this->input('title', ''))]);
    }

    public function attributes_(): array
    {
        return $this->safe()->only(['title', 'slug', 'body', 'author_id', 'published']);
    }

    public function tagIds(): array
    {
        return $this->safe()->integer('tags') ?? [];
    }
}
