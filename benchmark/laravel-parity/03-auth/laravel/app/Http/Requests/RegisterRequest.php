<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class RegisterRequest extends FormRequest
{
    public function rules(): array
    {
        return [
            'name' => 'required|string|max:80',
            'email' => 'required|email|unique:users,email',
            'password' => 'required|string|min:8|confirmed',
        ];
    }

    public function attributes(): array
    {
        return ['name' => '姓名', 'email' => '邮箱', 'password' => '密码'];
    }

    public function messages(): array
    {
        return ['email.unique' => '该邮箱已注册,直接登录或重置密码'];
    }
}
