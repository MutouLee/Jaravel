<?php

namespace App\Jobs;

use App\Models\Invoice;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use RuntimeException;

class GenerateStatement implements ShouldQueue
{
    use Queueable;

    public function __construct(public int $invoiceId)
    {
        $this->onQueue('reports');
    }

    public function displayName(): string
    {
        return "GenerateStatement(invoice={$this->invoiceId})";
    }

    public function handle(): void
    {
        $updated = Invoice::whereKey($this->invoiceId)->update(['status' => 'stated']);

        if ($updated === 0) {
            throw new RuntimeException("发票 {$this->invoiceId} 不存在了");
        }
    }
}
