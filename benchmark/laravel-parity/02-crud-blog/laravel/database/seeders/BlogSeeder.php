<?php

namespace Database\Seeders;

use App\Models\Author;
use App\Models\Comment;
use App\Models\Post;
use App\Models\Tag;
use Illuminate\Database\Seeder;

class BlogSeeder extends Seeder
{
    public function run(): void
    {
        $authors = collect(range(1, 3))->map(fn ($i) => Author::create([
            'name' => fake()->word().'作者',
            'email' => fake()->unique()->safeEmail(),
        ]));

        $tags = collect(range(1, 5))->map(fn ($i) => Tag::create([
            'name' => fake()->word(),
            'slug' => fake()->unique()->slug(1),
        ]));

        collect(range(0, 9))->each(function ($i) use ($authors, $tags) {
            $post = Post::create([
                'title' => fake()->sentence(),
                'slug' => fake()->unique()->slug(),
                'body' => fake()->paragraph(),
                'author_id' => $authors->random()->id,
                'published' => fake()->boolean(80),
                'views' => fake()->numberBetween(0, 500),
            ]);

            $post->tags()->attach([$tags[$i % $tags->count()]->id]);

            collect(range(1, 3))->each(fn () => Comment::create([
                'post_id' => $post->id,
                'author_name' => fake()->word().'读者',
                'body' => fake()->sentence(),
                'approved' => fake()->boolean(70),
            ]));
        });
    }
}
