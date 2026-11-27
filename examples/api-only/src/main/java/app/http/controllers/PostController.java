package app.http.controllers;

import org.springframework.stereotype.Component;

import app.http.requests.StorePostRequest;
import app.http.resources.PostResource;
import app.models.Post;
import net.dobeen.jaravel.http.Response;

/**
 * JSON API。**没有任何模板**,响应形状都由 JsonResource 决定;
 * 校验失败自动变 422 JSON,未找到自动 404,异常自动变 Problem JSON。
 */
@Component
public class PostController {

    public Object index() {
        return PostResource.collection(
                Post.query().orderByKeyDesc().get().toList(),
                PostResource::new);
    }

    public Object show(Long id) {
        return new PostResource(Post.findOrFail(id));
    }

    public Response store(StorePostRequest request) {
        Post post = Post.create(request.attributes_());
        return Response.json(new PostResource(post)).status(201);
    }
}
