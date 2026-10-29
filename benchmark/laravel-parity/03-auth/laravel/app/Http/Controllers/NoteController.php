<?php

namespace App\Http\Controllers;

use App\Models\Note;
use Illuminate\Http\Request;

class NoteController extends Controller
{
    public function index(Request $request)
    {
        return Note::ownedBy($request->user()->id)->latest('id')->get();
    }

    public function store(Request $request)
    {
        $this->authorize('create', Note::class);

        $data = $request->validate([
            'title' => 'required|string|max:200',
            'body' => 'required|string',
        ]);

        return $request->user()->notes()->create($data);
    }

    public function show(Note $note)
    {
        $this->authorize('view', $note);

        return $note;
    }

    public function update(Request $request, Note $note)
    {
        $this->authorize('update', $note);

        $note->update($request->validate([
            'title' => 'nullable|string|max:200',
            'body' => 'nullable|string',
        ]));

        return $note;
    }

    public function destroy(Note $note)
    {
        $this->authorize('delete', $note);
        $note->delete();

        return ['deleted' => true, 'id' => $note->id];
    }

    public function count(Request $request): array
    {
        return ['count' => $request->user()->notes()->count()];
    }
}
