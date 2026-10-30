<?php

namespace App\Mail;

use App\Models\Invoice;
use Illuminate\Bus\Queueable;
use Illuminate\Mail\Mailable;
use Illuminate\Mail\Mailables\Content;
use Illuminate\Mail\Mailables\Envelope;
use Illuminate\Queue\SerializesModels;

class InvoiceSent extends Mailable
{
    use Queueable, SerializesModels;

    public function __construct(public Invoice $invoice)
    {
    }

    public function envelope(): Envelope
    {
        return new Envelope(
            subject: "发票 {$this->invoice->order_no} 已发送",
            tags: ['invoice', 'transactional'],
            metadata: ['invoice_id' => (string) $this->invoice->id],
        );
    }

    public function content(): Content
    {
        return new Content(
            htmlString: sprintf(
                '<p>你的发票 <strong>%s</strong> 已发送。</p><p>金额:%d 元</p>',
                $this->invoice->order_no,
                $this->invoice->amount
            ),
        );
    }
}
