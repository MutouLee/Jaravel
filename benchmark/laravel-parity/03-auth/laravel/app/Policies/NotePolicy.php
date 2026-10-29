<?php

namespace App\Policies;

use App\Models\Note;
use App\Models\User;

class NotePolicy
{
    public function view(User $user, Note $note): bool
    {
        return $this->owns($user, $note);
    }

    public function update(User $user, Note $note): bool
    {
        return $this->owns($user, $note);
    }

    public function delete(User $user, Note $note): bool
    {
        return $this->owns($user, $note);
    }

    public function create(User $user): bool
    {
        return $user->hasVerifiedEmail();
    }

    private function owns(User $user, Note $note): bool
    {
        return $note->user_id === $user->id;
    }
}
