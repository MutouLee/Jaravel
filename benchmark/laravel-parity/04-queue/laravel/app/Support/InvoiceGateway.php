<?php

namespace App\Support;

/**
 * 下游支付网关的替身 —— 两侧都不计入 parity。
 */
class InvoiceGateway
{
    public function send(string $orderNo, int $amount): void
    {
    }
}
