<?php

namespace App\Console;

use App\Jobs\NightlyReminder;
use App\Models\Invoice;
use Illuminate\Console\Scheduling\Schedule;
use Illuminate\Foundation\Console\Kernel as ConsoleKernel;

class Kernel extends ConsoleKernel
{
    protected function schedule(Schedule $schedule): void
    {
        $schedule->timezone('Asia/Shanghai');

        $schedule->call(function () {
            Invoice::where('status', 'pending')->update(['status' => 'stale']);
        })
            ->everyFiveMinutes()
            ->between('08:00', '22:00')
            ->weekdays()
            ->withoutOverlapping(10)
            ->name('标记过期发票')
            ->description('标记过期发票');

        $schedule->job(new NightlyReminder())
            ->dailyAt('03:00')
            ->description('每日提醒');

        $schedule->call(fn () => null)
            ->yearly()
            ->when(fn () => false)
            ->name('手动触发');
    }
}
