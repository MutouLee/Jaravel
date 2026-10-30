<?php

namespace App\Jobs;

use App\Mail\InvoiceSent;
use App\Models\Customer;
use App\Models\Invoice;
use App\Notifications\InvoiceReady;
use App\Support\InvoiceGateway;
use Illuminate\Contracts\Queue\ShouldBeUnique;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Mail;
use Throwable;

class SendInvoice implements ShouldBeUnique, ShouldQueue
{
    use Queueable;

    public int $tries = 3;

    public int $uniqueFor = 600;

    public function __construct(public int $invoiceId)
    {
        $this->onQueue('mail');
    }

    public function backoff(): array
    {
        return [1, 2];
    }

    public function uniqueId(): string
    {
        return (string) $this->invoiceId;
    }

    public function displayName(): string
    {
        return "SendInvoice(invoice={$this->invoiceId})";
    }

    public function handle(InvoiceGateway $gateway): void
    {
        $invoice = Invoice::findOrFail($this->invoiceId);

        Invoice::whereKey($this->invoiceId)->increment('attempts');

        $gateway->send($invoice->order_no, $invoice->amount);

        $invoice->status = 'sent';
        $invoice->save();

        Mail::to('customer@example.com')->queue(new InvoiceSent($invoice));

        $this->notifyCustomer($invoice);
    }

    private function notifyCustomer(Invoice $invoice): void
    {
        if ($invoice->customer_id === null) {
            return;
        }

        try {
            Customer::find($invoice->customer_id)?->notify(new InvoiceReady(
                $invoice->id,
                $invoice->order_no,
                $invoice->amount
            ));
        } catch (Throwable $e) {
            report($e);
        }
    }

    public function failed(?Throwable $cause): void
    {
        Invoice::whereKey($this->invoiceId)->update(['status' => 'failed']);
    }
}
