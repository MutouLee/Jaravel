<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

class LoginRequest extends FormRequest
{
    public function rules(): array
    {
        return [
            'email' => 'required|email',
            'password' => 'required|string',
            'remember' => 'nullable|boolean',
        ];
    }

    public function attributes(): array
    {
        return ['email' => '邮箱', 'password' => '密码'];
    }

    public function credentials(): array
    {
        return $this->only('email', 'password');
    }
}
