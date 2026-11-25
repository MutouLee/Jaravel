<?php

use App\Http\Controllers\AuthController;
use App\Http\Controllers\DashboardController;
use App\Http\Controllers\NoteController;
use App\Http\Controllers\TwoFactorController;
use Illuminate\Support\Facades\Route;

Route::get('/', [DashboardController::class, 'home'])->name('home');

Route::middleware('guest')->group(function () {
    Route::post('/register', [AuthController::class, 'register'])->name('register');
    Route::post('/login', [AuthController::class, 'login'])
        ->middleware('throttle:10,1')->name('login');
    Route::post('/forgot-password', [AuthController::class, 'sendResetLink'])
        ->middleware('throttle:5,1')->name('password.email');
    Route::post('/reset-password', [AuthController::class, 'resetPassword'])
        ->name('password.update');
});

Route::middleware('auth')->group(function () {
    Route::get('/dashboard', [DashboardController::class, 'index'])->name('dashboard');
    Route::post('/logout', [AuthController::class, 'logout'])->name('logout');

    Route::get('/email/verify/{id}/{hash}', [AuthController::class, 'verifyEmail'])
        ->middleware('signed')->name('verification.verify');
    Route::post('/email/verification-notification',
        [AuthController::class, 'resendVerification'])
        ->middleware('throttle:6,1')->name('verification.send');

    Route::post('/api-token', [DashboardController::class, 'issueToken'])
        ->name('api-token');

    Route::post('/confirm-password', [AuthController::class, 'confirmPassword'])
        ->name('password.confirm');

    Route::get('/settings', [DashboardController::class, 'settings'])
        ->middleware(['verified', 'password.confirm'])->name('settings');

    // 开启二步验证:要已登录
    Route::get('/settings/two-factor', [TwoFactorController::class, 'setup'])
        ->name('two-factor.setup');
    Route::post('/settings/two-factor', [TwoFactorController::class, 'confirm'])
        ->name('two-factor.confirm');
});

// 挑战:此刻还没登录,所以不能挂 auth;
// throttle 不是可选的 —— 6 位数字只有一百万种
Route::get('/two-factor', [TwoFactorController::class, 'challengePage'])
    ->name('two-factor.challenge');
Route::post('/two-factor', [TwoFactorController::class, 'challenge'])
    ->middleware('throttle:5,1')->name('two-factor.verify');

Route::prefix('api')->middleware('auth:sanctum')->group(function () {
    Route::get('/me', [DashboardController::class, 'me']);
    Route::get('/notes', [NoteController::class, 'index']);
    Route::get('/notes/count', [NoteController::class, 'count']);
    Route::post('/notes', [NoteController::class, 'store']);
    Route::get('/notes/{note}', [NoteController::class, 'show']);
    Route::put('/notes/{note}', [NoteController::class, 'update']);
    Route::delete('/notes/{note}', [NoteController::class, 'destroy']);
});
