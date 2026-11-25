<?php

namespace App\Http\Controllers;

use App\Models\User;
use BaconQrCode\Renderer\Image\SvgImageBackEnd;
use BaconQrCode\Renderer\ImageRenderer;
use BaconQrCode\Renderer\RendererStyle\RendererStyle;
use BaconQrCode\Writer;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Str;
use PragmaRX\Google2FA\Google2FA;

/**
 * 二步验证(与 Jaravel 侧 TwoFactorController 一一对应)。
 *
 * TOTP 与二维码用 composer 包(pragmarx/google2fa + bacon/qr-code)——
 * 与 Jaravel 侧用框架自带的 Totp / QrCode 对称,两边都不计入那部分代码。
 *
 * 挑战态(「密码对了但还没登录」)两边都是手写:Laravel 侧放在
 * session 里自己管,Jaravel 侧用框架的 TwoFactorChallenge。
 */
class TwoFactorController extends Controller
{
    public function __construct(private Google2FA $google2fa)
    {
    }

    public function setup(Request $request)
    {
        $user = $request->user();
        $secret = $this->google2fa->generateSecretKey();
        $codes = collect(range(1, 10))
            ->map(fn () => Str::upper(Str::random(5).'-'.Str::random(5)))
            ->all();

        $user->two_factor_secret = $secret;
        $user->two_factor_recovery_codes = array_map(
            fn ($code) => hash('sha256', $code), $codes);
        $user->two_factor_enabled = false;
        $user->save();

        $uri = $this->google2fa->getQRCodeUrl('Laravel 示例', $user->email, $secret);
        $writer = new Writer(
            new ImageRenderer(new RendererStyle(192), new SvgImageBackEnd()));

        return view('two-factor.setup', [
            'qr' => $writer->writeString($uri),
            'secret' => $secret,
            'codes' => $codes,
        ]);
    }

    public function confirm(Request $request)
    {
        $user = $request->user();

        if (! $this->google2fa->verifyKey(
            (string) $user->two_factor_secret, $request->input('code', ''))) {
            return redirect('/settings/two-factor')
                ->withErrors(['code' => '验证码不对 —— 确认验证器里的账号与时间']);
        }

        $user->two_factor_enabled = true;
        $user->save();

        return redirect('/dashboard')->with('status', '二步验证已开启');
    }

    public function challengePage(Request $request)
    {
        if (! $request->session()->has('two-factor.pending')) {
            return redirect('/login');
        }

        return view('two-factor.challenge');
    }

    public function challenge(Request $request)
    {
        $id = $request->session()->get('two-factor.pending');
        $user = $id ? User::find($id) : null;
        $code = (string) $request->input('code', '');
        $status = null;

        if (! $user || $code === '') {
            return redirect('/two-factor')
                ->withErrors(['code' => '验证码或恢复码不正确']);
        }

        if (! ($user->two_factor_enabled && $this->google2fa->verifyKey(
            (string) $user->two_factor_secret, $code))) {
            if (! $this->consumeRecoveryCode($user, $code)) {
                return redirect('/two-factor')
                    ->withErrors(['code' => '验证码或恢复码不正确']);
            }
            $status = '恢复码已使用,请重新配置验证器';
        }

        $remember = (bool) $request->session()->pull('two-factor.remember', false);
        $request->session()->forget('two-factor.pending');
        Auth::login($user, $remember);
        $request->session()->regenerate();

        return redirect()->intended('/dashboard')->with('status', $status);
    }

    /** 恢复码是一次性的:匹配上就把那个哈希删掉并落库 */
    private function consumeRecoveryCode(User $user, string $code): bool
    {
        $stored = $user->two_factor_recovery_codes ?? [];
        $hash = hash('sha256', Str::upper(trim($code)));
        $remaining = array_values(array_filter(
            $stored, fn ($one) => ! hash_equals((string) $one, $hash)));

        if (count($remaining) === count($stored)) {
            return false;
        }

        $user->two_factor_recovery_codes = $remaining;
        $user->save();

        return true;
    }
}
