<?php

namespace App\Jobs;

use App\Models\Invoice;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;

class ArchiveInvoice implements ShouldQueue
{
    use Queueable;

    public function __construct(public int $invoiceId)
    {
        $this->onQueue('mail');
    }

    public function handle(): void
    {
        Invoice::whereKey($this->invoiceId)->update(['status' => 'archived']);
    }
}
