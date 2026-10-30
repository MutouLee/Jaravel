<?php

use App\Http\Controllers\InvoiceController;
use Illuminate\Support\Facades\Route;

Route::prefix('api')->group(function () {
    Route::post('/invoices', [InvoiceController::class, 'store'])->name('invoices.store');
    Route::get('/invoices/{invoice}', [InvoiceController::class, 'show'])->name('invoices.show');

    Route::post('/invoices/{invoice}/remind', [InvoiceController::class, 'remind'])
        ->name('invoices.remind');
    Route::post('/invoices/{invoice}/send-now', [InvoiceController::class, 'sendNow'])
        ->name('invoices.sendNow');
    Route::post('/invoices/{invoice}/pipeline', [InvoiceController::class, 'pipeline'])
        ->name('invoices.pipeline');

    Route::post('/statements', [InvoiceController::class, 'statements'])->name('statements');

    Route::get('/batches/{batch}', [InvoiceController::class, 'batchStatus'])
        ->name('batches.show');
    Route::delete('/batches/{batch}', [InvoiceController::class, 'cancelBatch'])
        ->name('batches.cancel');
});
