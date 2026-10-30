<?php

namespace App\Http\Controllers;

use App\Jobs\ArchiveInvoice;
use App\Jobs\GenerateStatement;
use App\Jobs\MarkInvoiceReady;
use App\Jobs\NotifyChainBroken;
use App\Jobs\SendInvoice;
use App\Jobs\StatementsDone;
use App\Models\Customer;
use App\Models\Invoice;
use Illuminate\Bus\Batch;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Bus;
use Throwable;

class InvoiceController extends Controller
{
    public function store(Request $request)
    {
        $customerId = $request->filled('customer_email')
            ? $this->customerFor(
                $request->input('customer_email'),
                $request->input('customer_name', '客户')
            )->id
            : null;

        $invoice = Invoice::create([
            'order_no' => $request->input('order_no', 'ORD-'.hrtime(true)),
            'amount' => (int) $request->input('amount', 100),
            'status' => 'pending',
            'customer_id' => $customerId,
        ]);

        SendInvoice::dispatch($invoice->id);

        $out = ['id' => $invoice->id, 'job' => 'queued'];
        if ($customerId !== null) {
            $out['customer_id'] = $customerId;
        }

        return $out;
    }

    private function customerFor(string $email, string $name): Customer
    {
        return Customer::firstOrCreate(['email' => $email], ['name' => $name]);
    }

    public function remind(Invoice $invoice, Request $request)
    {
        SendInvoice::dispatch($invoice->id)
            ->delay(now()->addSeconds((int) $request->input('after', 60)));

        return ['job' => 'queued'];
    }

    public function sendNow(Invoice $invoice)
    {
        SendInvoice::dispatchSync($invoice->id);

        return ['status' => 'sent'];
    }

    public function pipeline(Invoice $invoice)
    {
        Bus::chain([
            new MarkInvoiceReady($invoice->id),
            new SendInvoice($invoice->id),
            new ArchiveInvoice($invoice->id),
        ])->catch(function (Throwable $e) use ($invoice) {
            NotifyChainBroken::dispatch($invoice->id, $e);
        })->dispatch();

        return ['chain' => 'queued'];
    }

    public function statements()
    {
        $jobs = Invoice::pluck('id')
            ->map(fn (int $id) => new GenerateStatement($id))
            ->all();

        $batch = Bus::batch($jobs)
            ->name('月度对账单')
            ->allowFailures()
            ->then(fn (Batch $batch) => StatementsDone::dispatch('then', $batch))
            ->finally(fn (Batch $batch) => StatementsDone::dispatch('finally', $batch))
            ->dispatch();

        return ['batch' => $batch->id, 'total' => $batch->totalJobs];
    }

    public function batchStatus(string $batchId)
    {
        $batch = Bus::findBatch($batchId);

        abort_if($batch === null, 404, "批次不存在:{$batchId}");

        return [
            'id' => $batch->id,
            'name' => $batch->name ?? '',
            'progress' => $batch->progress(),
            'processed' => $batch->processedJobs(),
            'failed' => $batch->failedJobs,
            'finished' => $batch->finished(),
            'cancelled' => $batch->cancelled(),
        ];
    }

    public function cancelBatch(string $batchId)
    {
        Bus::findBatch($batchId)?->cancel();

        return ['status' => 'cancelled'];
    }

    public function show(Invoice $invoice)
    {
        return $invoice;
    }
}

/*
 * ⚠ 一处两侧不对等、而 parity 量不出来的差别:
 *
 * Laravel 的 dispatch() 返回 PendingDispatch,**永远不为 null** ——
 * ShouldBeUnique 的去重是静默发生的,调用方无从得知
 * 「这次投递其实被合并了」。
 *
 * Jaravel 的 Queue.dispatch(...) 在被去重时返回 null,所以
 * 示例的接口能把 "deduplicated" 回给调用方。
 *
 * 这是 Jaravel 的一个真实优势(「连点三次只发了一封」在接口层看得见),
 * 而行数比值反而因此对 Jaravel 不利 —— 它多写了判断那一行。
 * 记在这里:parity 量的是代码量,不是能力。
 */
