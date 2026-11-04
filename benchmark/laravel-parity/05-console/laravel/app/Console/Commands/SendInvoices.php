<?php

namespace App\Console\Commands;

use App\Jobs\SendInvoice;
use App\Models\Invoice;
use Illuminate\Console\Command;

class SendInvoices extends Command
{
    protected $signature = 'invoice:send
                            {customer? : 只处理这个客户的发票}
                            {--n|dry-run : 只列出,不真的投递}
                            {--limit=50 : 最多处理几张}';

    protected $description = '把待发的发票投进队列';

    public function handle(): int
    {
        $query = Invoice::where('status', 'pending');
        if ($customer = $this->argument('customer')) {
            $query->where('customer_id', $customer);
        }
        $pending = $query->limit($this->option('limit'))->get();

        if ($pending->isEmpty()) {
            $this->info('没有待发的发票。');

            return 0;
        }

        $this->table(['ID', '单号', '金额', '客户'], $pending->map(fn ($invoice) => [
            $invoice->id,
            $invoice->order_no,
            $invoice->amount.' 元',
            $invoice->customer_id ?? '—',
        ])->all());

        if ($this->option('dry-run')) {
            $this->newLine();
            $this->comment('--dry-run:什么都没投。');

            return 0;
        }
        if (! $this->confirm('把这 '.$pending->count().' 张投进队列?', true)) {
            $this->error('已取消。');

            return 1;
        }

        $this->withProgressBar($pending, fn ($invoice) => SendInvoice::dispatch($invoice->id));
        $this->newLine();
        $this->info('✓ 已投递 '.$pending->count().' 张。');

        return 0;
    }
}
