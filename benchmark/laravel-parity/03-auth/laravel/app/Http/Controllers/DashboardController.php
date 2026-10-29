<?php

namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;

class DashboardController extends Controller
{
    public function home(Request $request): array
    {
        return [
            'app' => 'Laravel Auth 示例',
            'authenticated' => Auth::check(),
            'csrf_token' => $request->session()->token(),
            'status' => $request->session()->get('status'),
        ];
    }

    public function index(Request $request): array
    {
        $user = $request->user();

        return [
            'id' => $user->id,
            'name' => $user->name,
            'email' => $user->email,
            'email_verified' => $user->hasVerifiedEmail(),
            'via_remember' => Auth::viaRemember(),
            'csrf_token' => $request->session()->token(),
            'status' => $request->session()->get('status'),
        ];
    }

    public function me(Request $request): array
    {
        return [
            'id' => $request->user()->id,
            'email' => $request->user()->email,
            'guard' => 'api',
        ];
    }

    public function issueToken(Request $request): array
    {
        $token = $request->user()->createToken($request->input('name', 'default'));

        return ['token' => $token->plainTextToken, 'id' => $token->accessToken->id];
    }

    public function settings(Request $request): array
    {
        return ['sensitive' => true, 'email' => $request->user()->email];
    }
}
