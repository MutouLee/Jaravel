package approach_e;

/** 关联目标,验证用的占位模型。 */
public class Post {
    public Long id;
    public String title;

    public Post(Long id, String title) { this.id = id; this.title = title; }

    @Override public String toString() { return "Post(" + id + ", " + title + ")"; }
}
