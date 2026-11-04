<?php

namespace App\Console\Commands;

use App\Models\Invoice;
use Illuminate\Console\Command;

class InvoiceReport extends Command
{
    protected $signature = 'invoice:report {--json : 输出 JSON}';

    protected $description = '按状态汇总发票';

    private const STATUSES = ['pending', 'sent', 'ready', 'archived', 'stale'];

    public function handle(): int
    {
        $counts = [];
        foreach (self::STATUSES as $status) {
            $counts[$status] = Invoice::where('status', $status)->count();
        }
        $total = array_sum($counts);

        if ($this->option('json')) {
            $this->line(json_encode(['total' => $total, 'byStatus' => $counts]));

            return 0;
        }

        $this->comment('发票(共 '.$total.' 张)');
        foreach ($counts as $status => $count) {
            $this->line(sprintf('  %-20s %s 张', $status, $count));
        }

        if ($counts['pending'] > 0) {
            $this->newLine();
            $this->line('有 '.$counts['pending'].' 张待发 —— invoice:send 可以投出去。');
        }

        return 0;
    }
}
