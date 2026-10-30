<?php

namespace App\Notifications;

use Illuminate\Notifications\Notification;

class InvoiceReady extends Notification
{
    public function __construct(
        public int $invoiceId,
        public string $orderNo,
        public int $amount
    ) {
    }

    public function via(object $notifiable): array
    {
        return ['database'];
    }

    public function toDatabase(object $notifiable): array
    {
        return [
            'message' => "您的发票 {$this->orderNo}(¥{$this->amount})已发出",
            'invoice_id' => $this->invoiceId,
            'url' => '/api/invoices/'.$this->invoiceId,
        ];
    }
}
