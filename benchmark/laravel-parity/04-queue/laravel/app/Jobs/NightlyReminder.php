<?php

namespace App\Jobs;

use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;

class NightlyReminder implements ShouldQueue
{
    use Queueable;

    public function __construct()
    {
        $this->onQueue('mail');
    }

    public function handle(): void
    {
        // 示例里不做事 —— 它存在只为证明 s.job(...) 的任务真的被投递了
    }
}
