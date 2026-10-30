<?php

namespace App\Support;

/**
 * 批次回调的观察点 —— 两侧都不计入 parity。
 */
class BatchLedger
{
    public function record(string $line): void
    {
    }
}
