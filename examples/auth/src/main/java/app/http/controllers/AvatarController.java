package app.http.controllers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import app.models.User;
import io.jaravel.auth.Auth;
import io.jaravel.http.Request;
import io.jaravel.http.UploadedFile;
import io.jaravel.storage.Storage;
import io.jaravel.validation.Validator;

/**
 * 头像上传 —— multipart 的端到端路径。
 *
 * <p>这个控制器存在的理由是验收那组安全检查:
 * 在它之前,「扩展名按内容嗅探」「随机文件名」「拒掉可执行类型」
 * 只被单元测试覆盖过,没有一条真实的 HTTP 请求走过。
 */
@Component
public class AvatarController {

    /**
     * POST /api/avatar —— 文件作为**方法参数**。
     *
     * <p>{@code UploadedFile avatar} 由参数绑定从字段名 {@code avatar} 取。
     * 没传就是 400,不是 null(见 {@code ArgumentBinder} 的说明)。
     *
     * <p>⚠ 即便如此**仍然要校验**:参数绑定只保证「有这个 part」,
     * 它不看内容。一个伪装成 .png 的 PHP 脚本能一路走到这里。
     */
    public Object store(UploadedFile avatar, Request request) {
        User me = Auth.userOrFail(User.class);

        // 规则串里的 avatar 取自 request.all() —— 文件也在里面。
        //
        // max:2048 的单位是 **KB**(= 2 MB),与 Laravel 一致。
        // 这一条与 config/uploads.yml 的 max-file-size 是两道不同的闸:
        // 后者在容器读流时就中断(413),前者给出一个带字段名的 422
        Validator.make(request.all(), Map.of(
                "avatar", "required|image|mimes:jpg,png|max:2048")).validate();

        // 存到**私有**磁盘并用随机名。
        //
        // 不用 putFileAs(..., avatar.originalName()):那会让一个
        // 上传 "../../x.png" 的请求去试探路径,而且同名文件互相覆盖
        String path = Storage.putFile("avatars/" + me.id, avatar);

        var out = new LinkedHashMap<String, Object>();
        out.put("path", path);
        out.put("size", avatar.size());

        // 真实类型来自内容,不是客户端声称的那个
        out.put("mime", avatar.mimeType().orElse("未识别"));
        out.put("claimed_mime", avatar.clientMimeType().orElse(""));
        out.put("original_name", avatar.originalName());

        // 私有磁盘上的文件没有公开 URL —— 给一个限时签名链接。
        //
        // ⚠ 这个 URL 指向的路由需要应用自己挂(框架还没有注册
        // 自带路由的入口),见 JaravelStorageAutoConfiguration 的注释
        out.put("temporary_url", Storage.temporaryUrl(path, Duration.ofMinutes(15)));
        return out;
    }

    /**
     * POST /api/avatar/multi —— 同名字段的多个文件。
     *
     * <p>这一条走 {@code request.files(...)} 而不是方法参数:
     * 一个 {@code List<UploadedFile>} 参数绑不了(泛型擦除拿不到元素类型)。
     */
    public Object storeMany(Request request) {
        User me = Auth.userOrFail(User.class);

        var files = request.files("photos");
        if (files.isEmpty()) {
            throw new io.jaravel.http.HttpException(400, "没有上传任何 photos。");
        }

        var paths = new java.util.ArrayList<String>();
        for (var file : files) {
            paths.add(Storage.putFile("photos/" + me.id, file));
        }
        return Map.of("count", paths.size(), "paths", paths);
    }

    /**
     * POST /api/avatar/public —— 往**对外可访问**的磁盘写。
     *
     * <p>存在的理由:那个磁盘打开了可执行类型检查,而这条路由
     * 是唯一能让一次真实的 HTTP 请求撞上它的地方。
     *
     * <p>这里**故意不校验** {@code image} —— 要验证的恰好是
     * 「校验被漏掉时,存储层那道闸还在」。
     * 真实应用两道都要有。
     */
    public Object storePublic(UploadedFile file) {
        return Map.of("path", Storage.putFile("uploads", file, "public"));
    }
}
