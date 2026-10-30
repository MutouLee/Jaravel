<?php

namespace App\Jobs;

use App\Models\Invoice;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Throwable;

class NotifyChainBroken implements ShouldQueue
{
    use Queueable;

    public function __construct(public int $invoiceId, public ?Throwable $cause = null)
    {
        $this->onQueue('mail');
    }

    public function handle(): void
    {
        Invoice::whereKey($this->invoiceId)->update([
            'status' => 'broken',
            'order_no' => 'broken-at:'.($this->cause ? class_basename($this->cause) : '?'),
        ]);
    }
}
