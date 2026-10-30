<?php

namespace App\Jobs;

use App\Support\BatchLedger;
use Illuminate\Bus\Batch;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;

class StatementsDone implements ShouldQueue
{
    use Queueable;

    public function __construct(public string $label, public Batch $batch)
    {
        $this->onQueue('reports');
    }

    public function handle(BatchLedger $ledger): void
    {
        $ledger->record(sprintf(
            '%s:%d/%d:失败%d',
            $this->label,
            $this->batch->processedJobs(),
            $this->batch->totalJobs,
            $this->batch->failedJobs
        ));
    }
}
