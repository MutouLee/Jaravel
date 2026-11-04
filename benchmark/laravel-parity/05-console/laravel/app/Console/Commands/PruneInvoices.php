<?php

namespace App\Console\Commands;

use App\Models\Invoice;
use Illuminate\Console\Command;

class PruneInvoices extends Command
{
    protected $signature = 'invoice:prune {--days=30 : 保留最近多少天} {--force : 跳过确认}';

    protected $description = '删掉 N 天前已归档的发票';

    public function handle(): int
    {
        $cutoff = now()->subDays((int) $this->option('days'));

        $doomed = Invoice::where('status', 'archived')
            ->where('created_at', '<', $cutoff)
            ->count();

        if ($doomed === 0) {
            $this->info($this->option('days').' 天前没有已归档的发票。');

            return 0;
        }
        if (! $this->option('force')) {
            $this->warn($doomed.' 张发票会被**删除**,不可恢复。');
            if (app()->environment('production')) {
                $this->error('这是生产环境 —— 要删请显式加 --force。');

                return 1;
            }
            if (! $this->confirm('继续?', false)) {
                $this->error('已取消。');

                return 1;
            }
        }

        $deleted = Invoice::where('status', 'archived')
            ->where('created_at', '<', $cutoff)
            ->delete();

        $this->info('✓ 已删除 '.$deleted.' 张。');

        return 0;
    }
}
